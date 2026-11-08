package de.chromefiles.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import de.chromefiles.app.ui.ChromeFilesScreen
import de.chromefiles.app.ui.theme.ChromeFilesTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var incomingUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null && intent?.action == Intent.ACTION_VIEW) {
            incomingUri = intent?.data
        }
        setContent {
            ChromeFilesTheme {
                ChromeFilesScreen(
                    incomingUri = incomingUri,
                    onConsumeIncomingUri = { incomingUri = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) {
            incomingUri = intent.data
        }
    }
}
