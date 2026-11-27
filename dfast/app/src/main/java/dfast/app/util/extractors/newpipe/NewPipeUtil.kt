package dfast.app.util.extractors.newpipe

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceManager
import dfast.app.database.models.ChapterItem
import dfast.app.database.models.Format
import dfast.app.database.models.ResultItem
import dfast.app.database.viewmodel.ResultViewModel
import dfast.app.util.LocaleUtil
import dfast.app.util.Extensions.getIDFromYoutubeURL
import dfast.app.util.Extensions.toStringDuration
import dfast.app.util.extractors.newpipe.potoken.NewPipePoTokenGenerator
import com.google.gson.Gson
import okhttp3.OkHttpClient
import org.json.JSONException
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

class NewPipeUtil(context: Context) {
    companion object {
        private class Cached(val info: StreamInfo, val at: Long)
        private val infoCache = java.util.concurrent.ConcurrentHashMap<String, Cached>()
        private val infoLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        private const val INFO_TTL_MS = 10 * 60_000L

        /**
         * StreamInfo.getInfo is one network round trip (2-3 s). The download sheet (formats), the player (stream urls)
         * and the details read the same data, so it is fetched once per link and kept for 10 minutes.
         * Two callers asking at the same moment share one request. Failures are never cached.
         */
        fun cachedInfo(url: String): StreamInfo {
            val now = android.os.SystemClock.elapsedRealtime()
            infoCache[url]?.let { if (now - it.at < INFO_TTL_MS) return it.info }
            val lock = infoLocks.getOrPut(url) { Any() }
            synchronized(lock) {
                infoCache[url]?.let { if (android.os.SystemClock.elapsedRealtime() - it.at < INFO_TTL_MS) return it.info }
                val info = StreamInfo.getInfo(url)
                if (infoCache.size > 40) infoCache.clear()
                infoCache[url] = Cached(info, android.os.SystemClock.elapsedRealtime())
                return info
            }
        }
        fun forgetInfo(url: String) { infoCache.remove(url) }
    }

    private var sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val countryCode = sharedPreferences.getString("locale", "")!!.ifEmpty { "US" }
    private val language = LocaleUtil.currentLocale().language
    private val useAppLanguageForMetadata = sharedPreferences.getBoolean("use_app_language_for_metadata", false)

    init {
        if (useAppLanguageForMetadata) {
            NewPipe.init(NewPipeDownloaderImpl(OkHttpClient.Builder()),  Localization(language, countryCode))
        } else {
            NewPipe.init(NewPipeDownloaderImpl(OkHttpClient.Builder()))
        }

        YoutubeStreamExtractor.setPoTokenProvider(NewPipePoTokenGenerator())
    }

    fun getVideoData(url : String, withFormats: Boolean = false) : Result<List<ResultItem>> {
        try {
            val streamInfo = cachedInfo(url)
            val vid = createVideoFromStream(streamInfo, url, withFormats) ?: return Result.failure(Throwable())
            return Result.success(listOf(vid))
        }catch (e: Exception) {
            return Result.failure(e)
        }
    }

    fun getFormats(url: String) : Result<List<Format> > {
        try {
            val streamInfo = cachedInfo(url)
            val vid = createVideoFromStream(streamInfo, url, true)
            return Result.success(vid!!.formats)
        }catch(e: Exception) {
            println(e)
            if (e is CancellationException) throw e
            return Result.failure(e)
        }
    }

    fun getFormatsForAll(urls: List<String>, progress: (progress: ResultViewModel.MultipleFormatProgress) -> Unit) : Result<MutableList<MutableList<Format>>> {
        return kotlin.runCatching {
            val formatCollection = mutableListOf<MutableList<Format>>()
            urls.forEach { url ->
                val streamInfo = StreamInfo.getInfo(url)
                createVideoFromStream(streamInfo, url, true).apply {
                    if (this!!.formats.isEmpty()) return Result.failure(Throwable())
                    formatCollection.add(this.formats.toMutableList())
                    progress(ResultViewModel.MultipleFormatProgress(url, this.formats))
                }
            }
            return Result.success(formatCollection)
        }.onFailure {
            return Result.failure(it)
        }
    }

