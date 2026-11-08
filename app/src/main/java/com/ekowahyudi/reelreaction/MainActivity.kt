package com.ekowahyudi.reelreaction

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import com.ekowahyudi.reelreaction.pipeline.UrlUtils
import com.ekowahyudi.reelreaction.ui.MainScreen
import com.ekowahyudi.reelreaction.ui.ReelTheme
import com.ekowahyudi.reelreaction.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            ReelTheme {
                AppRoot(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Link yang dibagikan dari YouTube/TikTok/dll. ditambahkan ke kolom link (bisa dibagikan berkali-kali). */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val links = UrlUtils.extractAll(intent.getStringExtra(Intent.EXTRA_TEXT))
        if (links.isEmpty()) return
        val addition = links.joinToString("\n")
        viewModel.input = if (viewModel.input.isBlank()) addition else viewModel.input.trimEnd() + "\n" + addition
    }
}

@Composable
private fun AppRoot(viewModel: MainViewModel) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val running by viewModel.running.collectAsState()

    // Layar tetap menyala selama antrian berjalan supaya proses tidak terhenti saat HP terkunci otomatis.
    val view = LocalView.current
    val busy = running != null
    SideEffect { view.keepScreenOn = busy }

    BackHandler(enabled = showSettings) { showSettings = false }

    if (showSettings) {
        SettingsScreen(viewModel, onBack = { showSettings = false })
    } else {
        MainScreen(viewModel, onOpenSettings = { showSettings = true })
    }
}
