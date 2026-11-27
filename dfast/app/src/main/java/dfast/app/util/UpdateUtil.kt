package dfast.app.util

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager
import dfast.app.BuildConfig
import dfast.app.R
import dfast.app.core.RuntimeManager
import dfast.app.core.models.YTDLRequest
import dfast.app.core.packages.PackageBase.Companion.sharedClient
import dfast.app.database.models.GithubRelease
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException


class UpdateUtil(var context: Context) {
    private val tag = "UpdateUtil"
    private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    private val channelMap = mapOf(
        Pair<String, RuntimeManager.UpdateChannel>("stable", RuntimeManager.UpdateChannel.STABLE),
        Pair<String, RuntimeManager.UpdateChannel>("nightly", RuntimeManager.UpdateChannel.NIGHTLY),
        Pair<String, RuntimeManager.UpdateChannel>("master", RuntimeManager.UpdateChannel.MASTER)
    )

    /**
     * Sortable version key from a tag ("v1.0.1-beta.3", "1.0.0") or a version name ("1.0.1.3-beta").
     * Compares (major, minor, patch, build) as numbers. An official release has no build number
     * and sorts above every beta of the same major.minor.patch.
     */
    private fun String.tagNameToVersionNumber() : Long {
        val nums = Regex("\\d+").findAll(this).map { it.value.toLongOrNull() ?: 0L }.toList()
        val major = nums.getOrElse(0) { 0L }.coerceAtMost(999L)
        val minor = nums.getOrElse(1) { 0L }.coerceAtMost(999L)
        val patch = nums.getOrElse(2) { 0L }.coerceAtMost(999L)
        val isBeta = this.contains("beta", true)
        val build = if (isBeta) nums.getOrElse(3) { 0L }.coerceAtMost(9998L) else 9999L
        return (((major * 1000L + minor) * 1000L + patch) * 10000L) + build
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun tryGetNewVersion() : Result<GithubRelease> {
        try {
            val skippedVersions = sharedPreferences.getString("skip_updates", "")?.split(",")?.distinct()?.toMutableList() ?: mutableListOf()
            val res = getGithubReleases()

            if (res.isEmpty()){
                return Result.failure(Error(context.getString(R.string.network_error)))
            }
            sharedPreferences.edit().putLong(APP_LAST_CHECK_KEY, System.currentTimeMillis()).apply()

            val currentVersion = BuildConfig.VERSION_NAME
            val currentVerNumber = currentVersion.tagNameToVersionNumber()

            val useBeta = sharedPreferences.getBoolean("update_beta", false)
            var isInLatest = true

            var v: GithubRelease
            if (useBeta) {
                v = res.firstOrNull { it.tag_name.contains("beta", true) } ?: res.first()
                val stableV = res.first { !it.tag_name.contains("beta", true) }

                val incomingVerNumber = v.tag_name.removePrefix("v").tagNameToVersionNumber()
                val incomingStableVerNumber = stableV.tag_name.removePrefix("v").tagNameToVersionNumber()

                //if in beta but latest stable higher
                if (currentVerNumber < incomingStableVerNumber) {
                    isInLatest = false
                    v = stableV
                }else{
                    isInLatest = currentVerNumber >= incomingVerNumber
                }
            }else {
                v = res.first { !it.tag_name.contains("beta", true) }
                val incomingVerNumber = v.tag_name.removePrefix("v").tagNameToVersionNumber()

                // offer the stable release only when it is newer than what is installed
                // (a beta of 1.0.1 must not be offered 1.0.0; the official 1.0.1 ranks above its betas)
                isInLatest = currentVerNumber >= incomingVerNumber
            }

            if (skippedVersions.contains(v.tag_name)) isInLatest = true
            if (isInLatest) return Result.failure(Error(context.getString(R.string.you_are_in_latest_version)))
            return Result.success(v)
        }catch (e: Exception){
            e.printStackTrace()
            return Result.failure(e)
        }
    }

    fun getGithubReleases(): List<GithubRelease> {
        val url = "https://api.github.com/repos/MenuM7D/dfast/releases"
        val conn: HttpURLConnection
        var json = listOf<GithubRelease>()
        try {
            val req = URL(url)
            conn = req.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 5000
            if (conn.responseCode < 300) {
                val myType = object : TypeToken<List<GithubRelease>>() {}.type
                json = Gson().fromJson(InputStreamReader(conn.inputStream), myType)
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.e(tag, e.toString())
        }
        return json
    }

    data class YTDLPUpdateResponse (
        val status: YTDLPUpdateStatus,
        val message: String = ""
    )

    enum class YTDLPUpdateStatus {
        DONE, ALREADY_UP_TO_DATE, PROCESSING, ERROR
    }

    suspend fun updateYTDL(c: String? = null) : YTDLPUpdateResponse =
        withContext(Dispatchers.IO){
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            if (updatingYTDL) {
                return@withContext YTDLPUpdateResponse(YTDLPUpdateStatus.PROCESSING)
            }

            updatingYTDL = true

            try {
                val channel = if (c.isNullOrBlank()) sharedPreferences.getString("ytdlp_source", "stable") else c

                val response = when(channel) {
                    "stable", "nightly", "master" -> {
                        val res = RuntimeManager.getInstance().updateYTDL(context, channelMap[channel]!!)
                        if (res != RuntimeManager.UpdateStatus.DONE) {
                            YTDLPUpdateResponse(YTDLPUpdateStatus.ALREADY_UP_TO_DATE)
                        }else {
                            val version = RuntimeManager.getInstance().version(context)
                            YTDLPUpdateResponse(YTDLPUpdateStatus.DONE, "Updated yt-dlp to ${channel}@${version}")
                        }
                    }
                    else -> {
                        val request = YTDLRequest(emptyList())
                        request.addOption("--update-to", "$channel")

                        val res = RuntimeManager.getInstance().execute(request)
                        val out = res.out.lines().last { it.isNotBlank() }

                        when {
                            out.contains("ERROR") -> YTDLPUpdateResponse(YTDLPUpdateStatus.ERROR, out)
                            out.contains("yt-dlp is up to date") -> YTDLPUpdateResponse(YTDLPUpdateStatus.ALREADY_UP_TO_DATE, out)
                            else -> YTDLPUpdateResponse(YTDLPUpdateStatus.DONE, out)
                        }
                    }
                }

                if (response.status == YTDLPUpdateStatus.DONE || response.status == YTDLPUpdateStatus.ALREADY_UP_TO_DATE) {
                    sharedPreferences.edit().putLong(YTDLP_LAST_CHECK_KEY, System.currentTimeMillis()).apply()
                }
                response
            } finally {
                updatingYTDL = false
            }
        }

    @SuppressLint("Range", "UnspecifiedRegisterReceiverFlag")
    suspend fun downloadReleaseApk(release: GithubRelease, onProgress: (Long) -> Unit) : Result<File> {
        return withContext(Dispatchers.IO) {
            try {
                val releaseVersion = release.assets.firstOrNull {
                    it.name.contains(Build.SUPPORTED_ABIS[0])
                    && !it.name.contains("foss")
                    && !it.name.contains("izzy")
                }!!
                val dir = File(FileUtil.getDefaultApksPath()).apply { mkdirs() }
                val finalName = releaseVersion.browser_download_url.split("/").last()
                val tempApk = File(dir, finalName)
                val part = File(dir, "$finalName.part")
                val expected = releaseVersion.size

                // old / other DFast APKs and leftovers go; only files named like our release assets are touched
                ApkCache.stale(dir.list()?.toList().orEmpty(), finalName).forEach { File(dir, it).delete() }

                // a finished, verified APK from an earlier tap is reused: straight to the installer
                val expectedSha = try {
                    release.assets.firstOrNull { it.name == "SHA256SUMS.txt" }?.let { a ->
                        sharedClient.newCall(Request.Builder().url(a.browser_download_url).build()).execute().use { r -> if (r.isSuccessful) ApkCache.expectedSha(r.body.string(), finalName) else null }
                    }
                } catch (e: Exception) { null }
                if (ApkCache.plan(tempApk.length(), part.length(), expected) is ApkCache.Plan.UseCached) {
                    if (verified(tempApk, expectedSha)) { onProgress(100); return@withContext Result.success(tempApk) }
                    tempApk.delete()
                }

                var resumeFrom = (ApkCache.plan(0, part.length(), expected) as? ApkCache.Plan.Resume)?.from ?: 0L
                if (resumeFrom == 0L) part.delete()
                val request = Request.Builder().url(releaseVersion.browser_download_url)
                    .apply { if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-") }.build()
                var response = sharedClient.newCall(request).execute()
                if (resumeFrom > 0 && response.code != 206) {
                    // the server did not honour the range: start over
                    response.close(); part.delete(); resumeFrom = 0
                    response = sharedClient.newCall(Request.Builder().url(releaseVersion.browser_download_url).build()).execute()
                }
                if (!response.isSuccessful) {
                    val msg = response.body.string(); response.close()
                    return@withContext Result.failure(Throwable(msg))
                }

                val body = response.body
                val totalBytes = if (expected > 0) expected else resumeFrom + body.contentLength()
                var bytesDownloaded = resumeFrom

                body.byteStream().use { inputStream ->
                    java.io.FileOutputStream(part, resumeFrom > 0).use { outputStream ->
                        val buffer = ByteArray(8192) // 8KB buffer
                        var bytesRead: Int

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            if (!isActive) throw CancellationException("Download cancelled by user")

                            outputStream.write(buffer, 0, bytesRead)
                            bytesDownloaded += bytesRead

                            // Calculate percentage
                            if (totalBytes > 0) onProgress(((bytesDownloaded * 100) / totalBytes).coerceIn(0, 100))
                        }
                    }
                }
                // the .part file stays on a cancel or an error, so the next tap resumes it
                if (expected > 0 && part.length() != expected) return@withContext Result.failure(Throwable("Incomplete download"))
                if (!verified(part, expectedSha)) { part.delete(); return@withContext Result.failure(Throwable("Downloaded file failed verification")) }
                tempApk.delete()
                if (!part.renameTo(tempApk)) return@withContext Result.failure(Throwable("Could not save the update"))

                Result.success(tempApk)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /** Checksum (when the release lists one), and the APK must be this app, signed by the same key. Android re-checks at install. */
    private fun verified(f: File, expectedSha: String?): Boolean = try {
        if (expectedSha != null && ApkCache.sha256(f) != expectedSha) false
        else if (Build.VERSION.SDK_INT >= 28) {
            val pm = context.packageManager
            val flag = android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            val archive = pm.getPackageArchiveInfo(f.absolutePath, flag)
            val own = pm.getPackageInfo(context.packageName, flag)
            val a = archive?.signingInfo?.apkContentsSigners?.map { it.toByteArray().contentHashCode() }?.toSet()
            val o = own.signingInfo?.apkContentsSigners?.map { it.toByteArray().contentHashCode() }?.toSet()
            // unknown signers (some devices return none) pass: Android checks at install; a different signer or package fails
            archive != null && archive.packageName == context.packageName && (a.isNullOrEmpty() || o.isNullOrEmpty() || a == o)
        } else true
    } catch (e: Exception) { false }

    companion object {
        const val APP_LAST_CHECK_KEY = "app_last_update_check"
        const val YTDLP_LAST_CHECK_KEY = "ytdlp_last_update_check"
        var updatingYTDL = false
        var updatingApp = false
    }
}