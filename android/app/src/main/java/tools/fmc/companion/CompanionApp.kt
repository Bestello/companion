package tools.fmc.companion

import android.app.Application
import android.content.Context
import androidx.annotation.StringRes

/** Holds the app's Context for strings outside Compose: models, services, notifications, errors. */
class CompanionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = this
    }
}

lateinit var appContext: Context
    private set

fun str(@StringRes id: Int, vararg args: Any): String = appContext.getString(id, *args)
