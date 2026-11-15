package page.stephens.sotto.shell.sync

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

internal data class SyncCredentials(
    val serverUrl: String,
    val email: String,
    val token: String,
)

internal interface CredentialStore {
    fun load(): SyncCredentials?
    fun save(credentials: SyncCredentials)
    fun clear()
}

@Suppress("DEPRECATION")
internal class EncryptedCredentialStore(context: Context) : CredentialStore {
    private val appContext = context.applicationContext
    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun load(): SyncCredentials? {
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        val email = prefs.getString(KEY_EMAIL, null) ?: return null
        val server = prefs.getString(KEY_SERVER, DEFAULT_SERVER) ?: DEFAULT_SERVER
        return SyncCredentials(serverUrl = server, email = email, token = token)
    }

    override fun save(credentials: SyncCredentials) {
        prefs.edit()
            .putString(KEY_SERVER, credentials.serverUrl)
            .putString(KEY_EMAIL, credentials.email)
            .putString(KEY_TOKEN, credentials.token)
            .commit()
    }

    override fun clear() {
        prefs.edit().clear().commit()
    }

    companion object {
        private const val PREFS_NAME = "sync-account"
        private const val KEY_SERVER = "server"
        private const val KEY_EMAIL = "email"
        private const val KEY_TOKEN = "token"
        const val DEFAULT_SERVER = "https://sotto.stephens.page"
    }
}
