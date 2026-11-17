package app.vitune.android.utils

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAll
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import app.vitune.android.Database
import app.vitune.android.LocalPlayerServiceBinder
import app.vitune.android.R
import app.vitune.android.models.Format
import app.vitune.android.service.LOCAL_KEY_PREFIX
import app.vitune.android.service.PlayerService
import app.vitune.android.service.PrecacheService
import app.vitune.android.service.downloadState
import app.vitune.android.ui.components.themed.CircularProgressIndicator
import app.vitune.android.ui.components.themed.HeaderIconButton
import app.vitune.android.ui.components.themed.LinearProgressIndicator
import app.vitune.core.ui.LocalAppearance
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlin.time.Duration.Companion.seconds

@Composable
fun PlaylistDownloadIcon(
    songs: ImmutableList<MediaItem>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val (colorPalette) = LocalAppearance.current

    val isDownloading by downloadState.collectAsState()

    AnimatedContent(
        targetState = isDownloading,
        label = "",
        transitionSpec = { fadeIn() togetherWith fadeOut() }
    ) { currentIsDownloading ->
        when {
            currentIsDownloading -> CircularProgressIndicator(modifier = Modifier.size(18.dp))

            !songs.map { it.mediaId }.fastAll {
                isCached(
                    mediaId = it,
                    key = isDownloading
                )
            } -> HeaderIconButton(
                icon = R.drawable.download,
                color = colorPalette.text,
                onClick = {
                    songs.forEach {
                        PrecacheService.scheduleCache(context.applicationContext, it)
                    }
                },
                modifier = modifier
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun isCached(
    mediaId: String,
    key: Any? = Unit,
    binder: PlayerService.Binder? = LocalPlayerServiceBinder.current
): Boolean {
    if (mediaId.startsWith(LOCAL_KEY_PREFIX)) return true

    var format: Format? by remember { mutableStateOf(null) }

    LaunchedEffect(mediaId, key) {
        Database
            .format(mediaId)
            .distinctUntilChanged()
            .collect { format = it }
    }

    return remember(mediaId, binder, format, key) {
        format?.contentLength?.let { len ->
            binder?.cache?.isCached(mediaId, 0, len)
        } ?: false
    }
}

/**
 * Car build: download progress for a whole playlist, shown in the playlist
 * screen header. Counts how many of the songs are already downloaded
 * (cached) and shows "X of Y" with a progress bar. While a download is
 * running the cached state is re-checked every second so the progress moves
 * live. Local songs count as downloaded: they are already on the device.
 * Progress is song-based (songs finished / total) rather than byte-based:
 * the download service does not expose per-song byte progress to the UI.
 */
@Composable
fun PlaylistDownloadProgress(
    songs: ImmutableList<MediaItem>,
    modifier: Modifier = Modifier
) {
    val typography = LocalAppearance.current.typography
    val isDownloading by downloadState.collectAsState()

    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(isDownloading) {
        while (isDownloading) {
            delay(1.seconds)
            tick++
        }
    }

    val total = songs.size
    if (total == 0) return

    val cachedCount = songs.count { isCached(mediaId = it.mediaId, key = tick) }

    if (cachedCount == total && !isDownloading) return

    Column(modifier = modifier.fillMaxWidth()) {
        BasicText(
            text = if (isDownloading) stringResource(R.string.downloading_x_of_y, cachedCount, total)
            else stringResource(R.string.downloaded_x_of_y, cachedCount, total),
            style = typography.xs.secondary,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        LinearProgressIndicator(
            progress = cachedCount / total.toFloat(),
            animateProgress = false,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

/**
 * Car build: per-song download state icon for playlist rows. A check mark
 * when the song is downloaded, a spinner while a download is running and
 * this song is not downloaded yet, nothing otherwise.
 */
@Composable
fun DownloadStateIcon(
    mediaId: String,
    modifier: Modifier = Modifier
) {
    val (colorPalette) = LocalAppearance.current
    val isDownloading by downloadState.collectAsState()

    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(isDownloading) {
        while (isDownloading) {
            delay(1.seconds)
            tick++
        }
    }

    val cached = isCached(mediaId = mediaId, key = tick)
    when {
        cached -> Image(
            painter = painterResource(R.drawable.download_done),
            contentDescription = null,
            colorFilter = ColorFilter.tint(colorPalette.accent),
            modifier = modifier.size(18.dp)
        )

        isDownloading -> CircularProgressIndicator(modifier = modifier.size(18.dp))
    }
}

@OptIn(UnstableApi::class)
class ConditionalCacheDataSourceFactory(
    private val cacheDataSourceFactory: CacheDataSource.Factory,
    private val upstreamDataSourceFactory: DataSource.Factory,
    private val shouldCache: (DataSpec) -> Boolean
) : DataSource.Factory {
    init {
        cacheDataSourceFactory.setUpstreamDataSourceFactory(upstreamDataSourceFactory)
    }

    override fun createDataSource() = object : DataSource {
        private lateinit var selectedFactory: DataSource.Factory
        private val transferListeners = mutableListOf<TransferListener>()

        private fun createSource(factory: DataSource.Factory = selectedFactory) =
            factory.createDataSource().apply {
                transferListeners.forEach { addTransferListener(it) }
            }

        private val open = AtomicBoolean(false)
        private var source by object : ReadWriteProperty<Any?, DataSource> {
            var s: DataSource? = null

            override fun getValue(thisRef: Any?, property: KProperty<*>) = s ?: run {
                val newSource = runCatching {
                    createSource()
                }.getOrElse {
                    if (it is UninitializedPropertyAccessException) throw PlaybackException(
                        /* message = */ "Illegal access of data source methods before calling open()",
                        /* cause = */ it,
                        /* errorCode = */ PlaybackException.ERROR_CODE_UNSPECIFIED
                    ) else throw it
                }
                s = newSource
                newSource
            }

            override fun setValue(thisRef: Any?, property: KProperty<*>, value: DataSource) {
                s = value
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int) =
            source.read(buffer, offset, length)

        override fun addTransferListener(transferListener: TransferListener) {
            if (::selectedFactory.isInitialized) source.addTransferListener(transferListener)

            transferListeners += transferListener
        }

        override fun open(dataSpec: DataSpec): Long {
            selectedFactory =
                if (shouldCache(dataSpec)) cacheDataSourceFactory else upstreamDataSourceFactory

            return runCatching {
                // Source is still considered 'open' even when an error occurs. See DataSource::close
                open.set(true)
                source.open(dataSpec)
            }.getOrElse {
                if (it is ReadOnlyException) {
                    source = createSource(upstreamDataSourceFactory)
                    source.open(dataSpec)
                } else throw it
            }
        }

        override fun getUri() = if (open.get()) source.uri else null
        override fun close() = if (open.compareAndSet(true, false)) {
            source.close()
        } else Unit
    }
}
