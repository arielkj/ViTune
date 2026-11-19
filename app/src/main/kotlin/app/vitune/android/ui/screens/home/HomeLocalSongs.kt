package app.vitune.android.ui.screens.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.vitune.android.Database
import app.vitune.android.LocalPlayerAwareWindowInsets
import app.vitune.android.R
import app.vitune.android.models.Song
import app.vitune.android.preferences.DataPreferences
import app.vitune.android.preferences.OrderPreferences
import app.vitune.android.service.LOCAL_KEY_PREFIX
import app.vitune.android.transaction
import app.vitune.android.ui.components.themed.FloatingActionsContainerWithScrollToTop
import app.vitune.android.ui.components.themed.Header
import app.vitune.android.ui.components.themed.SecondaryTextButton
import app.vitune.android.ui.items.PlaylistItem
import app.vitune.android.ui.screens.Route
import app.vitune.android.utils.AudioMediaCursor
import app.vitune.android.utils.LocalMusicFolders
import app.vitune.android.utils.LocalMusicFolders.LocalFolderGroup
import app.vitune.android.utils.hasPermission
import app.vitune.android.utils.medium
import app.vitune.android.utils.secondary
import app.vitune.core.ui.Dimensions
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
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
                LocalSongsContent(onSearchClick = onSearchClick)
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
 * The song area of the Local tab.
 *
 * When a chosen folder has subfolders, they show as playlist-like entries:
 * one section per chosen root folder, with a "Todas" row (all songs of that
 * root) on top and one row per first-level subfolder, however many there
 * are. Tapping a row opens that group's songs; tapping "Todas" opens the
 * flat list as before. When no subfolder exists anywhere, the tab shows
 * the flat song list exactly as it used to.
 *
 * The grouping runs on Dispatchers.Default, inside the same flow that
 * feeds the list, so nothing extra happens on the main thread or at
 * startup.
 */
@Composable
private fun LocalSongsContent(onSearchClick: () -> Unit) = with(OrderPreferences) {
    val groups by remember(localSongSortBy, localSongSortOrder) {
        Database.songs(
            sortBy = localSongSortBy,
            sortOrder = localSongSortOrder,
            isLocal = true
        )
            .map { songs -> songs.filter { it.durationText != "0:00" } }
            .map { songs -> LocalMusicFolders.groupSongs(songs, LocalMusicFolders.folders()) }
            .flowOn(Dispatchers.Default)
    }.collectAsState(initial = null)

    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedGroup = groups?.find { it.key == selectedKey }

    // The selected group vanished (folder removed, rescan): back to the list.
    LaunchedEffect(groups) {
        if (selectedKey != null && groups != null && selectedGroup == null) selectedKey = null
    }

    BackHandler(enabled = selectedKey != null) { selectedKey = null }

    val groupsValue = groups
    when {
        groupsValue.isNullOrEmpty() -> HomeSongs(
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

        selectedGroup != null -> HomeSongs(
            onSearchClick = onSearchClick,
            songProvider = remember(selectedGroup) { { flowOf(selectedGroup.songs) } },
            sortBy = localSongSortBy,
            setSortBy = { localSongSortBy = it },
            sortOrder = localSongSortOrder,
            setSortOrder = { localSongSortOrder = it },
            title = selectedGroup.headerTitle()
        )

        else -> LocalFolderGroupsList(
            groups = groupsValue,
            onGroupClick = { selectedKey = it.key },
            onSearchClick = onSearchClick
        )
    }
}

/**
 * The playlist-like list of subfolders: one section per chosen root
 * folder, "Todas" first, then one row per first-level subfolder,
 * alphabetically. The list is a LazyColumn, so 5, 10 or any number of
 * subfolders scroll cleanly.
 */
@Composable
private fun LocalFolderGroupsList(
    groups: List<LocalFolderGroup>,
    onGroupClick: (LocalFolderGroup) -> Unit,
    onSearchClick: () -> Unit
) {
    val (colorPalette, typography) = LocalAppearance.current
    val lazyListState = rememberLazyListState()
    // With a single section the folder bar above already says which folder
    // this is; section headers only earn their space with two or more.
    val showSectionHeaders = groups.map { it.rootUri }.distinct().size > 1

    Box(
        modifier = Modifier
            .background(colorPalette.background0)
            .fillMaxSize()
    ) {
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Vertical + WindowInsetsSides.End)
                .asPaddingValues()
        ) {
            item(key = "header", contentType = 0) {
                Header(title = stringResource(R.string.local))
            }

            var lastRoot: String? = null
            for (group in groups) {
                if (showSectionHeaders && group.rootUri != lastRoot) {
                    lastRoot = group.rootUri
                    item(key = "section-${group.rootUri}", contentType = 0) {
                        BasicText(
                            text = if (group.isOnDevice) stringResource(R.string.local_on_device)
                            else group.rootName,
                            style = typography.xs.secondary.semiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }

                item(key = "group-${group.key}", contentType = 1) {
                    LocalFolderGroupRow(
                        group = group,
                        modifier = Modifier
                            .clickable { onGroupClick(group) }
                            .animateItem()
                    )
                }
            }
        }

        FloatingActionsContainerWithScrollToTop(
            lazyListState = lazyListState,
            icon = R.drawable.search,
            onClick = onSearchClick
        )
    }
}

@Composable
private fun LocalFolderGroupRow(
    group: LocalFolderGroup,
    modifier: Modifier = Modifier
) {
    val (colorPalette) = LocalAppearance.current
    val coverUrl = group.coverUrl
    if (coverUrl != null) {
        PlaylistItem(
            thumbnailUrl = coverUrl,
            songCount = group.songs.size,
            name = group.rowName(),
            channelName = null,
            thumbnailSize = Dimensions.thumbnails.playlist,
            modifier = modifier
        )
    } else {
        PlaylistItem(
            icon = if (group.subfolder == null) R.drawable.musical_notes else R.drawable.playlist,
            colorTint = colorPalette.text,
            name = group.rowName(),
            songCount = group.songs.size,
            thumbnailSize = Dimensions.thumbnails.playlist,
            modifier = modifier
        )
    }
}

/** Row label: the subfolder name, or "Todas" for the all-songs entry. */
@Composable
private fun LocalFolderGroup.rowName(): String =
    subfolder ?: stringResource(R.string.local_all_songs)

/** Title of the song list opened from a group. */
@Composable
private fun LocalFolderGroup.headerTitle(): String = subfolder
    ?: if (isOnDevice) stringResource(R.string.local_on_device) else rootName

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
