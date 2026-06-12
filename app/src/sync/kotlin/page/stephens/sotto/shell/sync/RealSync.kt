package page.stephens.sotto.shell.sync

// The sync flavor's SyncProvider: token in EncryptedSharedPreferences (Keystore-
// backed master key; the password itself is never persisted), library mirror per
// docs/12-api-contract.md - pull lists server entries as not-downloaded rows,
// download materializes them, upload pushes local orations with the server's
// own slug-collision rule, renames reconcile last-write-wins. Every action logs.

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import page.stephens.sotto.engine.store.JobLog
import page.stephens.sotto.engine.store.OrationEntity
import page.stephens.sotto.engine.store.SottoDatabase
import java.io.File
import java.util.UUID

object SyncFactory {
    fun create(context: Context): SyncProvider = RealSync(context.applicationContext)
}

private class RealSync(private val context: Context) : SyncProvider {

    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            context, "sync-account",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    private val db by lazy { SottoDatabase.open(context) }
    private fun api() = SottoApi(
        prefs.getString("server", DEFAULT_SERVER)!!, prefs.getString("token", null))

    override fun accountEmail(): String? =
        if (prefs.getString("token", null) != null) prefs.getString("email", null) else null

    override suspend fun connect(serverUrl: String, email: String, password: String) =
        runCatching {
            withContext(Dispatchers.IO) {
                val server = serverUrl.trim().ifBlank { DEFAULT_SERVER }
                    .let { if (it.startsWith("http")) it else "https://$it" }
                val token = SottoApi(server).requestToken(email.trim(), password, "sotto-android")
                prefs.edit().putString("server", server).putString("email", email.trim())
                    .putString("token", token).apply()
                JobLog(db.joblog()).log("sync-connect", email.trim())
                "Connected as ${email.trim()}"
            }
        }

    override fun disconnect() {
        prefs.edit().clear().apply()
        JobLog(db.joblog()).log("sync-disconnect", "")
    }

    override suspend fun syncNow() = runCatching {
        withContext(Dispatchers.IO) {
            val dao = db.orations()
            var new = 0; var renamedLocal = 0; var renamedRemote = 0
            val now = System.currentTimeMillis()
            for (e in api().library()) {
                val row = dao.byServerName(e.name)
                if (row == null) {
                    dao.insert(OrationEntity(
                        id = UUID.randomUUID().toString(), title = e.title, status = "COMPLETE",
                        voiceId = e.voice ?: "af_heart", words = e.words ?: 0, chunksTotal = null,
                        audioBytes = e.size, audioDurationMs = (e.durationSecs ?: 0) * 1000,
                        narratedMs = (e.secs ?: 0) * 1000,
                        createdAt = e.mtime * 1000, updatedAt = e.mtime * 1000,
                        serverName = e.name, serverMtime = e.mtime, downloaded = false))
                    new++
                } else if (e.title != row.title) {
                    // Renames reconcile last-write-wins by mtime (contract).
                    if (e.mtime > (row.serverMtime ?: 0) && e.mtime * 1000 >= row.updatedAt) {
                        dao.rename(row.id, e.title, now); renamedLocal++
                    } else {
                        api().rename(e.name, row.title); renamedRemote++
                    }
                    dao.markSynced(row.id, e.name, e.mtime, now)
                } else {
                    dao.markSynced(row.id, e.name, e.mtime, now)
                }
            }
            JobLog(db.joblog()).log("sync-pull", "", detail = "$new new, $renamedLocal/$renamedRemote renames")
            "Synced: $new new from server, $renamedLocal renamed here, $renamedRemote renamed there"
        }
    }

    override suspend fun download(o: OrationEntity) = runCatching {
        withContext(Dispatchers.IO) {
            val name = o.serverName ?: error("not a server entry")
            val dir = File(context.filesDir, "orations/${o.id}")
            api().downloadAudio(name, File(dir, "audio.aac"))
            api().downloadText(name, File(dir, "source.md"))
            db.orations().setDownloaded(o.id, File(dir, "audio.aac").length(), System.currentTimeMillis())
            JobLog(db.joblog()).log("downloaded", o.title, o.id, name)
        }
    }

    override suspend fun upload(o: OrationEntity) = runCatching {
        withContext(Dispatchers.IO) {
            val audio = File(context.filesDir, "orations/${o.id}/audio.aac")
            require(audio.exists()) { "no audio to upload" }
            val text = File(context.filesDir, "orations/${o.id}/source.md").takeIf { it.exists() }
            val meta = JSONObject()
                .put("title", o.title).put("words", o.words)
                .put("secs", o.narratedMs / 1000).put("voice", o.voiceId)
                .put("duration_secs", audio.length() * 8 / 64_000).toString()
            val slug = o.title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                .take(50).ifBlank { "oration" }
            val name = try {
                api().upload("$slug.aac", audio, text, meta, ifNoneMatch = true); "$slug.aac"
            } catch (e: ApiException) {
                if (e.code != 409) throw e
                val alt = "$slug-${o.id.take(6)}.aac"   // the server's own collision rule
                api().upload(alt, audio, text, meta, ifNoneMatch = false); alt
            }
            db.orations().markSynced(o.id, name, System.currentTimeMillis() / 1000, System.currentTimeMillis())
            JobLog(db.joblog()).log("uploaded", o.title, o.id, name)
            "Uploaded as $name"
        }
    }

    companion object { const val DEFAULT_SERVER = "https://sotto.stephens.page" }
}
