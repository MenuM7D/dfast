package dfast.app.ui.play

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.PlayerView
import dfast.app.R
import dfast.app.database.DBManager
import dfast.app.database.enums.DownloadType
import dfast.app.database.models.ResultItem
import dfast.app.database.viewmodel.DownloadViewModel
import dfast.app.database.viewmodel.ResultViewModel
import dfast.app.services.PlaybackService
import dfast.app.util.Extensions.isYoutubeURL
import dfast.app.util.Extensions.loadThumbnail
import dfast.app.util.MediaRef
import dfast.app.util.OrbitColors
import dfast.app.util.PlayStore
import dfast.app.util.StartupTrace
import dfast.app.util.StreamResolver
import dfast.app.util.ThemeUtil
import dfast.app.util.VideoPlayerUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Player screen: video on top, actions under it, related items below. Local files play through the background service. */
class PlayerActivity : AppCompatActivity() {
    private lateinit var ref: MediaRef
    private var resultItem: ResultItem? = null
    private var ownPlayer: androidx.media3.exoplayer.ExoPlayer? = null
    private var queueRefs: List<MediaRef> = emptyList()
    private lateinit var playerView: PlayerView
    private lateinit var disc: DiscView
    private lateinit var favBtn: TextView
    private lateinit var offBtn: TextView
    private var localUri: String? = null
    /** true only when the app's own Saved-offline copy exists; a normal download keeps playing locally but is not "saved offline" */
    private var offlineSaved = false
    private val handler = Handler(Looper.getMainLooper())
    private var audioOnly = false
    private var shuffleOn = false
    private var repeatIdx = 0
    private var relatedPool: List<MediaRef> = emptyList()
    private val endListener = object : androidx.media3.common.Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == androidx.media3.common.Player.STATE_ENDED && repeatIdx == 0 &&
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(this@PlayerActivity).getBoolean("autoplay_next", true)) goNext()
        }
    }
    private var endAttached: androidx.media3.common.Player? = null

    // --- the screen shows what the player is REALLY playing ---
    private lateinit var titleTv: TextView
    private lateinit var metaTv: TextView
    private lateinit var spinner: android.widget.ProgressBar
    /** the page url this screen was opened for; the player item with this id (or the local uri) is "ours" */
    private var openedUrl = ""
    private var expectedLocalUri: String? = null
    /** false until the player is really playing the item we asked for; until then we show the loading state, never the old song */
    private var following = false
    private var loadFailed = false
    private var shownArt: String? = null
    private var followOn: androidx.media3.common.Player? = null

    private fun isMine(item: androidx.media3.common.MediaItem?): Boolean {
        if (item == null) return false
        val local = expectedLocalUri
        return if (local != null) item.localConfiguration?.uri?.toString() == local else item.mediaId == openedUrl
    }

    private val followListener = object : androidx.media3.common.Player.Listener {
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) { syncFromPlayer() }
        override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) { syncFromPlayer() }
        override fun onPlaybackStateChanged(state: Int) {
            if (StartupTrace.active()) { if (state == androidx.media3.common.Player.STATE_BUFFERING) StartupTrace.mark("buffering") else if (state == androidx.media3.common.Player.STATE_READY) StartupTrace.mark("ready") }
            updateSpinner()
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) StartupTrace.end("playing"); updateSpinner() }
    }

    // --- health: a picture that never comes, a stream that errors, a player that never binds ---
    private lateinit var failBox: LinearLayout
    private var skipLocal = false          // a saved file failed or has no picture: use the stream
    private var forceYtdlp = false         // NewPipe stream had no usable picture: use yt-dlp (h264 pick)
    private var healthOn: androidx.media3.common.Player? = null
    private var seekOnReady = 0L
    private var pictureFixed = HashSet<String>()
    private val watchdog = Runnable {
        val p = PlaybackService.player
        if (p != null && isMine(p.currentMediaItem) && !p.isPlaying && p.playWhenReady && p.playbackState != androidx.media3.common.Player.STATE_ENDED) showFailure()
    }
    private val healthListener = object : androidx.media3.common.Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val p = PlaybackService.player ?: return
            if (!isMine(p.currentMediaItem)) return
            if (expectedLocalUri != null && !skipLocal) { // the saved file does not play: stream it instead
                skipLocal = true; expectedLocalUri = null
                lifecycleScope.launch { startPlayback() }
                return
            }
            // for streams the queue already retries once with a fresh link; if that fails too, say so
            handler.removeCallbacks(watchdog); handler.postDelayed(watchdog, 12_000)
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) { handler.removeCallbacks(watchdog); hideFailure() } }
        override fun onPlaybackStateChanged(state: Int) {
            val p = PlaybackService.player ?: return
            if (state == androidx.media3.common.Player.STATE_READY && seekOnReady > 0 && isMine(p.currentMediaItem)) { p.seekTo(seekOnReady); seekOnReady = 0 }
        }
        override fun onRenderedFirstFrame() { frameRendered = true }
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) { frameRendered = false; handler.removeCallbacks(pictureCheck) }
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            if (audioOnly) return
            handler.removeCallbacks(pictureCheck); handler.postDelayed(pictureCheck, 2500)
        }
    }

    private var frameRendered = false
    private val pictureCheck = Runnable { checkPicture() }
    private var repaint: () -> Unit = {}

    /** No picture available for what is playing: say so by showing the audio view instead of a black rectangle. */
    private fun noPicture() { runOnUiThread { if (!audioOnly && !isDestroyed) { audioOnly = true; repaint() } } }

    /**
     * Video mode, sound present, but no video track this phone can show (audio-only file or queue item, or a codec without a decoder).
     * Works for the song we opened AND for any item the queue moved on to. A YouTube item (or a saved file with a page link) is
     * re-fetched once as video+audio (h264 preferred); if that is impossible or still has no picture, the screen switches to the audio view.
     */
    private fun checkPicture() {
        val p = PlaybackService.player ?: return
        if (audioOnly || isDestroyed || playerView.player !== p) return
        val item = p.currentMediaItem ?: return
        val tr = p.currentTracks
        if (!tr.groups.any { it.type == C.TRACK_TYPE_AUDIO } || tr.isTypeSupported(C.TRACK_TYPE_VIDEO) || frameRendered) return
        val mine = isMine(item)
        val key = if (mine) openedUrl else item.mediaId
        val canFix = key.startsWith("http") && (key.isYoutubeURL() || (mine && expectedLocalUri != null))
        if (!canFix || !pictureFixed.add(key)) { noPicture(); return }
        val pos = p.currentPosition
        val md = item.mediaMetadata
        lifecycleScope.launch {
            StreamResolver.forget(key)
            var urls = withContext(Dispatchers.IO) { StreamResolver.resolve(this@PlayerActivity, key, false, false, skipNewPipe = true) }
            if (urls.isEmpty()) urls = withContext(Dispatchers.IO) { StreamResolver.resolve(this@PlayerActivity, key, false, false) }
            if (isDestroyed) return@launch
            if (urls.isEmpty()) { noPicture(); return@launch }
            if (mine) { expectedLocalUri = null; skipLocal = true; forceYtdlp = true; seekOnReady = pos }
            PlaybackService.playStream(this@PlayerActivity, urls, md.title?.toString() ?: ref.title, md.artist?.toString() ?: ref.author, md.artworkUri?.toString() ?: ref.thumb, key, viewer = true)
        }
    }

    private fun watchHealth(p: androidx.media3.common.Player) {
        if (healthOn !== p) { healthOn?.removeListener(healthListener); p.addListener(healthListener); healthOn = p }
        handler.removeCallbacks(watchdog); handler.postDelayed(watchdog, 25_000)
    }

    private fun showFailure() {
        if (!::failBox.isInitialized || isFinishing || isDestroyed) return
        loadFailed = true; updateSpinner()
        failBox.visibility = View.VISIBLE
    }

    private fun hideFailure() { if (::failBox.isInitialized) failBox.visibility = View.GONE }

    /** The player never bound to this song within 10 s: still give the controls the real player, and say it failed. */
    private fun onAttachTimeout() {
        PlaybackService.player?.let { p -> playerView.player = p; watchVideo(p); watchHealth(p) }
        showFailure()
    }

    /** Retry: forget every cached link for this video and start over from a fresh lookup, streaming (not the saved file). */
    private fun retryPlayback() {
        hideFailure(); loadFailed = false; following = false
        StreamResolver.forget(openedUrl)
        skipLocal = true; forceYtdlp = true; pictureFixed.remove(openedUrl)
        updateSpinner()
        lifecycleScope.launch { startPlayback() }
    }

    private fun updateSpinner() {
        val p = PlaybackService.player
        val busy = !loadFailed && (!following || (p != null && p.playbackState == androidx.media3.common.Player.STATE_BUFFERING))
        if (::spinner.isInitialized) spinner.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun placeSpinner() {
        if (!::spinner.isInitialized) return
        val h = resources.displayMetrics.widthPixels * 9 / 16
        val frameH = if (audioOnly) (resources.displayMetrics.widthPixels * 1.2f).toInt() else h
        val cy = if (audioOnly) frameH * 0.26f else frameH / 2f
        (spinner.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin = (cy - dp(22)).toInt()
        spinner.requestLayout()
    }

    /** Title, artist, disc photo and the fav / download target follow the item the player is on (queue advance, notification next). */
    private fun syncFromPlayer() {
        val p = PlaybackService.player ?: return
        val item = p.currentMediaItem ?: return
        if (!following) {
            if (!isMine(item)) return
            following = true
        }
        val md = item.mediaMetadata
        val title = md.title?.toString().orEmpty()
        val artist = md.artist?.toString().orEmpty()
        val art = md.artworkUri?.toString().orEmpty()
        if (title.isNotBlank()) titleTv.text = title
        metaTv.text = artist
        if (art != shownArt) { shownArt = art; disc.setThumb(art) }
        if (item.mediaId.startsWith("http") && item.mediaId != ref.url) {
            ref = ref.copy(url = item.mediaId, title = title.ifBlank { ref.title }, author = artist, thumb = art, duration = "")
            recreateFavIcon(PlayStore.isFavorite(this, ref.url))
        }
        updateSpinner()
    }

    private fun attachFollow(p: androidx.media3.common.Player) {
        if (followOn !== p) { followOn?.removeListener(followListener); p.addListener(followListener); followOn = p }
        syncFromPlayer()
        updateSpinner()
    }

    private fun nextRef(): MediaRef? {
        val pool = (queueRefs.filter { it.url != ref.url }.takeIf { it.isNotEmpty() } ?: relatedPool).filter { it.url != ref.url }
        if (pool.isEmpty()) return null
        if (shuffleOn) return pool.random()
        val idx = queueRefs.indexOfFirst { it.url == ref.url }
        return if (idx >= 0 && idx + 1 < queueRefs.size) queueRefs[idx + 1] else pool.first()
    }

    private fun goNext() {
        val n = nextRef() ?: return
        history.addLast(ref)
        startActivity(intent(this, n.copy(audio = audioOnly), queueRefs))
        finish()
    }

    private fun goPrev() {
        val p = activePlayer
        if (p != null && p.currentPosition > 3000 || history.isEmpty()) { p?.seekTo(0); return }
        val r = history.removeLast()
        startActivity(intent(this, r, queueRefs))
        finish()
    }
    private var speedIdx = 0

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private val activePlayer: Player? get() = ownPlayer ?: PlaybackService.player

    companion object {
        val history = ArrayDeque<MediaRef>()
        fun intent(c: android.content.Context, ref: MediaRef, queue: List<MediaRef> = emptyList(), result: ResultItem? = null): Intent =
            Intent(c, PlayerActivity::class.java).putExtra("url", ref.url).putExtra("title", ref.title).putExtra("author", ref.author)
                .putExtra("thumb", ref.thumb).putExtra("duration", ref.duration).putExtra("audio", ref.audio)
                .putExtra("queue", org.json.JSONArray().also { a -> queue.forEach { a.put(it.toJson()) } }.toString())
                .apply { if (result != null) putExtra("result", result) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtil.updateTheme(this)
        super.onCreate(savedInstanceState)
        val i = intent
        ref = MediaRef(i.getStringExtra("url").orEmpty(), i.getStringExtra("title").orEmpty(), i.getStringExtra("author").orEmpty(),
            i.getStringExtra("thumb").orEmpty(), i.getStringExtra("duration").orEmpty(), i.getBooleanExtra("audio", false))
        audioOnly = ref.audio
        openedUrl = ref.url
        resultItem = if (android.os.Build.VERSION.SDK_INT >= 33) i.getParcelableExtra("result", ResultItem::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra("result")
        queueRefs = try { org.json.JSONArray(i.getStringExtra("queue") ?: "[]").let { a -> (0 until a.length()).map { MediaRef.fromJson(a.getJSONObject(it)) } } } catch (e: Exception) { emptyList() }
        PlayStore.addRecent(this, ref)
        // The media card needs notification permission. Ask every time playback opens while it is missing;
        // if Android no longer shows the dialog (denied twice), open the app's notification settings once.
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val sp = getSharedPreferences("orbit_player", MODE_PRIVATE)
            registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
                if (!granted && !shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS) && !sp.getBoolean("notif_settings_shown", false)) {
                    sp.edit().putBoolean("notif_settings_shown", true).apply()
                    android.widget.Toast.makeText(this, getString(R.string.o_notif_needed), android.widget.Toast.LENGTH_LONG).show()
                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)) }
                }
            }.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.bg_orbit) }
        val frame = android.widget.FrameLayout(this)
        val h = resources.displayMetrics.widthPixels * 9 / 16
        playerView = PlayerView(this).apply { setBackgroundColor(Color.BLACK); setShowNextButton(queueRefs.size > 1); setShowPreviousButton(queueRefs.size > 1) }
        frame.addView(playerView, android.widget.FrameLayout.LayoutParams(-1, h))
        // visible failure state with retry, instead of a silent black rectangle
        failBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE; setBackgroundColor(0xE6000000.toInt()); setPadding(dp(20), dp(10), dp(20), dp(10))
            elevation = 6 * resources.displayMetrics.density; isClickable = true
            addView(TextView(this@PlayerActivity).apply { text = getString(R.string.o_load_fail); setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER })
            addView(TextView(this@PlayerActivity).apply {
                text = getString(R.string.retry); textSize = 14f; setTextColor(0xFF14141C.toInt()); gravity = Gravity.CENTER
                setPadding(dp(22), dp(9), dp(22), dp(9)); background = GradientDrawable().apply { cornerRadius = 1000f; setColor(Color.WHITE) }
                setOnClickListener { retryPlayback() }
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        }
        frame.addView(failBox, android.widget.FrameLayout.LayoutParams(-1, h))
        disc = DiscView(this).apply { visibility = View.GONE; playerProvider = { activePlayer }; setThumb(ref.thumb); shownArt = ref.thumb; accent = 0xFF9D86FF.toInt()
            onNext = { goNext() }; onPrev = { goPrev() }
            onShuffle = { this@PlayerActivity.shuffleOn = !this@PlayerActivity.shuffleOn; shuffleOn = this@PlayerActivity.shuffleOn }
            onRepeat = { this@PlayerActivity.repeatIdx = (this@PlayerActivity.repeatIdx + 1) % 3; repeatMode = this@PlayerActivity.repeatIdx
                activePlayer?.repeatMode = when (this@PlayerActivity.repeatIdx) { 1 -> androidx.media3.common.Player.REPEAT_MODE_ALL; 2 -> androidx.media3.common.Player.REPEAT_MODE_ONE; else -> androidx.media3.common.Player.REPEAT_MODE_OFF } }
        }
        frame.addView(disc, android.widget.FrameLayout.LayoutParams(-1, h))
        spinner = android.widget.ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
        }
        frame.addView(spinner, android.widget.FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        frame.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_pl_back); setColorFilter(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x66000000) }
            contentDescription = getString(R.string.o_back); setOnClickListener { finish() }; dfast.app.util.Press.icon(this)
        }, android.widget.FrameLayout.LayoutParams(dp(40), dp(40)).apply { setMargins(dp(10), dp(10), 0, 0) })
        root.addView(frame, LinearLayout.LayoutParams(-1, -2))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(24)) }
        titleTv = TextView(this).apply {
            text = ref.title; textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(OrbitColors.ink(this@PlayerActivity)); maxLines = 3
            setPadding(dp(16), dp(14), dp(16), 0)
        }
        col.addView(titleTv)
        metaTv = TextView(this).apply {
            text = listOf(ref.author, ref.duration.takeIf { it.isNotBlank() && it != "-1" }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
            textSize = 12f; setTextColor(OrbitColors.inkSoft(this@PlayerActivity)); setPadding(dp(16), dp(2), dp(16), dp(6))
        }
        col.addView(metaTv)

        val actions = LinearLayout(this).apply { setPadding(dp(8), dp(8), dp(8), dp(4)) }
        fun action(icon: Int, label: String, filled: Boolean, onClick: () -> Unit): TextView {
            val tv = TextView(this).apply {
                text = label; textSize = 10f; gravity = Gravity.CENTER
                setTextColor(OrbitColors.ink(this@PlayerActivity)); compoundDrawablePadding = dp(4)
                setPadding(0, dp(4), 0, dp(4))
                val d = androidx.core.content.ContextCompat.getDrawable(this@PlayerActivity, icon)!!.mutate()
                d.setBounds(0, 0, dp(22), dp(22))
                d.setTint(if (filled) OrbitColors.pillText(this@PlayerActivity) else OrbitColors.ink(this@PlayerActivity))
                val circle = android.graphics.drawable.LayerDrawable(arrayOf(
                    GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (filled) OrbitColors.pill(this@PlayerActivity) else OrbitColors.glass2(this@PlayerActivity)); setSize(dp(46), dp(46)) },
                    d))
                circle.setLayerInset(1, dp(12), dp(12), dp(12), dp(12))
                circle.setBounds(0, 0, dp(46), dp(46))
                setCompoundDrawables(null, circle, null, null)
                setOnClickListener { onClick() }
            }
            actions.addView(tv, LinearLayout.LayoutParams(0, -2, 1f))
            return tv
        }
        action(R.drawable.ic_pl_download, getString(R.string.o_download), true) { chooseDownload() }
        favBtn = action(if (PlayStore.isFavorite(this, ref.url)) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline, getString(R.string.o_favorite), false) { toggleFav() }
        offBtn = action(R.drawable.ic_pl_offline, getString(R.string.o_save_offline), false) { saveOffline() }
        action(R.drawable.ic_pl_playlist, getString(R.string.o_playlist), false) { addToPlaylist() }
        col.addView(actions)

        val chips = LinearLayout(this).apply { setPadding(dp(14), dp(6), dp(14), dp(4)) }
        val videoChip = chip(getString(R.string.o_video), true); val audioChip = chip(getString(R.string.o_audio_only), false); val speedChip = chip("1x", false)
        fun paintChips() {
            for ((c, on) in listOf(videoChip to !audioOnly, audioChip to audioOnly)) {
                c.setTextColor(if (on) OrbitColors.pillText(this) else OrbitColors.ink(this))
                c.background = GradientDrawable().apply { cornerRadius = 1000f; setColor(if (on) OrbitColors.pill(this@PlayerActivity) else OrbitColors.glass2(this@PlayerActivity)) }
            }
            disc.visibility = if (audioOnly) View.VISIBLE else View.GONE
            disc.layoutParams = android.widget.FrameLayout.LayoutParams(-1, if (audioOnly) (resources.displayMetrics.widthPixels * 1.2f).toInt() else h)
            if (audioOnly) disc.invalidate()
            placeSpinner()
            activePlayer?.let { p -> p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly).build() }
        }
        videoChip.setOnClickListener { audioOnly = false; paintChips(); frameRendered = false; handler.removeCallbacks(pictureCheck); handler.postDelayed(pictureCheck, 1500) }
        audioChip.setOnClickListener { audioOnly = true; paintChips() }
        val speeds = floatArrayOf(1f, 1.25f, 1.5f, 2f, 0.75f)
        speedChip.setOnClickListener {
            speedIdx = (speedIdx + 1) % speeds.size
            activePlayer?.setPlaybackSpeed(speeds[speedIdx])
            speedChip.text = (if (speeds[speedIdx] % 1f == 0f) speeds[speedIdx].toInt().toString() else speeds[speedIdx].toString()) + "x"
        }
        repaint = { paintChips() }
        paintChips()
        chips.addView(videoChip); chips.addView(audioChip); chips.addView(speedChip)
        col.addView(chips)

        col.addView(TextView(this).apply {
            text = getString(R.string.o_related); textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(OrbitColors.ink(this@PlayerActivity)); setPadding(dp(16), dp(14), dp(16), dp(4))
        })
        val relBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(relBox)
        root.addView(ScrollView(this).apply { addView(col) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(0, b.top, 0, b.bottom); insets
        }

        lifecycleScope.launch { startPlayback() }
        lifecycleScope.launch { loadRelated(relBox) }
    }

    private fun chip(text: String, on: Boolean) = TextView(this).apply {
        this.text = text; textSize = 12f; gravity = Gravity.CENTER
        setPadding(dp(14), dp(7), dp(14), dp(7))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
    }

    /**
     * A saved copy of this link. [video] = the screen is in Video mode: only a saved VIDEO file counts, because a saved audio file
     * plays sound under a black picture. Queue neighbours use any saved file.
     */
    private suspend fun findLocal(url: String, video: Boolean = false): String? = withContext(Dispatchers.IO) {
        val entries = DBManager.getInstance(this@PlayerActivity).historyDao.getAllHistoryByURL(url)
            .filter { dfast.app.util.PlaybackPick.typeMatches(it.type == DownloadType.audio, video) }
        entries.firstNotNullOfOrNull { h ->
            h.downloadPath.firstOrNull { p -> dfast.app.util.PlaybackPick.isMediaPath(p) && (p.startsWith("content://") || File(p).exists()) }
        }?.let { if (it.startsWith("content://")) it else Uri.fromFile(File(it)).toString() }
    }

    private suspend fun hasOfflineCopy(url: String, video: Boolean): Boolean = withContext(Dispatchers.IO) {
        val priv = File(filesDir, "offline").absolutePath
        DBManager.getInstance(this@PlayerActivity).historyDao.getAllHistoryByURL(url)
            .filter { dfast.app.util.PlaybackPick.typeMatches(it.type == DownloadType.audio, video) }
            .any { h -> h.downloadPath.any { it.startsWith(priv) && dfast.app.util.PlaybackPick.isMediaPath(it) && File(it).exists() } }
    }

    private var videoWatch: androidx.media3.common.Player.Listener? = null
    private var videoWatchOn: androidx.media3.common.Player? = null

    /** Fixes the black first frame: if the video never renders after the player is ready, re-bind the surface and re-select the video track once. */
    private fun watchVideo(p: androidx.media3.common.Player) {
        videoWatch?.let { videoWatchOn?.removeListener(it) }
        val l = object : androidx.media3.common.Player.Listener {
            var rendered = false
            var kicks = 0
            override fun onRenderedFirstFrame() { rendered = true }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) { rendered = false; kicks = 0 }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { if (tracks.isTypeSupported(C.TRACK_TYPE_VIDEO) && !audioOnly) schedule() }
            override fun onPlaybackStateChanged(state: Int) { if (state == androidx.media3.common.Player.STATE_READY && !audioOnly) schedule() }
            fun schedule() {
                handler.postDelayed({
                    if (!rendered && !audioOnly && kicks < 2 && p.playbackState == androidx.media3.common.Player.STATE_READY) {
                        kicks++
                        playerView.player = null; playerView.player = p
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
                        handler.postDelayed({
                            if (!audioOnly) p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build()
                        }, 60)
                    }
                }, 900)
            }
        }
        p.addListener(l); videoWatch = l; videoWatchOn = p
    }

    private suspend fun startPlayback() {
        StartupTrace.begin("open")
        // the previous song must not keep playing under the new song's picture and title
        PlaybackService.player?.pause()
        failBox.post { hideFailure() }
        localUri = if (skipLocal) null else findLocal(ref.url, video = !audioOnly)
        offlineSaved = if (skipLocal) false else hasOfflineCopy(ref.url, video = !audioOnly)
        runOnUiThread { offBtn.text = getString(when (dfast.app.util.PlaybackPick.saveState(offlineSaved, localUri != null)) {
            dfast.app.util.PlaybackPick.SaveState.OFFLINE -> R.string.o_saved
            dfast.app.util.PlaybackPick.SaveState.DOWNLOADED -> R.string.o_downloaded_state
            else -> R.string.o_save_offline }) }
        val local = localUri
        if (local != null) {
            // local file: play through the background service so audio keeps going when the screen closes
            val items = mutableListOf(PlaybackService.Companion.QueueItem(local, ref.title, ref.author, ref.thumb))
            var start = 0
            if (queueRefs.size > 1) {
                items.clear()
                var found = false
                for (q in queueRefs) {
                    // the song on screen always uses the file chosen above (type-matched); only the neighbours are looked up
                    val u = if (q.url == ref.url) local else findLocal(q.url)
                    u?.let { items.add(PlaybackService.Companion.QueueItem(it, q.title, q.author, q.thumb)); if (q.url == ref.url) { start = items.size - 1; found = true } }
                }
                if (!found) { items.add(0, PlaybackService.Companion.QueueItem(local, ref.title, ref.author, ref.thumb)); start = 0 }
            }
            expectedLocalUri = items.getOrNull(start)?.uri
            PlaybackService.playQueue(this, items, start)
            var tries = 0
            fun attach() {
                val p = PlaybackService.player
                if (p != null && isMine(p.currentMediaItem)) {
                    attachFollow(p)
                    playerView.player = p; watchVideo(p); watchHealth(p)
                    if (endAttached !== p) { endAttached?.removeListener(endListener); p.addListener(endListener); endAttached = p }
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly).build()
                } else if (tries++ < 100) handler.postDelayed({ attach() }, 100) else onAttachTimeout()
            }
            attach()
        } else {
            try {
                val vm = ViewModelProvider(this)[ResultViewModel::class.java]
                val urls = ((if (forceYtdlp || skipLocal) null else resultItem?.urls)?.takeIf { it.isNotBlank() }?.split("\n")
                    ?: StreamResolver.resolve(this, ref.url, false, false, skipNewPipe = forceYtdlp)).filter { it.isNotBlank() }
                if (urls.isEmpty()) throw Exception("no stream")
                // streams also play through the service so the system media card and background audio work
                StartupTrace.mark("play-sent")
                PlaybackService.playStream(this, urls, ref.title, ref.author, ref.thumb, ref.url, viewer = true)
                var tries = 0
                fun attach() {
                    val p = PlaybackService.player
                    if (p != null && isMine(p.currentMediaItem)) {
                        attachFollow(p)
                        playerView.player = p; watchVideo(p); watchHealth(p)
                    if (endAttached !== p) { endAttached?.removeListener(endListener); p.addListener(endListener); endAttached = p }
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly).build()
                    } else if (tries++ < 100) handler.postDelayed({ attach() }, 100) else onAttachTimeout()
                }
                attach()
            } catch (e: Exception) {
                loadFailed = true; updateSpinner()
                showFailure()
            }
        }
    }

    private suspend fun loadRelated(box: LinearLayout) {
        val results = withContext(Dispatchers.IO) { DBManager.getInstance(this@PlayerActivity).resultDao.getAll() }
            .map { MediaRef(it.url, it.title, it.author, it.thumb, it.duration) }
        val pool = (queueRefs + results + PlayStore.recents(this)).filter { it.url != ref.url }.distinctBy { it.url }.take(14)
        if (pool.isEmpty()) { box.addView(TextView(this).apply { text = getString(R.string.o_no_related); setTextColor(OrbitColors.inkSoft(this@PlayerActivity)); setPadding(dp(16), dp(4), dp(16), 0) }); return }
        relatedPool = pool
        pool.forEach { r ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(6), dp(14), dp(6)) }
            val th = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
                background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(OrbitColors.glass2(this@PlayerActivity)) }
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            }
            th.loadThumbnail(false, r.thumb)
            row.addView(th, LinearLayout.LayoutParams(dp(110), dp(62)))
            val tc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            tc.addView(TextView(this).apply { text = r.title; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; textSize = 13f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(OrbitColors.ink(this@PlayerActivity)) })
            tc.addView(TextView(this).apply { text = r.author; maxLines = 1; textSize = 11f; setTextColor(OrbitColors.inkSoft(this@PlayerActivity)) })
            row.addView(tc, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
            row.setOnClickListener {
                startActivity(intent(this, r, queueRefs))
                finish()
            }
            box.addView(row)
        }
    }

    private fun toggleFav() {
        val on = PlayStore.toggleFavorite(this, ref.copy(audio = audioOnly))
        favBtn.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
        recreateFavIcon(on)
        Toast.makeText(this, if (on) getString(R.string.o_fav_added) else getString(R.string.o_fav_removed), Toast.LENGTH_SHORT).show()
    }

    private fun recreateFavIcon(on: Boolean) {
        val d = androidx.core.content.ContextCompat.getDrawable(this, if (on) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)!!.mutate()
        d.setTint(OrbitColors.ink(this))
        val circle = android.graphics.drawable.LayerDrawable(arrayOf(
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(OrbitColors.glass2(this@PlayerActivity)); setSize(dp(46), dp(46)) }, d))
        circle.setLayerInset(1, dp(12), dp(12), dp(12), dp(12))
        circle.setBounds(0, 0, dp(46), dp(46))
        favBtn.setCompoundDrawables(null, circle, null, null)
    }

    private fun resolveResult(): ResultItem {
        resultItem?.let { return it }
        val dao = DBManager.getInstance(this).resultDao
        return dao.getResultByURL(ref.url) ?: ViewModelProvider(this)[DownloadViewModel::class.java].createEmptyResultItem(ref.url)
    }

    private fun queue(type: DownloadType, priv: Boolean = false) {
        val vm = ViewModelProvider(this)[DownloadViewModel::class.java]
        lifecycleScope.launch {
            val item = withContext(Dispatchers.IO) { vm.createDownloadItemFromResult(result = resolveResult(), givenType = type) }
            if (priv) item.downloadPath = File(filesDir, "offline/" + (if (type == DownloadType.audio) "audio" else "video")).apply { mkdirs() }.absolutePath
            vm.queueDownloads(listOf(item))
            Toast.makeText(this@PlayerActivity, getString(R.string.o_added_dl), Toast.LENGTH_SHORT).show()
        }
    }

    private fun chooseDownload() {
        AlertDialog.Builder(this).setTitle(getString(R.string.o_download)).setItems(arrayOf(getString(R.string.o_dl_video), getString(R.string.o_dl_audio))) { _, w ->
            // open the normal download customization sheet with the matching tab selected
            val t = if (w == 0) DownloadType.video else DownloadType.audio
            runCatching {
                startActivity(Intent(this, dfast.app.receiver.ShareActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, ref.url).putExtra("TYPE", t.toString()).putExtra("quick_download", false).putExtra("BACKGROUND", false))
            }.onFailure { queue(t) }
        }.show()
    }

    private fun saveOffline() {
        if (offlineSaved) { Toast.makeText(this, getString(R.string.o_already_offline), Toast.LENGTH_SHORT).show(); return }
        queue(if (audioOnly) DownloadType.audio else DownloadType.video, priv = true)
    }

    private fun addToPlaylist() {
        val names = PlayStore.playlistNames(this)
        val labels = (names + getString(R.string.play_new_playlist)).toTypedArray()
        AlertDialog.Builder(this).setTitle(getString(R.string.play_add_to_playlist)).setItems(labels) { _, w ->
            if (w < names.size) { PlayStore.addToPlaylist(this, names[w], ref); Toast.makeText(this, names[w], Toast.LENGTH_SHORT).show() }
            else {
                val et = android.widget.EditText(this)
                AlertDialog.Builder(this).setTitle(getString(R.string.play_new_playlist)).setView(et).setPositiveButton(android.R.string.ok) { _, _ ->
                    val n = et.text.toString().trim()
                    if (n.isNotEmpty()) { PlayStore.createPlaylist(this, n); PlayStore.addToPlaylist(this, n, ref) }
                }.setNegativeButton(android.R.string.cancel, null).show()
            }
        }.show()
    }

    override fun onStart() {
        super.onStart()
        recreateFavIcon(PlayStore.isFavorite(this, ref.url))
    }

    override fun onStop() {
        super.onStop()
        ownPlayer?.pause()
    }

    override fun onDestroy() {
        videoWatch?.let { videoWatchOn?.removeListener(it) }
        handler.removeCallbacksAndMessages(null)
        endAttached?.removeListener(endListener); endAttached = null
        followOn?.removeListener(followListener); followOn = null
        healthOn?.removeListener(healthListener); healthOn = null
        playerView.player = null
        ownPlayer?.release(); ownPlayer = null
        super.onDestroy()
    }
}
