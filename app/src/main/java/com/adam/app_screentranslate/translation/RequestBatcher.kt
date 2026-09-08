package com.adam.app_screentranslate.translation

import com.adam.app_screentranslate.model.TranslationRequest

object RequestBatcher {
    const val LIMIT = 900
    fun split(text: String, limit: Int = LIMIT): List<String> {
        require(limit >= 2)
        val result = mutableListOf<String>()
        var rest = text.trim()
        while (rest.length > limit) {
            val whitespace = rest.lastIndexOf(' ', limit)
            var at = if (whitespace > limit / 2) whitespace else limit
            if (Character.isHighSurrogate(rest[at - 1])) at--
            result += rest.substring(0, at)
            rest = rest.substring(at).trimStart()
        }
        if (rest.isNotEmpty()) result += rest
        return result
    }
    fun batches(requests: List<TranslationRequest>): List<List<TranslationRequest>> {
        val result = mutableListOf<List<TranslationRequest>>()
        var batch = mutableListOf<TranslationRequest>(); var size = 0
        for (r in requests) {
            require(r.text.length <= LIMIT)
            if (batch.isNotEmpty() && (size + r.text.length > LIMIT || r.source != batch[0].source || r.target != batch[0].target)) {
                result += batch; batch = mutableListOf(); size = 0
            }
            batch += r; size += r.text.length
        }
        if (batch.isNotEmpty()) result += batch
        return result
    }
}
