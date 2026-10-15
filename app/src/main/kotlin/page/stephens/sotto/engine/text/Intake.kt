package page.stephens.sotto.engine.text

// Shared-text intake, mirroring the server's convert(): bytes decode as UTF-8
// with replacement (a Latin-1 file must never crash the share), the text is
// stripped, empty input is refused, and the cap is 400 KB of UTF-8 (MAX_INPUT
// in sotto app.py; PRD F2 and the edge-case list).

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

object Intake {
    const val MAX_INPUT_BYTES = 400 * 1024

    sealed class Checked {
        data class Ok(val text: String) : Checked()
        data object Empty : Checked()
        data class TooLarge(val bytes: Int) : Checked()
    }

    fun check(raw: String): Checked {
        val text = raw.trim()
        if (text.isEmpty()) return Checked.Empty
        val bytes = text.toByteArray(Charsets.UTF_8).size
        return if (bytes > MAX_INPUT_BYTES) Checked.TooLarge(bytes) else Checked.Ok(text)
    }

    /** Decodes a shared file. Reads at most twice the cap (leading/trailing
     *  whitespace may still bring an oversized read under it after the strip);
     *  anything beyond that is certainly too large, so stop rather than load it. */
    fun read(input: InputStream): String {
        val limit = MAX_INPUT_BYTES * 2 + 1
        val buf = ByteArray(limit)
        var n = 0
        while (n < limit) {
            val r = input.read(buf, n, limit - n)   // readNBytes is API 33; minSdk is 29
            if (r < 0) break
            n += r
        }
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(buf, 0, n))
            .toString()
        return text.removePrefix("\uFEFF")
    }
}
