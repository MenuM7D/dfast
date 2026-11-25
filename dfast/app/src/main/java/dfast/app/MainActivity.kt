package dfast.app

import android.app.ActionBar.LayoutParams
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.forEach
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.setupWithNavController
import androidx.preference.PreferenceManager
import com.anggrayudi.storage.file.getAbsolutePath
import dfast.app.core.RuntimeManager
import dfast.app.database.DBManager
import dfast.app.database.enums.DownloadType
import dfast.app.database.repository.DownloadRepository
import dfast.app.database.viewmodel.CookieViewModel
import dfast.app.database.viewmodel.DownloadCardViewModel
import dfast.app.database.viewmodel.DownloadViewModel
import dfast.app.database.viewmodel.ResultViewModel
import dfast.app.database.viewmodel.SettingsViewModel
import dfast.app.ui.BaseActivity
import dfast.app.ui.home.HomeFragment
import dfast.app.ui.downloads.DownloadQueueMainFragment
import dfast.app.ui.downloads.HistoryFragment
import dfast.app.ui.more.settings.SettingsActivity
import dfast.app.ui.welcome.WelcomeActivity
import dfast.app.util.ApkInstallUtil
import dfast.app.util.BgUtilsPoTokenGeneratorUtil
import dfast.app.util.CrashListener
import dfast.app.util.NavbarUtil
import dfast.app.util.NavbarUtil.applyNavBarStyle
import dfast.app.util.ThemeUtil
import dfast.app.util.CrashRecorder
import dfast.app.util.UpdateUtil
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.navigation.NavigationView
import com.google.android.material.navigationrail.NavigationRailView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlin.system.exitProcess


class MainActivity : BaseActivity() {

    lateinit var context: Context
    private lateinit var preferences: SharedPreferences
    private lateinit var resultViewModel: ResultViewModel
    private lateinit var cookieViewModel: CookieViewModel
    private lateinit var downloadViewModel: DownloadViewModel
    private lateinit var settingsViewModel: SettingsViewModel
    private lateinit var downloadCardViewModel: DownloadCardViewModel
    private var navigationView: NavigationView? = null
    private var drawer: androidx.drawerlayout.widget.DrawerLayout? = null

    fun openDrawer() { drawer?.openDrawer(androidx.core.view.GravityCompat.START) }
    private var navigationBarView: NavigationBarView? = null
    // the floating card that carries the bottom navigation in portrait layouts;
    // it is the only sibling of frame_layout that the page container can attach to
    private var dockView: View? = null
    private lateinit var navHostFragment : NavHostFragment
    private lateinit var navController : NavController
    private var loadingRuntimeDialog: androidx.appcompat.app.AlertDialog? = null

    lateinit var installLauncher: ActivityResultLauncher<Intent>
        private set

    private var miniPlayer: dfast.app.util.MiniPlayer? = null

    override fun onDestroy() {
        miniPlayer?.release()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // first run: the welcome flow asks for permissions and update preferences, then reopens this
        if (WelcomeActivity.isNeeded(PreferenceManager.getDefaultSharedPreferences(this))) {
            startActivity(Intent(this, WelcomeActivity::class.java).putExtra(WelcomeActivity.EXTRA_NEXT_INTENT, intent))
            finish()
            return
        }
        CrashListener(this).registerExceptionHandler()
        window.decorView.post { CrashRecorder.offerIfAny(this) }
        ThemeUtil.updateTheme(this)
        window.navigationBarColor = SurfaceColors.SURFACE_2.getColor(this)
        setContentView(R.layout.activity_main)
        context = baseContext

        resultViewModel = ViewModelProvider(this)[ResultViewModel::class.java]
        cookieViewModel = ViewModelProvider(this)[CookieViewModel::class.java]
        downloadViewModel = ViewModelProvider(this)[DownloadViewModel::class.java]
        settingsViewModel = ViewModelProvider(this)[SettingsViewModel::class.java]
        downloadCardViewModel = ViewModelProvider(this)[DownloadCardViewModel::class.java]
        preferences = PreferenceManager.getDefaultSharedPreferences(context)

        // open clean on Home: no restored search or results after the app was closed
        if (savedInstanceState == null) {
            lifecycleScope.launch(Dispatchers.IO) { resultViewModel.deleteAll() }
        }
        if (preferences.getBoolean("incognito", false)) {
            lifecycleScope.launch(Dispatchers.IO){
                resultViewModel.deleteAll()
            }
        }

        // new users already went through the permissions page, so only the required ones are re-checked
        askPermissions(includeOptional = !preferences.getBoolean(WelcomeActivity.PREF_COMPLETED, false))

        navHostFragment = supportFragmentManager.findFragmentById(R.id.frame_layout) as NavHostFragment
        navController = navHostFragment.findNavController()
        kotlin.runCatching {
            dockView = findViewById(R.id.dock_card)
        }
        miniPlayer = dfast.app.util.MiniPlayer(this)

        NavbarUtil.init(this)

        drawer = findViewById<NavigationView>(R.id.orbit_drawer_nav)?.parent as? androidx.drawerlayout.widget.DrawerLayout
        findViewById<NavigationView>(R.id.orbit_drawer_nav)?.apply {
            setupWithNavController(navController)
            // storage meter: how much of the phone's storage is used
            val header = getHeaderView(0)
            val st = android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path)
            val total = st.totalBytes; val free = st.availableBytes
            header?.findViewById<android.widget.ProgressBar>(R.id.storage_bar)?.progress = (((total - free) * 100) / total.coerceAtLeast(1)).toInt()
            header?.findViewById<TextView>(R.id.storage_text)?.text = getString(R.string.o_storage, android.text.format.Formatter.formatShortFileSize(this@MainActivity, free), android.text.format.Formatter.formatShortFileSize(this@MainActivity, total))
        }

