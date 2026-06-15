package page.stephens.sotto.shell.sync

// Live download percentage per oration id, observed by the Library row while a
// server entry is being materialized. Lives in `main` (not the sync flavor) so
// the UI can collect it unconditionally; the `local` flavor simply never writes
// to it, holding no network code. Emissions are deduped to whole percents by the
// writer, so a 150 MB download produces at most ~101 updates.

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

object DownloadProgress {
    private val _state = MutableStateFlow<Map<String, Int>>(emptyMap())
    val state: StateFlow<Map<String, Int>> = _state

    fun set(id: String, percent: Int) = _state.update { it + (id to percent.coerceIn(0, 100)) }
    fun clear(id: String) = _state.update { it - id }
    fun isActive(id: String) = _state.value.containsKey(id)
}
