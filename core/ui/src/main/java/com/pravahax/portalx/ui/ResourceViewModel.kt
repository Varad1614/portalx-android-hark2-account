package com.pravahax.portalx.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pravahax.portalx.data.Repo
import com.pravahax.portalx.net.Fn
import com.pravahax.portalx.net.PortalException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/** Everything a screen needs to draw one server resource. Immutable; emitted by [ResourceViewModel.state]. */
data class ResourceState(
    val data: JsonElement? = null,
    val loading: Boolean = false,
    val userRefresh: Boolean = false,
    val error: String? = null,
    /** True when [data] came from the on-device cache and the last network attempt failed. */
    val stale: Boolean = false,
    /** A 401 arrived; the UI signs out and then calls [ResourceViewModel.expiryHandled]. */
    val sessionExpired: Boolean = false,
)

/**
 * v0.7: one server resource, held in a ViewModel so it survives rotation and is unit-testable without Compose.
 * Room is the source of truth: [state].data follows [Repo.observe]; fetches only refresh Room. Refetches when
 * started, on [refresh], when a write invalidates [fn], and on resume after [STALE_AFTER_MS].
 */
class ResourceViewModel(
    private val repo: Repo,
    val fn: Fn,
    private val request: JsonElement?,
    val key: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    companion object { const val STALE_AFTER_MS = 60_000L }

    private val _state = MutableStateFlow(ResourceState())
    val state: StateFlow<ResourceState> = _state.asStateFlow()

    private var started = false
    private var loadedAt = 0L
    private var job: Job? = null
    /** A fetch was asked for while one was running: run once more when it ends (newest data wins, no cancellation races). */
    private var again = false

    init {
        viewModelScope.launch { repo.observe(key).collect { v -> if (v != null) _state.update { it.copy(data = v) } } }
        viewModelScope.launch {
            repo.versions.map { it[fn] ?: 0 }.distinctUntilChanged().drop(1).collect { if (started) fetch(user = false) }
        }
    }

    /** Called by the screen; idempotent. A disabled resource never fetches. */
    fun start(enabled: Boolean) {
        if (!enabled || started) return
        started = true
        fetch(user = false)
    }

    fun refresh() = fetch(user = true)

    /** Foreground again: refetch if the data is older than a minute. */
    fun onResume() {
        if (started && loadedAt != 0L && clock() - loadedAt > STALE_AFTER_MS) fetch(user = false)
    }

    fun expiryHandled() = _state.update { it.copy(sessionExpired = false) }

    private fun fetch(user: Boolean) {
        if (user) _state.update { it.copy(userRefresh = true) }
        if (job?.isActive == true) { again = true; return }
        job = viewModelScope.launch {
            do {
                again = false
                _state.update { it.copy(loading = true) }
                try {
                    val r = repo.load(fn, request, key)
                    loadedAt = clock()
                    _state.update { it.copy(data = r, error = null, stale = false) }
                } catch (e: CancellationException) { throw e
                } catch (e: PortalException) {
                    if (e.code == 401) _state.update { it.copy(sessionExpired = true) }
                    else _state.update { s ->
                        s.copy(stale = s.data != null, error = if (s.data != null && e.offline) "You're offline. Showing what was saved on this device." else e.message)
                    }
                } catch (e: Exception) {
                    _state.update { s -> s.copy(stale = s.data != null, error = "Something went wrong. Pull down to try again.") }
                }
            } while (again)
            _state.update { it.copy(loading = false, userRefresh = false) }
        }
    }
}
