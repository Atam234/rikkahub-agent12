package me.rerere.rikkahub.data.ai

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One-word shortcuts the user can say or type that expand into a full instruction for the
 * assistant. The expansion is what gets sent, so the assistant always receives the details.
 */
object CommandShortcuts {
    // Speech recognition spells "extraction" several ways, in English and Filipino.
    private val EXTRACTION = Regex(
        "^(?:mag-?|i-?)?(?:extraction|extractions|extract|ekstraksyon|ekstraksiyon|" +
            "extraksyon|extruction|ex\\s?traction)[\\s.!?]*$",
        RegexOption.IGNORE_CASE,
    )

    /** Returns the full instruction for [text] when it is a known shortcut, otherwise [text]. */
    fun expand(text: String, today: LocalDate = LocalDate.now()): String {
        val trimmed = text.trim()
        if (EXTRACTION.matches(trimmed)) return extractionPrompt(today)
        return text
    }

    private fun extractionPrompt(today: LocalDate): String {
        val date = today.format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH))
        return """
            EXTRACTION — news briefing. Today is $date.
            Use the web search tool now (do not answer from memory) to find the latest news from
            the last 24 hours, newest first:
            1. Tech industry first: AI, big tech companies, smartphones and gadgets, startups,
               chips, cybersecurity, major product launches and updates.
            2. Then the biggest international headlines (world events, economy, science).
            Only include stories published within the last 24-48 hours; skip anything older.
            For each story give a short headline, 1-2 sentences on what happened, and the
            source name. Give 5-8 tech stories and 3-5 world stories.
            Answer in Tagalog, short and clear, ready to be read aloud.
        """.trimIndent()
    }
}