        if (savedInstanceState == null) {
            val graph = navController.navInflater.inflate(R.navigation.nav_graph)
            graph.setStartDestination(NavbarUtil.getStartFragmentId(this@MainActivity))
            navController.graph = graph
        }

        navigationView?.apply {
            setupWithNavController(navController)
            //terminate button
            menu.getItem(8).setOnMenuItemClickListener {
                fun terminateApp() {
                    BgUtilsPoTokenGeneratorUtil.releaseServer(context, 0)
                    finishAndRemoveTask()
                    finishAffinity()
                    exitProcess(0)
                }

                if (preferences.getBoolean("ask_terminate_app", true)){
                    var doNotShowAgain = false
                    val terminateDialog = MaterialAlertDialogBuilder(this@MainActivity)
                    terminateDialog.setTitle(getString(R.string.confirm_delete_history))
                    val dialogView = layoutInflater.inflate(R.layout.dialog_terminate_app, null)
                    val checkbox = dialogView.findViewById<CheckBox>(R.id.doNotShowAgain)
                    terminateDialog.setView(dialogView)
                    checkbox.setOnCheckedChangeListener { compoundButton, _ ->
                        doNotShowAgain = compoundButton.isChecked
                    }

                    terminateDialog.setNegativeButton(getString(R.string.cancel)) { dialogInterface: DialogInterface, _: Int -> dialogInterface.cancel() }
                    terminateDialog.setPositiveButton(getString(R.string.ok)) { _: DialogInterface?, _: Int ->
                        runBlocking {
                            val job : Job = lifecycleScope.launch(Dispatchers.IO) {
                                val activeDownloads = downloadViewModel.getActiveDownloads().toMutableList()
                                activeDownloads.map { it.status = DownloadRepository.Status.Queued.toString() }
                                activeDownloads.forEach {
                                    downloadViewModel.updateDownload(it)
                                }
                            }
                            runBlocking {
                                job.join()
                                if (doNotShowAgain){
                                    preferences.edit().putBoolean("ask_terminate_app", false).apply()
                                }
                                terminateApp()
                            }
                        }
                    }
                    terminateDialog.show()
                }else{
                    terminateApp()
                }
                true
            }
            //settings button
            menu.getItem(9).setOnMenuItemClickListener {
                val intent = Intent(context, SettingsActivity::class.java)
                startActivity(intent)
                true
            }

            getHeaderView(0).findViewById<TextView>(R.id.title).text = ThemeUtil.getStyledAppName(this@MainActivity)
        }

