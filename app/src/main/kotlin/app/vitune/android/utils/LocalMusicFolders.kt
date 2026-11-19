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
import org.json.JSONObject

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
    // Car build (v4.2): a small pre-decoded thumbnail (~200 px) per song,
    // written next to the full cover. The list rows load this file instead
    // of decoding the full 600 px cover for a 54 dp row — on the weak
    // radio CPU that is the difference between a smooth fast scroll and
    // a stuttering one. The player keeps the full cover (v4.1).
    private const val MAX_THUMB_DIMENSION = 200
    private const val THUMB_QUALITY = 80
    private const val THUMB_SUFFIX = "-t"
    // Car build (v4.2): incremental scans. filesDir/local-scan-state.json
    // remembers "size|lastModified" per song id (and per folder cover),
    // so a manual rescan only re-reads files that are new or changed.
    // It is only an optimisation: if it is missing or corrupt the scan
    // just falls back to reading everything, like before.
    private const val SCAN_STATE_FILE = "local-scan-state.json"
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
        // Drop this folder's entries from the incremental-scan state, so
        // a folder re-added later is scanned fresh instead of trusting
        // fingerprints of songs that are no longer in the database.
        val state = loadScanState(context)
        val songPrefix = "$LOCAL_KEY_PREFIX$SAF_MARKER${treeUri}/document/"
        val coverPrefix = "cover:${treeUri}"
        if (state.keys.removeAll { it.startsWith(songPrefix) || it.startsWith(coverPrefix) }) {
            saveScanState(context, state)
        }
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
     * button — the ONLY way a scan starts in v4.2; nothing scans on its
     * own when the Local tab is opened). A folder that cannot be read
     * (USB unplugged, permission revoked) is skipped and its songs are
     * left untouched.
     *
     * The scan is incremental: a file whose size and last-modified match
     * the remembered fingerprint (and whose row is still in the
     * database) is left completely alone — no metadata read, no cover
     * work, and its row (with its play count) is kept. Only new, changed
     * or previously-missing songs are read, and only files that vanished
     * from the folder are deleted.
     */
    fun scanAll(context: Context, force: Boolean = false) {
        if (isScanning) return
        val toScan = folders().filter { force || it.toString() !in scannedFolders }
        if (toScan.isEmpty()) return
        isScanning = true
        lastScanError = null
        scanScope.launch {
            var total = 0
            val scanState = loadScanState(context)
            try {
                for (treeUri in toScan) {
                    val pattern =
                        "$LOCAL_KEY_PREFIX$SAF_MARKER" + escapeLike(treeUri.toString()) + "/document/%"
                    val existingById = Database.songsLike(pattern)
                        .associateBy { it.id }
                    val result = scanFolder(
                        context.applicationContext, treeUri, existingById, scanState
                    )
                    if (result != null) {
                        transaction {
                            if (result.idsToDelete.isNotEmpty()) {
                                Database.deleteSongsByIds(result.idsToDelete)
                            }
                            if (result.idsToReplace.isNotEmpty()) {
                                Database.deleteSongsByIds(result.idsToReplace)
                            }
                            result.songsToWrite.forEach(Database::insert)
                        }
                        scannedFolders.add(treeUri.toString())
                        total += result.foundCount
                    }
                }
                saveScanState(context, scanState)
                lastScannedCount = total
            } catch (e: Exception) {
                lastScanError = e.message
            } finally {
                isScanning = false
            }
        }
    }

    /** One audio file found while walking a chosen folder. */
    private class ScannedFile(
        val docUri: Uri,
        val displayName: String,
        val size: Long,
        val lastModified: Long,
        val coverRef: CoverRef?
    )

    /** A folder cover image (cover.jpg, folder.jpg, ...) with its size. */
    private class CoverRef(val uri: Uri, val size: Long, val lastModified: Long)

    /** What one folder scan found: songs to write, rows to drop. */
    private class FolderScanResult(
        val songsToWrite: List<Song>,
        val idsToDelete: List<String>,
        val idsToReplace: List<String>,
        val foundCount: Int
    )

    /**
     * Recursively collect the audio files of one folder and build the
     * songs that are new, changed, or missing from the database.
     * Returns null when the folder cannot be read at all.
     */
    private fun scanFolder(
        context: Context,
        treeUri: Uri,
        existingById: Map<String, Song>,
        scanState: MutableMap<String, String>
    ): FolderScanResult? {
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return null
        val found = ArrayList<ScannedFile>()
        try {
            val readable = walkDocuments(context, treeUri, rootId, 0, found)
            if (!readable) return null
        } catch (_: Exception) {
            return null
        }
        val songsToWrite = ArrayList<Song>()
        val idsToReplace = ArrayList<String>()
        val foundIds = HashSet<String>(found.size)
        val retriever = MediaMetadataRetriever()
        try {
            for (file in found) {
                val songId = "$LOCAL_KEY_PREFIX$SAF_MARKER${file.docUri}"
                foundIds += songId
                val fingerprint = "${file.size}|${file.lastModified}"
                val existing = existingById[songId]
                // Unchanged file with its row still in the database:
                // skip it entirely — no metadata read, no cover work.
                if (scanState[songId] == fingerprint && existing != null) continue
                val song = songFromDocument(
                    context, file.docUri, file.displayName, retriever, file.coverRef,
                    forceCover = scanState[songId] != fingerprint,
                    scanState = scanState
                )
                // First scan after v4.2 (no fingerprint yet): keep the
                // existing row when nothing about the song changed, so
                // its play count and likes survive the upgrade.
                if (existing != null && song.sameContentAs(existing)) {
                    scanState[songId] = fingerprint
                    continue
                }
                if (existing != null) idsToReplace += songId
                songsToWrite += song
                scanState[songId] = fingerprint
            }
        } finally {
            runCatching { retriever.release() }
        }
        val idsToDelete = existingById.keys
            .filter { it !in foundIds }
            .onEach { scanState.remove(it) }
        return FolderScanResult(songsToWrite, idsToDelete, idsToReplace, found.size)
    }

    /** Title/artist/duration/cover equality — the parts a scan can change. */
    private fun Song.sameContentAs(other: Song) =
        title == other.title &&
            artistsText == other.artistsText &&
            durationText == other.durationText &&
            thumbnailUrl == other.thumbnailUrl

    /** Returns false only when this level could not be listed at all. */
    private fun walkDocuments(
        context: Context,
        treeUri: Uri,
        documentId: String,
        depth: Int,
        out: MutableList<ScannedFile>,
        coverRef: CoverRef? = null
    ): Boolean {
        if (depth > MAX_DEPTH || out.size >= MAX_FILES) return true
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val cursor = runCatching {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
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
            val sizeIndex = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIndex =
                it.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (it.moveToNext()) {
                val childId = if (idIndex >= 0) it.getString(idIndex) else continue
                val name = if (nameIndex >= 0) it.getString(nameIndex).orEmpty() else ""
                val mime = if (mimeIndex >= 0) it.getString(mimeIndex).orEmpty() else ""
                val size = if (sizeIndex >= 0) it.getLong(sizeIndex) else -1L
                val modified = if (modifiedIndex >= 0) it.getLong(modifiedIndex) else 0L
                children += ChildDocument(childId, name, mime, size, modified)
            }
        }
        // This folder's own cover wins; otherwise the parent folder's
        // cover is inherited by the songs in here.
        val folderCoverRef = children.firstOrNull { isCoverFile(it) }
            ?.let {
                CoverRef(
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, it.id),
                    it.size,
                    it.lastModified
                )
            } ?: coverRef
        for (child in children) {
            if (out.size >= MAX_FILES) break
            if (child.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                if (!child.name.startsWith(".")) {
                    walkDocuments(context, treeUri, child.id, depth + 1, out, folderCoverRef)
                }
            } else if (isAudio(child.name, child.mime)) {
                out += ScannedFile(
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, child.id),
                    child.name,
                    child.size,
                    child.lastModified,
                    folderCoverRef
                )
            }
        }
        return true
    }

    private class ChildDocument(
        val id: String,
        val name: String,
        val mime: String,
        val size: Long = -1L,
        val lastModified: Long = 0L
    )

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
        docUri: Uri,
        displayName: String,
        retriever: MediaMetadataRetriever,
        coverRef: CoverRef?,
        forceCover: Boolean,
        scanState: MutableMap<String, String>
    ): Song {
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
                thumbnailUrl = cacheEmbeddedCover(context, docUri, embedded, forceCover)
                    ?.let { Uri.fromFile(it).toString() }
            }
        } catch (_: Exception) {
            // No metadata available; keep the file name as the title.
        }
        if (thumbnailUrl == null && coverRef != null) {
            cacheFolderCoverThumb(context, coverRef, scanState)
            thumbnailUrl = coverRef.uri.toString()
        }
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
     *
     * With [force] the file is rewritten (the audio file changed, so its
     * art may have changed too); otherwise an existing file is kept.
     * Either way the ~200 px list thumbnail is (re)generated when
     * missing, so songs scanned before v4.2 get one on the next rescan.
     */
    private fun cacheEmbeddedCover(
        context: Context,
        docUri: Uri,
        bytes: ByteArray,
        force: Boolean
    ): File? {
        val dir = File(context.filesDir, COVER_CACHE_DIR).apply { mkdirs() }
        val file = File(dir, sha256Hex(docUri.toString()) + ".jpg")
        if (!file.exists() || force) {
            val data = (if (bytes.size > MAX_EMBEDDED_BYTES) downscaleCover(bytes) else bytes)
                ?: return null
            if (!runCatching { file.writeBytes(data) }.isSuccess) return null
            enforceCoverCacheLimit(dir)
        }
        ensureSmallThumb(context, docUri.toString(), bytes)
        return file
    }

    /**
     * The ~200 px list thumbnail for one cover, written next to the full
     * cover as "<hash>-t.jpg". Generated once from the same bytes; the
     * list rows load this file instead of decoding the full cover.
     */
    private fun ensureSmallThumb(context: Context, key: String, bytes: ByteArray) {
        val dir = File(context.filesDir, COVER_CACHE_DIR).apply { mkdirs() }
        val small = File(dir, sha256Hex(key) + THUMB_SUFFIX + ".jpg")
        if (small.exists()) return
        val data = downscaleCover(bytes, MAX_THUMB_DIMENSION, THUMB_QUALITY) ?: return
        if (!runCatching { small.writeBytes(data) }.isSuccess) return
        enforceCoverCacheLimit(dir)
    }

    /**
     * The ~200 px list thumbnail for a folder cover image (cover.jpg,
     * folder.jpg, ...). The thumbnail file is named after the cover uri,
     * so the list can find it from the thumbnailUrl alone; it is
     * regenerated when the cover file itself changes (size or
     * last-modified, remembered in the scan state).
     */
    private fun cacheFolderCoverThumb(
        context: Context,
        coverRef: CoverRef,
        scanState: MutableMap<String, String>
    ) {
        val fingerprint = "${coverRef.size}|${coverRef.lastModified}"
        val stateKey = "cover:${coverRef.uri}"
        val dir = File(context.filesDir, COVER_CACHE_DIR).apply { mkdirs() }
        val small = File(dir, sha256Hex(coverRef.uri.toString()) + THUMB_SUFFIX + ".jpg")
        if (scanState[stateKey] == fingerprint && small.exists()) return
        val bytes = runCatching {
            context.contentResolver.openInputStream(coverRef.uri)?.use { it.readBytes() }
        }.getOrNull() ?: return
        val data = downscaleCover(bytes, MAX_THUMB_DIMENSION, THUMB_QUALITY) ?: return
        if (!runCatching { small.writeBytes(data) }.isSuccess) return
        enforceCoverCacheLimit(dir)
        scanState[stateKey] = fingerprint
    }

    private fun downscaleCover(
        bytes: ByteArray,
        maxDimension: Int = MAX_COVER_DIMENSION,
        quality: Int = 85
    ): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        if (maxDim <= 0) return null
        var sample = 1
        while (maxDim / sample > maxDimension) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: return null
        return try {
            ByteArrayOutputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * The list-row thumbnail for a song's cover: the pre-decoded ~200 px
     * file when one was cached at scan time, otherwise the original
     * thumbnailUrl unchanged (remote art, MediaStore art, songs scanned
     * before v4.2, evicted cache). The player keeps using the full cover
     * from the database, never this.
     */
    fun smallCoverUrl(context: Context, thumbnailUrl: String?): String? {
        if (thumbnailUrl == null) return null
        val dir = File(context.filesDir, COVER_CACHE_DIR)
        if (thumbnailUrl.startsWith("file:") && thumbnailUrl.endsWith(".jpg") &&
            !thumbnailUrl.endsWith("$THUMB_SUFFIX.jpg")
        ) {
            val smallUrl = thumbnailUrl.removeSuffix(".jpg") + "$THUMB_SUFFIX.jpg"
            val path = runCatching { Uri.parse(smallUrl).path }.getOrNull()
            if (path != null && File(path).exists()) return smallUrl
            return thumbnailUrl
        }
        if (thumbnailUrl.startsWith("content://")) {
            val small = File(dir, sha256Hex(thumbnailUrl) + THUMB_SUFFIX + ".jpg")
            if (small.exists()) return Uri.fromFile(small).toString()
            return thumbnailUrl
        }
        return thumbnailUrl
    }

    /**
     * The incremental-scan state: song id (or "cover:<uri>") to
     * "size|lastModified". Only an optimisation — a missing or corrupt
     * file just means the next scan reads everything, like before.
     */
    private fun loadScanState(context: Context): MutableMap<String, String> {
        val file = File(context.filesDir, SCAN_STATE_FILE)
        if (!file.exists()) return HashMap()
        return runCatching {
            val json = JSONObject(file.readText())
            val map = HashMap<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = json.optString(key)
            }
            map
        }.getOrElse { HashMap() }
    }

    private fun saveScanState(context: Context, state: Map<String, String>) {
        runCatching {
            val json = JSONObject()
            for ((key, value) in state) json.put(key, value)
            File(context.filesDir, SCAN_STATE_FILE).writeText(json.toString())
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
