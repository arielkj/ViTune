package app.vitune.android.utils

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.vitune.android.Database
import app.vitune.android.models.Song
import app.vitune.android.preferences.DataPreferences
import app.vitune.android.service.LOCAL_KEY_PREFIX
import app.vitune.android.transaction
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Car build: local music from folders the user chooses with the system folder
 * picker (SAF, ACTION_OPEN_DOCUMENT_TREE).
 *
 * Why this exists: on the AC8227L car radio (Android 8.1) the MediaStore
 * index does not reliably see the user's music (USB sticks / SD cards that
 * were never scanned by the system media scanner), so the Local tab stayed
 * empty even though the files were on the device. With SAF the user picks
 * the folder once; the permission is persisted, so the app can re-read it
 * on every launch without asking again.
 *
 * Songs found this way get ids of the form "local:saf:<document uri>".
 * They still start with LOCAL_KEY_PREFIX ("local:"), so the database
 * queries, the "is local" checks and the player cache logic treat them as
 * local songs; only [app.vitune.android.utils.asMediaItem] needs to know
 * that the part after the prefix is a document Uri instead of a MediaStore
 * row id. The id is derived from the document Uri, so it is stable across
 * rescans and survives app restarts.
 *
 * The scan is deliberately serial and capped: one file at a time, one
 * background job, at most [MAX_FILES] files and [MAX_DEPTH] levels deep,
 * so it never floods the weak quad-core radio with parallel work.
 */
object LocalMusicFolders {
    const val SAF_MARKER = "saf:"

    private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "ogg", "m4a", "wav", "opus", "aac")
    private const val MAX_FILES = 5000
    private const val MAX_DEPTH = 12

    /** True while a scan is running; shown in the Local tab. */
    var isScanning by mutableStateOf(false)
        private set

    /** Number of songs found by the last finished scan. */
    var lastScannedCount by mutableStateOf(0)
        private set

    /** Non-null when the last scan could not read a folder. */
    var lastScanError by mutableStateOf<String?>(null)
        private set

    private val scanScope =
        CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineName("Local music scan"))

    /** Folders already scanned in this process; rescan only when forced. */
    private val scannedFolders = mutableSetOf<String>()

    fun folders(): List<Uri> =
        DataPreferences.localMusicFolders.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }

    /** Short human-readable name for a tree Uri, e.g. "Music" for "primary:Music". */
    fun folderDisplayName(treeUri: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        val name = id?.substringAfter(':', id)?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
        return name ?: treeUri.lastPathSegment.orEmpty()
    }

    /** Persist the folder (with its permission) and scan it right away. */
    fun addFolder(context: Context, treeUri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Some providers do not grant persistable access; the folder can
            // still be read while the app process lives.
        }
        val current = DataPreferences.localMusicFolders.toMutableSet()
        if (current.add(treeUri.toString())) {
            DataPreferences.localMusicFolders = current
        }
        scanAll(context, force = true)
    }

    /** Forget a folder and remove the songs that came from it. */
    fun removeFolder(context: Context, treeUri: Uri) {
        val current = DataPreferences.localMusicFolders.toMutableSet()
        if (current.remove(treeUri.toString())) {
            DataPreferences.localMusicFolders = current
        }
        scannedFolders.remove(treeUri.toString())
        try {
            context.contentResolver.releasePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }
        val pattern = "$LOCAL_KEY_PREFIX$SAF_MARKER" + escapeLike(treeUri.toString()) + "/document/%"
        transaction { Database.deleteSongsLike(pattern) }
    }

    /**
     * Scan every chosen folder that has not been scanned yet in this
     * process, or all of them when [force] is true (the "Volver a buscar"
     * button). A folder that cannot be read (USB unplugged, permission
     * revoked) is skipped and its songs are left untouched.
     */
    fun scanAll(context: Context, force: Boolean = false) {
        if (isScanning) return
        val toScan = folders().filter { force || it.toString() !in scannedFolders }
        if (toScan.isEmpty()) return
        isScanning = true
        lastScanError = null
        scanScope.launch {
            var total = 0
            try {
                for (treeUri in toScan) {
                    val songs = scanFolder(context.applicationContext, treeUri)
                    if (songs != null) {
                        val pattern =
                            "$LOCAL_KEY_PREFIX$SAF_MARKER" + escapeLike(treeUri.toString()) + "/document/%"
                        transaction {
                            Database.deleteSongsLike(pattern)
                            songs.forEach(Database::insert)
                        }
                        scannedFolders.add(treeUri.toString())
                        total += songs.size
                    }
                }
                lastScannedCount = total
            } catch (e: Exception) {
                lastScanError = e.message
            } finally {
                isScanning = false
            }
        }
    }

    /**
     * Recursively collect the audio files of one folder.
     * Returns null when the folder cannot be read at all.
     */
    private fun scanFolder(context: Context, treeUri: Uri): List<Song>? {
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return null
        val found = ArrayList<Song>()
        val retriever = MediaMetadataRetriever()
        try {
            val readable = walkDocuments(context, treeUri, rootId, 0, found, retriever)
            if (!readable) return null
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { retriever.release() }
        }
        return found
    }

    /** Returns false only when this level could not be listed at all. */
    private fun walkDocuments(
        context: Context,
        treeUri: Uri,
        documentId: String,
        depth: Int,
        out: MutableList<Song>,
        retriever: MediaMetadataRetriever
    ): Boolean {
        if (depth > MAX_DEPTH || out.size >= MAX_FILES) return true
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val cursor = runCatching {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )
        }.getOrNull() ?: return false
        cursor.use {
            val idIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (it.moveToNext() && out.size < MAX_FILES) {
                val childId = if (idIndex >= 0) it.getString(idIndex) else continue
                val name = if (nameIndex >= 0) it.getString(nameIndex).orEmpty() else ""
                val mime = if (mimeIndex >= 0) it.getString(mimeIndex).orEmpty() else ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (!name.startsWith(".")) {
                        walkDocuments(context, treeUri, childId, depth + 1, out, retriever)
                    }
                } else if (isAudio(name, mime)) {
                    songFromDocument(context, treeUri, childId, name, retriever)?.let(out::add)
                }
            }
        }
        return true
    }

    private fun isAudio(name: String, mime: String): Boolean {
        if (mime.startsWith("audio/")) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }

    private fun songFromDocument(
        context: Context,
        treeUri: Uri,
        documentId: String,
        displayName: String,
        retriever: MediaMetadataRetriever
    ): Song? {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        var title = displayName.substringBeforeLast('.')
        var artist: String? = null
        var durationMs = 0L
        try {
            retriever.setDataSource(context, docUri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }?.let { title = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }?.let { artist = it }
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            // No metadata available; keep the file name as the title.
        }
        return Song(
            id = "$LOCAL_KEY_PREFIX$SAF_MARKER$docUri",
            title = title,
            artistsText = artist,
            durationText = durationMs.milliseconds.toComponents { minutes, seconds, _ ->
                "$minutes:${seconds.toString().padStart(2, '0')}"
            },
            thumbnailUrl = null
        )
    }

    private fun escapeLike(value: String) = value
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
}
