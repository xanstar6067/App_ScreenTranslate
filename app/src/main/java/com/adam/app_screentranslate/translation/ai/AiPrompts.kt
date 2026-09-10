package com.adam.app_screentranslate.translation.ai

data class AiPrompt(val id: String, val title: String, val description: String, val text: String)

/**
 * Built-in system prompts and their template engine. Stage 3 adds user copies on top of this list;
 * everything here is already written so that a copied prompt keeps working unchanged.
 */
object AiPrompts {
    const val DEFAULT = "game"

    private val REPAIR = """
        The OCR splits text at arbitrary boundaries. You may repair it before translating:
        - fix obvious OCR errors ("Compensation o" + "f maintenance" -> "Compensation of maintenance");
        - join a word broken across blocks, and lines that form one sentence;
        - join blocks that are one line of dialogue.
        Never invent text that is not there, never split a block, and never join independent
        interface elements: "Settings", "Exit", "Account" stay three separate fragments.
        When you join blocks, list every joined id in source_block_ids.
    """.trimIndent()

    private val CONTRACT = """
        You receive a JSON object with source_language, target_language and blocks, where each block
        has an integer id and the text recognized at one place on the screen.

        Return one fragment per piece of meaning. Rules that are never relaxed:
        - every input id appears in exactly one fragment, and no other ids exist;
        - corrected_source_text is the repaired original, translated_text is its translation;
        - translate into target_language even when the source is already close to it;
        - keep the register, punctuation and line-level brevity of interface text;
        - leave proper nouns, tags and codes that a player types or searches unchanged.
    """.trimIndent()

    val builtIn: List<AiPrompt> = listOf(
        AiPrompt("game", "Игровая локализация",
            "Живой перевод диалогов и интерфейса с ремонтом разрывов OCR",
            """
            You are a game localization engine. You translate text captured from a running game or
            application into {{TARGET_LANGUAGE}}. The source language is {{SOURCE_LANGUAGE}}.

            {{CONTRACT}}

            {{REPAIR_RULES}}

            Write the way a published localization reads: natural, idiomatic, and short enough to fit
            the same place on screen. Prefer the wording a player of this genre expects over a
            literal rendering.
            """.trimIndent()),
        AiPrompt("ui", "Строгий перевод интерфейса",
            "Буквально и коротко: меню, кнопки, счётчики. Ничего не объединяет",
            """
            You are a user interface translation engine. You translate interface text captured from a
            running application into {{TARGET_LANGUAGE}}. The source language is {{SOURCE_LANGUAGE}}.

            {{CONTRACT}}

            {{REPAIR_RULES}}

            Interface text is terse by design. Keep every label as short as the original, use the
            established platform wording for standard actions, and never expand a label into a
            sentence. Do not add articles, politeness or explanation that the original does not have.
            """.trimIndent())
    )

    fun byId(id: String): AiPrompt = builtIn.firstOrNull { it.id == id } ?: builtIn.first()

    fun system(id: String, source: String, target: String, repair: Boolean): String = render(
        byId(id).text,
        mapOf(
            "SOURCE_LANGUAGE" to source, "TARGET_LANGUAGE" to target, "CONTRACT" to CONTRACT,
            "REPAIR_RULES" to if (repair) REPAIR else
                "Never join blocks: every fragment contains exactly one id, and corrected_source_text " +
                "repairs only obvious OCR errors inside that one block."))

    /**
     * A placeholder that resolves to nothing takes its whole line with it. Leaving "Glossary:" with
     * an empty glossary behind, or a raw {{GLOSSARY}} in the prompt, both teach the model to answer
     * about something that was never supplied.
     */
    fun render(template: String, values: Map<String, String>): String {
        val placeholder = Regex("""\{\{\s*([A-Z_]+)\s*}}""")
        val lines = template.lines().mapNotNull { line ->
            val found = placeholder.findAll(line).map { it.groupValues[1] }.toList()
            if (found.any { values[it].isNullOrBlank() }) null
            else placeholder.replace(line) { values.getValue(it.groupValues[1]) }
        }
        return lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }
}
