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
    var phase by mutableStateOf<Phase>(Phase.Idle)
        private set
    var demo by mutableStateOf(prefs.getBoolean("demo", false))
        private set

    val loader get() = (phase as? Phase.Checked)?.state

    init {
        viewModelScope.launch {
            account.restore()
            user = account.user
            reload()
        }
    }

    fun switchDemo(on: Boolean) {
        demo = on
        prefs.edit().putBoolean("demo", on).apply()
        phase = Phase.Idle
    }

    fun check() = viewModelScope.launch {
        phase = Phase.Checking
        phase = try {
            Phase.Checked(if (demo) demoState() else readWatch(getApplication(), store))
        } catch (e: Exception) {
            Phase.Failed(e.message ?: "Couldn't read the watch.")
        }
    }

    suspend fun reload() {
        try {
            account.user?.let { mine = Catalog.mine(it.id, account.token) }
            all = Catalog.all()
            catalogError = null
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
