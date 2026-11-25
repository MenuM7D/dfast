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
        player = p
        session = MediaSession.Builder(this, p).build()
        listeners.forEach { it() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uri = intent?.getStringExtra("uri")
        if (uri != null) {
            val title = intent.getStringExtra("title") ?: ""
            val art = intent.getStringExtra("art")
            player?.apply {
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

        fun stop(context: Context) {
            player?.stop()
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
