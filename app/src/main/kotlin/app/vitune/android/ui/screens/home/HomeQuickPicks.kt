package app.vitune.android.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.vitune.android.Database
import app.vitune.android.LocalPlayerAwareWindowInsets
import app.vitune.android.LocalPlayerServiceBinder
import app.vitune.android.R
import app.vitune.android.query
import app.vitune.android.ui.components.LocalMenuState
import app.vitune.android.ui.components.ShimmerHost
import app.vitune.android.ui.components.themed.FloatingActionsContainerWithScrollToTop
import app.vitune.android.ui.components.themed.Header
import app.vitune.android.ui.components.themed.NonQueuedMediaItemMenu
import app.vitune.android.ui.items.SongItem
import app.vitune.android.ui.items.SongItemPlaceholder
import app.vitune.android.ui.screens.Route
import app.vitune.android.utils.asMediaItem
import app.vitune.android.utils.center
import app.vitune.android.utils.forcePlayAtIndex
import app.vitune.android.utils.playingSong
import app.vitune.android.utils.rememberSnapLayoutInfo
import app.vitune.android.utils.secondary
import app.vitune.core.ui.Dimensions
import app.vitune.core.ui.LocalAppearance
import app.vitune.core.ui.utils.isLandscape

/**
 * Car build: Quick picks shows ONLY songs already played in the app
 * (playback history stored locally in the database). No network request is
 * made here, so the home screen no longer waits on YouTube Music to load.
 * When the history is empty an empty state is shown instead of
 * recommendations.
 */
@Route
@Composable
fun QuickPicks(
    onSearchClick: () -> Unit
) {
    val (colorPalette, typography) = LocalAppearance.current
    val binder = LocalPlayerServiceBinder.current
    val menuState = LocalMenuState.current
    val windowInsets = LocalPlayerAwareWindowInsets.current

    val history by Database.history(size = 100).collectAsState(initial = null)

    val scrollState = rememberScrollState()
    val quickPicksLazyGridState = rememberLazyGridState()

    val endPaddingValues = windowInsets.only(WindowInsetsSides.End).asPaddingValues()

    val [currentMediaId, playing] = playingSong(binder)

    BoxWithConstraints {
        val quickPicksLazyGridItemWidthFactor =
            if (isLandscape && maxWidth * 0.475f >= 320.dp) 0.475f else 0.75f

        val snapLayoutInfoProvider = rememberSnapLayoutInfo(
            lazyGridState = quickPicksLazyGridState,
            positionInLayout = { layoutSize, itemSize ->
                (layoutSize * quickPicksLazyGridItemWidthFactor / 2f - itemSize / 2f)
            }
        )

        val itemInHorizontalGridWidth = maxWidth * quickPicksLazyGridItemWidthFactor

        Column(
            modifier = Modifier
                .background(colorPalette.background0)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(
                    windowInsets
                        .only(WindowInsetsSides.Vertical)
                        .asPaddingValues()
                )
        ) {
            Header(
                title = stringResource(R.string.quick_picks),
                modifier = Modifier.padding(endPaddingValues)
            )

            when {
                history == null -> ShimmerHost {
                    repeat(4) {
                        SongItemPlaceholder(thumbnailSize = Dimensions.thumbnails.song)
                    }
                }

                history!!.isEmpty() -> BasicText(
                    text = stringResource(R.string.empty_history),
                    style = typography.s.secondary.center,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(all = 16.dp)
                )

                else -> {
                    val songs = history!!
                    val mediaItems = remember(songs) { songs.map { it.asMediaItem } }

                    LazyHorizontalGrid(
                        state = quickPicksLazyGridState,
                        rows = GridCells.Fixed(4),
                        flingBehavior = rememberSnapFlingBehavior(snapLayoutInfoProvider),
                        contentPadding = endPaddingValues,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((Dimensions.thumbnails.song + Dimensions.items.verticalPadding * 2) * 4)
                    ) {
                        itemsIndexed(
                            items = songs,
                            key = { _, song -> song.id }
                        ) { index, song ->
                            SongItem(
                                modifier = Modifier
                                    .combinedClickable(
                                        onLongClick = {
                                            menuState.display {
                                                NonQueuedMediaItemMenu(
                                                    onDismiss = menuState::hide,
                                                    mediaItem = song.asMediaItem,
                                                    onRemoveFromQuickPicks = {
                                                        query {
                                                            Database.clearEventsFor(song.id)
                                                        }
                                                    }
                                                )
                                            }
                                        },
                                        onClick = {
                                            binder?.stopRadio()
                                            binder?.player?.forcePlayAtIndex(
                                                items = mediaItems,
                                                index = index
                                            )
                                        }
                                    )
                                    .animateItem(fadeInSpec = null, fadeOutSpec = null)
                                    .width(itemInHorizontalGridWidth),
                                song = song,
                                thumbnailSize = Dimensions.thumbnails.song,
                                showDuration = false,
                                isPlaying = playing && currentMediaId == song.id
                            )
                        }
                    }
                }
            }
        }

        FloatingActionsContainerWithScrollToTop(
            scrollState = scrollState,
            icon = R.drawable.search,
            onClick = onSearchClick
        )
    }
}
