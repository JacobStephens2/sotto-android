package page.stephens.sotto.shell.ui

// Constitution boundary 6: the About screen names Kokoro-82M, its Apache-2.0
// license and documented training data, calls the voice synthetic, and - in
// `local` only - states the no-network property next to its verification
// command. The `sync` build must not borrow that claim (ADR-007/008).

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvenanceTest {

    private fun assertProvenance(text: String) {
        listOf("Kokoro-82M", "Apache-2.0", "synthetic", "public-domain").forEach {
            assertTrue("About must say \"$it\"", it in text)
        }
    }

    @Test
    fun `the local build names the model and states the no-network claim with its proof`() {
        val text = Provenance.about(networked = false)
        assertProvenance(text)
        assertTrue("no INTERNET permission" in text)
        assertTrue("aapt dump permissions" in text)
    }

    @Test
    fun `the sync build names the model and does not borrow the no-network claim`() {
        val text = Provenance.about(networked = true)
        assertProvenance(text)
        assertFalse("INTERNET permission" in text)
        assertFalse("Nothing you share with Sotto leaves the phone" in text)
        assertTrue("OpenAI" in text)
    }
}
