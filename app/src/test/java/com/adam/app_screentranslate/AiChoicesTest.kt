package com.adam.app_screentranslate

import com.adam.app_screentranslate.data.AiChoices
import com.adam.app_screentranslate.model.AiProvider
import com.adam.app_screentranslate.model.AiRole
import com.adam.app_screentranslate.model.AiSettings
import com.adam.app_screentranslate.model.modelFor
import org.junit.Assert.*
import org.junit.Test

/**
 * Which model belongs to which provider. The failure this guards against is quiet and total: the
 * screen shows Gemini while the request carries a Grok model id, and every translation comes back
 * "model not found".
 */
class AiChoicesTest {
    /** The store as preferences hold it: one model per role *and* provider. */
    private class Memory(vararg entries: Pair<Pair<AiRole, AiProvider>, String>) {
        val values = mutableMapOf(*entries)
        fun read(role: AiRole, provider: AiProvider) = values[role to provider].orEmpty()
        fun write(remember: Map<AiRole, Pair<AiProvider, String>>) =
            remember.forEach { (role, kept) -> values[role to kept.first] = kept.second }
    }

    private val grokAndFlash = Memory(
        (AiRole.TRANSLATE to AiProvider.XAI) to "grok-4.20",
        (AiRole.TRANSLATE to AiProvider.GEMINI) to "gemini-flash-lite-latest")

    @Test fun switchingProviderRestoresThatProvidersOwnModel() {
        val before = AiSettings(provider = AiProvider.XAI, model = "grok-4.20")
        val choice = AiChoices.apply(before, before.copy(provider = AiProvider.GEMINI), grokAndFlash::read)
        assertEquals("gemini-flash-lite-latest", choice.settings.model)
    }

    /** The regression: the model on screen must not be written under the provider being chosen. */
    @Test fun switchingProviderDoesNotOverwriteTheOtherProvidersModel() {
        val before = AiSettings(provider = AiProvider.XAI, model = "grok-4.20")
        val choice = AiChoices.apply(before, before.copy(provider = AiProvider.GEMINI), grokAndFlash::read)
        grokAndFlash.write(choice.remember)
        assertEquals("grok-4.20", grokAndFlash.read(AiRole.TRANSLATE, AiProvider.XAI))
        assertEquals("gemini-flash-lite-latest", grokAndFlash.read(AiRole.TRANSLATE, AiProvider.GEMINI))
    }

    @Test fun switchingThereAndBackKeepsBothChoices() {
        var settings = AiSettings(provider = AiProvider.XAI, model = "grok-4.20")
        for (provider in listOf(AiProvider.GEMINI, AiProvider.OPENROUTER, AiProvider.XAI)) {
            val choice = AiChoices.apply(settings, settings.copy(provider = provider), grokAndFlash::read)
            grokAndFlash.write(choice.remember)
            settings = choice.settings
        }
        assertEquals("grok-4.20", settings.model)
        assertEquals("", grokAndFlash.read(AiRole.TRANSLATE, AiProvider.OPENROUTER))
    }

    @Test fun choosingAModelStoresItUnderItsOwnProvider() {
        val before = AiSettings(provider = AiProvider.GEMINI, model = "")
        val choice = AiChoices.apply(before, before.copy(model = "gemini-3-pro-preview"), grokAndFlash::read)
        grokAndFlash.write(choice.remember)
        assertEquals("gemini-3-pro-preview", grokAndFlash.read(AiRole.TRANSLATE, AiProvider.GEMINI))
        assertEquals("gemini-3-pro-preview", choice.settings.model)
    }

    // --- Две роли ----------------------------------------------------------------------------------

    @Test fun theRolesRememberSeparateModelsOnOneProvider() {
        val memory = Memory(
            (AiRole.TRANSLATE to AiProvider.XAI) to "grok-4.20",
            (AiRole.RESEARCH to AiProvider.XAI) to "grok-4-fast-reasoning")
        val before = AiSettings(provider = AiProvider.GEMINI, model = "gemini-flash-latest",
            researchProvider = AiProvider.GEMINI, researchModel = "gemini-3-pro-preview")
        val choice = AiChoices.apply(before,
            before.copy(provider = AiProvider.XAI, researchProvider = AiProvider.XAI), memory::read)
        assertEquals("grok-4.20", choice.settings.model)
        assertEquals("grok-4-fast-reasoning", choice.settings.researchModel)
    }

    @Test fun changingOneRoleLeavesTheOtherAlone() {
        val memory = Memory((AiRole.RESEARCH to AiProvider.OPENROUTER) to "anthropic/claude-sonnet-4.5")
        val before = AiSettings(provider = AiProvider.XAI, model = "grok-4.20",
            researchProvider = AiProvider.XAI, researchModel = "grok-4-fast-reasoning")
        val choice = AiChoices.apply(before, before.copy(researchProvider = AiProvider.OPENROUTER), memory::read)
        memory.write(choice.remember)
        assertEquals(AiProvider.XAI, choice.settings.provider)
        assertEquals("grok-4.20", choice.settings.model)
        assertEquals("anthropic/claude-sonnet-4.5", choice.settings.researchModel)
        // The model the researcher left behind stays with the provider it was chosen for.
        assertEquals("grok-4-fast-reasoning", memory.read(AiRole.RESEARCH, AiProvider.XAI))
        assertEquals("grok-4.20", memory.read(AiRole.TRANSLATE, AiProvider.XAI))
    }

    @Test fun everyRoleIsWrittenDownOnEveryChange() {
        val before = AiSettings()
        val choice = AiChoices.apply(before, before.copy(model = "grok-4.20"), grokAndFlash::read)
        assertEquals(AiRole.entries.toSet(), choice.remember.keys)
        AiRole.entries.forEach { assertEquals(choice.settings.modelFor(it), choice.remember.getValue(it).second) }
    }
}
