package dfast.app.util

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlin.math.max

object VideoPlayerUtil {
    private const val MINIMUM_BUFFER_DURATION = 1000 * 5 //exo default is 50s

    private var cache: androidx.media3.datasource.cache.SimpleCache? = null

    /** Disk cache (200 MB) so seeking back and replays do not hit the network again. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun cacheFactory(context: Context): androidx.media3.datasource.DataSource.Factory {
        val up = DefaultDataSource.Factory(context)
        val c = cache ?: kotlin.runCatching {
            androidx.media3.datasource.cache.SimpleCache(java.io.File(context.cacheDir, "player_cache"),
                androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(200L * 1024 * 1024), androidx.media3.database.StandaloneDatabaseProvider(context))
        }.getOrNull()?.also { cache = it } ?: return up
        return androidx.media3.datasource.cache.CacheDataSource.Factory().setCache(c).setUpstreamDataSourceFactory(up)
            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun buildPlayer(context: Context) : ExoPlayer {
        val dataSourceFactory = cacheFactory(context)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val loadControl = DefaultLoadControl.Builder()
            // cache the last three minutes
            .setBackBuffer(1000 * 60 * 3, true)
            .setBufferDurationsMs(
                15_000,
                60_000,
                1_500,
                3_000
            )
            .build()

        val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
            override fun buildAudioSink(ctx: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink? =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(ctx)
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(androidx.media3.exoplayer.audio.TeeAudioProcessor(AudioTap)))
                    .build()
        }
        return ExoPlayer.Builder(context, renderers)
            .setUsePlatformDiagnostics(false)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setTrackSelector(DefaultTrackSelector(context).apply { setParameters(buildUponParameters().setMaxVideoSize(1280, 720).setPreferredVideoMimeType("video/avc")) })
            .setHandleAudioBecomingNoisy(true)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, false)
            .build()
            .apply {
                skipSilenceEnabled = false
                playbackParameters = PlaybackParameters(1f, 1.0f)
            }
    }
}