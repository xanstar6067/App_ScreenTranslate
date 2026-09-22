package com.adam.app_screentranslate.translation.ai

import com.adam.app_screentranslate.model.AiEffort
import com.adam.app_screentranslate.model.AiProvider
import java.util.Locale

/** One way of asking Gemini to think. Both null means the thinking config is left out entirely. */
data class GeminiThinking(val level: String? = null, val budget: Int? = null)

/**
 * One way of asking an OpenRouter model to think. [enabled] false switches reasoning off on models
 * that think by default; null for both means the parameter is left out entirely.
 */
data class RouterReasoning(val effort: String? = null, val enabled: Boolean? = null)

/**
 * What a model can be asked about reasoning and search. Providers change what each model accepts
 * faster than an app is updated, so nothing here is final: each level comes as a ladder the client
 * walks down when the model refuses a rung, and the last rung always sends nothing at all.
 *
 * Model ids are read by hand rather than by pattern — Android's regex engine is stricter than the
 * desktop JVM the tests run on.
 */
object AiReasoning {
    /** Levels worth offering for [model]; empty when the model has nothing to adjust. */
    fun levels(provider: AiProvider, model: String): List<AiEffort> = when (provider) {
        AiProvider.XAI -> when {
            model.isBlank() -> AiEffort.entries
            model.lowercase(Locale.ROOT).contains("non-reasoning") -> emptyList()
            isGrok3Mini(model) -> listOf(AiEffort.LOW, AiEffort.HIGH)
            (grokMajor(model) ?: 4) >= 4 -> AiEffort.entries
            else -> emptyList()
        }
        AiProvider.GEMINI -> when {
            model.isBlank() -> AiEffort.entries
            gemini(model) == null -> emptyList()
            gemini(model)!! >= 2.5 -> AiEffort.entries
            else -> emptyList()
        }
        // The router's catalogue spans every vendor, and an id says nothing about what the model
        // behind it accepts. Every level is offered; the router drops what the model cannot use.
        AiProvider.OPENROUTER -> AiEffort.entries
    }

    /** Whether the provider's own search is known to work with [model]. The client still adapts. */
    fun searchable(provider: AiProvider, model: String): Boolean = when (provider) {
        AiProvider.XAI -> (grokMajor(model) ?: 4) >= 4
        AiProvider.GEMINI -> (gemini(model) ?: 0.0) >= 2.0
        // Not the model's own search: the router runs it beside any model and feeds it the pages.
        AiProvider.OPENROUTER -> true
    }

    /**
     * reasoning.effort values for xAI, most faithful first. xAI cannot switch reasoning off, so the
     * minimal level is "low"; a model without the parameter gets none.
     */
    fun xaiLadder(model: String, effort: AiEffort): List<String?> {
        if (levels(AiProvider.XAI, model).isEmpty()) return listOf(null)
        if (isGrok3Mini(model)) return listOf(if (effort == AiEffort.HIGH) "high" else "low", null)
        return when (effort) {
            AiEffort.MINIMAL, AiEffort.LOW -> listOf("low", null)
            AiEffort.MEDIUM -> listOf("medium", "high", null)
            AiEffort.HIGH -> listOf("high", null)
        }
    }

    /**
     * Gemini 3 speaks thinkingLevel, and not every model knows every level; Gemini 2.5 speaks a
     * token budget, and Pro refuses to switch thinking off, so its minimum is 128 tokens.
     */
    fun geminiLadder(model: String, effort: AiEffort): List<GeminiThinking?> {
        val version = gemini(model) ?: return listOf(null)
        if (version < 2.5) return listOf(null)
        if (version >= 3.0) return when (effort) {
            AiEffort.MINIMAL -> listOf(GeminiThinking(level = "minimal"), GeminiThinking(level = "low"), null)
            AiEffort.LOW -> listOf(GeminiThinking(level = "low"), null)
            AiEffort.MEDIUM -> listOf(GeminiThinking(level = "medium"), GeminiThinking(level = "high"), null)
            AiEffort.HIGH -> listOf(GeminiThinking(level = "high"), null)
        }
        return when (effort) {
            AiEffort.MINIMAL -> listOf(GeminiThinking(budget = 0), GeminiThinking(budget = 128), null)
            AiEffort.LOW -> listOf(GeminiThinking(budget = 1024), null)
            AiEffort.MEDIUM -> listOf(GeminiThinking(budget = 8192), null)
            AiEffort.HIGH -> listOf(GeminiThinking(budget = 24576), null)
        }
    }

