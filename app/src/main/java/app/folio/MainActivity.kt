package app.folio

import android.net.Uri
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import app.folio.data.BookEntity
import app.folio.ui.*

class MainActivity : ComponentActivity() {
    private val incoming = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) incoming.value = intent?.data
        setContent {
            FolioTheme {
                val lib: LibraryViewModel = viewModel()
                var open by remember { mutableStateOf<BookEntity?>(null) }
                val uri by incoming
                LaunchedEffect(uri) {
                    uri?.let { lib.openUri(it) { b -> open = b }; incoming.value = null }
                }
                BackHandler(enabled = open != null) { open = null }
                val b = open
                if (b == null) LibraryScreen(lib) { open = it } else ReaderScreen(b) { open = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incoming.value = intent.data
    }
}
