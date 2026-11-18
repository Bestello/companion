package tools.fmc.companion

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import tools.fmc.companion.wire.LoaderState

sealed interface Phase {
    data object Idle : Phase
    data object Checking : Phase

    /** Connected; `state` null = stock firmware (or a stub from before 2026-11-13, which has no K). */
    data class Checked(val state: LoaderState?) : Phase
    data class Failed(val message: String) : Phase
}

/** The app's state, for Compose. Features that need the App Loader show only when `loader` is set. */
class AppModel(app: Application) : AndroidViewModel(app) {
    val store = SecureStore(app)
    val account = Account(store)
    private val prefs = app.getSharedPreferences("app", 0)

    var user by mutableStateOf<User?>(null)
        private set
    var providers by mutableStateOf<List<OAuthProvider>>(emptyList())
        private set
    var all by mutableStateOf<List<Watchface>>(emptyList())
        private set
    var mine by mutableStateOf<List<Watchface>>(emptyList())
        private set
    var catalogError by mutableStateOf<String?>(null)
        private set
    /** Every like on fmc.tools: counts and the user's own. */
    var likes by mutableStateOf<List<Catalog.Like>>(emptyList())
        private set

    fun likeCount(face: Watchface) = likes.count { it.watchface == face.id }
    fun liked(face: Watchface) = user?.let { u -> likes.any { it.watchface == face.id && it.user == u.id } } ?: false

    suspend fun loadLikes() { runCatching { Catalog.likes() }.onSuccess { likes = it } }

    /** Likes or unlikes as the signed-in user, then reloads the likes (toggleLikeFx). */
    suspend fun toggleLike(face: Watchface) {
        val u = user ?: return
        val mine = likes.firstOrNull { it.watchface == face.id && it.user == u.id }
        likes = if (mine != null) likes - mine else likes + Catalog.Like("", u.id, face.id)
        runCatching { if (mine != null) Catalog.unlike(mine.id, account.token) else Catalog.like(u.id, face.id, account.token) }
        loadLikes()
    }
    var phase by mutableStateOf<Phase>(Phase.Idle)
        private set
    /** Bumped after each read or change of the faces on the watch: what shows them reads them again. */
    var facesRead by mutableStateOf(0)

    var services by mutableStateOf(prefs.getBoolean("services", false))
        private set

    /** The miniapp services' foreground service (RpcService); on again at every launch while switched on. */
    fun switchServices(on: Boolean) {
        services = on
        prefs.edit().putBoolean("services", on).apply()
        if (on) RpcService.start(getApplication()) else RpcService.stop(getApplication())
    }

    val loader get() = (phase as? Phase.Checked)?.state

    /** The last read while a new one runs: lists keep their rows through a refresh. */
    private var lastLoader by mutableStateOf<LoaderState?>(null)
    val listedLoader get() = if (phase == Phase.Checking) lastLoader else loader
    // not Idle: without Bluetooth permission the Apps tab never starts a read
    val isChecking get() = phase == Phase.Checking

    init {
        viewModelScope.launch { AppUpdate.check() }
        viewModelScope.launch {
            account.restore()
            user = account.user
            reload()
        }
    }

    fun check() = viewModelScope.launch {
        phase = Phase.Checking
        phase = try {
            Phase.Checked(readWatch(getApplication(), store))
        } catch (e: Exception) {
            Phase.Failed(e.message ?: "Couldn't read the watch.")
        }
        lastLoader = loader
    }

    suspend fun reload() {
        try {
            account.user?.let { mine = Catalog.mine(it.id, account.token) }
            all = Catalog.all()
            catalogError = null
            loadLikes()
        } catch (e: Exception) {
            catalogError = e.message ?: "Couldn't load watch faces"
        }
    }

    suspend fun face(id: String) = (all + mine).firstOrNull { it.id == id } ?: Catalog.face(id, account.token)

    suspend fun loadProviders() {
        providers = account.providers()
    }

    suspend fun signIn(email: String, password: String) {
        account.signIn(email, password)
        signedIn()
    }

    suspend fun signIn(provider: OAuthProvider, open: (String) -> Unit) {
        account.signIn(provider, open)
        signedIn()
    }

    private suspend fun signedIn() {
        user = account.user
        reload()
    }

    fun signOut() {
        account.signOut()
        user = null
        mine = emptyList()
    }

    suspend fun deleteAccount() {
        account.deleteAccount()
        signOut()
    }
}
