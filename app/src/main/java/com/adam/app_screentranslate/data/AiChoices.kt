package com.adam.app_screentranslate.data

import com.adam.app_screentranslate.model.AiProvider
import com.adam.app_screentranslate.model.AiRole
import com.adam.app_screentranslate.model.AiSettings
import com.adam.app_screentranslate.model.modelFor
import com.adam.app_screentranslate.model.providerFor
import com.adam.app_screentranslate.model.withModel

/** The settings to show, and the model each role leaves behind with the provider it was on. */
data class AiChoice(val settings: AiSettings, val remember: Map<AiRole, Pair<AiProvider, String>>)

/**
 * Which model belongs to which provider after a change of settings. Pure Kotlin, because getting
 * this wrong is silent: the screen keeps showing a model id that the chosen provider has never
 * heard of, and every translation fails with "model not found".
 *
 * The rule is that a model belongs to the provider it was chosen for. When a role changes provider,
 * the model on screen stays behind with the provider being left — it is not carried over and does
 * not overwrite what the provider being chosen was last set to, which is restored instead.
 */
object AiChoices {
    fun apply(previous: AiSettings, next: AiSettings, remembered: (AiRole, AiProvider) -> String): AiChoice {
        var settings = next
        val remember = mutableMapOf<AiRole, Pair<AiProvider, String>>()
        for (role in AiRole.entries) {
            val before = previous.providerFor(role)
            val now = next.providerFor(role)
            if (now == before) remember[role] = now to next.modelFor(role)
            else {
                remember[role] = before to previous.modelFor(role)
                settings = settings.withModel(role, remembered(role, now))
            }
        }
        return AiChoice(settings, remember)
    }
}
