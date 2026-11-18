package page.stephens.sotto.engine.text

// Shared-text intake, mirroring the server's convert(): bytes decode as UTF-8
// with replacement (a Latin-1 file must never crash the share), the text is
// stripped, empty input is refused, and the cap is 400 KB of UTF-8 (MAX_INPUT
// in sotto app.py; PRD F2 and the edge-case list).

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction

object Intake {
    const val MAX_INPUT_BYTES = 400 * 1024

    sealed interface Source {
        data class Text(val text: String) : Source
        class Stream(val open: () -> InputStream?) : Source
    }

    sealed interface Result {
        data class Accepted(val text: String) : Result
        data object Empty : Result
        data object TooLarge : Result
        data object Unreadable : Result
    }

    suspend fun accept(source: Source): Result = withContext(Dispatchers.IO) {
        try {
            when (source) {
                is Source.Text -> when (val checked = check(source.text)) {
                    is Checked.Ok -> Result.Accepted(checked.text)
                    Checked.Empty -> Result.Empty
                    is Checked.TooLarge -> Result.TooLarge
                }
                is Source.Stream -> source.open()?.use { readComplete(it) } ?: Result.Unreadable
            }
        } catch (failure: IOException) {
            currentCoroutineContext().ensureActive()
            Result.Unreadable
        } catch (failure: SecurityException) {
            currentCoroutineContext().ensureActive()
            Result.Unreadable
        }
    }

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

    private suspend fun readComplete(input: InputStream): Result {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val reader = InputStreamReader(input, decoder)
        val buffer = CharArray(4096)
        val text = StringBuilder()
        val whitespace = StringBuilder()
        var bytes = 0
        var whitespaceBytes = 0
        var first = true
        var highSurrogate: Char? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = reader.read(buffer)
            if (count < 0) break
            for (index in 0 until count) {
                val char = buffer[index]
                if (first) {
                    first = false
                    if (char == '\uFEFF') continue
                }
                if (char.isHighSurrogate()) {
                    highSurrogate = char
                    continue
                }
                val high = highSurrogate
                highSurrogate = null
                val charBytes = when {
                    high != null -> 4
                    char.code < 0x80 -> 1
                    char.code < 0x800 -> 2
                    else -> 3
                }
                if (high == null && char.isWhitespace()) {
                    if (text.isEmpty()) continue
                    // Overflow stays undecided until EOF or a later nonwhitespace character.
                    if (bytes + whitespaceBytes + charBytes <= MAX_INPUT_BYTES) whitespace.append(char)
                    whitespaceBytes = (whitespaceBytes + charBytes).coerceAtMost(MAX_INPUT_BYTES + 1)
                } else {
                    if (bytes + whitespaceBytes + charBytes > MAX_INPUT_BYTES) return Result.TooLarge
                    text.append(whitespace)
                    whitespace.setLength(0)
                    bytes += whitespaceBytes + charBytes
                    whitespaceBytes = 0
                    if (high != null) text.append(high)
                    text.append(char)
                }
            }
        }
        return if (text.isEmpty()) Result.Empty else Result.Accepted(text.toString())
    }
}
