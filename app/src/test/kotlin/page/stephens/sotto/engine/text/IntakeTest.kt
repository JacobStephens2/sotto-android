package page.stephens.sotto.engine.text

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class IntakeTest {
    private fun stream(raw: String) = Intake.Source.Stream { raw.byteInputStream() }
    private fun accept(source: Intake.Source) = runBlocking { Intake.accept(source) }

    @Test
    fun `interior whitespace beyond the read buffer cannot hide the tail`() {
        val raw = "A" + " ".repeat(819_200) + "TAIL"
        assertEquals(Intake.Result.TooLarge, accept(stream(raw)))
    }

    @Test
    fun `arbitrarily long surrounding whitespace is discarded only at document boundaries`() {
        val raw = " ".repeat(819_201) + "short" + " \n\t".repeat(400_000)
        assertEquals(Intake.Result.Accepted("short"), accept(stream(raw)))
        assertEquals(Intake.Result.Empty, accept(stream(" \n\t".repeat(400_000))))
    }

    @Test
    fun `an undecided whitespace prefix cannot be accepted before EOF`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val tail = object : ByteArrayInputStream("TAIL".toByteArray()) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return super.read(b, off, len)
            }
        }
        val result = async(Dispatchers.Default) {
            Intake.accept(Intake.Source.Stream {
                SequenceInputStream(("A" + " ".repeat(819_200)).byteInputStream(), tail)
            })
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(result.isCompleted)
        } finally { release.countDown() }
        assertEquals(Intake.Result.TooLarge, result.await())
    }

    @Test
    fun `text and files share complete normalized validation at byte boundaries`() {
        val samples = listOf(
            " \n\t", "\u2003# Title\n\nBody.\u00a0", "a".repeat(Intake.MAX_INPUT_BYTES),
            "a".repeat(Intake.MAX_INPUT_BYTES) + "b", "é".repeat(204_800),
            "é".repeat(204_800) + "é", "😀".repeat(102_400), "😀".repeat(102_400) + "x",
            "a" + "\u2003".repeat(136_533) + "z",
        )
        samples.forEach { raw ->
            val expected = when (val checked = Intake.check(raw)) {
                is Intake.Checked.Ok -> Intake.Result.Accepted(checked.text)
                Intake.Checked.Empty -> Intake.Result.Empty
                is Intake.Checked.TooLarge -> Intake.Result.TooLarge
            }
            assertEquals(expected, accept(Intake.Source.Text(raw)))
            assertEquals(expected, accept(stream(raw)))
        }
    }

    @Test
    fun `split UTF-8 sequences and surrogate pairs preserve text and normalized byte accounting`() {
        val raw = "a".repeat(4095) + "😀é\u2003tail"
        for (width in 1..7) {
            val source = Intake.Source.Stream {
                object : ByteArrayInputStream(raw.toByteArray()) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, width))
                }
            }
            assertEquals(Intake.Result.Accepted(raw), accept(source))
        }
    }

    @Test
    fun `malformed UTF-8 uses replacement including an incomplete final sequence`() {
        val bytes = byteArrayOf(0x61, 0xc3.toByte(), 0x28, 0xf0.toByte(), 0x9f.toByte())
        assertEquals(Intake.Result.Accepted("a�(�"), accept(Intake.Source.Stream { bytes.inputStream() }))
        val over = ByteArray(136_534) { 0xff.toByte() }
        assertEquals(Intake.Result.TooLarge, accept(Intake.Source.Stream { over.inputStream() }))
    }

    @Test
    fun `only files remove exactly one initial BOM before trimming`() {
        assertEquals(Intake.Result.Accepted("Hi"), accept(stream("\uFEFF Hi ")))
        assertEquals(Intake.Result.Accepted("\uFEFFHi"), accept(stream("\uFEFF\uFEFFHi")))
        assertEquals(Intake.Result.Accepted("\uFEFF Hi"), accept(Intake.Source.Text("\uFEFF Hi ")))
        assertEquals(Intake.Result.Accepted("\uFEFFHi"), accept(stream(" \uFEFFHi")))
    }

    @Test
    fun `real file streams are decoded and closed`() {
        val file = File.createTempFile("intake", ".txt")
        try {
            file.writeText("\uFEFF  Complete document. \n")
            assertEquals(Intake.Result.Accepted("Complete document."), accept(Intake.Source.Stream { file.inputStream() }))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `open read and close run away from the caller and every outcome closes`() {
        val caller = Thread.currentThread()
        listOf("hello", " \n", "a".repeat(Intake.MAX_INPUT_BYTES + 1)).forEach { raw ->
            var closed = false
            val source = Intake.Source.Stream {
                assertNotSame(caller, Thread.currentThread())
                object : ByteArrayInputStream(raw.toByteArray()) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        assertNotSame(caller, Thread.currentThread())
                        return super.read(b, off, len)
                    }
                    override fun close() {
                        assertNotSame(caller, Thread.currentThread())
                        closed = true
                    }
                }
            }
            accept(source)
            assertTrue(closed)
        }
    }

    @Test
    fun `expected open read and close failures are unreadable and programming failures propagate`() {
        assertEquals(Intake.Result.Unreadable, accept(Intake.Source.Stream { null }))
        for (failure in listOf(IOException("io"), SecurityException("access"))) {
            assertEquals(Intake.Result.Unreadable, accept(Intake.Source.Stream { throw failure }))
            for (failOnRead in listOf(true, false)) {
                var closed = false
                val source = Intake.Source.Stream {
                    object : ByteArrayInputStream("document".toByteArray()) {
                        override fun read(b: ByteArray, off: Int, len: Int): Int =
                            if (failOnRead) throw failure else super.read(b, off, len)
                        override fun close() {
                            closed = true
                            if (!failOnRead) throw failure
                        }
                    }
                }
                assertEquals(Intake.Result.Unreadable, accept(source))
                assertTrue(closed)
            }
        }
        assertThrows(IllegalStateException::class.java) {
            accept(Intake.Source.Stream { error("programming failure") })
        }
    }

    @Test
    fun `cancellation propagates and closes a blocked read after the provider returns`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val result = async(Dispatchers.Default) {
            Intake.accept(Intake.Source.Stream {
                object : InputStream() {
                    override fun read(): Int = error("use bulk reads")
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        b[off] = 'a'.code.toByte()
                        return 1
                    }
                    override fun close() { closed.countDown() }
                }
            })
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            result.cancel()
        } finally {
            release.countDown()
            result.cancelAndJoin()
        }
        assertTrue(result.isCancelled)
        assertTrue(closed.await(5, TimeUnit.SECONDS))
    }
}
