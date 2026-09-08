package com.adam.app_screentranslate.translation

import com.adam.app_screentranslate.data.TranslationStore
import com.adam.app_screentranslate.model.*
import com.adam.app_screentranslate.ocr.TextNormalizer
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TranslationManager(
    private val cache: TranslationStore,
    private val providers: List<TranslationProvider> = listOf(GoogleWebTranslationProvider(), YandexWebTranslationProvider())
) {
    private val lock = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<TranslationResult?>>()
    suspend fun translate(
        blocks: List<ScreenTextBlock>, settings: AppSettings,
        onResult: suspend (ScreenTextBlock) -> Unit
    ): Int = coroutineScope {
        val available = when (settings.provider) {
            ProviderMode.AUTO -> providers
            ProviderMode.GOOGLE -> providers.take(1)
            ProviderMode.YANDEX -> providers.takeLast(1)
        }
        val requests = blocks.map { TranslationRequest(it.id, TextNormalizer.normalize(it.originalText), it.detectedLanguage ?: "auto", settings.target) }
        val grouped = requests.filter { it.text.isNotEmpty() }.groupBy { "${it.source}\u0000${it.target}\u0000${it.text}" }
        val waiting = mutableListOf<Pair<List<TranslationRequest>, CompletableDeferred<TranslationResult?>>>()
        val owned = mutableListOf<Pair<String, TranslationRequest>>()
        for ((key, same) in grouped) {
            val flightKey = "${settings.provider}:$key"
            val deferred = lock.withLock {
                inFlight[flightKey] ?: CompletableDeferred<TranslationResult?>().also {
                    inFlight[flightKey] = it; owned += flightKey to same.first()
                }
            }
            waiting += same to deferred
        }
        val consumers = waiting.map { (same, future) -> async {
            val result = future.await()
            if (result != null) same.forEach { req ->
                val block = blocks.first { it.id == req.id }
                onResult(block.copy(translatedText = result.text))
            }
            if (result == null) same.size else 0
        } }
        try {
            val pending = mutableListOf<TranslationRequest>()
            for ((key, req) in owned) {
                var hit: TranslationResult? = null
                if (req.source == req.target) hit = TranslationResult(req.id, req.text, req.source, available.first().id)
                if (settings.cacheEnabled && hit == null) {
                    for (provider in available) {
                        hit = try { cache.get(provider.id, req) }
                            catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                        if (hit != null) break
                    }
                }
                if (hit != null) complete(key, hit) else pending += req
            }
            for (provider in available) {
                if (pending.isEmpty()) break
                val pieces = mutableListOf<TranslationRequest>()
                val pieceIds = mutableMapOf<Long, List<Long>>()
                var next = 0L
                pending.forEach { req ->
                    val ids = RequestBatcher.split(req.text).map { text ->
                        val id = next++; pieces += req.copy(id = id, text = text); id
                    }
                    pieceIds[req.id] = ids
                }
                val results = mutableMapOf<Long, TranslationResult>()
                for (batch in RequestBatcher.batches(pieces.sortedBy { it.source })) {
                    try { retry { provider.translate(batch) }.forEach { results[it.id] = it } }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        // Isolate a rejected block; other blocks on the same screen can still succeed.
                        val rejectedBlock = e !is java.io.IOException || (e is ProviderException && e.status in listOf(0, 400, 413, 422))
                        if (batch.size > 1 && rejectedBlock) for (req in batch) {
                            try { provider.translate(listOf(req)).forEach { results[it.id] = it } }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { }
                        }
                    }
                    val done = pending.filter { req -> pieceIds.getValue(req.id).all { results.containsKey(it) } }
                    for (req in done) {
                        val parts = pieceIds.getValue(req.id).map { results.getValue(it) }
                        val result = TranslationResult(req.id, parts.joinToString(" ") { it.text }, parts.first().detectedLanguage, provider.id)
                        if (settings.cacheEnabled) try { cache.put(req, result) }
                            catch (e: CancellationException) { throw e } catch (_: Exception) { /* Cache failure must not discard translation. */ }
                        val key = owned.first { it.second.id == req.id }.first
                        complete(key, result)
                    }
                    pending.removeAll(done.toSet())
                }
            }
            for ((key, _) in owned) complete(key, null)
            consumers.awaitAll().sum()
        } finally {
            withContext(NonCancellable) {
                lock.withLock { owned.forEach { (key, _) -> inFlight.remove(key)?.complete(null) } }
            }
        }
    }
    private suspend fun complete(key: String, value: TranslationResult?) = lock.withLock { inFlight[key]?.complete(value); Unit }
    private suspend fun <T> retry(action: suspend () -> T): T {
        try { return action() }
        catch (e: CancellationException) { throw e }
        catch (e: java.io.IOException) {
            if (e is ProviderException && e.status != 0 && e.status != 429 && e.status < 500) throw e
            delay(450)
            return action()
        }
    }
}