    /**
     * OpenRouter takes the OpenAI-shaped reasoning object. A model that thinks by default is asked
     * to stop at the minimal level, which is what a screen the player is waiting for needs; the
     * next rung asks for the shortest thinking instead, for models that refuse to stop.
     */
    fun routerLadder(model: String, effort: AiEffort): List<RouterReasoning?> = when (effort) {
        AiEffort.MINIMAL -> listOf(RouterReasoning(enabled = false), RouterReasoning(effort = "low"), null)
        AiEffort.LOW -> listOf(RouterReasoning(effort = "low"), null)
        AiEffort.MEDIUM -> listOf(RouterReasoning(effort = "medium"), RouterReasoning(effort = "high"), null)
        AiEffort.HIGH -> listOf(RouterReasoning(effort = "high"), null)
    }

    /** A 4xx that names the reasoning parameter: the next rung of the ladder may be accepted. */
    fun refusesReasoning(reason: String): Boolean {
        val lower = reason.lowercase(Locale.ROOT)
        return listOf("reasoning", "effort", "thinking", "budget").any { lower.contains(it) }
    }

    /** A 4xx that names the search tool: the request can still go out without it. */
    fun refusesSearch(reason: String): Boolean {
        val lower = reason.lowercase(Locale.ROOT)
        return listOf("tool", "search", "grounding").any { lower.contains(it) }
    }

    /** How an OpenRouter rung reads in the report. */
    fun describe(reasoning: RouterReasoning?): String = when {
        reasoning == null -> "по умолчанию модели"
        reasoning.enabled == false -> "отключено"
        else -> reasoning.effort ?: "по умолчанию модели"
    }

    /** How a rung reads in the report. */
    fun describe(thinking: GeminiThinking?): String = when {
        thinking == null -> "по умолчанию модели"
        thinking.level != null -> thinking.level
        thinking.budget == 0 -> "отключены"
        else -> "бюджет ${thinking.budget} токенов"
    }

    /** "grok-4.5" → 4, "grok-3-mini" → 3; null for anything else. */
    fun grokMajor(model: String): Int? {
        val lower = model.lowercase(Locale.ROOT)
        if (!lower.startsWith("grok-")) return null
        return lower.removePrefix("grok-").takeWhile { it.isDigit() }.toIntOrNull()
    }

    private fun isGrok3Mini(model: String) = model.lowercase(Locale.ROOT).startsWith("grok-3-mini")

    /**
     * "gemini-2.5-flash" → 2.5, "gemini-3-pro-preview" → 3.0. An alias without a version such as
     * "gemini-flash-latest" points at a current model and counts as 3; anything else — Gemma,
     * LearnLM — is not Gemini and has no thinking to configure.
     */
    fun gemini(model: String): Double? {
        val lower = model.lowercase(Locale.ROOT)
        if (!lower.startsWith("gemini-")) return null
        val rest = lower.removePrefix("gemini-")
        if (rest.firstOrNull()?.isDigit() != true) return 3.0
        val major = rest.takeWhile { it.isDigit() }
        val tail = rest.removePrefix(major)
        val minor = if (tail.startsWith(".")) tail.drop(1).takeWhile { it.isDigit() } else ""
        return "$major.${minor.ifEmpty { "0" }}".toDoubleOrNull()
    }
}
