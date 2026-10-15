package page.stephens.sotto.engine.text

// Kotlin port of the sotto server's text pipeline (app.py: clean_markdown(),
// chunk(); ported at server commit with vectors sha256 7566642d...). The server
// is the reference implementation: behavior changes land there first and arrive
// here only via regenerated tools/pipeline-vectors.json. Structure and comment
// landmarks deliberately mirror the Python so the two stay diffable by eye.
// All patterns use UNICODE_CHARACTER_CLASS so \w and \b match Python's re.

import java.util.regex.Pattern

object TextPipeline {

    const val KOKORO_CHUNK_LIMIT = 1000

    private fun p(re: String): Pattern = try {
        Pattern.compile(re, Pattern.UNICODE_CHARACTER_CLASS)
    } catch (e: IllegalArgumentException) {
        // Android's ICU regex rejects the flag because its \w and \b are already
        // Unicode-aware (the flag's behavior is the platform default there).
        // Desktop JVM needs the flag for Python re parity - the vector tests run
        // there and pin it.
        Pattern.compile(re)
    }
    private fun String.sub(pat: Pattern, repl: String): String = pat.matcher(this).replaceAll(repl)

    private val FENCE_OR_RULE = p("^\\s*(---+|```)\\s*$")
    private val TABLE_SEPARATOR = p("^\\s*\\|[\\s\\|:\\-]+\\|?\\s*$")
    private val MD_LINK = p("\\[([^\\]]*)\\]\\((?:[^)]*)\\)")
    private val RAW_URL = p("https?://\\S+")
    private val HEADING = p("^\\s{0,3}#{1,6}\\s*")
    private val BLOCKQUOTE = p("^\\s*>\\s?")
    private val BULLET = p("^\\s*[-*]\\s+")
    private val EM_OPEN = p("(?<!\\w)[*_](?=\\w)")
    private val EM_CLOSE = p("(?<=\\w)[*_](?!\\w)")
    private val CIRCLED = p("[①-⑨]")
    private val MD_FILE = p("\\b([\\w-]+)\\.md\\b")
    private val NUM_RANGE = p("(\\d)\\s*[–—\\-]\\s*(\\d)")
    private val NUM_K = p("(\\d)K\\b")
    private val DOLLARS = p("\\$\\s?(\\d[\\d,\\.]*)")
    private val NUM_PLUS = p("(\\d)\\+")
    private val ACRONYMS = listOf(
        p("\\bADRs\\b") to "architecture decision records",
        p("\\bADR\\b") to "architecture decision record",
        p("\\bhr/wk\\b") to "hours per week",
        p("\\bK8s\\b") to "Kubernetes",
        p("\\bIaC\\b") to "infrastructure as code",
        p("\\bJDs\\b") to "job descriptions",
        p("\\bJD\\b") to "job description",
        p("\\bPR #148\\b") to "pull request 148",
    )
    private val ENDS_PUNCT = p("[.!?:;,]$")
    private val SPACES = p("[ \\t]+")
    private val BLANKS = p("\\n{3,}")
    private val SENTENCE_END = p("(?<=[.!?])\\s+")

    fun cleanMarkdown(md: String): String {
        val out = ArrayList<String>()
        for (raw in md.split("\n")) {
            var line = raw.trimEnd()
            if (FENCE_OR_RULE.matcher(line).find()) {
                out.add(""); continue
            }
            if (line.trimStart().startsWith("|")) {
                if (TABLE_SEPARATOR.matcher(line).find()) continue
                val cells = line.trim().trim('|').split("|").map { it.trim() }.filter { it.isNotEmpty() }
                line = cells.joinToString(". ")
            }
            repeat(3) { line = line.sub(MD_LINK, "$1") }
            line = line.sub(RAW_URL, "")
            line = line.sub(HEADING, "")
            line = line.sub(BLOCKQUOTE, "")
            line = line.sub(BULLET, "")
            line = line.replace("`", "").replace("**", "").replace("__", "")
            line = line.sub(EM_OPEN, "")
            line = line.sub(EM_CLOSE, "")
            line = line.replace("*", "")
            out.add(line)
        }
        var text = out.joinToString("\n")
        text = CIRCLED.matcher(text).let { m ->
            // Spliced by hand: Matcher.appendReplacement/appendTail(StringBuilder)
            // are API 34 on Android and crash every narration on minSdk 29-33.
            val sb = StringBuilder()
            var last = 0
            while (m.find()) {
                sb.append(text, last, m.start()).append(" item ").append(m.group()[0].code - 0x245F)
                last = m.end()
            }
            sb.append(text, last, text.length).toString()
        }
        text = text.sub(MD_FILE, "$1")
        text = text.replace("§", "section ").replace("→", " to ").replace("↔", " and ")
        text = text.replace("≤", "at most ").replace("≥", "at least ")
        text = text.replace("×", " times ").replace("%", " percent")
        text = text.replace("·", ". ").replace("&middot;", ". ").replace("&nbsp;", " ")
        text = text.replace("&amp;", "and").replace("&emsp;", " ")
        text = text.sub(NUM_RANGE, "$1 to $2")
        text = text.replace("—", ", ").replace("–", ", ")
        text = text.sub(NUM_K, "$1 thousand")
        text = text.sub(DOLLARS, "$1 dollars")
        text = text.sub(NUM_PLUS, "$1 plus")
        for ((pat, value) in ACRONYMS) text = text.sub(pat, value)
        val fixed = ArrayList<String>()
        for (ln in text.split("\n")) {
            var s = ln.trim()
            if (s.isNotEmpty() && !ENDS_PUNCT.matcher(s).find() && s.length < 120) s += "."
            fixed.add(s)
        }
        text = fixed.joinToString("\n").sub(SPACES, " ")
        return text.sub(BLANKS, "\n\n").trim()
    }

    fun chunk(text: String, limit: Int = KOKORO_CHUNK_LIMIT): List<String> {
        val chunks = ArrayList<String>()
        var buf = ""
        val paragraphs = text.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
        for (para in paragraphs) {
            val parts = if (para.length > limit) SENTENCE_END.split(para).toList() else listOf(para)
            for (part in parts) {
                val add = (if (buf.isNotEmpty()) "\n\n" else "") + part
                if (buf.length + add.length > limit && buf.isNotEmpty()) {
                    chunks.add(buf); buf = part
                } else buf += add
            }
        }
        if (buf.isNotEmpty()) chunks.add(buf)
        return chunks
    }
}
