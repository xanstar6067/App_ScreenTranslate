package com.adam.app_screentranslate.translation.ai

import com.adam.app_screentranslate.model.AiModelInfo

/**
 * The xAI account sees the whole zoo: image and video generation, embeddings, multi-agent
 * orchestrators. Translation needs one thing — a model that takes text and returns text — and a
 * dropdown that offers anything else only invites a request that can never succeed.
 */
object XaiModels {
    /** Named for xAI alone; the media and embedding families are common to every provider. */
    private val excludedNames = listOf("imagine", "multi-agent")

    /**
     * The rich listing wins where both know a model — it is the one with modalities and prices —
     * and everything the minimal listing knows about on its own is added after it.
     */
    fun merge(rich: List<AiModelInfo>, plain: List<AiModelInfo>): List<AiModelInfo> {
        val known = rich.map { it.id }.toSet()
        return rich + plain.filter { it.id !in known }
    }

    fun textTranslationModels(models: List<AiModelInfo>): List<AiModelInfo> =
        models.filter { isTextTranslationModel(it) }.distinctBy { it.id }.sortedByDescending { it.id }

    fun isTextTranslationModel(model: AiModelInfo): Boolean {
        val names = (listOf(model.id) + model.aliases).map { it.lowercase() }
        if (names.any { name -> excludedNames.any { name.contains(it) } }) return false
        // Empty modality lists mean the legacy /models endpoint, which reports text models only.
        return MediaModels.isText(model)
    }
}