        cookieViewModel.updateCookiesFile()
        installLauncher = ApkInstallUtil.registerInstallLauncher(this)

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val useBgUtilPoTokenServer = preferences.getBoolean("use_bgutils_potoken_generator", false)
                val bgUtilsMethod = preferences.getString("bgutils_potoken_method", "server")
                val requiresServer = useBgUtilPoTokenServer && bgUtilsMethod == "server"
                if (requiresServer) {
                    BgUtilsPoTokenGeneratorUtil.acquireServer(this@MainActivity)
                }
            }
        }

        val intent = intent
        handleIntents(intent)

        callAutoUpdates(firstRun = intent.getBooleanExtra(WelcomeActivity.EXTRA_FROM_WELCOME, false))
    }
    override fun onSaveInstanceState(savedInstanceState: Bundle) {
        super.onSaveInstanceState(savedInstanceState)
        savedInstanceState.putBundle("nav_state", navController.saveState())
    }
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        navController.restoreState(savedInstanceState.getBundle("nav_state"))
    }

    private fun View.visibilityChanged(action: (View) -> Unit) {
        this.viewTreeObserver.addOnGlobalLayoutListener {
            val newVis: Int = this.visibility
            if (this.tag as Int? != newVis) {
                this.tag = this.visibility
                // visibility has changed
                action(this)
            }
        }
    }


    fun hideBottomNavigation(){}

    fun showBottomNavigation(){}

    fun disableBottomNavigation(){
        navigationBarView?.menu?.forEach { it.isEnabled = false }
        navigationView?.menu?.forEach { it.isEnabled = false }
    }

    fun enableBottomNavigation(){
        navigationBarView?.menu?.forEach { it.isEnabled = true }
        navigationView?.menu?.forEach { it.isEnabled = true }
    }

    override fun onResume() {
        super.onResume()
        //incognito header
        val incognitoHeader = findViewById<TextView>(R.id.incognito_header)
        if (preferences.getBoolean("incognito", false)){
            incognitoHeader.visibility = View.VISIBLE
            window.statusBarColor = (incognitoHeader.background as ColorDrawable).color
        }else{
            window.statusBarColor = getColor(android.R.color.transparent)
            incognitoHeader.visibility = View.GONE
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntents(intent)
    }

    private fun handleIntents(intent: Intent) {
        val action = intent.action
        val type = intent.type
        if (Intent.ACTION_SEND == action && type != null) {
            Log.e(TAG, action)
            try {
                val uri = if (Build.VERSION.SDK_INT >= 33){
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                }else{
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }

                var downloadType = DownloadType.valueOf(preferences.getString("preferred_download_type", "video")!!)
                if (preferences.getBoolean("quick_download", false) || downloadType == DownloadType.command) {
                    val docFile = DocumentFile.fromSingleUri(this, uri!!)
                    if (docFile?.exists() == true){
                        val bundle = Bundle()
                        val path = docFile.getAbsolutePath(this)
                        if (downloadType == DownloadType.auto) {
                            downloadType = downloadViewModel.getDownloadType(null, path)
                        }

                        downloadCardViewModel.setResultItem(downloadViewModel.createEmptyResultItem(path))
                        downloadCardViewModel.setDownloadItem(null)
                        bundle.putSerializable("type", downloadType)
                        navController.navigate(R.id.downloadBottomSheetDialog, bundle)
                        return
                    }
                }

                val `is` = contentResolver.openInputStream(uri!!)
                val textBuilder = StringBuilder()
                val reader: Reader = BufferedReader(
                    InputStreamReader(
                        `is`, Charset.forName(
                            StandardCharsets.UTF_8.name()
                        )
                    )
                )
                var c: Int
                while (reader.read().also { c = it } != -1) {
                    textBuilder.append(c.toChar())
                }
                val bundle = Bundle()
                bundle.putString("url", textBuilder.toString())
                navController.popBackStack(R.id.homeFragment, true)
                navController.navigate(
                    R.id.homeFragment,
                    bundle
                )
            } catch (e: Exception) {
                Toast.makeText(context, "Couldn't read file", Toast.LENGTH_LONG).show()
                e.printStackTrace()
            }
        }else if (action == Intent.ACTION_VIEW){

            val navbarItems = NavbarUtil.getNavBarItems(this)
            when(intent.getStringExtra("destination")){
                "Downloads" -> {
                    if (navbarItems.any { n -> n.itemId == R.id.historyFragment && n.isVisible }) {
                        navController.popBackStack(navController.graph.startDestinationId, true)
                    }
                    navController.navigate(R.id.historyFragment)
                }
                "Queue" -> {
                    if (navbarItems.any { n -> n.itemId == R.id.downloadQueueMainFragment && n.isVisible }) {
                        navController.popBackStack(navController.graph.startDestinationId, true)
                    }

                    val bundle = Bundle()
                    intent.getStringExtra("tab")?.apply {
                        bundle.putString("tab", this)
                    }
                    intent.getLongExtra("reconfigure", 0L).apply {
                        if (this != 0L){
                            bundle.putLong("reconfigure", this)
                        }
                    }
                    navController.navigate(R.id.downloadQueueMainFragment, bundle)
                }
                "Search" -> {
                    val bundle = Bundle()
                    bundle.putBoolean("search", true)
                    navController.popBackStack(R.id.homeFragment, true)
                    navController.navigate(
                        R.id.homeFragment,
                        bundle
                    )
                }
            }
        }
    }

    private fun callAutoUpdates(firstRun : Boolean = false) {
        resultViewModel.checkForUpdates { //on app update found
            if (preferences.getBoolean("automatic_backup", false)) {
                settingsViewModel.backup()
            }
        }
        if (preferences.getBoolean("auto_update_ytdlp", false)){
            CoroutineScope(SupervisorJob()).launch {
                try {
                    val hasActiveQueuedDownloads = DBManager.getInstance(this@MainActivity).downloadDao.getDownloadsCountByStatus(listOf("Active", "Queued")) > 0
                    if (hasActiveQueuedDownloads) return@launch

                    if (firstRun) {
                        Snackbar.make(findViewById(R.id.frame_layout), context.getString(R.string.ytdl_updating_started), Snackbar.LENGTH_LONG).apply {
                            
                            show()
                        }
                    }

                    val updateRes = withContext(Dispatchers.IO) {
                        UpdateUtil(this@MainActivity).updateYTDL()
                    }

                    if (updateRes.status == UpdateUtil.YTDLPUpdateStatus.DONE) {
                        val version = RuntimeManager.getInstance().version(context)
                        val message = this@MainActivity.getString(R.string.ytld_update_success) + " [${version}]"
                        Snackbar.make(findViewById(R.id.frame_layout), message, Snackbar.LENGTH_LONG).apply {
                            
                            show()
                        }
                    }
                } catch (err: Exception) {}
            }
        }
    }


    companion object {
        private const val TAG = "MainActivity"
    }
}