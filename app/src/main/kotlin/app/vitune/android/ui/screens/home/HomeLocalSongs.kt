package app.vitune.android.ui.screens.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vitune.android.Database
import app.vitune.android.R
import app.vitune.android.models.Song
import app.vitune.android.preferences.DataPreferences
import app.vitune.android.preferences.OrderPreferences
import app.vitune.android.service.LOCAL_KEY_PREFIX
import app.vitune.android.transaction
import app.vitune.android.ui.components.themed.SecondaryTextButton
import app.vitune.android.ui.screens.Route
import app.vitune.android.utils.AudioMediaCursor
import app.vitune.android.utils.LocalMusicFolders
import app.vitune.android.utils.hasPermission
import app.vitune.android.utils.medium
import app.vitune.android.utils.secondary
import app.vitune.core.ui.LocalAppearance
import app.vitune.core.ui.utils.isAtLeastAndroid13
import app.vitune.core.ui.utils.isCompositionLaunched
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val permission = if (isAtLeastAndroid13) Manifest.permission.READ_MEDIA_AUDIO
else Manifest.permission.READ_EXTERNAL_STORAGE

@Route
@Composable
fun HomeLocalSongs(onSearchClick: () -> Unit) = with(OrderPreferences) {
    val context = LocalContext.current
    val [_, typography] = LocalAppearance.current

    var hasPermission by remember(isCompositionLaunched()) {
        mutableStateOf(context.applicationContext.hasPermission(permission))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { hasPermission = it }
    )

    // Car build: system folder picker (SAF). The user chooses the folder
    // with their music once; the permission is persisted, so the songs are
    // there on every launch without asking again.
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri -> uri?.let { LocalMusicFolders.addFolder(context, it) } }
    )

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            // scanAll() first: it returns immediately (the scan runs in its
            // own scope), while collect() below never returns.
            LocalMusicFolders.scanAll(context)
            context.musicFilesAsFlow().collect()
        }
    }

    if (hasPermission) {
        Column(modifier = Modifier.fillMaxSize()) {
            LocalFoldersBar(
                onChooseFolder = { folderLauncher.launch(null) },
                onRescan = { LocalMusicFolders.scanAll(context, force = true) },
                onRemoveFolder = { uri -> LocalMusicFolders.removeFolder(context, uri) }
            )

            Box(modifier = Modifier.weight(1f)) {
                HomeSongs(
                    onSearchClick = onSearchClick,
                    songProvider = {
                        Database.songs(
                            sortBy = localSongSortBy,
                            sortOrder = localSongSortOrder,
                            isLocal = true
                        ).map { songs -> songs.filter { it.durationText != "0:00" } }
                    },
                    sortBy = localSongSortBy,
                    setSortBy = { localSongSortBy = it },
                    sortOrder = localSongSortOrder,
                    setSortOrder = { localSongSortOrder = it },
                    title = stringResource(R.string.local)
                )
            }
        }
    } else {
        LaunchedEffect(Unit) { permissionLauncher.launch(permission) }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BasicText(
                text = stringResource(R.string.media_permission_declined),
                modifier = Modifier.fillMaxWidth(0.75f),
                style = typography.m.medium
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SecondaryTextButton(
                    text = stringResource(R.string.retry_permission),
                    onClick = { permissionLauncher.launch(permission) }
                )
                SecondaryTextButton(
                    text = stringResource(R.string.open_settings),
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                        )
                    }
                )
            }
        }
    }
}

/**
 * Folder management bar at the top of the Local tab: pick a folder, rescan
 * the chosen ones, and remove folders. The MediaStore scan still runs as a
 * complement, but on the car radio it often finds nothing (USB/SD music
 * that the system media scanner never indexed), so the chosen folders are
 * what actually fills the list.
 */
@Composable
private fun LocalFoldersBar(
    onChooseFolder: () -> Unit,
    onRescan: () -> Unit,
    onRemoveFolder: (Uri) -> Unit
) {
    val [_, typography] = LocalAppearance.current
    val folders = DataPreferences.localMusicFolders

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SecondaryTextButton(
                text = stringResource(R.string.choose_music_folder),
                onClick = onChooseFolder
            )
            SecondaryTextButton(
                text = stringResource(R.string.rescan_music),
                onClick = onRescan,
                enabled = folders.isNotEmpty() && !LocalMusicFolders.isScanning
            )
        }

        when {
            LocalMusicFolders.isScanning -> BasicText(
                text = stringResource(R.string.scanning_music),
                style = typography.xs.secondary
            )

            folders.isEmpty() -> BasicText(
                text = stringResource(R.string.no_music_folder),
                style = typography.xs.secondary
            )

            LocalMusicFolders.lastScanError != null -> BasicText(
                text = stringResource(R.string.folder_read_error),
                style = typography.xs.secondary
            )
        }

        folders.forEach { folderUriString ->
            val folderUri = runCatching { Uri.parse(folderUriString) }.getOrNull() ?: return@forEach
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    text = LocalMusicFolders.folderDisplayName(folderUri),
                    modifier = Modifier.weight(1f),
                    style = typography.xs.medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                SecondaryTextButton(
                    text = stringResource(R.string.remove_folder),
                    onClick = { onRemoveFolder(folderUri) }
                )
            }
        }
    }
}

private val mediaScope = CoroutineScope(Dispatchers.IO + CoroutineName("MediaStore worker"))
fun Context.musicFilesAsFlow(): StateFlow<List<Song>> = flow {
    var version: String? = null

    while (currentCoroutineContext().isActive) {
        val newVersion = MediaStore.getVersion(applicationContext)

        if (version != newVersion) {
            version = newVersion

            AudioMediaCursor.query(contentResolver) {
                buildList {
                    while (next()) {
                        if (!isMusic || duration == 0) continue
                        add(
                            Song(
                                id = "$LOCAL_KEY_PREFIX$id",
                                title = name,
                                artistsText = artist,
                                durationText = duration.milliseconds.toComponents { minutes, seconds, _ ->
                                    "$minutes:${seconds.toString().padStart(2, '0')}"
                                },
                                thumbnailUrl = albumUri.toString()
                            )
                        )
                    }
                }
            }?.let { emit(it) }
        }
        delay(5.seconds)
    }
}.distinctUntilChanged()
    .onEach { songs -> transaction { songs.forEach(Database::insert) } }
    // Car build: WhileSubscribed instead of Eagerly — the MediaStore poller
    // only runs while the Local tab is actually open, instead of polling
    // every 5 seconds for the whole life of the app once started.
    .stateIn(mediaScope, SharingStarted.WhileSubscribed(), listOf())
