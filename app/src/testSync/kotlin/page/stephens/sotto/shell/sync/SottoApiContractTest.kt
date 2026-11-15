package page.stephens.sotto.shell.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class SottoApiContractTest {

    private data class LoopbackExchange(
        val method: String,
        val rawPath: String,
        val headers: Map<String, String>,
        val body: ByteArray,
        val socket: Socket,
        val out: OutputStream,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    private lateinit var serverSocket: ServerSocket
    private lateinit var baseUrl: String
    private val tempRoot = Files.createTempDirectory("sotto-api-contract").toFile()
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val stopped = AtomicBoolean(false)
    private val handler = AtomicReference<(LoopbackExchange) -> Unit>({ ex ->
        replyText(ex, 404, "not found")
    })

    @Before
    fun startServer() {
        serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        baseUrl = "http://127.0.0.1:${serverSocket.localPort}"
        executor.execute {
            while (!stopped.get() && !serverSocket.isClosed) {
                val client = try {
                    serverSocket.accept()
                } catch (_: IOException) {
                    break
                }
                executor.execute {
                    handleConnection(client)
                }
            }
        }
    }

    @After
    fun stopServer() {
        stopped.set(true)
        runCatching { serverSocket.close() }
        executor.shutdownNow()
        tempRoot.deleteRecursively()
    }

    @Test
    fun `token issuance and account capabilities parse contract fields and attach bearer auth`() = runBlocking {
        var sawTokenBody: JSONObject? = null
        var sawAuthHeader: String? = null
        var sawUserAgent: String? = null
        handler.set { ex ->
            when (ex.rawPath) {
                "/api/v1/token" -> {
                    assertEquals("POST", ex.method)
                    sawTokenBody = JSONObject(ex.body.decodeToString())
                    replyJson(ex, 201, JSONObject().put("token", "tok-123").put("created", "now").toString())
                }
                "/api/v1/me" -> {
                    assertEquals("GET", ex.method)
                    sawAuthHeader = ex.header("Authorization")
                    sawUserAgent = ex.header("User-Agent")
                    replyJson(
                        ex,
                        200,
                        JSONObject()
                            .put("email", "reader@example.com")
                            .put("may_openai", true)
                            .put("openai_voices", JSONArray(listOf("onyx", "nova")))
                            .put("default_voice", "af_heart")
                            .toString(),
                    )
                }
                else -> replyText(ex, 404, "not found")
            }
        }

        val api: RemoteServer = SottoApi()
        val token = api.requestToken(baseUrl, "reader@example.com", "secret", "sotto-android")
        assertEquals("tok-123", token)
        assertEquals("reader@example.com", sawTokenBody?.getString("email"))
        assertEquals("secret", sawTokenBody?.getString("password"))
        assertEquals("sotto-android", sawTokenBody?.getString("label"))

        val account = api.account(baseUrl, token)
        assertEquals("Bearer tok-123", sawAuthHeader)
        assertTrue(sawUserAgent.orEmpty().contains("sotto-android"))
        assertEquals(ServerAccount("reader@example.com", true, listOf("onyx", "nova"), "af_heart"), account)
    }

    @Test
    fun `dynamic path segments percent-encode special characters and reject blank or traversal segments`() = runBlocking {
        val seenRawPaths = mutableListOf<String>()
        handler.set { ex ->
            seenRawPaths.add(ex.rawPath)
            when {
                ex.rawPath.endsWith("/audio") -> replyBytes(ex, 200, byteArrayOf(1, 2, 3))
                ex.rawPath.endsWith("/text") -> replyText(ex, 200, "hello")
                ex.method == "PATCH" -> replyJson(ex, 200, "{}")
                ex.method == "GET" && ex.rawPath.startsWith("/api/v1/jobs/") ->
                    replyJson(ex, 200, JSONObject().put("status", "running").put("pct", 50).toString())
                else -> replyText(ex, 404, "nope")
            }
        }

        val api: RemoteServer = SottoApi()
        val weirdName = "My Essay #1?v=2&x=100% + café.aac"
        val destAudio = File(tempRoot, "weird/audio.aac")
        val destText = File(tempRoot, "weird/source.md")
        api.downloadAudio(baseUrl, "tok", weirdName, destAudio)
        assertTrue(api.downloadText(baseUrl, "tok", weirdName, destText))
        api.rename(baseUrl, "tok", weirdName, "Renamed")
        api.jobStatus(baseUrl, "tok", "job #9/part".replace("/", "_"))

        val expectedEncoded = "My%20Essay%20%231%3Fv%3D2%26x%3D100%25%20%2B%20caf%C3%A9.aac"
        assertEquals("/api/v1/library/$expectedEncoded/audio", seenRawPaths[0])
        assertEquals("/api/v1/library/$expectedEncoded/text", seenRawPaths[1])
        assertEquals("/api/v1/library/$expectedEncoded", seenRawPaths[2])
        assertEquals("/api/v1/jobs/job%20%239_part", seenRawPaths[3])

        for (bad in listOf("", "   ", ".", "..", "../secret", "a/b", "a\\b")) {
            try {
                api.downloadAudio(baseUrl, "tok", bad, File(tempRoot, "bad.aac"))
                fail("Expected IllegalArgumentException for '$bad'")
            } catch (_: IllegalArgumentException) {
            }
            try {
                api.jobStatus(baseUrl, "tok", bad)
                fail("Expected IllegalArgumentException for jobId '$bad'")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `downloads handle optional 404 and 200 text, clean temp files on error or rename failure, and propagate non-2xx`() = runBlocking {
        val audioBytes = ByteArray(128 * 1024) { (it % 251).toByte() }
        handler.set { ex ->
            when (ex.rawPath) {
                "/api/v1/library/doc.aac/audio" -> replyBytes(ex, 200, audioBytes)
                "/api/v1/library/doc.aac/text" -> replyText(ex, 200, "# Source\nBody")
                "/api/v1/library/notext.aac/text" -> replyJson(ex, 404, """{"error":"not_found","detail":"no source"}""")
                "/api/v1/library/broken.aac/audio" -> replyJson(ex, 500, """{"error":"boom","detail":"disk error"}""")
                "/api/v1/library/truncated.aac/audio" -> {
                    val hdr = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: 10000\r\nConnection: close\r\n\r\n"
                    ex.out.write(hdr.toByteArray(Charsets.US_ASCII))
                    ex.out.write(byteArrayOf(1, 2, 3))
                    ex.out.flush()
                    ex.socket.close()
                }
                else -> replyText(ex, 404, "missing")
            }
        }

        val api: RemoteServer = SottoApi()
        val okDir = File(tempRoot, "ok").apply { mkdirs() }
        val audioFile = File(okDir, "audio.aac")
        val textFile = File(okDir, "source.md")
        var lastBytes = 0L
        api.downloadAudio(baseUrl, "tok", "doc.aac", audioFile) { lastBytes = it }
        assertEquals(audioBytes.size.toLong(), lastBytes)
        assertArrayEquals(audioBytes, audioFile.readBytes())
        assertTrue(api.downloadText(baseUrl, "tok", "doc.aac", textFile))
        assertEquals("# Source\nBody", textFile.readText())

        assertFalse(api.downloadText(baseUrl, "tok", "notext.aac", File(okDir, "missing.md")))
        assertFalse(File(okDir, "missing.md").exists())

        val failDir = File(tempRoot, "fail").apply { mkdirs() }
        val serverErr = try {
            api.downloadAudio(baseUrl, "tok", "broken.aac", File(failDir, "audio.aac"))
            null
        } catch (e: ApiException) {
            e
        }
        assertEquals(500, serverErr?.code)
        assertTrue(serverErr?.message.orEmpty().contains("disk error"))
        assertTrue(failDir.listFiles()!!.isEmpty())

        val truncErr = try {
            api.downloadAudio(baseUrl, "tok", "truncated.aac", File(failDir, "trunc.aac"))
            null
        } catch (e: IOException) {
            e
        }
        assertTrue(truncErr != null)
        assertTrue(failDir.listFiles()!!.isEmpty())

        // Rename failure: destination is a non-empty directory, so renameTo fails and temp file must be cleaned up.
        val renameParent = File(tempRoot, "rename-fail").apply { mkdirs() }
        val blockingDir = File(renameParent, "audio.aac").apply { mkdirs() }
        File(blockingDir, "keep").writeText("keep")
        val renameErr = try {
            api.downloadAudio(baseUrl, "tok", "doc.aac", blockingDir)
            null
        } catch (e: IOException) {
            e
        }
        assertTrue(renameErr != null)
        assertEquals(listOf("audio.aac"), renameParent.list()!!.toList())
        assertEquals("keep", File(blockingDir, "keep").readText())
    }

    @Test
    fun `upload sends multipart with If-None-Match 409 retry and rename uses PATCH`() = runBlocking {
        val requests = mutableListOf<Triple<String, String?, String>>()
        handler.set { ex ->
            val body = ex.body.decodeToString()
            val ifNoneMatch = ex.header("If-None-Match")
            requests.add(Triple("${ex.method} ${ex.rawPath}", ifNoneMatch, body))
            when {
                ex.method == "PUT" && ex.rawPath == "/api/v1/library/essay.aac" && ifNoneMatch == "*" ->
                    replyJson(ex, 409, """{"error":"conflict","detail":"exists"}""")
                ex.method == "PUT" && ex.rawPath == "/api/v1/library/essay-abc123.aac" ->
                    replyJson(ex, 201, "{}")
                ex.method == "PATCH" && ex.rawPath == "/api/v1/library/essay-abc123.aac" ->
                    replyJson(ex, 200, "{}")
                else -> replyText(ex, 400, "unexpected")
            }
        }

        val api: RemoteServer = SottoApi()
        val audio = File(tempRoot, "up.aac").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val text = File(tempRoot, "up.md").apply { writeText("Source") }
        val conflict = try {
            api.upload(baseUrl, "tok", "essay.aac", audio, text, """{"title":"Essay"}""", ifNoneMatch = true)
            null
        } catch (e: ApiException) {
            e
        }
        assertEquals(409, conflict?.code)
        assertTrue(api.upload(baseUrl, "tok", "essay-abc123.aac", audio, text, """{"title":"Essay"}""", ifNoneMatch = false))
        assertTrue(requests[1].third.contains("name=\"meta\""))
        assertTrue(requests[1].third.contains("name=\"audio\""))
        assertTrue(requests[1].third.contains("name=\"text\""))
        assertTrue(requests[1].third.contains("Source"))

        api.rename(baseUrl, "tok", "essay-abc123.aac", "New Title")
        assertEquals("PATCH /api/v1/library/essay-abc123.aac", requests[2].first)
        assertEquals("New Title", JSONObject(requests[2].third).getString("title"))
    }

    @Test
    fun `HTTP 401 is distinguished across endpoints and configurable timeouts apply`() = runBlocking {
        val slowEntered = CountDownLatch(1)
        val releaseSlow = CountDownLatch(1)
        handler.set { ex ->
            when (ex.rawPath) {
                "/api/v1/library" -> replyJson(ex, 401, """{"error":"unauthorized","detail":"token revoked"}""")
                "/api/v1/library/a.aac/audio" -> replyJson(ex, 401, """{"error":"unauthorized","detail":"expired"}""")
                "/api/v1/me" -> {
                    slowEntered.countDown()
                    releaseSlow.await(5, TimeUnit.SECONDS)
                    replyJson(ex, 200, """{"email":"a@b.com","may_openai":false,"openai_voices":[],"default_voice":"af_heart"}""")
                }
                else -> replyText(ex, 404, "")
            }
        }

        val api: RemoteServer = SottoApi(connectTimeoutMs = 1_000, readTimeoutMs = 150)
        try {
            val libErr = try {
                api.library(baseUrl, "bad")
                null
            } catch (e: ApiException) {
                e
            }
            assertTrue(libErr is UnauthorizedApiException)
            assertTrue(libErr!!.isUnauthorized)
            assertEquals(401, libErr.code)

            val dlErr = try {
                api.downloadAudio(baseUrl, "bad", "a.aac", File(tempRoot, "dl.aac"))
                null
            } catch (e: ApiException) {
                e
            }
            assertTrue(dlErr is UnauthorizedApiException)
            assertTrue(dlErr!!.isUnauthorized)

            val timeoutErr = assertThrows(SocketTimeoutException::class.java) {
                runBlocking { api.account(baseUrl, "tok") }
            }
            assertTrue(timeoutErr is SocketTimeoutException)
        } finally {
            releaseSlow.countDown()
        }
    }

    @Test
    fun `coroutine cancellation promptly disconnects an active HTTP download and cleans temp files`() = runBlocking {
        val startedStreaming = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        val serverSawIoError = CountDownLatch(1)
        handler.set { ex ->
            val hdr = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
            try {
                ex.out.write(hdr.toByteArray(Charsets.US_ASCII))
                val chunk = ByteArray(1024) { 5 }
                ex.out.write("${Integer.toHexString(chunk.size)}\r\n".toByteArray(Charsets.US_ASCII))
                ex.out.write(chunk)
                ex.out.write("\r\n".toByteArray(Charsets.US_ASCII))
                ex.out.flush()
                startedStreaming.countDown()
                releaseServer.await(10, TimeUnit.SECONDS)
                // Loop brief writes so Linux loopback delivers RST after the client closes the socket.
                repeat(20) {
                    ex.out.write("1\r\nA\r\n".toByteArray(Charsets.US_ASCII))
                    ex.out.flush()
                    Thread.sleep(25)
                }
            } catch (_: IOException) {
                serverSawIoError.countDown()
            } finally {
                runCatching { ex.socket.close() }
            }
        }

        val api: RemoteServer = SottoApi(connectTimeoutMs = 5_000, readTimeoutMs = 30_000)
        val dir = File(tempRoot, "cancel").apply { mkdirs() }
        val dest = File(dir, "audio.aac")
        val job = async(Dispatchers.IO) {
            api.downloadAudio(baseUrl, "tok", "slow.aac", dest)
        }
        try {
            assertTrue(startedStreaming.await(5, TimeUnit.SECONDS))
            withTimeout(2_000) {
                job.cancel()
                try {
                    job.await()
                } catch (e: CancellationException) {
                    if (e is TimeoutCancellationException) throw e
                }
            }
            assertFalse(dest.exists())
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally {
            releaseServer.countDown()
        }
        assertTrue(serverSawIoError.await(2, TimeUnit.SECONDS))
    }

    private fun handleConnection(socket: Socket) {
        socket.use { sock ->
            sock.soTimeout = 10_000
            val input = BufferedInputStream(sock.getInputStream())
            val out = sock.getOutputStream()
            val requestLine = readLineAscii(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0]
            val rawTarget = parts[1].substringBefore('?')
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = readLineAscii(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
            }
            val body = readRequestBody(input, headers)
            handler.get().invoke(LoopbackExchange(method, rawTarget, headers, body, sock, out))
        }
    }

    private fun readRequestBody(input: InputStream, headers: Map<String, String>): ByteArray {
        val transferEncoding = headers["transfer-encoding"] ?: ""
        if (transferEncoding.contains("chunked", ignoreCase = true)) {
            val out = ByteArrayOutputStream()
            while (true) {
                val sizeLine = readLineAscii(input) ?: break
                val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
                if (size == 0) {
                    readLineAscii(input)
                    break
                }
                val chunk = readExact(input, size)
                out.write(chunk)
                readLineAscii(input)
            }
            return out.toByteArray()
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        return if (contentLength > 0) readExact(input, contentLength) else ByteArray(0)
    }

    private fun readExact(input: InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buf, offset, count - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == count) buf else buf.copyOf(offset)
    }

    private fun readLineAscii(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var sawAny = false
        while (true) {
            val b = input.read()
            if (b < 0) return if (sawAny) out.toByteArray().toString(Charsets.US_ASCII) else null
            sawAny = true
            if (b == '\n'.code) {
                val bytes = out.toByteArray()
                val len = if (bytes.isNotEmpty() && bytes[bytes.size - 1] == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return bytes.decodeToString(0, len)
            }
            out.write(b)
        }
    }

    private fun replyJson(ex: LoopbackExchange, code: Int, json: String) =
        replyRaw(ex, code, "application/json", json.toByteArray(Charsets.UTF_8))

    private fun replyText(ex: LoopbackExchange, code: Int, text: String) =
        replyRaw(ex, code, "text/plain", text.toByteArray(Charsets.UTF_8))

    private fun replyBytes(ex: LoopbackExchange, code: Int, bytes: ByteArray) =
        replyRaw(ex, code, "application/octet-stream", bytes)

    private fun replyRaw(ex: LoopbackExchange, code: Int, contentType: String, bytes: ByteArray) {
        val hdr = "HTTP/1.1 $code OK\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        ex.out.write(hdr.toByteArray(Charsets.US_ASCII))
        ex.out.write(bytes)
        ex.out.flush()
    }
}
