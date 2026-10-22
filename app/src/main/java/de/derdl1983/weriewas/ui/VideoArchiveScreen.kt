package de.derdl1983.weriewas.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Das Video-Archiv: sammelt dauerhaft alle von der App exportierten
 * Erklärvideos und bietet Direkt-Uploads zu YouTube, TikTok, Instagram,
 * WhatsApp, Telegram, Facebook oder jedem anderen Ziel.
 */
data class ArchiveVideo(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val addedMs: Long,
    val file: File? = null
)

/** Liest alle exportierten Videos: ab Android 10 aus der Mediathek (Filme/WerWieWas), darunter aus dem App-Ordner. */
fun loadVideoArchive(context: Context): List<ArchiveVideo> {
    return try {
        if (Build.VERSION.SDK_INT >= 29) {
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_ADDED
            )
            val videos = mutableListOf<ArchiveVideo>()
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?",
                arrayOf("Movies/WerWieWas/%"),
                MediaStore.Video.Media.DATE_ADDED + " DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    videos.add(
                        ArchiveVideo(
                            uri = ContentUris.withAppendedId(
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                c.getLong(0)
                            ),
                            name = c.getString(1) ?: "Video",
                            sizeBytes = c.getLong(2),
                            addedMs = c.getLong(3) * 1000L
                        )
                    )
                }
            }
            videos
        } else {
            val dir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir,
                "videos"
            )
            (dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") } ?: emptyArray())
                .sortedByDescending { it.lastModified() }
                .mapNotNull { f ->
                    val uri = runCatching {
                        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
                    }.getOrNull()
                    if (uri == null) {
                        null
                    } else {
                        ArchiveVideo(uri, f.name, f.length(), f.lastModified(), f)
                    }
                }
        }
    } catch (e: Exception) {
        emptyList()
    }
}

/** Löscht ein Archiv-Video (Mediathek-Eintrag oder Datei). */
private fun deleteVideo(context: Context, video: ArchiveVideo): Boolean {
    val file = video.file
    if (file != null) return file.delete()
    return try {
        context.contentResolver.delete(video.uri, null, null) > 0
    } catch (e: Exception) {
        false
    }
}

/** Öffnet das Video in einem beliebigen installierten Videoplayer. */
private fun openVideo(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "video/mp4")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Kein Videoplayer gefunden.", Toast.LENGTH_SHORT).show()
    }
}

/** Übergibt ein Archiv-Video an eine Ziel-App (YouTube, TikTok, …) oder die System-Auswahl. */
private fun shareVideo(context: Context, uri: Uri, pkg: String?) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Wer Wie Was – Erklärvideo")
        clipData = ClipData.newRawUri("video", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (pkg != null) setPackage(pkg)
    }
    try {
        context.startActivity(send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // Ziel-App nicht installiert: System-Auswahl anbieten
        send.setPackage(null)
        Toast.makeText(context, "Ziel-App nicht gefunden – bitte aus der Auswahl wählen.", Toast.LENGTH_SHORT).show()
        runCatching {
            context.startActivity(
                Intent.createChooser(send, "Video teilen")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    } catch (e: Exception) {
        // Weiter ignorieren
    }
}

/** Video-Archiv: Liste aller exportierten Videos mit Upload- und Teilen-Funktionen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoArchiveScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var videos by remember { mutableStateOf<List<ArchiveVideo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var confirmDelete by remember { mutableStateOf<ArchiveVideo?>(null) }

    LaunchedEffect(Unit) {
        videos = loadVideoArchive(context)
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Video-Archiv") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        loading = true
                        videos = loadVideoArchive(context)
                        loading = false
                    }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Aktualisieren")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))
            if (loading) {
                Spacer(Modifier.height(24.dp))
                CircularProgressIndicator()
                Text("Lade Videos …", style = MaterialTheme.typography.bodyMedium)
            } else if (videos.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                WerWieWasMark(size = 72.dp)
                Text(
                    "Noch keine Videos im Archiv",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
                Text(
                    "Jedes exportierte Erklärvideo wird automatisch hier gespeichert – " +
                        "mit Direkt-Upload zu YouTube, TikTok und weiteren Plattformen.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            } else {
                videos.forEach { video ->
                    ArchiveVideoCard(video, onDelete = { confirmDelete = video })
                }
                Text(
                    "Alle Videos liegen zusätzlich in deiner Mediathek unter Filme/WerWieWas.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    val pending = confirmDelete
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Video löschen?") },
            text = { Text("„" + pending.name + "“ wird dauerhaft vom Gerät gelöscht.") },
            confirmButton = {
                TextButton(onClick = {
                    val ok = deleteVideo(context, pending)
                    confirmDelete = null
                    loading = true
                    videos = loadVideoArchive(context)
                    loading = false
                    Toast.makeText(
                        context,
                        if (ok) "Video gelöscht." else "Löschen nicht möglich.",
                        Toast.LENGTH_SHORT
                    ).show()
                }) { Text("Löschen") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Abbrechen") }
            }
        )
    }
}

/** Karte eines Archiv-Videos mit Upload- und Teilen-Knöpfen. */
@Composable
private fun ArchiveVideoCard(video: ArchiveVideo, onDelete: () -> Unit) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.VideoLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        video.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        formatDate(video.addedMs) + " – " + formatSize(video.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            Button(
                onClick = { shareVideo(context, video.uri, "com.google.android.youtube") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Auf YouTube hochladen") }
            Button(
                onClick = { shareVideo(context, video.uri, "com.zhiliaoapp.musically") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Auf TikTok hochladen") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { shareVideo(context, video.uri, "com.instagram.android") },
                    modifier = Modifier.weight(1f)
                ) { Text("Instagram") }
                OutlinedButton(
                    onClick = { shareVideo(context, video.uri, "com.whatsapp") },
                    modifier = Modifier.weight(1f)
                ) { Text("WhatsApp") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { shareVideo(context, video.uri, "org.telegram.messenger") },
                    modifier = Modifier.weight(1f)
                ) { Text("Telegram") }
                OutlinedButton(
                    onClick = { shareVideo(context, video.uri, "com.facebook.katana") },
                    modifier = Modifier.weight(1f)
                ) { Text("Facebook") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { openVideo(context, video.uri) },
                    modifier = Modifier.weight(1f)
                ) { Text("Abspielen") }
                OutlinedButton(
                    onClick = { shareVideo(context, video.uri, null) },
                    modifier = Modifier.weight(1f)
                ) { Text("Alle Apps …") }
            }
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) { Text("Löschen") }
            Text(
                "YouTube und TikTok öffnen ihren Upload mit dem Video als Entwurf – " +
                    "Titel, Beschreibung und Veröffentlichen legst du dort fest.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

private fun formatDate(ms: Long): String =
    SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.getDefault()).format(Date(ms))

private fun formatSize(bytes: Long): String =
    if (bytes >= 1024 * 1024) {
        String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
    } else {
        (bytes / 1024).toString() + " KB"
    }
