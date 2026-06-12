package page.stephens.sotto.shell.sync

// The sync flavor's entire network surface: a thin HttpURLConnection client for
// the server's /api/v1 (docs/12-api-contract.md). Deliberately stdlib-only -
// no HTTP library enters the dependency graph, which keeps the constitution's
// allowlist trivial and the audit surface one file.

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val code: Int, message: String) : Exception("HTTP $code: $message")

class SottoApi(private val baseUrl: String, private val token: String? = null) {

    data class Entry(
        val name: String, val title: String, val words: Int?, val secs: Long?,
        val voice: String?, val size: Long, val durationSecs: Long?, val mtime: Long,
        val hasText: Boolean,
    )

    fun requestToken(email: String, password: String, label: String): String {
        val body = JSONObject().put("email", email).put("password", password).put("label", label)
        val resp = call("POST", "/api/v1/token", json = body.toString())
        return JSONObject(resp).getString("token")
    }

    fun me(): String = JSONObject(call("GET", "/api/v1/me")).getString("email")

    fun library(): List<Entry> {
        val arr = JSONObject(call("GET", "/api/v1/library")).getJSONArray("entries")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(
                name = o.getString("name"), title = o.getString("title"),
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

    fun downloadAudio(name: String, dest: File) = downloadTo("/api/v1/library/$name/audio", dest)

    /** Returns false on 404 (no source text on the server). */
    fun downloadText(name: String, dest: File): Boolean = try {
        downloadTo("/api/v1/library/$name/text", dest); true
    } catch (e: ApiException) {
        if (e.code == 404) false else throw e
    }

    /** PUT an oration. Returns true if created, false if it replaced. Throws
     *  ApiException(409) when ifNoneMatch and the name exists. */
    fun upload(name: String, audio: File, text: File?, metaJson: String, ifNoneMatch: Boolean): Boolean {
        val boundary = "sotto-" + System.nanoTime()
        val conn = open("PUT", "/api/v1/library/$name")
        if (ifNoneMatch) conn.setRequestProperty("If-None-Match", "*")
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.doOutput = true
        conn.setChunkedStreamingMode(0)
        conn.outputStream.use { out ->
            fun part(headers: String, write: () -> Unit) {
                out.write("--$boundary\r\n$headers\r\n\r\n".toByteArray()); write(); out.write("\r\n".toByteArray())
            }
            part("Content-Disposition: form-data; name=\"meta\"") { out.write(metaJson.toByteArray()) }
            part("Content-Disposition: form-data; name=\"audio\"; filename=\"a\"\r\nContent-Type: application/octet-stream") {
                audio.inputStream().use { it.copyTo(out) }
            }
            if (text != null) part("Content-Disposition: form-data; name=\"text\"; filename=\"t\"\r\nContent-Type: text/markdown") {
                text.inputStream().use { it.copyTo(out) }
            }
            out.write("--$boundary--\r\n".toByteArray())
        }
        val code = conn.responseCode
        if (code !in 200..299) throw ApiException(code, conn.errorStream?.readBytes()?.decodeToString() ?: "")
        conn.inputStream.close()
        return code == 201
    }

    fun rename(name: String, title: String) {
        call("PATCH", "/api/v1/library/$name", json = JSONObject().put("title", title).toString())
    }

    // --- plumbing ---------------------------------------------------------

    private fun open(method: String, path: String): HttpURLConnection {
        val conn = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 120_000
        token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        conn.setRequestProperty("User-Agent", "sotto-android (sync)")
        return conn
    }

    private fun call(method: String, path: String, json: String? = null): String {
        val conn = open(method, path)
        if (json != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(json.toByteArray()) }
        }
        val code = conn.responseCode
        if (code !in 200..299) throw ApiException(code, conn.errorStream?.readBytes()?.decodeToString() ?: "")
        return conn.inputStream.use { it.readBytes().decodeToString() }
    }

    private fun downloadTo(path: String, dest: File) {
        val conn = open("GET", path)
        val code = conn.responseCode
        if (code !in 200..299) throw ApiException(code, "")
        dest.parentFile?.mkdirs()
        conn.inputStream.use { ins: InputStream ->
            File(dest.path + ".tmp").outputStream().use { ins.copyTo(it) }
        }
        File(dest.path + ".tmp").renameTo(dest)
    }
}
