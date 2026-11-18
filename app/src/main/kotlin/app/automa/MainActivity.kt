package app.automa

import android.graphics.Color
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.automa.ui.Icons
import app.automa.ui.nav.AppNav
import app.automa.ui.nav.Navigator
import app.automa.ui.theme.AutomaTheme

/** The only activity. With the app lock on, it shows the lock after a cold start and after a minute away. */
class MainActivity : ComponentActivity() {
    private val nav: Navigator by viewModels()
    private var locked by mutableStateOf(true)
    private var stoppedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is always dark (DESIGN §5): light bar icons, also when the system is light.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        keepSplashUntilReady()
        // The terminal can show secrets: no preview in recents (review SEC-10).
        setRecentsScreenshotEnabled(false)
        setContent {
            AutomaTheme {
                Surface(Modifier.fillMaxSize()) {
                    // The splash screen stops the drawing, not the composition: wait for the settings (review SEC-3).
                    val ready by app.ready.collectAsStateWithLifecycle()
                    if (!ready) return@Surface
                    val lockOn by app.prefs.lock.collectAsStateWithLifecycle()
                    LaunchedEffect(lockOn) {
                        // A lock that the user turns on now starts at the next return to the app.
                        if (!lockOn) locked = false
                    }
                    if (lockOn && locked) LockScreen(::unlock) else AppNav(nav)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (stoppedAt != 0L && SystemClock.elapsedRealtime() - stoppedAt > LOCK_AFTER_MS) locked = true
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
    }

    private fun unlock() {
        BiometricPrompt.Builder(this)
            .setTitle("Unlock Automa")
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
            .build()
            .authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    locked = false
                }
            })
    }

    /** Holds the system splash screen until the settings are read, so that the lock state is known. */
    private fun keepSplashUntilReady() {
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!app.ready.value) return false
                content.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
    }

    private companion object {
        const val LOCK_AFTER_MS = 60_000L
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Lock, null, Modifier.size(48.dp))
        Button(onClick = onUnlock) { Text("Unlock") }
    }
}
