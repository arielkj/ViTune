package app.vitune.android.utils

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
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

    // Local covers: the art embedded in each audio file is cached once per
    // song under filesDir/local-covers, so the lists, the subfolder rows
    // and the player can all load it through the normal thumbnailUrl path
    // (Coil + the player's BitmapProvider). Raw bytes are stored as-is up
    // to MAX_EMBEDDED_BYTES; bigger art is downscaled to
    // MAX_COVER_DIMENSION px first, and the whole cache is capped at
    // MAX_COVER_CACHE_BYTES (oldest files evicted first).
    private const val COVER_CACHE_DIR = "local-covers"
    private const val MAX_EMBEDDED_BYTES = 400 * 1024
    private const val MAX_COVER_DIMENSION = 600
    private const val MAX_COVER_CACHE_BYTES = 200L * 1024 * 1024
    private val COVER_FILENAMES = setOf(
        "cover.jpg", "cover.jpeg", "folder.jpg", "folder.jpeg",
        "albumart.jpg", "albumart.jpeg", "front.jpg", "front.jpeg",
        "cover.png", "folder.png"
    )

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
        retriever: MediaMetadataRetriever,
        coverUri: Uri? = null
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
        // Collect the children first: a cover file (cover.jpg, folder.jpg,
        // ...) in this folder is the artwork of the songs in it, so it has
        // to be known before the audio files are read.
        val children = ArrayList<ChildDocument>()
        cursor.use {
            val idIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (it.moveToNext()) {
                val childId = if (idIndex >= 0) it.getString(idIndex) else continue
                val name = if (nameIndex >= 0) it.getString(nameIndex).orEmpty() else ""
                val mime = if (mimeIndex >= 0) it.getString(mimeIndex).orEmpty() else ""
                children += ChildDocument(childId, name, mime)
            }
        }
        // This folder's own cover wins; otherwise the parent folder's
        // cover is inherited by the songs in here.
        val folderCoverUri = children.firstOrNull { isCoverFile(it) }
            ?.let { DocumentsContract.buildDocumentUriUsingTree(treeUri, it.id) }
            ?: coverUri
        for (child in children) {
            if (out.size >= MAX_FILES) break
            if (child.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                if (!child.name.startsWith(".")) {
                    walkDocuments(context, treeUri, child.id, depth + 1, out, retriever, folderCoverUri)
                }
            } else if (isAudio(child.name, child.mime)) {
                songFromDocument(context, treeUri, child.id, child.name, retriever, folderCoverUri)
                    ?.let(out::add)
            }
        }
        return true
    }

    private class ChildDocument(val id: String, val name: String, val mime: String)

    private fun isCoverFile(child: ChildDocument): Boolean {
        val name = child.name.lowercase()
        if (name in COVER_FILENAMES) return true
        if (!child.mime.startsWith("image/")) return false
        return name.startsWith("cover.") || name.startsWith("folder.")
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
        retriever: MediaMetadataRetriever,
        coverUri: Uri?
    ): Song? {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        var title = displayName.substringBeforeLast('.')
        var artist: String? = null
        var durationMs = 0L
        // Car build: local covers. SAF songs used to be stored with no
        // cover at all (thumbnailUrl null), so nothing ever showed in the
        // lists or the player. Priority: the art embedded in the file
        // itself, cached once per song as a small file; otherwise the
        // folder's cover image (cover.jpg / folder.jpg, ...).
        var thumbnailUrl: String? = null
        try {
            retriever.setDataSource(context, docUri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }?.let { title = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }?.let { artist = it }
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val embedded = runCatching { retriever.embeddedPicture }.getOrNull()
            if (embedded != null && embedded.isNotEmpty()) {
                thumbnailUrl = cacheEmbeddedCover(context, docUri, embedded)
                    ?.let { Uri.fromFile(it).toString() }
            }
        } catch (_: Exception) {
            // No metadata available; keep the file name as the title.
        }
        if (thumbnailUrl == null) thumbnailUrl = coverUri?.toString()
        return Song(
            id = "$LOCAL_KEY_PREFIX$SAF_MARKER$docUri",
            title = title,
            artistsText = artist,
            durationText = durationMs.milliseconds.toComponents { minutes, seconds, _ ->
                "$minutes:${seconds.toString().padStart(2, '0')}"
            },
            thumbnailUrl = thumbnailUrl
        )
    }

    /**
     * Cache the art embedded in one audio file as a small JPEG under
     * filesDir/local-covers, once per song (the file name is the hash of
     * the document uri, so a rescan reuses it instead of writing it
     * again). Raw bytes are stored as-is up to [MAX_EMBEDDED_BYTES];
     * bigger art is downscaled to [MAX_COVER_DIMENSION] px first so the
     * cache never grows without bound. The whole cache is capped at
     * [MAX_COVER_CACHE_BYTES] (oldest files evicted first).
     */
    private fun cacheEmbeddedCover(context: Context, docUri: Uri, bytes: ByteArray): File? {
        val dir = File(context.filesDir, COVER_CACHE_DIR).apply { mkdirs() }
        val file = File(dir, sha256Hex(docUri.toString()) + ".jpg")
        if (file.exists()) return file
        val data = (if (bytes.size > MAX_EMBEDDED_BYTES) downscaleCover(bytes) else bytes)
            ?: return null
        if (!runCatching { file.writeBytes(data) }.isSuccess) return null
        enforceCoverCacheLimit(dir)
        return file
    }

    private fun downscaleCover(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        if (maxDim <= 0) return null
        var sample = 1
        while (maxDim / sample > MAX_COVER_DIMENSION) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: return null
        return try {
            ByteArrayOutputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)) return null
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun enforceCoverCacheLimit(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_COVER_CACHE_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_COVER_CACHE_BYTES) break
            total -= file.length()
            file.delete()
        }
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun escapeLike(value: String) = value
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    /**
     * One playlist-like entry in the Local tab: either all the songs of a
     * chosen root folder (the "Todas" entry, [subfolder] null) or the songs
     * of one of its first-level subfolders (e.g. "Rock" for
     * Musica/Rock/...). [rootUri] is empty for songs that came from the
     * system media library instead of a chosen folder.
     */
    data class LocalFolderGroup(
        val key: String,
        val rootUri: String,
        val rootName: String,
        val subfolder: String?,
        val songs: List<Song>
    ) {
        /** True for the songs the system media scanner found (no folder). */
        val isOnDevice: Boolean get() = rootUri.isEmpty()

        /** Cover of the first song in the group that has one, if any. */
        val coverUrl: String? get() = songs.firstOrNull { it.thumbnailUrl != null }?.thumbnailUrl
    }

    /**
     * Group local songs by first-level subfolder of each chosen root folder.
     * One group per subfolder, however many there are, plus one "Todas"
     * group per root with all of that root's songs. Songs directly in a
     * root (no subfolder) only appear in its "Todas" group. Songs from the
     * system media library have no folder, so they get their own "Todas"
     * group at the end when there are any.
     *
     * Returns an empty list when no subfolder exists anywhere, so the
     * caller keeps the flat song list exactly as before. The grouping is
     * pure string work on the song ids (no I/O), meant to run off the main
     * thread in a flow.
     */
    fun groupSongs(songs: List<Song>, roots: List<Uri>): List<LocalFolderGroup> {
        if (songs.isEmpty() || roots.isEmpty()) return emptyList()
        val rootIds = roots.mapNotNull { root ->
            runCatching { DocumentsContract.getTreeDocumentId(root) }.getOrNull()
                ?.let { root to it }
        }
        if (rootIds.isEmpty()) return emptyList()

        val buckets = rootIds.associate { (root, _) -> root to RootBucket() }
        val ungrouped = ArrayList<Song>()
        var hasSubfolder = false
        for (song in songs) {
            val docId = safDocumentId(song)
            if (docId == null) {
                ungrouped += song
                continue
            }
            val match = rootIds.firstOrNull { (_, rootId) ->
                docId == rootId || docId.startsWith("$rootId/")
            }
            if (match == null) {
                ungrouped += song
                continue
            }
            val (root, rootId) = match
            val remainder = if (docId == rootId) "" else docId.removePrefix("$rootId/")
            val subfolder = remainder.substringBefore('/').takeIf { remainder.contains('/') }
            val bucket = buckets.getValue(root)
            bucket.songs += song
            if (subfolder != null) {
                bucket.subfolders.getOrPut(subfolder) { ArrayList() } += song
                hasSubfolder = true
            }
        }
        if (!hasSubfolder) return emptyList()

        val groups = ArrayList<LocalFolderGroup>()
        for ((root, _) in rootIds) {
            val bucket = buckets.getValue(root)
            if (bucket.songs.isEmpty()) continue
            val rootName = folderDisplayName(root)
            groups += LocalFolderGroup(
                key = "$root\u0001",
                rootUri = root.toString(),
                rootName = rootName,
                subfolder = null,
                songs = bucket.songs
            )
            for ((name, subSongs) in bucket.subfolders.entries
                .sortedBy { (name, _) -> name.lowercase() }) {
                groups += LocalFolderGroup(
                    key = "$root\u0001$name",
                    rootUri = root.toString(),
                    rootName = rootName,
                    subfolder = name,
                    songs = subSongs
                )
            }
        }
        if (ungrouped.isNotEmpty()) {
            groups += LocalFolderGroup(
                key = "\u0001",
                rootUri = "",
                rootName = "",
                subfolder = null,
                songs = ungrouped
            )
        }
        return groups
    }

    private class RootBucket {
        val songs = ArrayList<Song>()
        val subfolders = linkedMapOf<String, MutableList<Song>>()
    }

    /**
     * The document id of a song scanned from a chosen folder, e.g.
     * "primary:Musica/Rock/song.mp3". The id of such a song is
     * "local:saf:<document uri>" where the document id is the last path
     * segment of the uri (percent-decoded by [Uri.getLastPathSegment]).
     * Returns null for songs that did not come from a chosen folder
     * (system media library songs).
     */
    private fun safDocumentId(song: Song): String? {
        val prefix = "$LOCAL_KEY_PREFIX$SAF_MARKER"
        if (!song.id.startsWith(prefix)) return null
        return runCatching {
            Uri.parse(song.id.removePrefix(prefix)).lastPathSegment
        }.getOrNull()
    }
}