    @Throws(JSONException::class)
    fun search(query: String): Result<ArrayList<ResultItem>> {
        try {
            val items = arrayListOf<ResultItem>()
            val res = SearchInfo.getInfo(NewPipe.getService(ServiceList.YouTube.serviceId),
                NewPipe.getService(ServiceList.YouTube.serviceId)
                    .searchQHFactory
                    .fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), ""))

            if (res.relatedItems.isEmpty()) return Result.failure(Throwable())

            for (i in 0 until res.relatedItems.size) {
                val element = res.relatedItems[i]
                if (element is StreamInfoItem) {
                    if (element.duration <= 0) continue
                    val v = createVideoFromStreamInfoItem(element, element.url) ?: continue
                    items.add(v)
                }
            }
            return Result.success(items)

        }catch (e: Exception){
            return Result.failure(e)
        }
    }

    @Throws(JSONException::class)
    fun searchMusic(query: String): Result<ArrayList<ResultItem>> {
        try {
            val items = arrayListOf<ResultItem>()
            val res = SearchInfo.getInfo(NewPipe.getService(ServiceList.YouTube.serviceId),
                NewPipe.getService(ServiceList.YouTube.serviceId)
                    .searchQHFactory
                    .fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), ""))
            if (res.relatedItems.isEmpty()) return Result.failure(Throwable())

            for (i in 0 until res.relatedItems.size) {
                val element = res.relatedItems[i]
                if (element is StreamInfoItem) {
                    if (element.duration <= 0) continue
                    val v = createVideoFromStreamInfoItem(element, element.url) ?: continue
                    items.add(v)
                }
            }
            return Result.success(items)

        }catch (e: Exception){
            return Result.failure(e)
        }
    }

    /** Every video stream NewPipe lists that has a playable url (muxed and video-only), no bitrate filter, so nothing is hidden from the quality list. */
    private fun rawVideoStreams(info: StreamInfo): List<org.schabi.newpipe.extractor.stream.VideoStream> =
        (info.videoOnlyStreams + info.videoStreams).filter { runCatching { !it.content.isNullOrBlank() }.getOrDefault(false) }

    private fun labelOf(v: org.schabi.newpipe.extractor.stream.VideoStream) = dfast.app.util.QualityLabels.standard(v.height, runCatching { v.quality }.getOrNull() ?: runCatching { v.resolution }.getOrNull())

    /** Standard quality labels (144, 240 ... 2160) this video offers, lowest first. Every stream seen is written to the log (tag DFastQuality) so a short list can be explained. */
    fun videoHeights(url: String): List<Int> = runCatching {
        val info = cachedInfo(url)
        val raw = rawVideoStreams(info)
        runCatching {
            android.util.Log.d("DFastQuality", "hls master: ${if (info.hlsUrl.isNullOrBlank() || info.hlsUrl == "null") "none" else "present"} (dash: ${if (info.dashMpdUrl.isNullOrBlank()) "none" else "present"})")
            android.util.Log.d("DFastQuality", "newpipe streams: video-only=${info.videoOnlyStreams.size} muxed=${info.videoStreams.size} withUrl=${raw.size}")
            (info.videoOnlyStreams + info.videoStreams).forEach { v -> android.util.Log.d("DFastQuality", "  itag=${v.itag} ${v.width}x${v.height} q=${v.quality} codec=${v.codec} bitrate=${v.bitrate} url=${!v.content.isNullOrBlank()} -> label ${labelOf(v)}") }
        }
        dfast.app.util.QualityLabels.ascending(raw.map { labelOf(it) })
    }.getOrDefault(emptyList())

    /** maxHeight = 0: the normal pick (h264 up to 720p). maxHeight > 0: exactly that height, h264 first; fails if the video has none. */
    fun getStreamingUrlAndChapters(url: String, audioOnly: Boolean = false, exactHeight: Int = 0) : Result<Pair<List<String>, List<ChapterItem>?>> {
        try {
            val streamInfo = cachedInfo(url)
            val item = createVideoFromStream(streamInfo, url, ignoreFormatPreference = true)!!

            val withUrl = item.formats.filter { !it.url.isNullOrBlank() }
            val videos = withUrl.filter { it.vcodec.isNotBlank() && it.vcodec != "none" }
            // this is only for previews, so stay at or under 720p instead of streaming the best quality
            // h264 first: every phone decodes it. AV1/VP9 can leave a black picture with sound on phones without that decoder.
            val inRange = videos.filter { (it.height ?: 0) in 1..720 }
            val h264 = inRange.filter { it.vcodec.startsWith("avc", true) || it.vcodec.contains("h264", true) }
            val videoURL = if (exactHeight > 0) {
                val at = rawVideoStreams(streamInfo).filter { labelOf(it) == exactHeight }
                    .sortedWith(compareByDescending<org.schabi.newpipe.extractor.stream.VideoStream> { it.isVideoOnly }.thenByDescending { it.bitrate })
                (at.firstOrNull { (it.codec ?: "").startsWith("avc", true) || (it.codec ?: "").contains("h264", true) } ?: at.firstOrNull())?.content
                    ?: return Result.failure(Throwable("No stream at ${exactHeight}p"))
            } else ((if (h264.isNotEmpty()) h264 else inRange).maxByOrNull { it.height ?: 0 } ?: videos.minByOrNull { it.height ?: Int.MAX_VALUE })?.url
            val audioURL = withUrl.firstOrNull { it.vcodec.isBlank() || it.vcodec == "none" }?.url

            // audio only: just the audio stream (same one as before); a video url is used only if there is no audio stream at all
            // video + sound: a video-only link alone plays a silent picture. If NewPipe found no audio stream, fail so the
            // caller asks yt-dlp (which returns the real video+audio pair) instead of playing without sound.
            if (!audioOnly && audioURL == null && videoURL != null && streamInfo.videoStreams.none { !it.isVideoOnly && it.content == videoURL }) {
                return Result.failure(Throwable("No audio stream"))
            }
            val urls = if (audioOnly) listOfNotNull(audioURL ?: videoURL) else listOfNotNull(videoURL, audioURL)
            if (urls.isEmpty()) return Result.failure(Throwable("No streams found"))
            val chapters = item.chapters
            return Result.success(Pair(urls, chapters))
        }catch (e: Exception) {
            return Result.failure(e)
        }
    }

    suspend fun getChannelData(url: String, progress: suspend (pagedResults: MutableList<ResultItem>) -> Unit) : Result<List<ResultItem>> {
        try {
            //return Result.failure(Throwable())
            val req = ChannelInfo.getInfo(ServiceList.YouTube, url)
            println(Gson().toJson(req))
            val items = mutableListOf<ResultItem>()
            for (tab in req.tabs) {
                if (listOf("videos", "shorts", "livestreams").contains(tab.contentFilters[0])) {
                    val tabInfo = ChannelTabInfo.getInfo(ServiceList.YouTube, tab)
                    val tmp = getChannelTabData(tab, tabInfo, req.name, "${url}/${tabInfo.url.split("/").last()}") {
                        progress(it)
                    }
                    if (tmp.isFailure) continue
                    else items.addAll(tmp.getOrNull()!!)
                }
            }
            return Result.success(items)
        }catch (e: Exception) {
            return Result.failure(e)
        }
    }

    private suspend fun getChannelTabData(linkHandler: ListLinkHandler, tabInfo: ChannelTabInfo, channelName: String, playlistURL: String, progress: suspend (pagedResults: MutableList<ResultItem>) -> Unit) : Result<List<ResultItem>> {
        try {
            val totalItems = mutableListOf<ResultItem>()
            var nextPage : Page? = null
            var playlistName = ""

            while (true) {
                val items = mutableListOf<ResultItem>()
                val req = if (nextPage == null) {
                    if (tabInfo.hasNextPage()) {
                        nextPage = tabInfo.nextPage
                    }
                    playlistName = "$channelName - ${tabInfo.name}"
                    tabInfo.relatedItems.toList()
                } else {
                    val tmp = ChannelTabInfo.getMoreItems(ServiceList.YouTube, linkHandler, nextPage)
                    nextPage = if (tmp.hasNextPage()) tmp.nextPage else null
                    tmp.items.toList()
                }

                if (req.isEmpty()) return Result.failure(Throwable())

                for (element in req) {
                    if (element is StreamInfoItem) {
                        if (element.duration <= 0) continue
                        val v = createVideoFromStreamInfoItem(element, element.url) ?: continue
                        v.apply {
                            playlistTitle = playlistName
                            this.playlistURL = playlistURL
                            items.add(this)
                        }
                    }
                }

                totalItems.addAll(items)
                progress(items)
                if (nextPage == null || items.isEmpty()) break
            }

            return Result.success(totalItems)
        }catch (e: Exception) {
            return Result.failure(e)
        }
    }

    suspend fun getPlaylistData(playlistURL: String, progress: suspend (pagedResults: MutableList<ResultItem>) -> Unit) : Result<List<ResultItem>> {
        try {
            val totalItems = mutableListOf<ResultItem>()
            var nextPage : Page? = null
            var playlistName = ""

            while (true) {
                val items = mutableListOf<ResultItem>()
                val req = if (nextPage == null) {
                    val tmp = PlaylistInfo.getInfo(ServiceList.YouTube, playlistURL)
                    if (tmp.hasNextPage()) {
                        nextPage = tmp.nextPage
                    }
                    playlistName = tmp.name
                    tmp.relatedItems.toList()
                } else {
                    val tmp = PlaylistInfo.getMoreItems(ServiceList.YouTube, playlistURL, nextPage)
                    nextPage = if (tmp.hasNextPage()) tmp.nextPage else null
                    tmp.items.toList()
                }

                for (element in req) {
                    if (element is StreamInfoItem) {
                        if (element.duration <= 0) continue
                        val v = createVideoFromStreamInfoItem(element, element.url) ?: continue
                        v.apply {
                            playlistTitle = playlistName
                            this.playlistURL = playlistURL
                            items.add(this)
                        }
                    }
                }

                totalItems.addAll(items)
                progress(items)
                if (nextPage == null || items.isEmpty()) break
            }

            return Result.success(totalItems)
        }catch (e: Exception) {
            return Result.failure(e)
        }
    }


    fun getTrending(): ArrayList<ResultItem> {
        try {
            val items = arrayListOf<ResultItem>()
            val kioskList = NewPipe.getService(ServiceList.YouTube.serviceId).kioskList
            kioskList.forceContentCountry(ContentCountry(countryCode))

            val extractor = kioskList.getExtractorById("trending_music", null)
            extractor.fetchPage()

            val info = KioskInfo.getInfo(extractor)
            if (info.relatedItems.isEmpty()) return arrayListOf()

            for (i in 0 until info.relatedItems.size) {
                val element = info.relatedItems[i]
                if (element is StreamInfoItem) {
                    if (element.duration <= 0) continue
                    val v = createVideoFromStreamInfoItem(element, element.url) ?: continue
                    items.add(v)
                }
            }

            return items
        }catch (err: Exception) {
            return arrayListOf()
        }
    }

    private fun createVideoFromStreamInfoItem(stream: StreamInfoItem, url: String) : ResultItem? {
        var video: ResultItem? = null
        try {
            val id = url.getIDFromYoutubeURL()
            val title = stream.name
            val author = stream.uploaderName.removeSuffix(" - Topic")
            val duration = stream.duration.toInt().toStringDuration(Locale.US)
            val thumb = "https://i.ytimg.com/vi/$id/hqdefault.jpg"

            video = ResultItem(0,
                url,
                title,
                author,
                duration,
                thumb,
                "youtube",
                "",
                ArrayList(),
                "",
                ArrayList()
            )

        } catch (e: Exception) {
            Log.e("NewPipeUtil", e.toString())
        }
        return video
    }

    private fun createVideoFromStream(stream: StreamInfo, url: String, ignoreFormatPreference : Boolean = false): ResultItem? {
        var video: ResultItem? = null
        try {
            val id = url.getIDFromYoutubeURL()
            val title = stream.name
            val author = stream.uploaderName.removeSuffix(" - Topic")
            val duration = stream.duration.toInt().toStringDuration(Locale.US)
            val thumb = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
            val formats : ArrayList<Format> = ArrayList()


            if(sharedPreferences.getString("formats_source", "yt-dlp") == "newpipe" || ignoreFormatPreference){
                if (stream.audioStreams.isNotEmpty()){
                    stream.audioStreams = stream.audioStreams.sortedByDescending { it.bitrate }
                    for (f in 0 until stream.audioStreams.size){
                        val it = stream.audioStreams[f]
                        if (it.bitrate == 0 || listOf(599, 600).contains(it.itag)) continue

                        val formatObj = Format(
                            format_id = it.itag.toString(),
                            container = it.format!!.name,
                            acodec = it.codec,
                            filesize = it.itagItem!!.contentLength,
                            format_note = (it.audioTrackName ?: (it.itagItem?.getResolutionString() ?: ((it.bitrate / 1000).toString() + "kbps"))) + " Audio",
                            lang = it.audioLocale?.language,
                            asr = it.itagItem!!.sampleRate.toString(),
                            url = it.content,
                            tbr = (it.bitrate / 1000).toString() + "k",
                        )

                        formats.add(formatObj)
                    }

                    val hasDefaultFormat = formats.firstOrNull { it.format_note.contains("ORIGINAL", true) }
                    if (hasDefaultFormat != null) {
                        formats.remove(hasDefaultFormat)
                        formats.add(0, hasDefaultFormat)
                    }
                }

                if (stream.videoOnlyStreams.isNotEmpty()){
                    for (f in 0 until stream.videoOnlyStreams.size){
                        val it = stream.videoOnlyStreams[f]
                        if (it.bitrate == 0) continue

                        val formatObj = Format(
                            format_id = it.itag.toString(),
                            container = it.format!!.name,
                            vcodec = it.codec,
                            format_note = it.itagItem!!.getResolutionString() ?: it.quality,
                            filesize = it.itagItem!!.contentLength,
                            url = it.content,
                            tbr = (it.bitrate / 1000).toString() + "k",
                            _width = it.width.toString(),
                            _height = it.height.toString()
                        )
                        formats.add(formatObj)
                    }
                }

                if (stream.videoStreams.isNotEmpty()){
                    for (f in 0 until stream.videoStreams.size){
                        val it = stream.videoStreams[f]
                        if (it.bitrate == 0) continue

                        val formatObj = Format(
                            format_id = it.itag.toString(),
                            container = it.format!!.name,
                            vcodec = it.codec,
                            format_note = it.itagItem!!.getResolutionString() ?: it.quality,
                            filesize = it.itagItem!!.contentLength,
                            url = it.content,
                            tbr = (it.bitrate / 1000).toString() + "k",
                            _width = it.width.toString(),
                            _height = it.height.toString()
                        )
                        formats.add(formatObj)
                    }
                }


                formats.groupBy { it.format_id }.forEach {
                    if (it.value.count() > 1) {
                        it.value.filter { f-> !f.format_note.contains("original", true) }.forEachIndexed { index, format -> format.format_id = format.format_id.split("-")[0] + "-${index}" }
                        val defaultLang = it.value.find { f -> f.format_note.contains("original", true) }
                        defaultLang?.format_id = (defaultLang?.format_id?.split("-")?.get(0) ?: "") + "-${it.value.size-1}"
                    }
                }
            }

            val chapters = ArrayList<ChapterItem>()
            if (stream.streamSegments.isNotEmpty()){
                for (c in 0 until stream.streamSegments.size){
                    val chapter = stream.streamSegments[c]
                    val end = if (c == stream.streamSegments.size - 1) stream.duration.toInt() else stream.streamSegments[c+1].startTimeSeconds
                    val item = ChapterItem(chapter.startTimeSeconds.toLong(), end.toLong(), chapter.title)
                    chapters.add(item)
                }
            }

            video = ResultItem(0,
                url,
                title,
                author,
                duration,
                thumb,
                "youtube",
                "",
                formats,
                if (stream.hlsUrl.isNotBlank() && stream.hlsUrl != "null") stream.hlsUrl else "",
                chapters
            )
            // uploader-provided captions only (auto-generated ones are speech recognition)
            runCatching {
                video?.availableSubtitles = stream.subtitles.filter { !it.isAutoGenerated }.map { it.languageTag }.filter { it.isNotBlank() }.distinct()
            }
        } catch (e: Exception) {
            Log.e("NewPipeUtil", e.toString())
        }
        return video
    }
}