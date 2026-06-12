package page.stephens.sotto.engine.store

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class FileAudioSinkTest {

    private fun temp(): File = File.createTempFile("sink", ".bin").apply { deleteOnExit() }

    @Test
    fun `append grows the file and survives reopen`() {
        val f = temp()
        FileAudioSink(f).use { it.append(byteArrayOf(1, 2, 3)); it.sync() }
        FileAudioSink(f).use {
            assertEquals(3L, it.length())
            it.append(byteArrayOf(4, 5)); it.sync()
        }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), f.readBytes())
    }

    @Test
    fun `truncate drops garbage past the durable offset`() {
        val f = temp()
        FileAudioSink(f).use { it.append(byteArrayOf(1, 2, 3, 4)); it.sync() }
        f.appendBytes(byteArrayOf(99, 98))            // crash garbage past the offset
        FileAudioSink(f).use {
            it.truncate(4L)
            it.append(byteArrayOf(5)); it.sync()
        }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), f.readBytes())
    }

    @Test
    fun `truncate to zero resets cleanly`() {
        val f = temp()
        FileAudioSink(f).use { it.append(byteArrayOf(7, 7)); it.sync(); it.truncate(0); assertEquals(0L, it.length()) }
    }
}
