package page.stephens.sotto.engine.store

// File-backed AudioSink: append-only writes, truncate-to-offset for resume, and
// a real fsync so data-model invariant 1 holds (progress advances only after the
// bytes are durable). Pure java.io - JVM-unit-testable.

import page.stephens.sotto.engine.narrate.AudioSink
import java.io.File
import java.io.RandomAccessFile

class FileAudioSink(private val file: File) : AudioSink, AutoCloseable {
    private val raf = RandomAccessFile(file.also { it.parentFile?.mkdirs() }, "rw")
        .apply { seek(length()) }

    override fun length(): Long = raf.length()

    override fun truncate(toBytes: Long) {
        raf.setLength(toBytes)
        raf.seek(toBytes)
    }

    override fun append(bytes: ByteArray) {
        raf.seek(raf.length())
        raf.write(bytes)
    }

    override fun sync() = raf.fd.sync()

    override fun close() = raf.close()
}
