package dfast.app.services

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dfast.app.util.VideoPlayerUtil

/** Background audio playback with a system media notification. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val p = VideoPlayerUtil.buildPlayer(this)
        p.setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(), true
        )
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // skip a broken track instead of leaving the notification stuck
                if (p.hasNextMediaItem()) { p.seekToNextMediaItem(); p.prepare(); p.play() }
                else android.widget.Toast.makeText(applicationContext, "Playback failed", android.widget.Toast.LENGTH_SHORT).show()
            }
        })
        player = p
        val open = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, dfast.app.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, p).setSessionActivity(open).build()
        // register the session with the service so the media card is posted without any controller attached
        session?.let { addSession(it) }
        setMediaNotificationProvider(
            androidx.media3.session.DefaultMediaNotificationProvider.Builder(this)
                .setChannelId("dfast_playback")
                .setChannelName(dfast.app.R.string.o_playback_channel)
                .build()
        )
        listeners.forEach { it() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val queue = intent?.getStringArrayListExtra("q_uris")
        if (queue != null && queue.isNotEmpty()) {
            val titles = intent.getStringArrayListExtra("q_titles") ?: arrayListOf()
            val authors = intent.getStringArrayListExtra("q_authors") ?: arrayListOf()
            val arts = intent.getStringArrayListExtra("q_arts") ?: arrayListOf()
            val items = queue.mapIndexed { i, u ->
                MediaItem.Builder().setUri(Uri.parse(u)).setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(titles.getOrNull(i).orEmpty())
                        .setArtist(authors.getOrNull(i).orEmpty())
                        .apply { arts.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { setArtworkUri(Uri.parse(it)) } }
                        .build()
                ).build()
            }
            player?.apply {
                shuffleModeEnabled = intent.getBooleanExtra("q_shuffle", false)
                setMediaItems(items, intent.getIntExtra("q_start", 0).coerceIn(0, items.size - 1), 0L)
                repeatMode = Player.REPEAT_MODE_ALL
                prepare()
                play()
            }
            return super.onStartCommand(intent, flags, startId)
        }
        val streams = intent?.getStringArrayListExtra("s_urls")
        if (streams != null && streams.isNotEmpty()) {
            val meta = MediaMetadata.Builder()
                .setTitle(intent.getStringExtra("title").orEmpty())
                .setArtist(intent.getStringExtra("author").orEmpty())
                .apply { intent.getStringExtra("art")?.takeIf { it.isNotBlank() }?.let { setArtworkUri(Uri.parse(it)) } }
                .build()
            val factory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dfast.app.util.VideoPlayerUtil.cacheFactory(this))
            val first = factory.createMediaSource(MediaItem.Builder().setUri(Uri.parse(streams[0])).setMediaMetadata(meta).build())
            player?.let { p ->
                val exo = p as androidx.media3.exoplayer.ExoPlayer
                p.shuffleModeEnabled = false
                p.repeatMode = Player.REPEAT_MODE_OFF
                if (streams.size >= 2) exo.setMediaSource(androidx.media3.exoplayer.source.MergingMediaSource(first, factory.createMediaSource(MediaItem.fromUri(Uri.parse(streams[1])))))
                else exo.setMediaSource(first)
                exo.prepare(); exo.play()
            }
            return super.onStartCommand(intent, flags, startId)
        }
        val uri = intent?.getStringExtra("uri")
        if (uri != null) {
            val title = intent.getStringExtra("title") ?: ""
            val art = intent.getStringExtra("art")
            player?.apply {
                shuffleModeEnabled = false
                repeatMode = Player.REPEAT_MODE_OFF
                setMediaItem(
                    MediaItem.Builder().setUri(Uri.parse(uri))
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).apply { if (!art.isNullOrBlank()) setArtworkUri(Uri.parse(art)) }.build()).build()
                )
                prepare()
                play()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (player?.playWhenReady != true) stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        player?.release()
        session = null
        player = null
        listeners.forEach { it() }
        super.onDestroy()
    }

    companion object {
        @Volatile var player: Player? = null
        val listeners = mutableListOf<() -> Unit>()

        fun play(context: Context, uri: String, title: String, art: String? = null) {
            val i = Intent(context, PlaybackService::class.java).putExtra("uri", uri).putExtra("title", title).putExtra("art", art)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        data class QueueItem(val uri: String, val title: String, val author: String, val art: String)

        fun playQueue(context: Context, items: List<QueueItem>, start: Int = 0, shuffle: Boolean = false) {
            if (items.isEmpty()) return
            val i = Intent(context, PlaybackService::class.java)
                .putStringArrayListExtra("q_uris", ArrayList(items.map { it.uri }))
                .putStringArrayListExtra("q_titles", ArrayList(items.map { it.title }))
                .putStringArrayListExtra("q_authors", ArrayList(items.map { it.author }))
                .putStringArrayListExtra("q_arts", ArrayList(items.map { it.art }))
                .putExtra("q_start", start).putExtra("q_shuffle", shuffle)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        fun playStream(context: Context, urls: List<String>, title: String, author: String, art: String) {
            if (urls.isEmpty()) return
            val i = Intent(context, PlaybackService::class.java)
                .putStringArrayListExtra("s_urls", ArrayList(urls)).putExtra("title", title).putExtra("author", author).putExtra("art", art)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            player?.stop()
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
