package com.adam.app_screentranslate.translation

import com.adam.app_screentranslate.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface TranslationProvider {
    val id: String
    suspend fun translate(blocks: List<TranslationRequest>): List<TranslationResult>
}
class ProviderException(val provider: String, val status: Int = 0) : IOException("Provider $provider unavailable ($status)")
internal class WebClient {
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    suspend fun fetch(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request.newBuilder().header("User-Agent", "Mozilla/5.0").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!it.isSuccessful) throw ProviderException(request.url.host, it.code)
                        val body = it.body?.string() ?: throw IOException("Empty response")
                        if (continuation.isActive) continuation.resume(body)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }
        })
    }
}

// All credentials are ephemeral. Never persist or log authorization or user text.
internal class WebToken(private val load: suspend () -> String) {
    private val mutex = Mutex()
    private var value: String? = null
    private var fetchedAt = 0L
    private var lastAttempt = 0L
    private var failed = false
    suspend fun get(force: Boolean = false): String = mutex.withLock {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && value != null && now - fetchedAt < 20 * 60_000) return@withLock value!!
        if (!force && failed && now - lastAttempt < 30_000) throw IOException("Authorization temporarily unavailable")
        lastAttempt = now
        try {
            load().also { value = it; fetchedAt = now; failed = false }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { failed = true; value = null; throw e }
    }
}

class GoogleWebTranslationProvider : TranslationProvider {
    override val id = "google"
    private val web = WebClient()
    private val token = WebToken {
        val bootstrap = web.fetch(Request.Builder().url("https://translate.google.com/translate_a/element.js?cb=googleTranslateElementInit&hl=en").build())
        val scriptUrl = GoogleBootstrap.scriptUrl(bootstrap) ?: SCRIPT_URL
        val js = web.fetch(Request.Builder().url(scriptUrl).build())
        Regex("""["']x-goog-api-key["']\s*:\s*["']([\w-]{39})["']""", RegexOption.IGNORE_CASE)
            .find(js)?.groupValues?.get(1) ?: throw ProviderException(id)
    }
    override suspend fun translate(blocks: List<TranslationRequest>): List<TranslationResult> {
        if (blocks.isEmpty()) return emptyList()
        require(blocks.all { it.source == blocks[0].source && it.target == blocks[0].target })
        for (attempt in 0..1) {
            val key = token.get(force = attempt == 1)
            val texts = JSONArray()
            blocks.forEach { texts.put("<pre>${escapeHtml(it.text)}</pre>") }
            val body = JSONArray().put(JSONArray().put(texts).put(blocks[0].source).put(blocks[0].target)).put("te")
            try {
                val raw = web.fetch(Request.Builder().url(ENDPOINT).header("X-goog-api-key", key)
                    .post(body.toString().toRequestBody("application/json+protobuf".toMediaType())).build())
                val response = JSONArray(raw)
                val translated = response.getJSONArray(0)
                if (translated.length() != blocks.size) throw ProviderException(id)
                val detected = response.optJSONArray(1)
                return blocks.mapIndexed { i, b ->
                    val text = Jsoup.parseBodyFragment(translated.getString(i)).text()
                    if (text.isBlank()) throw ProviderException(id)
                    TranslationResult(b.id, text, detected?.optString(i)?.takeIf { it.isNotBlank() }, id)
                }
            } catch (e: ProviderException) {
                if (attempt == 0 && e.status in listOf(401, 403)) continue
                throw e
            }
        }
        throw ProviderException(id)
    }
    companion object {
        const val ENDPOINT = "https://translate-pa.googleapis.com/v1/translateHtml"
        const val SCRIPT_URL = "https://translate.googleapis.com/_/translate_http/_/js/k=translate_http.tr.en_US.YusFYy3P_ro.O/am=AAg/d=1/exm=el_conf/ed=1/rs=AN8SPfq1Hb8iJRleQqQc8zhdzXmF9E56eQ/m=el_main"
    }
}

class YandexWebTranslationProvider : TranslationProvider {
    override val id = "yandex"
    private val web = WebClient()
    private val token = WebToken {
        val widget = try {
            web.fetch(Request.Builder().url("https://translate.yandex.net/website-widget/v1/widget.js?widgetId=ytWidget&pageLang=en&widgetTheme=light&autoMode=false").build())
        } catch (e: CancellationException) { throw e } catch (_: IOException) { "" }
        extractSid(widget) ?: run {
            // Current TWP uses the public translated-page bootstrap when the widget omits SID.
            val page = web.fetch(Request.Builder().url("https://translated.turbopages.org/proxy_u/en-es.en/https/example.com/").build())
            extractSid(page) ?: throw ProviderException(id)
        }
    }
    override suspend fun translate(blocks: List<TranslationRequest>): List<TranslationResult> {
        if (blocks.isEmpty()) return emptyList()
        require(blocks.all { it.source == blocks[0].source && it.target == blocks[0].target })
        for (attempt in 0..1) {
            val sid = token.get(force = attempt == 1)
            val source = blocks[0].source
            val lang = if (source == "auto") blocks[0].target else "$source-${blocks[0].target}"
            val url = ENDPOINT.toHttpUrl().newBuilder()
                .addQueryParameter("srv", if (sid.length > 40) "tr-touch-url" else "tr-url-widget")
                .addQueryParameter("id", "$sid-0-0").addQueryParameter("format", "html").addQueryParameter("lang", lang)
            blocks.forEach { url.addQueryParameter("text", escapeHtml(it.text)) }
            try {
                val response = JSONObject(web.fetch(Request.Builder().url(url.build()).build()))
                val code = response.optInt("code", 200)
                if (code != 200) throw ProviderException(id, code)
                val translated = response.getJSONArray("text")
                if (translated.length() != blocks.size) throw ProviderException(id)
                return blocks.mapIndexed { i, b ->
                    val text = Jsoup.parseBodyFragment(translated.getString(i)).text()
                    if (text.isBlank()) throw ProviderException(id)
                    TranslationResult(b.id, text, response.optString("lang").substringBefore('-').ifBlank { null }, id)
                }
            } catch (e: ProviderException) {
                if (attempt == 0 && e.status in listOf(401, 402, 403)) continue
                throw e
            }
        }
        throw ProviderException(id)
    }
    private fun extractSid(text: String): String? =
        Regex("""["']?sid["']?\s*[:=]\s*["']([0-9a-f.]+)["']""").find(text)?.groupValues?.get(1)
    companion object { const val ENDPOINT = "https://translate.yandex.net/api/v1/tr.json/translate" }
}
internal fun escapeHtml(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

internal object GoogleBootstrap {
    fun scriptUrl(bootstrap: String): String? {
        val encoded = Regex("""_loadJs\(['"]([^'"]+)['"]\)""").find(bootstrap)?.groupValues?.get(1) ?: return null
        val decoded = Regex("""\\(?:x([0-9a-fA-F]{2})|u([0-9a-fA-F]{4}))""").replace(encoded) {
            (it.groupValues[1].ifEmpty { it.groupValues[2] }).toInt(16).toChar().toString()
        }.replace("\\/", "/")
        val url = runCatching { decoded.toHttpUrl() }.getOrNull() ?: return null
        return decoded.takeIf { url.isHttps && url.host == "translate.googleapis.com" && url.encodedPath.startsWith("/_/translate_http/_/js/") }
    }
}

