package com.adam.app_screentranslate

import com.adam.app_screentranslate.data.TranslationStore
import com.adam.app_screentranslate.model.*
import com.adam.app_screentranslate.translation.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TranslationManagerTest {
    private class MemoryStore : TranslationStore {
        val values = mutableMapOf<String, TranslationResult>()
        private fun key(provider: String, r: TranslationRequest) = "$provider:${r.source}:${r.target}:${r.text}"
        override suspend fun get(provider: String, request: TranslationRequest) = values[key(provider, request)]?.copy(id=request.id)
        override suspend fun put(request: TranslationRequest, result: TranslationResult) { values[key(result.provider, request)] = result }
    }
    private class Fake(override val id: String, val action: suspend (List<TranslationRequest>) -> List<TranslationResult> = { list ->
        list.map { TranslationResult(it.id, "Перевод ${it.text}", it.source, id) }
    }) : TranslationProvider {
        val calls = AtomicInteger()
        override suspend fun translate(blocks: List<TranslationRequest>): List<TranslationResult> { calls.incrementAndGet(); return action(blocks) }
    }
    private fun block(id: Long = 1, text: String = "Mission Complete") =
        ScreenTextBlock(id, text, Box(0f,0f,100f,20f), detectedLanguage="en")
    @Test fun duplicateBlocksUseOneRequestAndCache() = runBlocking {
        val store = MemoryStore(); val google = Fake("google"); val yandex = Fake("yandex")
        val manager = TranslationManager(store, listOf(google, yandex))
        val output = mutableListOf<ScreenTextBlock>()
        assertEquals(0, manager.translate(listOf(block(1), block(2)), AppSettings()) { output += it })
        assertEquals(1, google.calls.get())
        assertEquals(setOf(1L, 2L), output.map { it.id }.toSet())
        manager.translate(listOf(block(3)), AppSettings()) { }
        assertEquals(1, google.calls.get())
    }
    @Test fun autoFallsBackButExplicitProviderDoesNot() = runBlocking {
        val google = Fake("google") { throw ProviderException("google", 403) }
        val yandex = Fake("yandex")
        val manager = TranslationManager(MemoryStore(), listOf(google, yandex))
        assertEquals(0, manager.translate(listOf(block()), AppSettings()) { })
        assertEquals(1, yandex.calls.get())
        assertEquals(1, manager.translate(listOf(block()), AppSettings(provider=ProviderMode.GOOGLE)) { })
        assertEquals(1, yandex.calls.get())
    }
    @Test fun partialFailurePreservesGoodBlocks() = runBlocking {
        val provider = Fake("google") { list ->
            if (list.any { it.text == "bad" }) throw ProviderException("google", 400)
            list.map { TranslationResult(it.id, "OK", "en", "google") }
        }
        val output = mutableListOf<ScreenTextBlock>()
        val manager = TranslationManager(MemoryStore(), listOf(provider, Fake("yandex")))
        val errors = manager.translate(listOf(block(1, "good"), block(2, "bad")),
            AppSettings(provider=ProviderMode.GOOGLE)) { output += it }
        assertEquals(1, errors)
        assertEquals(listOf(1L), output.map { it.id })
    }
    @Test fun concurrentIdenticalCallsShareFlightEvenWithCacheDisabled() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val provider = Fake("google") { list ->
            entered.complete(Unit); release.await()
            list.map { TranslationResult(it.id, "OK", "en", "google") }
        }
        val manager = TranslationManager(MemoryStore(), listOf(provider, Fake("yandex")))
        val first = async { manager.translate(listOf(block(1)), AppSettings(cacheEnabled=false)) { } }
        entered.await()
        val second = async(start=CoroutineStart.UNDISPATCHED) {
            manager.translate(listOf(block(2)), AppSettings(cacheEnabled=false)) { }
        }
        release.complete(Unit)
        assertEquals(0, first.await()); assertEquals(0, second.await())
        assertEquals(1, provider.calls.get())
    }
    @Test fun cancelledFlightDoesNotPoisonNextAttempt() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val provider = Fake("google") { list ->
            if (!entered.isCompleted) { entered.complete(Unit); awaitCancellation() }
            list.map { TranslationResult(it.id, "OK", "en", "google") }
        }
        val manager = TranslationManager(MemoryStore(), listOf(provider, Fake("yandex")))
        val first = launch { manager.translate(listOf(block()), AppSettings(cacheEnabled=false)) { } }
        entered.await(); first.cancelAndJoin()
        val errors = withTimeout(2000) { manager.translate(listOf(block()), AppSettings(cacheEnabled=false)) { } }
        assertEquals(0, errors)
        assertEquals(2, provider.calls.get())
    }
    @Test fun longParagraphReassemblesAfterMultiplePackets() = runBlocking {
        val provider = Fake("google") { list -> list.map { TranslationResult(it.id, it.text, "en", "google") } }
        val text = ("word ".repeat(500)).trim()
        val output = mutableListOf<ScreenTextBlock>()
        TranslationManager(MemoryStore(), listOf(provider, Fake("yandex")))
            .translate(listOf(block(text=text)), AppSettings()) { output += it }
        assertEquals(text, output.single().translatedText)
        assertTrue(provider.calls.get() > 1)
    }
    @Test fun decodesAndValidatesGoogleBootstrapUrl() {
        val bootstrap = """_loadJs('https:\/\/translate.googleapis.com\/_\/translate_http\/_\/js\/k\x3dtest\/m\u003del_main')"""
        assertEquals("https://translate.googleapis.com/_/translate_http/_/js/k=test/m=el_main", GoogleBootstrap.scriptUrl(bootstrap))
        assertNull(GoogleBootstrap.scriptUrl("_loadJs('https://example.com/evil.js')"))
        assertNull(GoogleBootstrap.scriptUrl("_loadJs('http://translate.googleapis.com/_/translate_http/_/js/k=test')"))
    }
}

