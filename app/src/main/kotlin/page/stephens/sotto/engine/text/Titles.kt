package page.stephens.sotto.engine.text

// Title derivation, ported from the server's convert(): the first markdown
// heading, else the first 3-80 character line, else "Untitled document".
// User-entered names take precedence at the call site (PRD S1).

object Titles {
    private val HEADING = Regex("^#\\s+(.+)$", RegexOption.MULTILINE)
    private val FIRST_LINE = Regex("^(.{3,80})$", RegexOption.MULTILINE)

    fun derive(md: String): String =
        (HEADING.find(md)?.groupValues?.get(1) ?: FIRST_LINE.find(md)?.groupValues?.get(1))
            ?.trim()?.take(120) ?: "Untitled document"
}
