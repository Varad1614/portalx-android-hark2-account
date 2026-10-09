package com.pravahax.portalx.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import com.pravahax.portalx.data.Repo
import com.pravahax.portalx.data.SessionUser
import com.pravahax.portalx.net.PortalException
import com.pravahax.portalx.net.RefreshResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Auth state machine. Every transition is explicit; nothing signs the user out except a real 401 or "Sign out". */
sealed interface Auth {
    data object Checking : Auth
    /** Session exists but couldn't be verified and nothing is cached (server down / offline on first run). */
    data class Unavailable(val message: String) : Auth
    data class SignedOut(val notice: String? = null) : Auth
    data class SignedIn(val user: SessionUser) : Auth
}

/**
 * v0.7: the app shell's state, moved out of composables so it survives rotation and can be unit-tested.
 * Also hands out the signed-in session's [ViewModelStore]: every screen ViewModel lives there, and a new
 * sign-in gets a fresh one, so nothing from one session (data, in-flight loads) leaks into the next.
 */
class AppViewModel(private val repo: Repo) : ViewModel() {
    private val _auth = MutableStateFlow(
        if (!repo.api.hasSession()) Auth.SignedOut() else repo.cachedMe()?.let { Auth.SignedIn(it) } ?: Auth.Checking
    )
    val auth: StateFlow<Auth> = _auth.asStateFlow()

    private var signingOut = false

    /** Owner for everything inside the signed-in shell (NavHost entries and their ViewModels). */
    var session: ViewModelStoreOwner = newSession(); private set
    private fun newSession() = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    /** The old store is cleared by the shell when it leaves the screen (see PortalApp), never mid-animation here. */
    private fun endSession() { session = newSession() }

    init { verify() }

    /** Re-reads the user from the server (start-up and "Try again"). Offline with a cached user keeps working. */
    fun verify() {
        if (!repo.api.hasSession()) return
        viewModelScope.launch {
            try {
                val me = repo.me()
                if (me != null) _auth.value = Auth.SignedIn(me) else { repo.logout(); endSession(); _auth.value = Auth.SignedOut("Please sign in again.") }
            } catch (e: CancellationException) { throw e
            } catch (e: PortalException) {
                if (e.code == 401) signOut(EXPIRED)
                else if (_auth.value !is Auth.SignedIn) _auth.value = Auth.Unavailable(e.message ?: "Portal One can't be reached right now.")
            } catch (e: Exception) {
                if (_auth.value !is Auth.SignedIn) _auth.value = Auth.Unavailable("Portal One can't be reached right now.")
            }
        }
    }

    fun retry() { _auth.value = Auth.Checking; verify() }

    /** Idempotent: many screens can hit a 401 at once; only the first one runs. */
    fun signOut(notice: String?) {
        if (signingOut) return
        signingOut = true
        viewModelScope.launch {
            try { repo.logout() } finally { endSession(); _auth.value = Auth.SignedOut(notice); signingOut = false }
        }
    }

    fun signedIn(user: SessionUser) { endSession(); _auth.value = Auth.SignedIn(user) }

    /** Password changed / profile re-read: same session, new user object. */
    fun userUpdated(user: SessionUser) { _auth.value = Auth.SignedIn(user) }

    /** On every resume: rotate the session if < 48 h is left. A 401 from auth/refresh means it's gone. */
    fun onResume() {
        if (!repo.api.hasSession()) return
        viewModelScope.launch {
            val r = try { repo.refreshSession() } catch (e: CancellationException) { throw e } catch (e: Exception) { RefreshResult.Failed }
            if (r == RefreshResult.SignedOut) signOut(EXPIRED)
        }
    }

    suspend fun refreshMe() {
        val me = repo.me()
        if (me != null && _auth.value is Auth.SignedIn) _auth.value = Auth.SignedIn(me)
    }

    override fun onCleared() { session.viewModelStore.clear() }

    companion object { const val EXPIRED = "Your session expired. Please sign in again." }
}
