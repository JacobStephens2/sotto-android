package page.stephens.sotto.shell.sync

import android.content.Context

// The local flavor has no sync - and no network code to have it with.
object SyncFactory {
    fun create(@Suppress("UNUSED_PARAMETER") context: Context): SyncProvider? = null
}
