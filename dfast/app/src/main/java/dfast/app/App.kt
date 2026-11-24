package dfast.app

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.webkit.WebView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.preference.PreferenceManager
import dfast.app.core.RuntimeManager
import dfast.app.core.models.ExecuteException
import dfast.app.database.DBManager
import dfast.app.database.repository.ObserveSourcesRepository
import dfast.app.services.BgUtilsPoTokenGeneratorService
import dfast.app.util.ApkInstallUtil
import dfast.app.util.BgUtilsPoTokenGeneratorUtil
import dfast.app.util.Extensions.hasReachedEnd
import dfast.app.util.NotificationUtil
import dfast.app.util.ObserveAlarmScheduler
import dfast.app.util.ThemeUtil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        val sharedPreferences =  PreferenceManager.getDefaultSharedPreferences(this@App)
        setDefaultValues()
        ThemeUtil.init(this)

        val processName = getProcessNameImpl()
        if (processName.endsWith(":incognito_process") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WebView.setDataDirectorySuffix("incognito_store")
            return
        }

        applicationScope = CoroutineScope(SupervisorJob())
        applicationScope.launch((Dispatchers.IO)) {
            try {
                createNotificationChannels()
                initLibraries()

                val appVer = sharedPreferences.getString("version", "")!!
                if(appVer.isEmpty() || appVer != BuildConfig.VERSION_NAME){
                    sharedPreferences.edit(commit = true){
                        putString("version", BuildConfig.VERSION_NAME)
                    }
                }

                val db = DBManager.getInstance(this@App)
                val scheduler = ObserveAlarmScheduler(this@App)
                db.observeSourcesDao.getAllSources()
                    .filter { it.status == ObserveSourcesRepository.SourceStatus.ACTIVE && !it.hasReachedEnd() }
                    .forEach { scheduler.schedule(it) }         // idempotent: FLAG_UPDATE_CURRENT updates in place

            }catch (e: Exception){
                Looper.prepare().runCatching {
                    Toast.makeText(this@App, e.message, Toast.LENGTH_SHORT).show()
                }
                e.printStackTrace()
            }
        }
    }
    @Throws(ExecuteException::class)
    private fun initLibraries() {
        RuntimeManager.getInstance().init(this)
    }

    private fun setDefaultValues(){
        val SPL = 1
        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        if (sp.getInt("spl", 0) != SPL) {
            PreferenceManager.setDefaultValues(this, R.xml.root_preferences, true)
            PreferenceManager.setDefaultValues(this, R.xml.downloading_preferences, true)
            PreferenceManager.setDefaultValues(this, R.xml.general_preferences, true)
            PreferenceManager.setDefaultValues(this, R.xml.processing_preferences, true)
            PreferenceManager.setDefaultValues(this, R.xml.folders_preference, true)
            PreferenceManager.setDefaultValues(this, R.xml.updating_preferences, true)
            PreferenceManager.setDefaultValues(this, R.xml.ytdlp_update_preferences, true)
            PreferenceManager.setDefaultValues(this, R.xml.app_update_preferences, true)
            PreferenceManager.setDefaultValues(this, R.xml.advanced_preferences, true)
            sp.edit().putInt("spl", SPL).apply()
        }

    }

    private fun createNotificationChannels() {
        val notificationUtil = NotificationUtil(this)
        notificationUtil.createNotificationChannel()
    }

    private fun getProcessNameImpl() : String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            packageName
        }
    }

    companion object {
        private const val TAG = "App"
        private lateinit var applicationScope: CoroutineScope
        lateinit var instance: App
    }
}