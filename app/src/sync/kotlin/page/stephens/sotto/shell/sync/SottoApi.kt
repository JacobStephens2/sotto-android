package page.stephens.sotto.shell.sync

// The sync flavor's entire network surface: a thin HttpURLConnection client for
// the server's /api/v1 (docs/12-api-contract.md). Deliberately stdlib-only -
// no HTTP library enters the dependency graph, which keeps the constitution's
// allowlist trivial and the audit surface one file.

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import page.stephens.sotto.BuildConfig
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class SottoApi(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 120_000,
    private val userAgent: String = "sotto-android/${BuildConfig.VERSION_NAME} (sync)",
) : RemoteServer {

    override suspend fun requestToken(
        serverUrl: String,
        email: String,
        password: String,
        label: String,
    ): String {
        val body = JSONObject().put("email", email).put("password", password).put("label", label)
        val resp = call(serverUrl, null, "POST", "/api/v1/token", json = body.toString())
        return JSONObject(resp).getString("token")
    }

    override suspend fun account(serverUrl: String, token: String): ServerAccount {
        val o = JSONObject(call(serverUrl, token, "GET", "/api/v1/me"))
        val ov = o.optJSONArray("openai_voices")
        return ServerAccount(
            email = o.getString("email"),
            mayOpenai = o.optBoolean("may_openai"),
            openaiVoices = (0 until (ov?.length() ?: 0)).map { ov!!.getString(it) },
            defaultVoice = o.optString("default_voice", "af_heart"),
        )
    }

    override suspend fun narrate(
        serverUrl: String,
        token: String,
        text: String,
        voice: String,
        title: String,
    ): String {
        val body = JSONObject().put("text", text).put("voice", voice).put("title", title)
        return JSONObject(call(serverUrl, token, "POST", "/api/v1/narrate", json = body.toString()))
            .getString("job_id")
    }

    override suspend fun jobStatus(serverUrl: String, token: String, jobId: String): ServerJob {
        val encodedJob = encodeSegment(jobId)
        val o = JSONObject(call(serverUrl, token, "GET", "/api/v1/jobs/$encodedJob"))
        return ServerJob(
            status = o.optString("status", "unknown"),
            pct = o.optInt("pct"),
            name = if (o.isNull("name")) null else o.optString("name"),
            error = if (o.isNull("error")) null else o.optString("error"),
        )
    }

    override suspend fun library(serverUrl: String, token: String): List<RemoteEntry> {
        val arr = JSONObject(call(serverUrl, token, "GET", "/api/v1/library")).getJSONArray("entries")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RemoteEntry(
                name = o.getString("name"),
                title = o.getString("title"),
                words = if (o.isNull("words")) null else o.getInt("words"),
                secs = if (o.isNull("secs")) null else o.getLong("secs"),
                voice = if (o.isNull("voice")) null else o.getString("voice"),
                size = o.getLong("size"),
                durationSecs = if (o.isNull("duration_secs")) null else o.getLong("duration_secs"),
                mtime = o.getLong("mtime"),
                hasText = o.optBoolean("has_text"),
            )
        }
    }

    override suspend fun downloadAudio(
        serverUrl: String,
        token: String,
        name: String,
        dest: File,
        onBytes: ((Long) -> Unit)?,
    ) = downloadTo(serverUrl, token, "/api/v1/library/${encodeSegment(name)}/audio", dest, onBytes)

    override suspend fun downloadText(
        serverUrl: String,
        token: String,
        name: String,
        dest: File,
    ): Boolean = try {
        downloadTo(serverUrl, token, "/api/v1/library/${encodeSegment(name)}/text", dest)
        true
    } catch (e: ApiException) {
        if (e.code == HttpURLConnection.HTTP_NOT_FOUND) false else throw e
    }

    override suspend fun upload(
        serverUrl: String,
        token: String,
        name: String,
        audio: File,
        text: File?,
        metaJson: String,
        ifNoneMatch: Boolean,
    ): Boolean {
        val path = "/api/v1/library/${encodeSegment(name)}"
        return withConnection(serverUrl, token, "PUT", path) { conn, _ ->
            val boundary = "sotto-${UUID.randomUUID()}"
            if (ifNoneMatch) conn.setRequestProperty("If-None-Match", "*")
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.doOutput = true
            conn.setChunkedStreamingMode(0)
            conn.outputStream.use { out ->
                fun part(headers: String, write: () -> Unit) {
                    out.write("--$boundary\r\n$headers\r\n\r\n".toByteArray())
                    write()
                    out.write("\r\n".toByteArray())
                }
                part("Content-Disposition: form-data; name=\"meta\"") { out.write(metaJson.toByteArray()) }
                part("Content-Disposition: form-data; name=\"audio\"; filename=\"a\"\r\nContent-Type: application/octet-stream") {
                    audio.inputStream().use { it.copyTo(out) }
                }
                if (text != null) {
                    part("Content-Disposition: form-data; name=\"text\"; filename=\"t\"\r\nContent-Type: text/markdown") {
                        text.inputStream().use { it.copyTo(out) }
                    }
                }
                out.write("--$boundary--\r\n".toByteArray())
            }
            val code = conn.responseCode
            if (code !in 200..299) throwApiError(conn, code)
            runCatching { conn.inputStream?.use { it.readBytes() } }
            code == HttpURLConnection.HTTP_CREATED
        }
    }

    override suspend fun rename(serverUrl: String, token: String, name: String, title: String) {
        val path = "/api/v1/library/${encodeSegment(name)}"
        call(serverUrl, token, "PATCH", path, json = JSONObject().put("title", title).toString())
    }

    // --- plumbing ---------------------------------------------------------

    private fun buildUrl(serverUrl: String, path: String): URL {
        val base = serverUrl.trim().trimEnd('/')
        val url = URL(base + path)
        require(url.protocol == "http" || url.protocol == "https") { "Unsupported server URL scheme: ${url.protocol}" }
        require(url.host.isNotBlank()) { "Server URL must include a host" }
        return url
    }

    private fun configureConnection(url: URL, token: String?, method: String): HttpURLConnection {
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        conn.setRequestProperty("User-Agent", userAgent)
        return conn
    }

    private class AbortableHandle {
        val cancelled = AtomicBoolean(false)
        val connRef = AtomicReference<HttpURLConnection?>()
        val socketRef = AtomicReference<Socket?>()

        fun abortAsync() {
            cancelled.set(true)
            val conn = connRef.getAndSet(null)
            val sock = socketRef.getAndSet(null)
            if (conn != null || sock != null) {
                runCatching {
                    Dispatchers.IO.asExecutor().execute {
                        runCatching { sock?.close() }
                        runCatching { conn?.disconnect() }
                    }
                }
            }
        }
    }

    private suspend fun <T> runCancellableIo(block: (AbortableHandle) -> T): T {
        val ioDone = CompletableDeferred<Unit>()
        try {
            return suspendCancellableCoroutine { cont ->
                val handle = AbortableHandle()
                cont.invokeOnCancellation { handle.abortAsync() }
                try {
                    Dispatchers.IO.asExecutor().execute {
                        try {
                            if (handle.cancelled.get()) return@execute
                            val result = block(handle)
                            handle.connRef.set(null)
                            handle.socketRef.set(null)
                            if (!handle.cancelled.get()) cont.resume(result)
                        } catch (t: Throwable) {
                            handle.connRef.set(null)
                            handle.socketRef.set(null)
                            if (!handle.cancelled.get()) cont.resumeWithException(t)
                        } finally {
                            runCatching { handle.socketRef.getAndSet(null)?.close() }
                            runCatching { handle.connRef.getAndSet(null)?.disconnect() }
                            ioDone.complete(Unit)
                        }
                    }
                } catch (t: Throwable) {
                    ioDone.complete(Unit)
                    if (!handle.cancelled.get()) cont.resumeWithException(t)
                }
            }
        } finally {
            withContext(NonCancellable) { ioDone.await() }
        }
    }

    private suspend fun <T> withConnection(
        serverUrl: String,
        token: String?,
        method: String,
        path: String,
        block: (HttpURLConnection, () -> Boolean) -> T,
    ): T = runCancellableIo { handle ->
        val conn = configureConnection(buildUrl(serverUrl, path), token, method)
        handle.connRef.set(conn)
        if (handle.cancelled.get()) throw CancellationException("Transfer cancelled")
        block(conn) { handle.cancelled.get() }
    }

    private suspend fun call(
        serverUrl: String,
        token: String?,
        method: String,
        path: String,
        json: String? = null,
    ): String = try {
        withConnection(serverUrl, token, method, path) { conn, _ ->
            if (json != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code !in 200..299) throwApiError(conn, code)
            conn.inputStream.use { it.readBytes().decodeToString() }
        }
    } catch (e: ProtocolException) {
        // OpenJDK's HttpURLConnection rejects PATCH; Android's OkHttp-backed implementation accepts it.
        if (method != "PATCH") throw e
        executeRawSocket(serverUrl, token, method, path, json) { resp ->
            if (resp.code !in 200..299) {
                val detail = runCatching { resp.body.use { it.readBytes().decodeToString() } }.getOrNull() ?: ""
                throwParsedApiError(resp.code, detail)
            }
            resp.body.use { it.readBytes().decodeToString() }
        }
    }

    private suspend fun downloadTo(
        serverUrl: String,
        token: String,
        path: String,
        dest: File,
        onBytes: ((Long) -> Unit)? = null,
    ) {
        val url = buildUrl(serverUrl, path)
        val probeClass = (url.openConnection() as HttpURLConnection).javaClass.name
        if (probeClass.startsWith("sun.net.www.")) {
            // OpenJDK's sun.net.www.* HttpURLConnection.disconnect() acquires Chunked/MeteredStream's
            // readLock before closing the socket, stalling cancellation until read timeout.
            executeRawSocket(serverUrl, token, "GET", path, json = null) { resp ->
                if (resp.code !in 200..299) {
                    val detail = runCatching { resp.body.use { it.readBytes().decodeToString() } }.getOrNull() ?: ""
                    throwParsedApiError(resp.code, detail)
                }
                writeStreamToTempAndRename(
                    ins = resp.body,
                    expectedLength = resp.contentLengthLong,
                    contentEncoding = resp.contentEncoding,
                    dest = dest,
                    cancelled = resp.cancelled,
                    onBytes = onBytes,
                )
            }
            return
        }
        withConnection(serverUrl, token, "GET", path) { conn, cancelled ->
            val code = conn.responseCode
            if (code !in 200..299) throwApiError(conn, code)
            conn.inputStream.use { ins ->
                writeStreamToTempAndRename(
                    ins = ins,
                    expectedLength = conn.contentLengthLong,
                    contentEncoding = conn.contentEncoding,
                    dest = dest,
                    cancelled = cancelled,
                    onBytes = onBytes,
                )
            }
        }
    }

    private fun writeStreamToTempAndRename(
        ins: InputStream,
        expectedLength: Long,
        contentEncoding: String?,
        dest: File,
        cancelled: () -> Boolean,
        onBytes: ((Long) -> Unit)?,
    ) {
        val parent = dest.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Could not create transfer directory: $parent")
        }
        val tmp = File(parent ?: File("."), ".${dest.name}.${UUID.randomUUID()}.tmp")
        try {
            var total = 0L
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (cancelled()) throw CancellationException("Transfer cancelled")
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    onBytes?.invoke(total)
                }
            }
            if (cancelled()) throw CancellationException("Transfer cancelled")
            if (expectedLength >= 0 && (contentEncoding == null || contentEncoding.equals("identity", ignoreCase = true)) &&
                total != expectedLength
            ) {
                throw IOException("Truncated response: expected $expectedLength bytes, received $total")
            }
            if (!tmp.renameTo(dest)) {
                throw IOException("Could not move temporary transfer file to ${dest.name}")
            }
        } finally {
            runCatching { tmp.delete() }
        }
    }

    private data class RawResponse(
        val code: Int,
        val contentLengthLong: Long,
        val contentEncoding: String?,
        val body: InputStream,
        val cancelled: () -> Boolean,
    )

    private suspend fun <T> executeRawSocket(
        serverUrl: String,
        token: String?,
        method: String,
        path: String,
        json: String?,
        block: (RawResponse) -> T,
    ): T = runCancellableIo { handle ->
        val url = buildUrl(serverUrl, path)
        val port = if (url.port != -1) url.port else url.defaultPort
        val raw = Socket()
        raw.soTimeout = readTimeoutMs
        handle.socketRef.set(raw)
        if (handle.cancelled.get()) throw CancellationException("Transfer cancelled")
        raw.connect(InetSocketAddress(url.host, port), connectTimeoutMs)
        val sock: Socket = if (url.protocol == "https") {
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, url.host, port, true) as SSLSocket
            ssl.useClientMode = true
            handle.socketRef.set(ssl)
            if (handle.cancelled.get()) throw CancellationException("Transfer cancelled")
            ssl.startHandshake()
            ssl
        } else {
            raw
        }
        val out = sock.getOutputStream()
        val hostHeader = if (url.port != -1) "${url.host}:${url.port}" else url.host
        val reqTarget = url.file.ifEmpty { "/" }
        val req = StringBuilder()
            .append("$method $reqTarget HTTP/1.1\r\n")
            .append("Host: $hostHeader\r\n")
            .append("User-Agent: $userAgent\r\n")
            .append("Connection: close\r\n")
        if (token != null) req.append("Authorization: Bearer $token\r\n")
        val bodyBytes = json?.toByteArray(Charsets.UTF_8)
        if (bodyBytes != null) {
            req.append("Content-Type: application/json\r\n")
            req.append("Content-Length: ${bodyBytes.size}\r\n")
        }
        req.append("\r\n")
        out.write(req.toString().toByteArray(Charsets.US_ASCII))
        if (bodyBytes != null) out.write(bodyBytes)
        out.flush()

        val input = BufferedInputStream(sock.getInputStream())
        var code: Int
        val headers = linkedMapOf<String, String>()
        while (true) {
            val statusLine = readLineAscii(input) ?: throw EOFException("Unexpected end of HTTP response")
            val codeToken = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
                ?: throw IOException("Malformed HTTP status line: $statusLine")
            headers.clear()
            while (true) {
                val line = readLineAscii(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
            }
            if (codeToken != 100) {
                code = codeToken
                break
            }
        }
        val transferEncoding = headers["transfer-encoding"] ?: ""
        val contentLength = headers["content-length"]?.toLongOrNull() ?: -1L
        val contentEncoding = headers["content-encoding"]
        val bodyStream: InputStream = when {
            transferEncoding.contains("chunked", ignoreCase = true) -> ChunkedInputStream(input)
            contentLength >= 0 -> BoundedInputStream(input, contentLength)
            else -> input
        }
        block(RawResponse(code, contentLength, contentEncoding, bodyStream) { handle.cancelled.get() })
    }

    private class BoundedInputStream(private val source: InputStream, private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = source.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val n = source.read(b, off, toRead)
            if (n > 0) remaining -= n
            return n
        }
    }

    private class ChunkedInputStream(private val source: InputStream) : InputStream() {
        private var chunkRemaining = 0
        private var eof = false

        override fun read(): Int {
            val single = ByteArray(1)
            val n = read(single, 0, 1)
            return if (n < 0) -1 else single[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (eof) return -1
            if (chunkRemaining == 0) {
                val line = readLineAscii(source) ?: throw EOFException("Truncated chunked response")
                val size = line.substringBefore(';').trim().toIntOrNull(16)
                    ?: throw IOException("Malformed chunk size: $line")
                if (size == 0) {
                    readLineAscii(source)
                    eof = true
                    return -1
                }
                chunkRemaining = size
            }
            val n = source.read(b, off, minOf(len, chunkRemaining))
            if (n < 0) throw EOFException("Truncated chunked response")
            chunkRemaining -= n
            if (chunkRemaining == 0) {
                readLineAscii(source) ?: throw EOFException("Missing chunk terminator")
            }
            return n
        }
    }

    private fun throwApiError(conn: HttpURLConnection, code: Int): Nothing {
        val detail = runCatching { conn.errorStream?.use { it.readBytes().decodeToString() } }.getOrNull() ?: ""
        if (detail.isEmpty()) runCatching { conn.inputStream?.close() }
        throwParsedApiError(code, detail)
    }

    private fun throwParsedApiError(code: Int, detail: String): Nothing {
        if (code == HttpURLConnection.HTTP_UNAUTHORIZED) throw UnauthorizedApiException(detail)
        throw ApiException(code, detail)
    }

    companion object {
        private val HEX = "0123456789ABCDEF".toCharArray()

        private fun readLineAscii(input: InputStream): String? {
            val out = ByteArrayOutputStream()
            var sawAny = false
            while (true) {
                val b = input.read()
                if (b < 0) return if (sawAny) out.toByteArray().toString(Charsets.US_ASCII) else null
                sawAny = true
                if (b == '\n'.code) {
                    val bytes = out.toByteArray()
                    val len = if (bytes.isNotEmpty() && bytes[bytes.size - 1] == '\r'.code.toByte()) {
                        bytes.size - 1
                    } else {
                        bytes.size
                    }
                    return bytes.decodeToString(0, len)
                }
                out.write(b)
            }
        }

        internal fun encodeSegment(segment: String): String {
            if (segment.isBlank() || segment == "." || segment == ".." ||
                '/' in segment || '\\' in segment || segment.any { it.code < 0x20 || it.code == 0x7F }
            ) {
                throw IllegalArgumentException("Invalid path segment: '$segment'")
            }
            val bytes = segment.toByteArray(Charsets.UTF_8)
            val out = StringBuilder(bytes.size)
            for (b in bytes) {
                val c = b.toInt() and 0xFF
                val unreserved = (c in 'A'.code..'Z'.code) ||
                    (c in 'a'.code..'z'.code) ||
                    (c in '0'.code..'9'.code) ||
                    c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
                if (unreserved) {
                    out.append(c.toChar())
                } else {
                    out.append('%')
                    out.append(HEX[c ushr 4])
                    out.append(HEX[c and 0x0F])
                }
            }
            return out.toString()
        }
    }
}
