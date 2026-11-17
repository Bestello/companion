package tools.fmc.companion

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()
    private val nav = Nav()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent { FmcTheme { App(model, nav) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** A link to a face opens it. */
    private fun handle(intent: Intent) {
        intent.dataString?.let(FaceLink::watchface)?.let {
            nav.tab = 0
            nav.signingIn = false
            nav.face = it
        }
        // Screenshots and manual checks without a watch: adb shell am start -n … --ez demo true --ei tab 1 --ez check true
        if (BuildConfig.DEBUG) {
            if (intent.hasExtra("demo")) model.switchDemo(intent.getBooleanExtra("demo", false))
            if (intent.hasExtra("tab")) nav.tab = intent.getIntExtra("tab", 0)
            if (intent.getBooleanExtra("signin", false)) nav.signingIn = true
            if (intent.getBooleanExtra("check", false)) model.check()
        }
    }
}
