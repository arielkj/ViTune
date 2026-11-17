package app.vitune.android.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import app.vitune.android.R
import app.vitune.android.preferences.UIStatePreferences
import app.vitune.android.ui.components.themed.Scaffold
import app.vitune.android.ui.screens.GlobalRoutes
import app.vitune.android.ui.screens.Route
import app.vitune.android.ui.screens.builtInPlaylistRoute
import app.vitune.android.ui.screens.builtinplaylist.BuiltInPlaylistScreen
import app.vitune.android.ui.screens.localPlaylistRoute
import app.vitune.android.ui.screens.localplaylist.LocalPlaylistScreen
import app.vitune.android.ui.screens.pipedPlaylistRoute
import app.vitune.android.ui.screens.searchRoute
import app.vitune.android.ui.screens.settingsRoute
import app.vitune.compose.persist.PersistMapCleanup
import app.vitune.compose.routing.RouteHandler

/**
 * Car build: only 4 home tabs remain (Quick picks, Songs, Playlists, Local).
 * Discover, Artists and Albums were removed. The Scaffold key is "home_car"
 * (instead of "home") so the persisted hidden-tabs state starts empty: the
 * old "home" state stored tab keys from the 7-tab layout and could hide the
 * wrong tabs after renumbering. The Local tab can never be hidden
 * (canHide = false) so it is always visible on the car radio.
 */
@Route
@Composable
fun HomeScreen() {
    val saveableStateHolder = rememberSaveableStateHolder()

    PersistMapCleanup("home/")

    RouteHandler {
        GlobalRoutes()

        localPlaylistRoute { playlistId ->
            LocalPlaylistScreen(playlistId = playlistId)
        }

        builtInPlaylistRoute { builtInPlaylist ->
            BuiltInPlaylistScreen(builtInPlaylist = builtInPlaylist)
        }

        Content {
            // Clamp the persisted tab index: installs that used the old 7-tab
            // layout may have 4, 5 or 6 stored, which no longer exist.
            val tabIndex = UIStatePreferences.homeScreenTabIndex.coerceIn(0, 3)

            Scaffold(
                key = "home_car",
                topIconButtonId = R.drawable.settings,
                onTopIconButtonClick = { settingsRoute() },
                tabIndex = tabIndex,
                onTabChange = { UIStatePreferences.homeScreenTabIndex = it },
                tabColumnContent = {
                    tab(0, R.string.quick_picks, R.drawable.sparkles)
                    tab(1, R.string.songs, R.drawable.musical_notes)
                    tab(2, R.string.playlists, R.drawable.playlist)
                    tab(3, R.string.local, R.drawable.download, canHide = false)
                }
            ) { currentTabIndex ->
                saveableStateHolder.SaveableStateProvider(key = currentTabIndex) {
                    val onSearchClick = { searchRoute("") }
                    when (currentTabIndex) {
                        0 -> QuickPicks(
                            onSearchClick = onSearchClick
                        )

                        1 -> HomeSongs(
                            onSearchClick = onSearchClick
                        )

                        2 -> HomePlaylists(
                            onBuiltInPlaylist = { builtInPlaylistRoute(it) },
                            onPlaylistClick = { localPlaylistRoute(it.id) },
                            onPipedPlaylistClick = { session, playlist ->
                                pipedPlaylistRoute(
                                    p0 = session.apiBaseUrl.toString(),
                                    p1 = session.token,
                                    p2 = playlist.id.toString()
                                )
                            },
                            onSearchClick = onSearchClick
                        )

                        3 -> HomeLocalSongs(
                            onSearchClick = onSearchClick
                        )
                    }
                }
            }
        }
    }
}
