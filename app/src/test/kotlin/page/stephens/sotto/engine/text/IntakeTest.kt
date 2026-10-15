package page.stephens.sotto.engine.text

// Shared-text intake (PRD F2 and the empty/encoding edge cases), mirroring the
// server's convert(): decode UTF-8 with replacement, strip, reject empty, cap
// at 400 KB of UTF-8.

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class IntakeTest {

    @Test
    fun `whitespace-only text is rejected as empty`() {
        assertEquals(Intake.Checked.Empty, Intake.check(" \n\t  "))
    }

    @Test
    fun `accepted text is stripped like the server's`() {
        assertEquals(Intake.Checked.Ok("# Title\n\nBody."), Intake.check("\n  # Title\n\nBody.  \n"))
    }

    @Test
    fun `text exactly at the cap is accepted and one byte over is rejected`() {
        val atCap = "a".repeat(Intake.MAX_INPUT_BYTES)
        assertTrue(Intake.check(atCap) is Intake.Checked.Ok)
        assertTrue(Intake.check(atCap + "é") is Intake.Checked.TooLarge)
    }

    @Test
    fun `the cap counts UTF-8 bytes, not characters`() {
        // 'é' is two bytes in UTF-8: 210,000 of them is 420,000 bytes.
        assertTrue(Intake.check("é".repeat(210_000)) is Intake.Checked.TooLarge)
    }

    @Test
    fun `non-UTF-8 bytes decode with replacement instead of crashing`() {
        val latin1 = "café au lait".toByteArray(Charsets.ISO_8859_1)
        assertEquals("caf� au lait", Intake.read(ByteArrayInputStream(latin1)))
    }

    @Test
    fun `a UTF-8 byte-order mark is dropped`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "# Hi".toByteArray()
        assertEquals("# Hi", Intake.read(ByteArrayInputStream(bom)))
    }

    @Test
    fun `reading stops past the cap so a huge file cannot exhaust memory`() {
        val huge = ByteArray(Intake.MAX_INPUT_BYTES * 10) { 'a'.code.toByte() }
        val read = Intake.read(ByteArrayInputStream(huge))
        assertTrue(read.length < huge.size)
        assertTrue(Intake.check(read) is Intake.Checked.TooLarge)
    }
}
