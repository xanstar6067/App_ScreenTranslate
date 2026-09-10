package com.adam.app_screentranslate.translation.ai

import com.adam.app_screentranslate.model.AiModelInfo

/**
 * The xAI account sees the whole zoo: image and video generation, embeddings, multi-agent
 * orchestrators. Translation needs one thing — a model that takes text and returns text — and a
 * dropdown that offers anything else only invites a request that can never succeed.
 */
object XaiModels {
    private val excludedNames = listOf("imagine-image", "imagine-video", "-image", "-video", "embed", "multi-agent")

    fun textTranslationModels(models: List<AiModelInfo>): List<AiModelInfo> =
        models.filter { isTextTranslationModel(it) }.distinctBy { it.id }.sortedByDescending { it.id }

    fun isTextTranslationModel(model: AiModelInfo): Boolean {
        val names = (listOf(model.id) + model.aliases).map { it.lowercase() }
        if (names.any { name -> excludedNames.any { name.contains(it) } }) return false
        // Empty modality lists mean the legacy /models endpoint, which reports text models only.
        if (model.outputModalities.isNotEmpty() && !model.outputModalities.any { it.equals("text", true) }) return false
        if (model.inputModalities.isNotEmpty() && !model.inputModalities.any { it.equals("text", true) }) return false
        // A model that also emits pictures is a generator wearing a text output modality.
        return model.outputModalities.none { it.equals("image", true) || it.equals("video", true) }
    }
}
