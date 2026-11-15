package page.stephens.sotto.shell.sync

import java.io.File

internal data class RemoteEntry(
    val name: String,
    val title: String,
    val words: Int?,
    val secs: Long?,
    val voice: String?,
    val size: Long,
    val durationSecs: Long?,
    val mtime: Long,
    val hasText: Boolean,
)

internal open class ApiException(
    val code: Int,
    val detail: String,
) : Exception(if (detail.isBlank()) "HTTP $code" else "HTTP $code: $detail") {
    val isUnauthorized: Boolean get() = code == 401
}

internal class UnauthorizedApiException(detail: String) : ApiException(401, detail)

internal interface RemoteServer {
    suspend fun requestToken(serverUrl: String, email: String, password: String, label: String): String
    suspend fun account(serverUrl: String, token: String): ServerAccount
    suspend fun library(serverUrl: String, token: String): List<RemoteEntry>
    suspend fun downloadAudio(
        serverUrl: String,
        token: String,
        name: String,
        dest: File,
        onBytes: ((Long) -> Unit)? = null,
    )

    /** Returns false on 404 (no source text on the server). */
    suspend fun downloadText(serverUrl: String, token: String, name: String, dest: File): Boolean

    /** PUT an oration. Returns true if created (201), false if replaced (200). Throws
     *  ApiException(409) when ifNoneMatch and the name exists. */
    suspend fun upload(
        serverUrl: String,
        token: String,
        name: String,
        audio: File,
        text: File?,
        metaJson: String,
        ifNoneMatch: Boolean,
    ): Boolean

    suspend fun rename(serverUrl: String, token: String, name: String, title: String)
    suspend fun narrate(serverUrl: String, token: String, text: String, voice: String, title: String): String
    suspend fun jobStatus(serverUrl: String, token: String, jobId: String): ServerJob
}
