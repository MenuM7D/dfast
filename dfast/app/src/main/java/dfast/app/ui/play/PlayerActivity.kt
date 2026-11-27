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
import kotlinx.coroutines.async
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
    private lateinit var shareBtn: TextView
    /** Share only exists for a real web link; with none the button is hidden (no empty gap, the others re-space). */
    private fun updateShare() { if (::shareBtn.isInitialized) shareBtn.visibility = if (dfast.app.util.ShareLink.canShare(ref.url)) View.VISIBLE else View.GONE }
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
            if (state == androidx.media3.common.Player.STATE_ENDED) onEnded()
            else if (suggestOnly && ::nextCard.isInitialized && nextCard.visibility == View.VISIBLE) cancelNext() // replayed or seeked: the suggestion goes away
        }
        /** pause-at-end hold: the player stops at the end of the item (state stays READY) instead of moving on by itself */
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == androidx.media3.common.Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) { heldAtEnd = true; onEnded() }
            else if (playWhenReady && heldAtEnd) { // user pressed play at the end: replay this video, do not move on
                heldAtEnd = false; cancelNext()
                activePlayer?.let { it.seekTo(it.currentMediaItemIndex, 0L) }
            }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) heldAtEnd = false }
        override fun onPositionDiscontinuity(oldPosition: androidx.media3.common.Player.PositionInfo, newPosition: androidx.media3.common.Player.PositionInfo, reason: Int) {
            if (reason == androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK) { heldAtEnd = false; if (suggestOnly) cancelNext() }
        }
    }
    private var heldAtEnd = false

    // --- autoplay (YouTube style). The queue and the service are never touched: this only runs when the player reached the END with nothing queued after it ---
    private var autoJob: kotlinx.coroutines.Job? = null
    private var ytRelated: kotlinx.coroutines.Deferred<List<dfast.app.util.AutoCand>>? = null
    private lateinit var nextCard: android.widget.FrameLayout
    private lateinit var nextRing: CountdownRing
    private lateinit var nextThumb: ImageView
    private lateinit var nextTitle: TextView
    private lateinit var nextAuthor: TextView
    private fun autoplayOn() = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).getBoolean("autoplay_next", true)
    /** metered connection = mobile data: autoplay stops after 30 minutes without a touch there (4 hours otherwise) */
    private fun onMobileData(): Boolean = runCatching { (getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).isActiveNetworkMetered }.getOrDefault(false)
    private fun inFront() = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)

    /** true while the end card only SUGGESTS (autoplay off): no countdown, nothing opens by itself */
    private var suggestOnly = false

    private fun onEnded() {
        if (repeatIdx != 0 || !inFront()) return // the player screen must be in front: otherwise autoplay does nothing
        if (!autoplayOn()) { // autoplay off: playback just stops; a finished VIDEO shows a suggestion the user can tap
            if (!audioOnly) { suggestOnly = true; startNextFlow() }
            return
        }
        // autoplay belongs to THIS screen: it never follows the background queue. Idle past the limit: only a suggestion card (video), nothing opens by itself
        if (!dfast.app.util.AutoplayPick.allowed(true, false, System.currentTimeMillis(), dfast.app.util.AutoplayState.lastTouchMs, dfast.app.util.AutoplayPick.limitFor(onMobileData()))) {
            if (!audioOnly) { suggestOnly = true; startNextFlow() }
            return
        }
        suggestOnly = false
        startNextFlow()
    }

    /** the pick for the video on screen: its own YouTube Related first, local list only as fallback */
    private suspend fun computePick(shuffle: Boolean = false): MediaRef? {
        val related = kotlinx.coroutines.withTimeoutOrNull(9000) { ytRelated?.await() } ?: emptyList()
        val local = relatedPool.map { dfast.app.util.AutoCand(it.url, it.title, it.author, it.thumb, dfast.app.util.AutoplayPick.parseDuration(it.duration), false) }
        val dur = (activePlayer?.duration ?: 0L).let { if (it > 0) it / 1000 else 0L }
        val pick = dfast.app.util.AutoplayPick.pick(related, local, ref.url, dfast.app.util.AutoplayState.recent(), dfast.app.util.AutoplayPick.Ctx(ref.title, ref.author, dur),
            dfast.app.util.AutoplayPick.Opts(music = audioOnly || ref.author.endsWith("- Topic"), recentAuthors = dfast.app.util.AutoplayState.recentAuthors(),
                recentTitles = dfast.app.util.AutoplayState.recentTitles(), shuffle = shuffle)) ?: return null
        return MediaRef(pick.url, pick.title, pick.author, pick.thumb, if (pick.durationSec > 0) fmtDurSec(pick.durationSec) else "")
    }

    private fun startNextFlow() {
        autoJob?.cancel()
        autoJob = lifecycleScope.launch {
            val n = computePick() ?: return@launch
            if (suggestOnly) { if (inFront() && endedNow()) showNextCard(n); return@launch }
            if (audioOnly) { if (stillEnded()) openAuto(n); return@launch } // audio mode: straight to the next one, no wait
            showNextCard(n)
        }
    }

    private fun endedNow() = heldAtEnd || activePlayer?.playbackState == androidx.media3.common.Player.STATE_ENDED
    private fun stillEnded() = inFront() && autoplayOn() && endedNow()

    // --- hold at the end + prefetch. The hold is only set while this screen is in front with autoplay on, and always reset when it leaves ---
    private fun applyHold() {
        val sp = PlaybackService.player as? androidx.media3.exoplayer.ExoPlayer ?: return
        val want = inFront() && autoplayOn() && repeatIdx == 0 && ownPlayer == null
        if (want) { HoldOwner.token = this; sp.setPauseAtEndOfMediaItems(true) }
        else if (HoldOwner.token === this) { HoldOwner.token = null; sp.setPauseAtEndOfMediaItems(false) }
    }
    private fun releaseHold() {
        if (HoldOwner.token === this) { HoldOwner.token = null; (PlaybackService.player as? androidx.media3.exoplayer.ExoPlayer)?.setPauseAtEndOfMediaItems(false) }
    }
    private var prefetchedFor = ""
    private val tick: Runnable = Runnable {
        if (!isDestroyed && inFront()) {
            applyHold()
            val p = activePlayer
            if (p != null && autoplayOn() && repeatIdx == 0 && p.duration > 0 && p.duration - p.currentPosition <= 20_000 && prefetchedFor != ref.url && p.isPlaying) {
                prefetchedFor = ref.url
                lifecycleScope.launch {
                    val n = computePick() ?: return@launch
                    withContext(Dispatchers.IO) {
                        runCatching { StreamResolver.resolve(this@PlayerActivity, n.url, false, false) }
                        // its own Related list too, so the next screen shows it at once
                        runCatching { dfast.app.util.extractors.newpipe.NewPipeUtil.cachedInfo(n.url) }
                    }
                    android.util.Log.d("DFastAutoplay", "prefetched ${n.url}")
                }
            }
            saveResume()
            handler.postDelayed(tick, 3000)
        }
    }
    /** remembers where a (real, 2 min+) video is, forgets it once it is finished; the Home "Continue watching" shelf reads it */
    private fun saveResume() {
        val p = activePlayer ?: return
        if (audioOnly || !p.isPlaying || !isMine(p.currentMediaItem) || !ref.url.startsWith("http")) return
        val dur = p.duration; val pos = p.currentPosition
        if (dur <= 0) return
        if (dfast.app.util.ResumeStore.shouldKeep(pos, dur)) dfast.app.util.ResumeStore.save(this, dfast.app.util.ResumeStore.Entry(ref.url, ref.title, ref.author, ref.thumb, fmtDurSec(dur / 1000), pos, dur, System.currentTimeMillis()))
        else if (dfast.app.util.ResumeStore.finished(pos, dur)) dfast.app.util.ResumeStore.remove(this, ref.url)
    }
    private fun fmtDurSec(sec: Long): String = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)

    private fun openAuto(n: MediaRef) {
        history.push(ref)
        startActivity(navIntent(n.copy(audio = dfast.app.util.AutoplayPick.audioFor(audioOnly, false)), queueRefs))
        finish()
    }

    private suspend fun showNextCard(n: MediaRef) {
        placeNextCard()
        nextTitle.text = n.title; nextAuthor.text = n.author; nextThumb.loadThumbnail(false, n.thumb)
        nextCard.visibility = View.VISIBLE
        nextCard.tag = n
        nextRing.visibility = if (suggestOnly) View.GONE else View.VISIBLE
        if (suggestOnly) return // suggestion only: stays until the user taps, cancels, leaves or replays
        try {
            for (t in 0 until 50) {
                nextRing.set(t / 50f, 5 - t / 10)
                kotlinx.coroutines.delay(100)
                if (!stillEnded()) { nextCard.visibility = View.GONE; return }
            }
            nextCard.visibility = View.GONE
            openAuto(n)
        } finally { if (autoJob?.isActive != true) nextCard.visibility = View.GONE }
    }

    private fun cancelNext() { autoJob?.cancel(); if (::nextCard.isInitialized) nextCard.visibility = View.GONE }

    private fun loadYtRelated(url: String = openedUrl) {
        if (!url.startsWith("http")) return
        ytRelated = lifecycleScope.async(Dispatchers.IO) {
            // the same for every entry point (search, feed, music, library, queue): up to 3 tries, a failed or empty read is never kept
            var out: List<dfast.app.util.AutoCand> = emptyList()
            // YouTube's own related list for this video first (the watch-next data the YouTube site uses), then NewPipe's
            val vid = dfast.app.util.UrlIds.of(url)
            if (vid.isNotEmpty()) out = dfast.app.util.YtRelated.fetch(vid)
            if (out.isNotEmpty()) return@async out
            for (attempt in 0 until 3) {
                out = runCatching {
                    dfast.app.util.extractors.newpipe.NewPipeUtil.cachedInfo(url).relatedItems
                        .filterIsInstance<org.schabi.newpipe.extractor.stream.StreamInfoItem>()
                        .map { dfast.app.util.AutoCand(it.url, it.name.orEmpty(), it.uploaderName.orEmpty().removeSuffix(" - Topic"),
                            runCatching { it.thumbnails.lastOrNull()?.url }.getOrNull().orEmpty(), it.duration,
                            it.streamType == org.schabi.newpipe.extractor.stream.StreamType.LIVE_STREAM || it.streamType == org.schabi.newpipe.extractor.stream.StreamType.AUDIO_LIVE_STREAM) }
                }.getOrDefault(emptyList())
                if (out.isNotEmpty()) break
                dfast.app.util.extractors.newpipe.NewPipeUtil.forgetInfo(url)
                kotlinx.coroutines.delay(1500L * (attempt + 1))
            }
            out
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean { dfast.app.util.AutoplayState.touch(); return super.dispatchTouchEvent(ev) }
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
    /** Attempt chain for one open: 0 = NewPipe (fast), 1 = yt-dlp with fresh links, 2 = yt-dlp single ready-made file (no merging). The failure screen only comes after stage 2. */
    private var stage = 0
    private var progressiveMode = false
    private var lastReason = ""
    private var wdBuffered = 0L
    private var wdRounds = 0
    private val watchdog: Runnable = Runnable {
        val p = PlaybackService.player
        if (p != null && isMine(p.currentMediaItem) && !p.isPlaying && p.playWhenReady && p.playbackState != androidx.media3.common.Player.STATE_ENDED) {
            // a slow start that is still filling its buffer is not a failure: wait while the buffer keeps growing (at most 6 x 10 s extra)
            val b = p.totalBufferedDuration
            if (p.playbackState == androidx.media3.common.Player.STATE_BUFFERING && b > wdBuffered && wdRounds < 6) {
                wdBuffered = b; wdRounds++
                android.util.Log.d("DFastStartup", "watchdog: still buffering (${b}ms), waiting")
                handler.postDelayed(watchdog, 10_000)
            } else giveUp("no-progress")
        }
    }

    /** Nothing played and nothing is filling: start once more from a fresh lookup; only if that was already done, say it failed. */
    private fun giveUp(reason: String) {
        if (stage < 2 && expectedLocalUri == null && openedUrl.startsWith("http")) {
            android.util.Log.d("DFastStartup", "attempt $stage failed ($reason), next attempt")
            retryPlayback(reason)
        } else {
            android.util.Log.d("DFastStartup", "attempt $stage failed ($reason), no attempts left: showing failure")
            showFailure()
        }
    }
    private val healthListener = object : androidx.media3.common.Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val p = PlaybackService.player ?: return
            if (!isMine(p.currentMediaItem)) return
            android.util.Log.d("DFastStartup", "player error ${error.errorCodeName} (attempt $stage)")
            if (expectedLocalUri != null && !skipLocal) { // the saved file does not play: stream it instead
                skipLocal = true; expectedLocalUri = null
                lifecycleScope.launch { startPlayback() }
                return
            }
            // for streams the queue already retries once with a fresh link; if that fails too, say so
            handler.removeCallbacks(watchdog); handler.postDelayed(watchdog, 12_000)
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) { handler.removeCallbacks(watchdog); hideFailure(); updateSpinner() } }
        override fun onPlaybackStateChanged(state: Int) {
            val p = PlaybackService.player ?: return
            // a quality switch / re-fetch reloads the stream from 0: ask for the saved position as soon as the new stream is preparing,
            // so a long video loads around where it was instead of its first seconds and then jumps
            if (state == androidx.media3.common.Player.STATE_BUFFERING && seekOnReady > 0 && isMine(p.currentMediaItem) && p.currentPosition < seekOnReady - 3000) p.seekTo(seekOnReady)
            if (state == androidx.media3.common.Player.STATE_READY && seekOnReady > 0 && isMine(p.currentMediaItem)) { if (kotlin.math.abs(p.currentPosition - seekOnReady) > 3000) p.seekTo(seekOnReady); seekOnReady = 0 }
        }
        override fun onRenderedFirstFrame() { frameRendered = true; handler.removeCallbacks(hideCover); hideCover.run() }
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) { frameRendered = false; handler.removeCallbacks(pictureCheck) }
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            runCatching { // what is actually playing (codec, size, rate), never links or titles
                for (g in tracks.groups) if (g.type == C.TRACK_TYPE_VIDEO || g.type == C.TRACK_TYPE_AUDIO)
                    for (i in 0 until g.length) if (g.isTrackSelected(i)) { val f = g.getTrackFormat(i)
                        if (g.type == C.TRACK_TYPE_VIDEO && f.height > 0) qPlaying = dfast.app.util.QualityLabels.standard(f.height)
                        android.util.Log.d("DFastStartup", "track ${if (g.type == C.TRACK_TYPE_VIDEO) "video" else "audio"} ${f.sampleMimeType} ${f.codecs} ${f.height}p ${f.bitrate / 1000}kbps (attempt $stage)") }
            }
            if (audioOnly) return
            handler.removeCallbacks(pictureCheck); handler.postDelayed(pictureCheck, 2500)
        }
    }

    private var frameRendered = false
    private val pictureCheck = Runnable { checkPicture(); checkSound() }
    private val soundFixed = HashSet<String>()
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
            if (!forceYtdlp) StreamResolver.markNewPipeBad(key)
            if (mine) { expectedLocalUri = null; skipLocal = true; forceYtdlp = true; stage = maxOf(stage, 1); seekOnReady = pos }
            PlaybackService.playStream(this@PlayerActivity, urls, md.title?.toString() ?: ref.title, md.artist?.toString() ?: ref.author, md.artworkUri?.toString() ?: ref.thumb, key, viewer = true, startMs = pos)
        }
    }

    /**
     * Video mode and a picture, but the stream carries NO usable audio track (a video-only link: silent picture).
     * A YouTube stream is re-fetched once through yt-dlp (the real video+audio pair), at the same position.
     * Once per video: a video that truly has no sound is left alone after that single retry.
     */
    private fun checkSound() {
        val p = PlaybackService.player ?: return
        if (audioOnly || isDestroyed || playerView.player !== p) return
        val item = p.currentMediaItem ?: return
        if (p.playbackState != androidx.media3.common.Player.STATE_READY) return
        val tr = p.currentTracks
        if (!tr.groups.any { it.type == C.TRACK_TYPE_VIDEO }) return // tracks not known yet
        if (tr.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }) return
        if (!isMine(item) || expectedLocalUri != null || localUri != null) return
        val key = openedUrl
        if (!key.startsWith("http") || !key.isYoutubeURL() || !soundFixed.add(key)) return
        val pos = p.currentPosition
        val md = item.mediaMetadata
        android.util.Log.d("DFastStartup", "no audio track, retrying through yt-dlp")
        lifecycleScope.launch {
            StreamResolver.forget(key)
            // a quality the user picked keeps its height: the retry asks yt-dlp for that exact height, never for the default pick
            val q = qChosen
            val urls = if (q > 0) StreamResolver.resolveHeight(this@PlayerActivity, key, q, skipNewPipe = true)
                       else withContext(Dispatchers.IO) { StreamResolver.resolve(this@PlayerActivity, key, false, false, skipNewPipe = true) }
            if (isDestroyed || urls.size < 2) return@launch // nothing better to offer: keep what plays today
            if (!forceYtdlp) StreamResolver.markNewPipeBad(key)
            forceYtdlp = true; stage = maxOf(stage, 1); seekOnReady = pos
            PlaybackService.playStream(this@PlayerActivity, urls, md.title?.toString() ?: ref.title, md.artist?.toString() ?: ref.author, md.artworkUri?.toString() ?: ref.thumb, key, viewer = true, startMs = pos)
        }
    }

    private fun watchHealth(p: androidx.media3.common.Player) {
        if (healthOn !== p) { healthOn?.removeListener(healthListener); p.addListener(healthListener); healthOn = p }
        wdBuffered = 0L; wdRounds = 0
        handler.removeCallbacks(watchdog); handler.postDelayed(watchdog, 25_000)
    }

    private fun showFailure() {
        if (!::failBox.isInitialized || isFinishing || isDestroyed) return
        loadFailed = true; updateSpinner()
        failBox.visibility = View.VISIBLE
        signInHint()
    }

    /** A YouTube video that will not load while no account is signed in may be age-restricted or login-only: say where the fix is. */
    private fun signInHint() {
        if (openedUrl.isYoutubeURL() && !dfast.app.util.GoogleAccount.isSignedIn(this)) Toast.makeText(this, getString(R.string.o_site_signin, "YouTube"), Toast.LENGTH_LONG).show()
    }

    private fun hideFailure() { if (::failBox.isInitialized) failBox.visibility = View.GONE }

    /** The player never bound to this song within 10 s: still give the controls the real player, and say it failed. */
    private fun onAttachTimeout() {
        PlaybackService.player?.let { p -> playerView.player = p; watchVideo(p); watchHealth(p) }
        giveUp("bind-timeout")
    }

    /** Retry: forget every cached link for this video and start over from a fresh lookup, streaming (not the saved file). */
    // ---- video quality (this screen only; never touches the service, the queue or the pill) ----
    private var qHeights: List<Int> = emptyList()   // heights this video really offers (NewPipe, with a url)
    private var qChosen = 0                          // 0 = Auto (the normal pick), else the exact height; per screen, never saved
    private var qPlaying = 0                         // height actually playing, read from the player's selected video track
    private var qBusy = false

    private fun qualityAvailable() = !audioOnly && expectedLocalUri == null && localUri == null && openedUrl.isYoutubeURL() && qLadder().size >= 2

    private fun loadQualities() {
        if (qHeights.isNotEmpty() || !openedUrl.isYoutubeURL()) return
        val u = openedUrl
        lifecycleScope.launch {
            val hs = withContext(Dispatchers.IO) { runCatching { dfast.app.util.extractors.newpipe.NewPipeUtil(applicationContext).videoHeights(u) }.getOrDefault(emptyList()) }
            if (!isDestroyed && u == openedUrl) { qHeights = hs; preResolveQualities() }
        }
    }

    private var qPopup: android.widget.PopupWindow? = null
    private var ytLabels: List<Int> = emptyList()
    private var ytLabelsFor = ""

    /** standard labels, lowest first: what NewPipe lists plus what yt-dlp added */
    private fun qLadder(): List<Int> = dfast.app.util.QualityLabels.ascending(qHeights + ytLabels)

    private fun resText(kind: String, name: String, fallback: String): String {
        val id = resources.getIdentifier(name, kind, packageName)
        return if (id != 0) getString(id) else fallback
    }

    private fun fmtSpeed(x: Float) = if (x % 1f == 0f) "${x.toInt()}x" else "${x}x"

    /** The dock's gear opens OUR panel: Quality, Speed and Audio rows; the open row expands in place with chips. */

    /** the player screen has no text field of its own: make sure the keyboard is down */
    private fun hideKeyboard() {
        runCatching { androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.ime()) }
    }

    private fun showSettingsPanel(anchor: View) {
        qPopup?.dismiss()
        val p = activePlayer
        val accent = 0xFF9D86FF.toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        val scroll = ScrollView(this).apply { addView(box); isVerticalScrollBarEnabled = false }
        scroll.background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0xF5212126.toInt()) }
        scroll.clipToOutline = true; scroll.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        val w = dp(290)
        val pw = android.widget.PopupWindow(scroll, w, dp(200), true).apply {
            isOutsideTouchable = true; elevation = dp(8).toFloat(); setBackgroundDrawable(null)
            // this panel has no text field: it must never bring the keyboard up (the recording shows the keyboard flashing after a pick)
            inputMethodMode = android.widget.PopupWindow.INPUT_METHOD_NOT_NEEDED
            softInputMode = android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            setOnDismissListener { hideKeyboard() }
        }
        qPopup = pw
        val loc = IntArray(2); anchor.getLocationOnScreen(loc)
        val x = (loc[0] + anchor.width - w + dp(4)).coerceIn(dp(6), resources.displayMetrics.widthPixels - w - dp(6))
        var open = ""
        val speeds = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        var shown = false
        fun place() {
            box.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val screenH = resources.displayMetrics.heightPixels
            val below = loc[1] < screenH / 2
            val maxH = (if (below) screenH - (loc[1] + anchor.height) - dp(24) else loc[1] - dp(12)).coerceAtLeast(dp(160))
            val h = minOf(box.measuredHeight, maxH)
            val y = if (below) loc[1] + anchor.height + dp(6) else (loc[1] - h - dp(6)).coerceAtLeast(dp(6))
            if (!shown) { shown = true; pw.height = h; pw.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y) } else pw.update(x, y, w, h)
        }
        fun chips(items: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
            val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(11), dp(2), dp(11), dp(10)) }
            items.chunked(4).forEachIndexed { r, rowItems ->
                val line = LinearLayout(this)
                rowItems.forEachIndexed { c, (label, sel) ->
                    val idx = r * 4 + c
                    line.addView(TextView(this).apply {
                        text = label; textSize = 13f; gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                        setTextColor(Color.WHITE); if (sel) setTypeface(typeface, android.graphics.Typeface.BOLD)
                        background = GradientDrawable().apply { cornerRadius = 1000f; setColor(if (sel) accent else 0x33FFFFFF) }
                        setOnClickListener { onPick(idx) }
                    }, LinearLayout.LayoutParams(0, dp(36), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
                }
                repeat(4 - rowItems.size) { line.addView(View(this), LinearLayout.LayoutParams(0, dp(36), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }) }
                holder.addView(line)
            }
            box.addView(holder)
        }
        lateinit var render: () -> Unit
        fun row(key: String, icon: Int, label: String, value: String) {
            val isOpen = open == key
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(52); setPadding(dp(16), 0, dp(14), 0); isClickable = true; if (isOpen) setBackgroundColor(0x2E9D86FF) }
            r.addView(ImageView(this).apply { setImageResource(icon); setColorFilter(Color.WHITE) }, LinearLayout.LayoutParams(dp(22), dp(22)))
            r.addView(TextView(this).apply { text = label; textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
            r.addView(TextView(this).apply { text = value; textSize = 13f; setTextColor(0xFFCFCFD8.toInt()); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
            r.addView(ImageView(this).apply { setImageResource(R.drawable.ic_pl_expand); setColorFilter(0xFFCFCFD8.toInt()); rotation = if (isOpen) 180f else 0f }, LinearLayout.LayoutParams(dp(20), dp(20)))
            r.setOnClickListener { open = if (isOpen) "" else key; render() }
            box.addView(r, LinearLayout.LayoutParams(-1, -2))
        }
        render = {
            box.removeAllViews()
            if (qualityAvailable()) {
                val ladder = qLadder()
                row("q", R.drawable.ic_pl_tune, getString(R.string.video_quality),
                    if (qChosen == 0) getString(R.string.auto) + (if (qPlaying > 0) " · ${qPlaying}p" else "") else "${qChosen}p")
                if (open == "q") {
                    chips(listOf(getString(R.string.auto) to (qChosen == 0)) + ladder.map { "${it}p" to (qChosen == it) }) { i ->
                        pw.dismiss(); chooseQuality(if (i == 0) 0 else ladder[i - 1])
                    }
                }
            }
            val sp = activePlayer?.playbackParameters?.speed ?: 1f
            row("s", R.drawable.ic_pl_speed, resText("string", "exo_controls_playback_speed", "Speed"), fmtSpeed(sp))
            if (open == "s") chips(speeds.map { fmtSpeed(it) to (kotlin.math.abs(it - sp) < 0.01f) }) { i ->
                activePlayer?.setPlaybackSpeed(speeds[i]); speedChipTv?.text = fmtSpeed(speeds[i]); render()
            }
            val groups = activePlayer?.currentTracks?.groups?.filter { it.type == C.TRACK_TYPE_AUDIO } ?: emptyList()
            val ov = activePlayer?.trackSelectionParameters?.overrides?.keys?.filter { it.type == C.TRACK_TYPE_AUDIO }.orEmpty()
            fun gLabel(i: Int): String { val f = groups[i].getTrackFormat(0); return (f.label ?: f.language?.takeIf { it != "und" } ?: "${f.bitrate.coerceAtLeast(0) / 1000}kbps") }
            val selIdx = groups.indexOfFirst { g -> ov.any { it == g.mediaTrackGroup } }
            row("a", R.drawable.ic_pl_volume, resText("string", "exo_track_selection_title_audio", "Audio"), if (selIdx >= 0) gLabel(selIdx) else getString(R.string.auto))
            if (open == "a") chips(listOf(getString(R.string.auto) to (selIdx < 0)) + groups.indices.map { gLabel(it) to (it == selIdx) }) { i ->
                val pl = activePlayer
                if (pl != null) {
                    pl.trackSelectionParameters = if (i == 0) pl.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
                        else pl.trackSelectionParameters.buildUpon().setOverrideForType(androidx.media3.common.TrackSelectionOverride(groups[i - 1].mediaTrackGroup, 0)).build()
                }
                render()
            }
            // shuffle and repeat live here now (purple = on); the repeat icon shows "1" for repeat-one
            fun toggleRow(icon: Int, label: String, on: Boolean, onClick: () -> Unit) {
                val col = if (on) accent else Color.WHITE
                val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(52); setPadding(dp(16), 0, dp(14), 0); isClickable = true }
                r.addView(ImageView(this).apply { setImageResource(icon); setColorFilter(col) }, LinearLayout.LayoutParams(dp(22), dp(22)))
                r.addView(TextView(this).apply { text = label; textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(col) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
                if (on) r.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) } }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
                r.setOnClickListener { onClick(); render() }
                box.addView(r, LinearLayout.LayoutParams(-1, -2))
            }
            toggleRow(R.drawable.ic_pd_shuffle, getString(R.string.o_shuffle_queue), shuffleOn) { toggleShuffle() }
            toggleRow(if (repeatIdx == 2) R.drawable.ic_pd_repeat_1 else R.drawable.ic_pd_repeat, getString(R.string.o_repeat_mode), repeatIdx != 0) { cycleRepeat() }
            place()
        }
        // the quality list is complete from NewPipe alone for most videos; when it is short, yt-dlp's own list is added in the background (logged as DFastQuality)
        if (qualityAvailable() || (qHeights.isNotEmpty() && !audioOnly)) if (qHeights.size < 5 && ytLabelsFor != openedUrl && openedUrl.isYoutubeURL()) {
            val u = openedUrl; ytLabelsFor = u
            lifecycleScope.launch {
                val l = StreamResolver.ytdlpLabels(this@PlayerActivity, u)
                if (!isDestroyed && u == openedUrl && l.isNotEmpty()) { ytLabels = l; preResolveQualities(); if (pw.isShowing) render() }
            }
        }
        render()
    }

    /** Same position, same attempt chain: a failed pick keeps what is playing now and says so. */
    private fun chooseQuality(h: Int) {
        if (h == qChosen || qBusy || !qualityAvailable()) return
        val p = activePlayer ?: return
        val pos = p.currentPosition
        val prev = qChosen
        qChosen = h // the header shows the pick at once; a failed pick puts it back
        qBusy = true; updateSpinner() // the picture keeps playing while the new link is fetched; the ring in the dock says a switch is under way
        lifecycleScope.launch {
            val urls = if (h == 0) withContext(Dispatchers.IO) { StreamResolver.resolve(this@PlayerActivity, openedUrl, false, false, skipNewPipe = forceYtdlp, progressive = progressiveMode) }
                       else StreamResolver.resolveHeight(this@PlayerActivity, openedUrl, h, skipNewPipe = forceYtdlp)
            qBusy = false
            if (isDestroyed) return@launch
            if (urls.isEmpty()) { qChosen = prev; updateSpinner(); Toast.makeText(this@PlayerActivity, getString(R.string.o_load_fail), Toast.LENGTH_SHORT).show(); signInHint(); return@launch }
            val now = activePlayer?.currentPosition ?: pos // the video kept playing while the link was fetched: start where it is now
            seekOnReady = now; frameRendered = false
            PlaybackService.playStream(this@PlayerActivity, urls, ref.title, ref.author, ref.thumb, openedUrl, viewer = true, startMs = now)
            PlaybackService.player?.let { watchHealth(it) }
            updateSpinner()
        }
    }

    /** Once per video: the links of every listed height are looked up in the background (NewPipe only, no yt-dlp process), so a tap on a chip finds its link already cached. */
    private var preResolved = ""
    private fun preResolveQualities() {
        val u = openedUrl
        val ytMode = forceYtdlp
        val tag = u + ytMode + qLadder().size
        if (preResolved == tag || !qualityAvailable()) return
        preResolved = tag
        val hs = qLadder()
        lifecycleScope.launch {
            // yt-dlp mode (account / age-restricted videos): every link comes out of the one listing, so no extra process runs
            if (ytMode) { // the listing is a second yt-dlp run: wait until the picture is playing so it never slows the start
                var waited = 0
                while (waited < 60_000 && !isDestroyed && activePlayer?.playbackState != androidx.media3.common.Player.STATE_READY) { kotlinx.coroutines.delay(500); waited += 500 }
            }
            for (h in hs) { if (isDestroyed || u != openedUrl) return@launch; runCatching { StreamResolver.resolveHeight(this@PlayerActivity, u, h, skipNewPipe = ytMode, newPipeOnly = true) } }
        }
    }

    private fun retryPlayback(reason: String = "manual") {
        if (qChosen != 0) { qChosen = 0; activePlayer?.let { seekOnReady = it.currentPosition }; Toast.makeText(this, getString(R.string.o_load_fail), Toast.LENGTH_SHORT).show() } // chosen quality failed: back to Auto
        hideFailure(); loadFailed = false; following = false
        StreamResolver.forget(openedUrl)
        stage = (stage + 1).coerceAtMost(2); lastReason = reason
        if (!forceYtdlp) StreamResolver.markNewPipeBad(openedUrl)
        skipLocal = true; forceYtdlp = true; progressiveMode = stage >= 2; pictureFixed.remove(openedUrl)
        updateSpinner()
        lifecycleScope.launch { startPlayback() }
    }

    private fun updateSpinner() {
        val p = PlaybackService.player
        // the picture or sound is really going for OUR item: whatever flags a retry or fallback left behind, the loading state is over
        if (p != null && p.isPlaying && isMine(p.currentMediaItem)) { following = true; loadFailed = false; hideFailure() }
        val busy = !loadFailed && (qBusy || !following || (p != null && p.playbackState == androidx.media3.common.Player.STATE_BUFFERING))
        // video mode: the loading ring sits in the dock in place of the play icon (never over a control); audio view keeps the old one
        if (::dock.isInitialized) dock.loading = busy && !audioOnly
        if (::spinner.isInitialized) spinner.visibility = if (busy && audioOnly) View.VISIBLE else View.GONE
    }

    private fun placeSpinner() {
        if (!::spinner.isInitialized) return
        val lp = spinner.layoutParams as android.widget.FrameLayout.LayoutParams
        if (fullscreen && !audioOnly) { lp.gravity = Gravity.CENTER; lp.topMargin = 0 }
        else {
            val h = inlineH()
            val frameH = if (audioOnly) (resources.displayMetrics.widthPixels * 1.2f).toInt() else h
            val cy = if (audioOnly) frameH * 0.26f else frameH / 2f
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; lp.topMargin = (cy - dp(22)).toInt()
        }
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
            refreshRelated(ref.url)
        }
        updateShare()
        updateSpinner()
    }

    private fun attachFollow(p: androidx.media3.common.Player) {
        if (followOn !== p) { followOn?.removeListener(followListener); p.addListener(followListener); followOn = p }
        syncFromPlayer()
        updateSpinner()
    }

    /** the next item of a playlist the user is playing through (the screen's item is inside the queue), else null */
    private fun queueNext(): MediaRef? {
        val idx = queueRefs.indexOfFirst { it.url == ref.url }
        if (idx < 0 || queueRefs.size < 2) return null
        if (shuffleOn) return queueRefs.filter { it.url != ref.url }.randomOrNull()
        return queueRefs.getOrNull(idx + 1)
    }

    /**
     * Next: inside a playlist the playlist order; everywhere else exactly what autoplay would pick for this video
     * (its own YouTube Related, same rules). Never disabled: when nothing fits, the first related item is used.
     */
    private fun goNext() {
        lifecycleScope.launch {
            val n = queueNext() ?: computePick(shuffleOn) ?: relatedPool.firstOrNull { it.url != ref.url } ?: queueRefs.firstOrNull { it.url != ref.url }
            if (n == null) { Toast.makeText(this@PlayerActivity, getString(R.string.o_no_related), Toast.LENGTH_SHORT).show(); return@launch }
            history.push(ref)
            startActivity(navIntent(n.copy(audio = audioOnly), queueRefs))
            finish()
        }
    }

    /** Previous: the video played before this one (any position); inside a playlist at its start, the previous item. Disabled only with no history. */
    private fun canPrev(): Boolean = history.canBack() || queueRefs.indexOfFirst { it.url == ref.url } > 0

    private fun goPrev() {
        val r = if (history.canBack()) history.pop() else queueRefs.getOrNull(queueRefs.indexOfFirst { it.url == ref.url } - 1)
        if (r == null) { activePlayer?.seekTo(0); return }
        startActivity(navIntent(r.copy(audio = audioOnly), queueRefs))
        finish()
    }
    private var speedIdx = 0
    private var speedChipTv: TextView? = null

    /**
     * End-of-video card (video mode only): what plays next, a 5 second ring, Cancel and Play now.
     * It never covers the controls: under the video (normal), at the top (portrait fullscreen) or at the right above the dock (landscape fullscreen).
     * Texts wrap (title 2 lines, then "...") inside the card; nothing can run outside it.
     */
    private fun buildNextCard() {
        nextCard = android.widget.FrameLayout(this).apply {
            visibility = View.GONE; elevation = 8 * resources.displayMetrics.density; isClickable = true
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0xF2181820.toInt()); setStroke(dp(1), 0x669D86FF) }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)) }
        box.addView(TextView(this).apply { text = getString(R.string.o_up_next); textSize = 12f; setTextColor(0xFF9D86FF.toInt()); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; textAlignment = View.TEXT_ALIGNMENT_VIEW_START }, LinearLayout.LayoutParams(-1, -2))
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        nextThumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0xFF2A2840.toInt()) }; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND }
        row.addView(nextThumb, LinearLayout.LayoutParams(dp(96), dp(54)))
        val tc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nextTitle = TextView(this).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START; textDirection = View.TEXT_DIRECTION_FIRST_STRONG }
        nextAuthor = TextView(this).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; textSize = 11f; setTextColor(0xB3FFFFFF.toInt())
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START; textDirection = View.TEXT_DIRECTION_FIRST_STRONG }
        tc.addView(nextTitle, LinearLayout.LayoutParams(-1, -2)); tc.addView(nextAuthor, LinearLayout.LayoutParams(-1, -2))
        row.addView(tc, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        val btns = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fun pill(t: String, fill: Int, ink: Int, click: () -> Unit) = TextView(this).apply {
            text = t; textSize = 13f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(ink); gravity = Gravity.CENTER
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(10), dp(8), dp(10), dp(8)); background = GradientDrawable().apply { cornerRadius = 1000f; setColor(fill) }; setOnClickListener { click() }
        }
        btns.addView(pill(getString(R.string.cancel), Color.WHITE, 0xFF14141C.toInt()) { cancelNext() }, LinearLayout.LayoutParams(0, -2, 1f))
        nextRing = CountdownRing(this)
        btns.addView(nextRing, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(10); marginEnd = dp(10) })
        btns.addView(pill(getString(R.string.o_play_now), 0xFF9D86FF.toInt(), Color.WHITE) { (nextCard.tag as? MediaRef)?.let { cancelNext(); openAuto(it) } }, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(btns, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        nextCard.addView(box, android.widget.FrameLayout.LayoutParams(-1, -2))
    }

    /** puts the card where it cannot cover the dock (see [buildNextCard]) */
    private fun placeNextCard() {
        if (!::nextCard.isInitialized) return
        (nextCard.parent as? android.view.ViewGroup)?.removeView(nextCard)
        val dm = resources.displayMetrics
        when (dfast.app.util.cardSlot(fullscreen, fsPortrait)) {
            dfast.app.util.CardSlot.OVER_VIDEO -> rootView.addView(nextCard, 1, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(8), dp(12), 0) })
            dfast.app.util.CardSlot.TOP -> frameView.addView(nextCard, android.widget.FrameLayout.LayoutParams(minOf(dm.widthPixels - dp(24), dp(460)), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(64) })
            dfast.app.util.CardSlot.RIGHT_ABOVE_DOCK -> frameView.addView(nextCard, android.widget.FrameLayout.LayoutParams(minOf(dm.widthPixels - dp(24), dp(380)), -2, Gravity.BOTTOM or Gravity.RIGHT).apply { rightMargin = dp(24); bottomMargin = dp(130) })
        }
    }

    /** thin ring that fills over 5 seconds, with the seconds left in the middle */
    private class CountdownRing(c: android.content.Context) : View(c) {
        private var frac = 0f; private var num = 5
        private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 3f * c.resources.displayMetrics.density }
        private val txt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = android.graphics.Paint.Align.CENTER; textSize = 15f * c.resources.displayMetrics.density; isFakeBoldText = true }
        fun set(f: Float, n: Int) { frac = f; num = n; invalidate() }
        override fun onDraw(cv: android.graphics.Canvas) {
            val pad = ring.strokeWidth; val r = android.graphics.RectF(pad, pad, width - pad, height - pad)
            ring.color = 0x44FFFFFF; cv.drawOval(r, ring)
            ring.color = 0xFF9D86FF.toInt(); cv.drawArc(r, -90f, 360f * frac, false, ring)
            cv.drawText(num.toString(), width / 2f, height / 2f - (txt.descent() + txt.ascent()) / 2f, txt)
        }
    }

    /** the autoplay switch: solid track + knob, accent when on */
    private class MiniSwitch(c: android.content.Context, private var on: Boolean, private val changed: (Boolean) -> Unit) : View(c) {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        init { setOnClickListener { on = !on; invalidate(); changed(on) }; isClickable = true }
        override fun onDraw(cv: android.graphics.Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            p.color = if (on) 0xFF9D86FF.toInt() else 0xFF5A5A62.toInt(); cv.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, p)
            val r = h / 2 - 3 * resources.displayMetrics.density
            val cx = if (on) w - h / 2 else h / 2
            p.color = Color.WHITE; cv.drawCircle(cx, h / 2, r, p)
        }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    /** the next screen of the chain keeps the history, and keeps fullscreen if this one was fullscreen */
    private fun navIntent(r: MediaRef, q: List<MediaRef>): Intent = chainIntent(this, r, q).apply { if (fullscreen && !audioOnly) putExtra("fs", if (fsPortrait) 1 else 2) }

    // --- shuffle / repeat (one place, both the dock and the audio disc follow it) ---
    private fun toggleShuffle() { shuffleOn = !shuffleOn; if (::disc.isInitialized) disc.shuffleOn = shuffleOn; if (::dock.isInitialized) dock.shuffleOn = shuffleOn }
    private fun cycleRepeat() {
        repeatIdx = (repeatIdx + 1) % 3
        if (::disc.isInitialized) disc.repeatMode = repeatIdx
        if (::dock.isInitialized) dock.repeatIdx = repeatIdx
        activePlayer?.repeatMode = when (repeatIdx) { 1 -> Player.REPEAT_MODE_ALL; 2 -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
    }
    private fun togglePlayPause() {
        val p = activePlayer ?: return
        if (p.playbackState == Player.STATE_ENDED) p.seekToDefaultPosition()
        dfast.app.util.PlayQueue.togglePlay(p)
    }

    // --- fullscreen: follows the video (portrait video = portrait, otherwise landscape) ---
    private lateinit var rootView: LinearLayout
    private lateinit var frameView: android.widget.FrameLayout
    private lateinit var scrollView: ScrollView
    private lateinit var dock: PlayerDock
    private lateinit var audioBack: ImageButton
    private lateinit var cover: ImageView
    private val hideCover = Runnable { if (::cover.isInitialized) cover.visibility = View.GONE }
    private fun showCover() {
        if (!::cover.isInitialized || ref.thumb.isBlank()) return
        cover.loadThumbnail(false, ref.thumb); cover.visibility = View.VISIBLE
        handler.removeCallbacks(hideCover); handler.postDelayed(hideCover, 3000)
    }
    private var fullscreen = false
    private var fsPortrait = false
    /** true when the button chose the orientation (locked); false when the phone was simply turned sideways */
    private var fsLocked = false
    private var userExitedFs = false

    /** height of the video on the normal screen: 16:9 on the width in portrait, half the screen when the phone is sideways */
    private fun inlineH(): Int {
        val dm = resources.displayMetrics
        return if (dm.widthPixels > dm.heightPixels) dm.heightPixels / 2 else dm.widthPixels * 9 / 16
    }

    private fun setH(v: View, h: Int) { val lp = v.layoutParams ?: return; lp.height = h; v.layoutParams = lp }

    private fun applyFrameSize() {
        val fh = if (fullscreen && !audioOnly) android.view.ViewGroup.LayoutParams.MATCH_PARENT else inlineH()
        setH(frameView, if (fullscreen && !audioOnly) android.view.ViewGroup.LayoutParams.MATCH_PARENT else android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        setH(playerView, fh)
        setH(failBox, fh)
        if (::cover.isInitialized) setH(cover, fh)
        scrollView.visibility = if (fullscreen && !audioOnly) View.GONE else View.VISIBLE
        placeSpinner()
    }

    private fun enterFullscreen(auto: Boolean, forced: Int = 0) {
        if (audioOnly || fullscreen || !::frameView.isInitialized) return
        val vs = activePlayer?.videoSize
        fsPortrait = if (forced != 0) forced == 1 else vs != null && dfast.app.util.fullscreenPortrait(vs.width, vs.height, vs.unappliedRotationDegrees)
        fullscreen = true
        fsLocked = !auto
        if (!auto) requestedOrientation = if (fsPortrait) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        dock.fullscreen = true
        applyFrameSize()
        if (::nextCard.isInitialized && nextCard.visibility == View.VISIBLE) placeNextCard()
    }

    private fun exitFullscreen(user: Boolean) {
        if (!fullscreen) return
        fullscreen = false
        val sideways = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (user && sideways) userExitedFs = true
        fsLocked = false
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        dock.fullscreen = false
        applyFrameSize()
        if (::nextCard.isInitialized && nextCard.visibility == View.VISIBLE) placeNextCard()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::frameView.isInitialized) return
        if (newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            if (!fullscreen && !audioOnly && !userExitedFs) enterFullscreen(true)
        } else {
            userExitedFs = false
            if (fullscreen && !fsLocked) exitFullscreen(false)
        }
        if (!fullscreen) repaint()
        applyFrameSize()
    }
    private val activePlayer: Player? get() = ownPlayer ?: PlaybackService.player

    companion object {
        /** every open player screen: a new one closes the others, so Back always closes the player once, never walks through played tracks */
        private val live = ArrayList<java.lang.ref.WeakReference<PlayerActivity>>()
        val history = dfast.app.util.PlayHistory<MediaRef>({ it.url })
        /** opening from inside the player screen keeps the history; any other entry point starts a fresh one */
        fun chainIntent(c: android.content.Context, ref: MediaRef, queue: List<MediaRef> = emptyList()): Intent = intent(c, ref, queue).putExtra("chain", true)
        fun intent(c: android.content.Context, ref: MediaRef, queue: List<MediaRef> = emptyList(), result: ResultItem? = null): Intent =
            Intent(c, PlayerActivity::class.java).putExtra("url", ref.url).putExtra("title", ref.title).putExtra("author", ref.author)
                .putExtra("thumb", ref.thumb).putExtra("duration", ref.duration).putExtra("audio", ref.audio)
                .putExtra("queue", org.json.JSONArray().also { a -> queue.forEach { a.put(it.toJson()) } }.toString())
                .apply { if (result != null) putExtra("result", result) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtil.updateTheme(this)
        super.onCreate(savedInstanceState)
        // no keyboard on the player screen, and nothing resizes or pans when one tries to appear
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        live.removeAll { it.get() == null || it.get() === this }
        live.forEach { it.get()?.takeIf { o -> !o.isFinishing }?.finish() }
        live.clear(); live.add(java.lang.ref.WeakReference(this))
        val i = intent
        ref = MediaRef(i.getStringExtra("url").orEmpty(), i.getStringExtra("title").orEmpty(), i.getStringExtra("author").orEmpty(),
            i.getStringExtra("thumb").orEmpty(), i.getStringExtra("duration").orEmpty(), i.getBooleanExtra("audio", false))
        audioOnly = ref.audio
        openedUrl = ref.url
        resultItem = if (android.os.Build.VERSION.SDK_INT >= 33) i.getParcelableExtra("result", ResultItem::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra("result")
        queueRefs = try { org.json.JSONArray(i.getStringExtra("queue") ?: "[]").let { a -> (0 until a.length()).map { MediaRef.fromJson(a.getJSONObject(it)) } } } catch (e: Exception) { emptyList() }
        if (!i.getBooleanExtra("chain", false)) history.clear()
        PlayStore.addRecent(this, ref)
        dfast.app.util.AutoplayState.played(ref.url)
        dfast.app.util.AutoplayState.playedMeta(ref.author, ref.title)
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
        rootView = root
        val frame = android.widget.FrameLayout(this)
        frameView = frame
        val h = inlineH()
        // our own controls (PlayerDock) replace the stock ones; the stock controller is off
        playerView = PlayerView(this).apply { setBackgroundColor(Color.BLACK); useController = false }
        frame.addView(playerView, android.widget.FrameLayout.LayoutParams(-1, h))
        // while the picture comes back after Audio only -> Video, show the poster instead of a black rectangle
        cover = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.BLACK); visibility = View.GONE }
        frame.addView(cover, android.widget.FrameLayout.LayoutParams(-1, h))
        dock = PlayerDock(this).apply {
            playerProvider = { activePlayer }
            onPrev = { goPrev() }; onNext = { goNext() }
            onShuffle = { toggleShuffle() }; onRepeat = { cycleRepeat() }
            onPlayPause = { togglePlayPause() }
            onSettings = { a -> showSettingsPanel(a) }
            onBack = { if (this@PlayerActivity.fullscreen) exitFullscreen(true) else finish() }
            onDownload = { chooseDownload() }
            onFavorite = { toggleFav() }
            onPlaylist = { addToPlaylist() }
            onShare = { dfast.app.util.ShareLink.share(this@PlayerActivity, ref.url, ref.title) }
            infoProvider = { Triple(titleTv.text.toString(), ref.author, PlayStore.isFavorite(this@PlayerActivity, ref.url)) }
            onFullscreen = { if (this@PlayerActivity.fullscreen) exitFullscreen(true) else enterFullscreen(false) }
            canPrev = { this@PlayerActivity.canPrev() }
            rtl = { resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL }
        }
        frame.addView(dock, android.widget.FrameLayout.LayoutParams(-1, -1))
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
        buildNextCard()
        disc = DiscView(this).apply { visibility = View.GONE; playerProvider = { activePlayer }; setThumb(ref.thumb); shownArt = ref.thumb; accent = 0xFF9D86FF.toInt()
            onNext = { goNext() }; onPrev = { goPrev() }
            onShuffle = { toggleShuffle() }
            onRepeat = { cycleRepeat() }
        }
        frame.addView(disc, android.widget.FrameLayout.LayoutParams(-1, h))
        spinner = android.widget.ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
        }
        frame.addView(spinner, android.widget.FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        audioBack = ImageButton(this).apply {
            setImageResource(R.drawable.ic_pd_chevron_down); setColorFilter(Color.WHITE)
            background = null; setPadding(dp(10), dp(10), dp(10), dp(10)); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.o_back); setOnClickListener { if (fullscreen) exitFullscreen(true) else finish() }; dfast.app.util.Press.icon(this)
        }
        // the big round back button is only for the audio view; the video controls have their own small chevron
        frame.addView(audioBack, android.widget.FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START).apply { marginStart = dp(8); topMargin = dp(8) })
        root.addView(frame, LinearLayout.LayoutParams(-1, -2))
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (fullscreen) exitFullscreen(true) else { isEnabled = false; onBackPressedDispatcher.onBackPressed() } }
        })

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
                text = label; textSize = 10f; gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
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
        shareBtn = action(R.drawable.baseline_share_24, getString(R.string.share), false) { dfast.app.util.ShareLink.share(this, ref.url, ref.title) }
        updateShare()
        col.addView(actions)

        val chips = LinearLayout(this).apply { setPadding(dp(14), dp(6), dp(14), dp(4)) }
        val videoChip = chip(getString(R.string.o_video), true); val audioChip = chip(getString(R.string.o_audio_only), false); val speedChip = chip("1x", false)
        speedChipTv = speedChip
        fun paintChips() {
            for ((c, on) in listOf(videoChip to !audioOnly, audioChip to audioOnly)) {
                c.setTextColor(if (on) OrbitColors.pillText(this) else OrbitColors.ink(this))
                c.background = GradientDrawable().apply { cornerRadius = 1000f; setColor(if (on) OrbitColors.pill(this@PlayerActivity) else OrbitColors.glass2(this@PlayerActivity)) }
            }
            disc.visibility = if (audioOnly) View.VISIBLE else View.GONE
            disc.layoutParams = android.widget.FrameLayout.LayoutParams(-1, if (audioOnly) (resources.displayMetrics.widthPixels * 1.2f).toInt() else inlineH())
            dock.visibility = if (audioOnly) View.GONE else View.VISIBLE
            if (::audioBack.isInitialized) audioBack.visibility = if (audioOnly) View.VISIBLE else View.GONE
            if (audioOnly) disc.invalidate()
            updateSpinner()
            placeSpinner()
            activePlayer?.let { p -> p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly).build() }
        }
        videoChip.setOnClickListener { val was = audioOnly; audioOnly = false; paintChips(); if (was) showCover(); frameRendered = false; handler.removeCallbacks(pictureCheck); handler.postDelayed(pictureCheck, 1500) }
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

        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(2))
            addView(TextView(this@PlayerActivity).apply {
                text = getString(R.string.o_related); textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(OrbitColors.ink(this@PlayerActivity))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@PlayerActivity).apply {
                text = getString(R.string.o_autoplay); textSize = 12f; setTextColor(OrbitColors.inkSoft(this@PlayerActivity))
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            addView(MiniSwitch(this@PlayerActivity, autoplayOn()) { on ->
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(this@PlayerActivity).edit().putBoolean("autoplay_next", on).apply()
                if (!on) cancelNext()
            }.apply { contentDescription = getString(R.string.o_autoplay) }, LinearLayout.LayoutParams(dp(44), dp(26)))
        })
        val relBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(relBox)
        scrollView = ScrollView(this).apply { addView(col); setOnScrollChangeListener { _, _, y, _, oy -> if (y != oy && ::nextCard.isInitialized && nextCard.visibility == View.VISIBLE) cancelNext() } }
        root.addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(0, b.top, 0, b.bottom); insets
        }

        lifecycleScope.launch { startPlayback() }
        // opened while the phone is already sideways: go straight to the fullscreen view
        val carriedFs = i.getIntExtra("fs", 0)
        if (carriedFs != 0 && !audioOnly) root.post { enterFullscreen(false, carriedFs) }
        else if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && !audioOnly) root.post { enterFullscreen(true) }
        relBoxView = relBox
        loadYtRelated()
        relatedJob = lifecycleScope.launch { loadRelated(relBox) }
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
        StartupTrace.begin(if (stage == 0) "open" else "open-s$stage")
        if (stage > 0) StartupTrace.mark("reason", lastReason)
        // the previous song must not keep playing under the new song's picture and title
        PlaybackService.player?.pause()
        failBox.post { hideFailure() }
        if (!audioOnly && stage == 0 && seekOnReady == 0L && ref.url.startsWith("http")) {
            dfast.app.util.ResumeStore.get(this, ref.url)?.let { seekOnReady = dfast.app.util.ResumeStore.resumeAt(it.posMs) }
        }
        if (!forceYtdlp && !audioOnly && StreamResolver.newPipeBad(ref.url)) forceYtdlp = true // NewPipe links of this video did not play before: straight to yt-dlp
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
                    ?: StreamResolver.resolve(this, ref.url, false, false, skipNewPipe = forceYtdlp, progressive = progressiveMode)).filter { it.isNotBlank() }
                if (urls.isEmpty()) throw Exception("no stream")
                // streams also play through the service so the system media card and background audio work
                StartupTrace.mark("play-sent")
                loadQualities(); preResolveQualities()
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
                giveUp("resolve-failed")
            }
        }
    }

    private var relBoxView: LinearLayout? = null
    private var relatedJob: kotlinx.coroutines.Job? = null
    private var relatedFor = ""

    /** the Related list follows the video that is playing: new video, new list (never a static one) */
    private fun refreshRelated(url: String) {
        val box = relBoxView ?: return
        if (url == relatedFor || !url.startsWith("http")) return
        relatedJob?.cancel(); prefetchedFor = ""
        loadYtRelated(url)
        relatedJob = lifecycleScope.launch { box.removeAllViews(); loadRelated(box) }
    }

    private suspend fun loadRelated(box: LinearLayout) {
        relatedFor = ref.url
        val yt = kotlinx.coroutines.withTimeoutOrNull(12000) { ytRelated?.await() }.orEmpty()
            .filter { it.url != ref.url && !it.live && it.title.isNotBlank() && !dfast.app.util.AutoplayPick.nearDuplicate(it.title, ref.title) }
            .let { l -> dfast.app.util.AutoplayPick.dedupe(l) { it.title } }
            .let { l -> dfast.app.util.AutoplayPick.rankLongFormLast(l) { dfast.app.util.AutoplayPick.longForm(it, null) } }.take(20)
            .map { MediaRef(it.url, it.title, it.author, it.thumb, if (it.durationSec > 0) fmtDurSec(it.durationSec) else "") }
        val results = if (yt.isNotEmpty()) emptyList() else withContext(Dispatchers.IO) { DBManager.getInstance(this@PlayerActivity).resultDao.getAll() }
            .map { MediaRef(it.url, it.title, it.author, it.thumb, it.duration) }
        // a YouTube video shows YouTube's related only: the old fallback listed the queue/feed/recents under "related", which is what looked unrelated
        val pool = if (yt.isNotEmpty()) yt else if (ref.url.isYoutubeURL()) emptyList() else (queueRefs + results + PlayStore.recents(this)).filter { it.url != ref.url }.distinctBy { it.url }.take(14)
        if (pool.isEmpty()) { box.addView(TextView(this).apply { text = getString(R.string.o_no_related); setTextColor(OrbitColors.inkSoft(this@PlayerActivity)); setPadding(dp(16), dp(4), dp(16), 0) }); return }
        relatedPool = pool
        val dl: Set<String> = withContext(Dispatchers.IO) {
            runCatching { DBManager.getInstance(this@PlayerActivity).historyDao.getAllHistoryList().map { dfast.app.util.UrlIds.of(it.url) }.filter { it.isNotEmpty() }.toHashSet() }.getOrDefault(emptySet<String>())
        }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sameCh = pool.filter { it.author.isNotBlank() && it.author == ref.author }
        val otherCh = pool.filter { it.author.isBlank() || it.author != ref.author }
        var mode = "all"
        fun fill() {
            rows.removeAllViews()
            val shown = when (mode) { "same" -> sameCh; "other" -> otherCh; else -> pool }
            shown.forEach { rows.addView(relatedRow(it, dl)) }
        }
        if (sameCh.isNotEmpty() && otherCh.isNotEmpty()) {
            val chipsRow = LinearLayout(this).apply { setPadding(dp(14), dp(2), dp(14), dp(4)) }
            fun paint() {
                chipsRow.removeAllViews()
                listOf("all" to getString(R.string.o_chip_all), "same" to getString(R.string.o_rel_same), "other" to getString(R.string.o_rel_other)).forEach { (k, label) ->
                    val on = k == mode
                    chipsRow.addView(chip(label, on).apply {
                        setTextColor(if (on) OrbitColors.pillText(this@PlayerActivity) else OrbitColors.ink(this@PlayerActivity))
                        background = GradientDrawable().apply { cornerRadius = 1000f; setColor(if (on) OrbitColors.pill(this@PlayerActivity) else OrbitColors.glass2(this@PlayerActivity)) }
                        setOnClickListener { mode = k; paint(); fill() }
                    })
                }
            }
            paint()
            box.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chipsRow) })
        }
        box.addView(rows)
        fill()
    }

    private fun relatedRow(r: MediaRef, downloaded: Set<String>): View {
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
        tc.addView(TextView(this).apply {
            text = r.author; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; textSize = 11f; setTextColor(OrbitColors.inkSoft(this@PlayerActivity))
            // already downloaded or saved on this phone: a small check before the channel name
            if (dfast.app.util.UrlIds.of(r.url).let { it.isNotEmpty() && it in downloaded }) {
                val d = androidx.core.content.ContextCompat.getDrawable(this@PlayerActivity, R.drawable.ic_o_check)?.mutate()
                d?.setBounds(0, 0, dp(13), dp(13)); d?.setTint(0xFF9D86FF.toInt())
                setCompoundDrawablesRelative(d, null, null, null); compoundDrawablePadding = dp(4)
            }
        })
        row.addView(tc, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        row.setOnClickListener {
            history.push(ref)
            startActivity(navIntent(r.copy(audio = audioOnly), queueRefs))
            finish()
        }
        return row
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
                startActivity(Intent(this, dfast.app.receiver.ShareActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra("inapp", true)
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
        handler.removeCallbacks(tick); handler.postDelayed(tick, 1500)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(tick)
        releaseHold()
        cancelNext()
        ownPlayer?.pause()
    }

    override fun onDestroy() {
        live.removeAll { it.get() == null || it.get() === this }
        releaseHold()
        qPopup?.dismiss()
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

/** which screen currently owns the pause-at-end flag on the shared player, so a newer screen's flag is never reset by an older one */
private object HoldOwner { @Volatile var token: Any? = null }
