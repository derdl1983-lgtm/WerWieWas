package de.derdl1983.weriewas

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import de.derdl1983.weriewas.ui.AnalyzeScreen
import de.derdl1983.weriewas.ui.HistoryScreen
import de.derdl1983.weriewas.ui.HomeScreen
import de.derdl1983.weriewas.ui.ResultScreen
import de.derdl1983.weriewas.ui.SettingsScreen
import de.derdl1983.weriewas.ui.VideoScreen
import de.derdl1983.weriewas.ui.WerWieWasTheme
import de.derdl1983.weriewas.util.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Navigationszustände der App. */
sealed interface Screen {
    data object Home : Screen
    data class Analyze(val path: String) : Screen
    data class Result(val discoveryId: String, val fromHistory: Boolean = false) : Screen
    data object History : Screen
    data class Video(val discoveryId: String, val fromHistory: Boolean = false) : Screen
    data object Settings : Screen
}

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_OPEN_CAMERA = "de.derdl1983.weriewas.OPEN_CAMERA"
        private const val PROVIDER_SUFFIX = ".fileprovider"
        private const val KEY_SCREEN = "screen"
        private const val KEY_ARG = "screen_arg"
        private const val KEY_FLAG = "screen_flag"
        private const val KEY_PHOTO = "last_photo"
    }

    private var currentScreen by mutableStateOf<Screen>(Screen.Home)
    private var autoOpenCamera by mutableStateOf(false)
    private var lastPhotoFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) {
            // Zustand nach Drehen / Prozess-Tod wiederherstellen
            lastPhotoFile = savedInstanceState.getString(KEY_PHOTO)?.let { File(it) }
            currentScreen = restoreScreen(
                savedInstanceState.getString(KEY_SCREEN),
                savedInstanceState.getString(KEY_ARG),
                savedInstanceState.getBoolean(KEY_FLAG)
            )
        } else {
            handleLaunchIntent(intent)
        }

        setContent {
            WerWieWasTheme {
                AppRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    /** Wertet den Start-Intent (z. B. von der Kachel) genau einmal aus. */
    private fun handleLaunchIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_CAMERA) {
            intent.action = null // verbraucht, damit eine Rotation die Kamera nicht erneut öffnet
            currentScreen = Screen.Home
            autoOpenCamera = true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PHOTO, lastPhotoFile?.absolutePath)
        when (val s = currentScreen) {
            Screen.Home -> outState.putString(KEY_SCREEN, "home")
            is Screen.Analyze -> {
                outState.putString(KEY_SCREEN, "analyze"); outState.putString(KEY_ARG, s.path)
            }
            is Screen.Result -> {
                outState.putString(KEY_SCREEN, "result"); outState.putString(KEY_ARG, s.discoveryId)
                outState.putBoolean(KEY_FLAG, s.fromHistory)
            }
            Screen.History -> outState.putString(KEY_SCREEN, "history")
            is Screen.Video -> {
                outState.putString(KEY_SCREEN, "video"); outState.putString(KEY_ARG, s.discoveryId)
                outState.putBoolean(KEY_FLAG, s.fromHistory)
            }
            Screen.Settings -> outState.putString(KEY_SCREEN, "settings")
        }
    }

    private fun restoreScreen(kind: String?, arg: String?, flag: Boolean): Screen = when {
        kind == "analyze" && arg != null -> Screen.Analyze(arg)
        kind == "result" && arg != null -> Screen.Result(arg, flag)
        kind == "history" -> Screen.History
        kind == "video" && arg != null -> Screen.Video(arg, flag)
        kind == "settings" -> Screen.Settings
        else -> Screen.Home
    }

    private fun openCamera() {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        lastPhotoFile = file
        val uri = FileProvider.getUriForFile(this, "$packageName$PROVIDER_SUFFIX", file)
        cameraCaptureLauncher.launch(uri)
    }

    private val cameraCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val photo = lastPhotoFile
            if (!success) {
                photo?.delete()
                lastPhotoFile = null
                Toast.makeText(this, "Foto wurde abgebrochen.", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            if (photo != null && photo.exists()) {
                startAnalysis(Uri.fromFile(photo), deleteAfter = photo)
            } else {
                Toast.makeText(this, "Das Foto konnte nicht gefunden werden.", Toast.LENGTH_SHORT).show()
            }
            lastPhotoFile = null
        }

    private val galleryPickerLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                startAnalysis(uri, deleteAfter = null)
            }
        }

    private fun openGallery() {
        val request = PickVisualMediaRequest.Builder()
            .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
            .build()
        galleryPickerLauncher.launch(request)
    }

    /** Kopiert das Bild im Hintergrund in den App-Speicher und startet dann die Analyse. */
    private fun startAnalysis(uri: Uri, deleteAfter: File?) {
        lifecycleScope.launch {
            val path = withContext(Dispatchers.IO) {
                val result = Images.copyToInternal(this@MainActivity, uri)
                deleteAfter?.delete() // Original aus dem Cache entfernen
                result
            }
            if (path == null) {
                Toast.makeText(this@MainActivity, "Das Bild konnte nicht gelesen werden.", Toast.LENGTH_SHORT).show()
            } else {
                currentScreen = Screen.Analyze(path)
            }
        }
    }

    @Composable
    private fun AppRoot() {
        BackHandler(enabled = currentScreen != Screen.Home) {
            currentScreen = when (val s = currentScreen) {
                is Screen.Video -> Screen.Result(s.discoveryId, s.fromHistory)
                is Screen.Result -> if (s.fromHistory) Screen.History else Screen.Home
                else -> Screen.Home
            }
        }
        when (val screen = currentScreen) {
            Screen.Home -> HomeScreen(
                autoOpenCamera = autoOpenCamera,
                onAutoOpened = { autoOpenCamera = false },
                onCamera = { openCamera() },
                onGallery = { openGallery() },
                onHistory = { currentScreen = Screen.History },
                onSettings = { currentScreen = Screen.Settings }
            )
            is Screen.Analyze -> AnalyzeScreen(
                imagePath = screen.path,
                onDone = { id -> currentScreen = Screen.Result(id) },
                onCancel = { currentScreen = Screen.Home }
            )
            is Screen.Result -> ResultScreen(
                discoveryId = screen.discoveryId,
                onVideo = { currentScreen = Screen.Video(screen.discoveryId, screen.fromHistory) },
                onClose = { currentScreen = if (screen.fromHistory) Screen.History else Screen.Home }
            )
            Screen.History -> HistoryScreen(
                onOpen = { id -> currentScreen = Screen.Result(id, fromHistory = true) },
                onClose = { currentScreen = Screen.Home }
            )
            is Screen.Video -> VideoScreen(
                discoveryId = screen.discoveryId,
                onClose = { currentScreen = Screen.Result(screen.discoveryId, screen.fromHistory) }
            )
            Screen.Settings -> SettingsScreen(
                onClose = { currentScreen = Screen.Home }
            )
        }
    }
}
