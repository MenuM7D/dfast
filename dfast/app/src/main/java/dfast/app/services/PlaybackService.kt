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
                if (dfast.app.util.PlayQueue.willRecover(p.currentMediaItem)) return
                if (p.hasNextMediaItem()) { p.seekToNextMediaItem(); p.prepare(); p.play() }
                else android.widget.Toast.makeText(applicationContext, "Playback failed", android.widget.Toast.LENGTH_SHORT).show()
            }
        })
        player = p
        dfast.app.util.PlayQueue.stateListeners.add(cardRefresh)
        dfast.app.util.PlayQueue.attach(applicationContext, p)
        dfast.app.util.QueueStore.track(applicationContext, p)
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
        // Own media notification (see postCard). Media3's built-in notification manager is switched off below,
        // so one code path owns notification id 1001 and nothing can overwrite it with the placeholder.
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { postCard() }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { postCard() }
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) { postCard() }
            override fun onPlaybackStateChanged(playbackState: Int) { postCard() }
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

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        // intentionally empty: postCard() builds the notification
    }

    private var artUri: String? = null
    private var artBmp: android.graphics.Bitmap? = null

    private fun action(icon: Int, label: String, act: String, code: Int) = androidx.core.app.NotificationCompat.Action.Builder(
        icon, label, android.app.PendingIntent.getService(this, code, Intent(this, PlaybackService::class.java).setAction(act),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)).build()

    /** Posts the media notification (title, artist, artwork, previous / play-pause / next) from the player's own state. */
    private fun postCard() {
        runCatching {
            val p = player ?: return
            val s = session ?: return
            if (p.mediaItemCount == 0) return
            val md = p.mediaMetadata
            val art = md.artworkUri?.toString()
            if (art.isNullOrBlank() && artUri != null) { artUri = null; artBmp = null } // no stale picture from the previous song
            if (!art.isNullOrBlank() && art != artUri) {
                artUri = art; artBmp = null
                Thread { runCatching { com.squareup.picasso.Picasso.get().load(art).resize(256, 256).centerCrop().get() }.getOrNull()?.let { bmp ->
                    android.os.Handler(android.os.Looper.getMainLooper()).post { if (artUri == art) { artBmp = bmp; postCard() } } } }.start()
            }
            val open = android.app.PendingIntent.getActivity(this, 0, Intent(this, dfast.app.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            val waiting = dfast.app.util.PlayQueue.starting && !p.isPlaying
            val playing = p.isPlaying || waiting || p.playWhenReady && p.playbackState != Player.STATE_ENDED && p.playbackState != Player.STATE_IDLE
            val b = androidx.core.app.NotificationCompat.Builder(this, "dfast_playback")
                .setSmallIcon(dfast.app.R.drawable.ic_stat_music)
                .setContentTitle(md.title?.takeIf { it.isNotBlank() } ?: getString(dfast.app.R.string.app_name))
                .setContentText(md.artist)
                .setContentIntent(open).setOnlyAlertOnce(true).setSilent(true).setShowWhen(false)
                .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(playing)
                .addAction(action(dfast.app.R.drawable.ic_mini_prev, "Previous", "dfast.prev", 1))
                .addAction(if (playing) action(dfast.app.R.drawable.ic_mini_pause, "Pause", "dfast.toggle", 2) else action(dfast.app.R.drawable.ic_mini_play, "Play", "dfast.toggle", 2))
                .addAction(action(dfast.app.R.drawable.ic_mini_next, "Next", "dfast.next", 3))
                .setStyle(androidx.media3.session.MediaStyleNotificationHelper.MediaStyle(s).setShowActionsInCompactView(0, 1, 2))
            artBmp?.let { b.setLargeIcon(it) }
            if (waiting) b.setProgress(0, 0, true) // loading: indeterminate bar on the card until sound starts
            val n = b.build()
            if (playing) {
                androidx.core.app.ServiceCompat.startForeground(this, 1001, n,
                    if (android.os.Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
            } else {
                // paused: keep the card but let the user swipe it away
                stopForeground(STOP_FOREGROUND_DETACH)
                androidx.core.app.NotificationManagerCompat.from(this).notify(1001, n)
            }
        }
    }

    /** Android requires startForeground() within seconds of startForegroundService(). Media3 only does it once playback starts, so we post a placeholder at once. */
    private fun ensureForeground() {
        if ((player?.mediaItemCount ?: 0) > 0) { postCard() }
        else runCatching {
            val nm = androidx.core.app.NotificationManagerCompat.from(this)
            nm.createNotificationChannel(
                androidx.core.app.NotificationChannelCompat.Builder("dfast_playback", androidx.core.app.NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(getString(dfast.app.R.string.o_playback_channel)).build()
            )
            val n = androidx.core.app.NotificationCompat.Builder(this, "dfast_playback")
                .setSmallIcon(dfast.app.R.drawable.ic_stat_music)
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
        when (intent?.action) {
            "dfast.prev" -> { player?.let { dfast.app.util.PlayQueue.previous(it) }; postCard(); return START_NOT_STICKY }
            "dfast.next" -> { player?.seekToNextMediaItem(); postCard(); return START_NOT_STICKY }
            "dfast.toggle" -> { player?.let { dfast.app.util.PlayQueue.togglePlay(it) }; postCard(); return START_NOT_STICKY }
            "dfast.restore" -> {
                // put the saved queue back, paused. Nothing is prepared or resolved until the user presses play.
                val p = player
                val saved = if (p != null && p.mediaItemCount == 0) dfast.app.util.QueueStore.load(applicationContext) else null
                if (p != null && saved != null) {
                    dfast.app.util.PlayQueue.markRestored(saved.items[saved.index].mediaId, saved.positionMs)
                    p.shuffleModeEnabled = saved.shuffle
                    p.repeatMode = saved.repeat
                    p.playWhenReady = false
                    p.setMediaItems(saved.items, saved.index, saved.positionMs)
                    dfast.app.util.PlayQueue.rotateToFront()
                    postCard()
                    dfast.app.util.PlayQueue.warmCurrent(applicationContext)
                } else if (p == null || p.mediaItemCount == 0) stopSelf()
                return START_NOT_STICKY
            }
        }
        ensureForeground()
        dfast.app.util.PlayQueue.suspended = false
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
            val cur = player
            if (items.size == 1 && cur != null && cur.mediaItemCount >= 2 && !intent.getBooleanExtra("q_shuffle", false)) {
                // opening one local song while a queue exists: it joins the queue as the current song, the rest stays
                val u = items[0].localConfiguration?.uri
                var at = (0 until cur.mediaItemCount).firstOrNull { cur.getMediaItemAt(it).localConfiguration?.uri == u } ?: -1
                if (at >= 0) cur.removeMediaItem(at) else at = (cur.currentMediaItemIndex + 1).coerceIn(0, cur.mediaItemCount)
                cur.addMediaItem(at, items[0]); cur.seekTo(at, 0L); cur.prepare(); cur.play()
                return super.onStartCommand(intent, flags, startId)
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
            val first = factory.createMediaSource(MediaItem.Builder().setMediaId(intent.getStringExtra("page").orEmpty().ifBlank { streams[0] }).setUri(Uri.parse(streams[0])).setMediaMetadata(meta).build())
            player?.let { p ->
                val exo = p as androidx.media3.exoplayer.ExoPlayer
                val src = if (streams.size >= 2) androidx.media3.exoplayer.source.MergingMediaSource(first, factory.createMediaSource(MediaItem.fromUri(Uri.parse(streams[1])))) else first
                val page = intent.getStringExtra("page").orEmpty()
                val prevViewer = lastViewer
                if (intent.getBooleanExtra("viewer", false)) lastViewer = page
                if (p.mediaItemCount >= 2 || (p.mediaItemCount == 1 && p.currentMediaItem?.mediaId.orEmpty().let { it.isNotBlank() && it != prevViewer && it != page })) {
                    // a queue (or a single song somebody queued, not a video the player screen opened) exists: the opened video/song joins it as the current item and the rest stays (shuffle/repeat untouched)
                    var at = if (page.isBlank()) -1 else (0 until p.mediaItemCount).firstOrNull { p.getMediaItemAt(it).mediaId == page } ?: -1
                    if (at >= 0) exo.removeMediaItem(at) else at = (p.currentMediaItemIndex + 1).coerceIn(0, p.mediaItemCount)
                    exo.addMediaSource(at, src); exo.seekTo(at, 0L); exo.prepare(); exo.play()
                    return super.onStartCommand(intent, flags, startId)
                }
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
        player?.let { dfast.app.util.QueueStore.saveNow(applicationContext, it) }
        if (player?.playWhenReady != true) stopSelf()
    }

    private val cardRefresh: () -> Unit = { postCard() }

    override fun onDestroy() {
        dfast.app.util.PlayQueue.stateListeners.remove(cardRefresh)
        // keep the queue for the next launch (the close button clears it before stopping the service)
        player?.let { dfast.app.util.QueueStore.saveNow(applicationContext, it) }
        dfast.app.util.QueueStore.untrack()
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

        /** The song the player screen itself opened last. Alone in the player it is replaced by the next one; next to a real queue it is kept. */
        @Volatile var lastViewer: String = ""

        fun playStream(context: Context, urls: List<String>, title: String, author: String, art: String, page: String = "", viewer: Boolean = false) {
            if (urls.isEmpty()) return
            val i = Intent(context, PlaybackService::class.java)
                .putStringArrayListExtra("s_urls", ArrayList(urls)).putExtra("viewer", viewer).putExtra("title", title).putExtra("author", author).putExtra("art", art).putExtra("page", page)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        /** Starts the service just to put the saved queue back (paused). Does nothing when there is no saved queue or a player already exists. */
        fun restore(context: Context) {
            if (player != null || !dfast.app.util.QueueStore.hasSaved(context)) return
            runCatching { context.startService(Intent(context, PlaybackService::class.java).setAction("dfast.restore")) }
        }

        /** The close (x) button: stops playback AND forgets the saved queue. */
        fun stop(context: Context) {
            player?.clearMediaItems()
            dfast.app.util.QueueStore.clear(context)
            player?.stop()
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
