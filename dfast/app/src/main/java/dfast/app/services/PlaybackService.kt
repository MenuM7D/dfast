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
                if (dfast.app.util.PlayQueue.isPending(p.currentMediaItem)) return
                if (p.hasNextMediaItem()) { p.seekToNextMediaItem(); p.prepare(); p.play() }
                else android.widget.Toast.makeText(applicationContext, "Playback failed", android.widget.Toast.LENGTH_SHORT).show()
            }
        })
        player = p
        dfast.app.util.PlayQueue.attach(applicationContext, p)
        val open = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, dfast.app.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        val cbk = object : MediaSession.Callback {
            override fun onConnect(s: MediaSession, c: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                val cmds = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(CMD_SHUFFLE).add(CMD_REPEAT).build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(s).setAvailableSessionCommands(cmds).build()
            }
            override fun onCustomCommand(s: MediaSession, c: MediaSession.ControllerInfo, cmd: androidx.media3.session.SessionCommand, args: android.os.Bundle): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult> {
                when (cmd.customAction) {
                    "dfast_shuffle" -> p.shuffleModeEnabled = !p.shuffleModeEnabled
                    "dfast_repeat" -> p.repeatMode = when (p.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
                }
                return com.google.common.util.concurrent.Futures.immediateFuture(androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS))
            }
        }
        session = MediaSession.Builder(this, p).setSessionActivity(open).setCallback(cbk).build()
        fun refreshButtons() {
            val sh = androidx.media3.session.CommandButton.Builder(if (p.shuffleModeEnabled) androidx.media3.session.CommandButton.ICON_SHUFFLE_ON else androidx.media3.session.CommandButton.ICON_SHUFFLE_OFF)
                .setSessionCommand(CMD_SHUFFLE).setDisplayName("Shuffle").build()
            val rp = androidx.media3.session.CommandButton.Builder(when (p.repeatMode) {
                Player.REPEAT_MODE_ONE -> androidx.media3.session.CommandButton.ICON_REPEAT_ONE
                Player.REPEAT_MODE_ALL -> androidx.media3.session.CommandButton.ICON_REPEAT_ALL
                else -> androidx.media3.session.CommandButton.ICON_REPEAT_OFF })
                .setSessionCommand(CMD_REPEAT).setDisplayName("Repeat").build()
            session?.setCustomLayout(com.google.common.collect.ImmutableList.of(sh, rp))
        }
        // Safeguard: if playback runs but only our placeholder notification is showing (no controls), force the media card.
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) return
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    runCatching {
                        val nm = getSystemService(android.app.NotificationManager::class.java)
                        val n = nm.activeNotifications.firstOrNull { it.id == 1001 }?.notification
                        if (n == null || n.actions == null || n.actions.isEmpty()) session?.let { onUpdateNotification(it, true) }
                    }
                }, 1500)
            }
        })
        p.addListener(object : Player.Listener {
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { refreshButtons() }
            override fun onRepeatModeChanged(repeatMode: Int) { refreshButtons() }
        })
        refreshButtons()
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

    /** Android requires startForeground() within seconds of startForegroundService(). Media3 only does it once playback starts, so we post a placeholder at once. */
    private fun ensureForeground() {
        runCatching {
            val nm = androidx.core.app.NotificationManagerCompat.from(this)
            nm.createNotificationChannel(
                androidx.core.app.NotificationChannelCompat.Builder("dfast_playback", androidx.core.app.NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(getString(dfast.app.R.string.o_playback_channel)).build()
            )
            val n = androidx.core.app.NotificationCompat.Builder(this, "dfast_playback")
                .setSmallIcon(dfast.app.R.drawable.ic_notifications)
                .setContentTitle(getString(dfast.app.R.string.app_name))
                .setOngoing(true).setSilent(true).build()
            androidx.core.app.ServiceCompat.startForeground(this, 1001, n,
                if (android.os.Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        }
        // if nothing starts playing (error, no focus), do not leave the service hanging
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val p = player
            if (p == null || p.playbackState == Player.STATE_IDLE) {
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }, 20000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
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
        private val CMD_SHUFFLE = androidx.media3.session.SessionCommand("dfast_shuffle", android.os.Bundle.EMPTY)
        private val CMD_REPEAT = androidx.media3.session.SessionCommand("dfast_repeat", android.os.Bundle.EMPTY)
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
