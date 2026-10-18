package de.derdl1983.weriewas

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.activityresult.PickVisualMediaRequest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import de.derdl1983.weriewas.ui.AnalyzeScreen
import de.derdl1983.weriewas.ui.HistoryScreen
import de.derdl1983.weriewas.ui.HomeScreen
import de.derdl1983.weriewas.ui.ResultScreen
import de.derdl1983.weriewas.ui.SettingsScreen
import de.derdl1983.weriewas.ui.VideoScreen
import de.derdl1983.weriewas.ui.WerWieWasTheme
import de.derdl1983.weriewas.util.Images
import java.io.File

/** Navigationszustände der App. */
sealed interface Screen {
    data object Home : Screen
    data class Analyze(val path: String) : Screen
    data class Result(val discoveryId: String) : Screen
    data object History : Screen
    data class Video(val discoveryId: String) : Screen
    data object Settings : Screen
}

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_OPEN_CAMERA = "de.derdl1983.weriewas.OPEN_CAMERA"
        private const val PROVIDER_SUFFIX = ".fileprovider"
    }

    private var currentScreen by mutableStateOf<Screen>(Screen.Home)
    private var autoOpenCamera by mutableStateOf(false)
    private var lastPhotoFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == ACTION_OPEN_CAMERA) {
            autoOpenCamera = true
        }

        setContent {
            WerWieWasTheme {
                AppRoot()
            }
        }
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
            if (!success) {
                Toast.makeText(this, "Foto wurde abgebrochen.", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            val photo = lastPhotoFile
            if (photo != null && photo.exists()) {
                startAnalysis(Uri.fromFile(photo))
            } else {
                Toast.makeText(this, "Das Foto konnte nicht gefunden werden.", Toast.LENGTH_SHORT).show()
            }
        }

    private val galleryPickerLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                startAnalysis(uri)
            }
        }

    private fun openGallery() {
        val request = PickVisualMediaRequest.Builder()
            .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
            .build()
        galleryPickerLauncher.launch(request)
    }

    private fun startAnalysis(uri: Uri) {
        val path = Images.copyToInternal(this, uri)
        if (path == null) {
            Toast.makeText(this, "Das Bild konnte nicht gelesen werden.", Toast.LENGTH_SHORT).show()
            return
        }
        currentScreen = Screen.Analyze(path)
    }

    @Composable
    private fun AppRoot() {
        BackHandler(enabled = currentScreen != Screen.Home) {
            currentScreen = Screen.Home
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
                onVideo = { currentScreen = Screen.Video(screen.discoveryId) },
                onClose = { currentScreen = Screen.Home }
            )
            Screen.History -> HistoryScreen(
                onOpen = { id -> currentScreen = Screen.Result(id) },
                onClose = { currentScreen = Screen.Home }
            )
            is Screen.Video -> VideoScreen(
                discoveryId = screen.discoveryId,
                onClose = { currentScreen = Screen.Result(screen.discoveryId) }
            )
            Screen.Settings -> SettingsScreen(
                onClose = { currentScreen = Screen.Home }
            )
        }
    }
}
