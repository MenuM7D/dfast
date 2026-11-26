package dfast.app.ui.downloadcard

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import dfast.app.R
import dfast.app.database.enums.DownloadType
import dfast.app.database.models.DownloadItem
import dfast.app.database.models.ResultItem
import dfast.app.database.repository.DownloadRepository
import dfast.app.database.viewmodel.CommandTemplateViewModel
import dfast.app.database.viewmodel.DownloadCardViewModel
import dfast.app.database.viewmodel.DownloadViewModel
import dfast.app.database.viewmodel.HistoryViewModel
import dfast.app.database.viewmodel.ResultViewModel
import dfast.app.receiver.ShareActivity
import dfast.app.ui.BaseActivity
import dfast.app.ui.more.cookies.WebViewActivity
import dfast.app.util.UiUtil
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.elevation.SurfaceColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL


class DownloadBottomSheetDialog : BottomSheetDialogFragment() {
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager2: ViewPager2
    private lateinit var fragmentAdapter : DownloadFragmentAdapter
    private lateinit var downloadViewModel: DownloadViewModel
    private lateinit var historyViewModel: HistoryViewModel
    private lateinit var resultViewModel: ResultViewModel
    private lateinit var downloadCardViewModel: DownloadCardViewModel
    private lateinit var behavior: BottomSheetBehavior<View>
    private lateinit var commandTemplateViewModel : CommandTemplateViewModel
    private lateinit var sharedPreferences : SharedPreferences
    private lateinit var updateItem : Button
    private lateinit var view: View
    private lateinit var shimmerLoading :ShimmerFrameLayout
    private lateinit var title : View
    private lateinit var shimmerLoadingSubtitle : ShimmerFrameLayout
    private lateinit var subtitle : View
    private lateinit var parentActivity: BaseActivity


    private lateinit var result: ResultItem
    private lateinit var type: DownloadType
    private var ignoreDuplicates: Boolean = false
    private var disableUpdateData : Boolean = false
    private var currentDownloadItem: DownloadItem? = null
    private var incognito: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]
        historyViewModel = ViewModelProvider(requireActivity())[HistoryViewModel::class.java]
        resultViewModel = ViewModelProvider(requireActivity())[ResultViewModel::class.java]
        commandTemplateViewModel = ViewModelProvider(requireActivity())[CommandTemplateViewModel::class.java]
        downloadCardViewModel = ViewModelProvider(requireActivity())[DownloadCardViewModel::class.java]
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())

        val res = downloadCardViewModel.resultItem
        val dwl = downloadCardViewModel.downloadItem

        type = arguments?.getSerializable("type") as DownloadType
        disableUpdateData = arguments?.getBoolean("disableUpdateData") == true
        ignoreDuplicates = arguments?.getBoolean("ignore_duplicates") == true

        if (res == null){
            dismiss()
            return
        }
        result = res
        currentDownloadItem = dwl
        incognito = currentDownloadItem?.incognito ?: sharedPreferences.getBoolean("incognito", false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (quickActive) return
        val downloadItem = getDownloadItem()
        downloadCardViewModel.setResultItem(result)
        downloadCardViewModel.setDownloadItem(downloadItem)
        arguments?.putSerializable("type", downloadItem.type)
    }

    @SuppressLint("RestrictedApi", "InflateParams")
    override fun setupDialog(dialog: Dialog, style: Int) {
        super.setupDialog(dialog, style)
        view = LayoutInflater.from(context).inflate(R.layout.download_bottom_sheet, null)
        dialog.setContentView(view)
        dialog.window?.navigationBarColor = SurfaceColors.SURFACE_1.getColor(requireActivity())
        parentActivity = activity as BaseActivity

        dialog.setOnShowListener {
            behavior = BottomSheetBehavior.from(view.parent as View)
            val displayMetrics = DisplayMetrics()
            requireActivity().windowManager.defaultDisplay.getMetrics(displayMetrics)
            if(resources.getBoolean(R.bool.isTablet) || resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE){
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.peekHeight = displayMetrics.heightPixels
            }
        }

        tabLayout = view.findViewById(R.id.download_tablayout)
        viewPager2 = view.findViewById(R.id.download_viewpager)
        updateItem = view.findViewById(R.id.update_item)
        viewPager2.isUserInputEnabled = sharedPreferences.getBoolean("swipe_gestures_download_card", true)


        //loading shimmers
        shimmerLoading = view.findViewById(R.id.shimmer_loading_title)
        title = view.findViewById(R.id.bottom_sheet_title)
        shimmerLoadingSubtitle = view.findViewById(R.id.shimmer_loading_subtitle)
        subtitle = view.findViewById(R.id.bottom_sheet_subtitle)

        shimmerLoading.setOnClickListener {
            lifecycleScope.launch {
                resultViewModel.cancelUpdateItemData()
                (updateItem.parent as LinearLayout).visibility = View.VISIBLE
            }
        }


        (viewPager2.getChildAt(0) as? RecyclerView)?.apply {
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        var commandTemplateNr = 0
        lifecycleScope.launch{
            withContext(Dispatchers.IO){
                commandTemplateNr = commandTemplateViewModel.getTotalNumber()
                if (!Patterns.WEB_URL.matcher(result.url).matches()) commandTemplateNr++
                if(commandTemplateNr <= 0){
                    (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(2)?.isClickable = true
                    (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(2)?.alpha = 0.3f
                }
            }
        }

        // the command tab is a developer feature
        if (!sharedPreferences.getBoolean("developer_mode", false) && type != DownloadType.command) {
            (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(2)?.visibility = View.GONE
        }

        //check if the item has formats and its audio-only
        val formats = result.formats
        var isAudioOnly = formats.isNotEmpty() && formats.none { !it.format_note.contains("audio") }
        if (isAudioOnly){
            (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(1)?.isClickable = true
            (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(1)?.alpha = 0.3f
        }

        //remove outdated player url of 1hr so it can refetch it in the cut player
        if (result.creationTime > System.currentTimeMillis() - 3600000) result.urls = ""
        val fragmentManager = parentFragmentManager
        fragmentAdapter = DownloadFragmentAdapter(
            fragmentManager,
            lifecycle,
            result,
            currentDownloadItem,
            nonSpecific = result.url.endsWith(".txt"),
            isIncognito = incognito
        )

        viewPager2.adapter = fragmentAdapter
        viewPager2.isSaveFromParentEnabled = false

        view.post {
            when(type) {
                DownloadType.audio -> {
                    tabLayout.getTabAt(0)!!.select()
                    viewPager2.setCurrentItem(0, false)
                }
                DownloadType.video -> {
                    if (isAudioOnly){
                        tabLayout.getTabAt(0)!!.select()
                        viewPager2.setCurrentItem(0, false)
                        Toast.makeText(context, getString(R.string.audio_only_item), Toast.LENGTH_SHORT).show()
                    }else{
                        tabLayout.getTabAt(1)!!.select()
                        viewPager2.setCurrentItem(1, false)
                    }
                }
                else -> {
                    tabLayout.getTabAt(2)!!.select()
                    viewPager2.postDelayed( {
                        viewPager2.setCurrentItem(2, false)
                    }, 200)
                }
            }

            //check if the item is coming from a text file
            val isCommandOnly = (type == DownloadType.command && !Patterns.WEB_URL.matcher(result.url).matches())
            if (isCommandOnly){
                (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.isClickable = false
                (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.alpha = 0.3f

                (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(1)?.isClickable = false
                (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(1)?.alpha = 0.3f

                (updateItem.parent as LinearLayout).visibility = View.GONE
            }
        }

        sharedPreferences.edit(commit = true) {
            putString("last_used_download_type",
                type.toString())
        }


        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                if (tab!!.position == 2 && commandTemplateNr == 0){
                    tabLayout.selectTab(tabLayout.getTabAt(1))
                    val s = Snackbar.make(view, getString(R.string.add_template_first), Snackbar.LENGTH_LONG)
                    val snackbarView: View = s.view
                    val snackTextView = snackbarView.findViewById<View>(com.google.android.material.R.id.snackbar_text) as TextView
                    snackTextView.maxLines = 9999999
                    s.setAction(R.string.new_template){
                        UiUtil.showCommandTemplateCreationOrUpdatingSheet(
                            item = null, context = requireActivity(), lifeCycle = this@DownloadBottomSheetDialog, commandTemplateViewModel = commandTemplateViewModel,
                            newTemplate = {
                                commandTemplateNr = 1
                                (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(2)?.isClickable = true
                                (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(2)?.alpha = 1f
                                tabLayout.selectTab(tabLayout.getTabAt(2))
                            },
                            dismissed = {

                            }
                        )
                    }
                    s.show()
                }else if (tab.position == 1 && isAudioOnly){
                    tabLayout.selectTab(tabLayout.getTabAt(0))
                    Toast.makeText(context, getString(R.string.audio_only_item), Toast.LENGTH_SHORT).show()
                }
                else{
                    viewPager2.setCurrentItem(tab.position, false)
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {
            }

            override fun onTabReselected(tab: TabLayout.Tab?) {
            }
        })

        viewPager2.registerOnPageChangeCallback(object: ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                tabLayout.selectTab(tabLayout.getTabAt(position))
                runCatching {
                    sharedPreferences.edit(commit = true) {
                        putString("last_used_download_type",
                            listOf(DownloadType.audio, DownloadType.video, DownloadType.command)[position].toString())
                    }
                    fragmentAdapter.updateWhenSwitching(viewPager2.currentItem)
                }
            }
        })

        viewPager2.setPageTransformer(BackgroundToForegroundPageTransformer())
        setupQuick()

        val shownFields = sharedPreferences.getStringSet("modify_download_card", requireContext().resources.getStringArray(R.array.modify_download_card_values).toSet())!!.toList()

        val scheduleBtn = view.findViewById<MaterialButton>(R.id.bottomsheet_schedule_button)
        scheduleBtn.visibility = if(shownFields.contains("schedule")){
            View.VISIBLE
        }else{
            View.GONE
        }
        val download = view.findViewById<Button>(R.id.bottomsheet_download_button)


        scheduleBtn.setOnClickListener{
            UiUtil.showDatePicker(fragmentManager, sharedPreferences) {
                lifecycleScope.launch {
                    resultViewModel.cancelUpdateItemData()
                    resultViewModel.cancelUpdateFormatsItemData()
                }

                scheduleBtn.isEnabled = false
                download.isEnabled = false
                val item: DownloadItem = getDownloadItem()
                item.status = DownloadRepository.Status.Scheduled.toString()
                item.downloadStartTime = it.timeInMillis
                if (item.videoPreferences.alsoDownloadAsAudio){
                    val itemsToQueue = mutableListOf<DownloadItem>()
                    itemsToQueue.add(item)

                    getAlsoAudioDownloadItem(finished = { audioDownloadItem ->
                        audioDownloadItem.downloadStartTime = it.timeInMillis
                        audioDownloadItem.status = DownloadRepository.Status.Scheduled.toString()
                        itemsToQueue.add(audioDownloadItem)

                        lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO){
                                downloadViewModel.queueDownloads(itemsToQueue, ignoreDuplicates)
                            }

                            if (result.message.isNotBlank()){
                                Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                            }

                            withContext(Dispatchers.Main){
                                handleDuplicatesAndDismiss(result.duplicateDownloadIDs)
                            }
                        }
                    })
                }else{
                    lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO){
                            downloadViewModel.queueDownloads(listOf(item), ignoreDuplicates)
                        }

                        if (result.message.isNotBlank()){
                            Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                        }

                        withContext(Dispatchers.Main){
                            handleDuplicatesAndDismiss(result.duplicateDownloadIDs)
                        }
                    }
                }

            }
        }
        download!!.setOnClickListener {
            lifecycleScope.launch {
                resultViewModel.cancelUpdateItemData()
                resultViewModel.cancelUpdateFormatsItemData()
                scheduleBtn.isEnabled = false
                download.isEnabled = false
                val item: DownloadItem = getDownloadItem()
                if (item.videoPreferences.alsoDownloadAsAudio){
                    val itemsToQueue = mutableListOf<DownloadItem>()
                    itemsToQueue.add(item)

                    getAlsoAudioDownloadItem(finished = {
                        itemsToQueue.add(it)

                        lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                downloadViewModel.queueDownloads(itemsToQueue, ignoreDuplicates)
                            }
                            withContext(Dispatchers.Main){
                                handleDuplicatesAndDismiss(result.duplicateDownloadIDs)
                            }
                        }
                    })
                }else{
                    val result = withContext(Dispatchers.IO) {
                        downloadViewModel.queueDownloads(listOf(item), ignoreDuplicates)
                    }
                    handleDuplicatesAndDismiss(result.duplicateDownloadIDs)
                }
            }
        }

        download.setOnLongClickListener {
            val dd = MaterialAlertDialogBuilder(requireContext())
            dd.setTitle(getString(R.string.save_for_later))
            dd.setNegativeButton(getString(R.string.cancel)) { dialogInterface: DialogInterface, _: Int -> dialogInterface.cancel() }
            dd.setPositiveButton(getString(R.string.ok)) { _: DialogInterface?, _: Int ->
                lifecycleScope.launch(Dispatchers.IO){
                    downloadViewModel.putToSaved(getDownloadItem())
                    dismiss()
                }
            }
            dd.show()
            true
        }

        val link = view.findViewById<Button>(R.id.bottom_sheet_link)
        link.visibility = if(shownFields.contains("url")){
            View.VISIBLE
        }else{
            View.GONE
        }

        if (Patterns.WEB_URL.matcher(result.url).matches()){
            link.text = result.url
            link.setOnClickListener{
                UiUtil.openLinkIntent(requireContext(), result.url)
            }
            link.setOnLongClickListener{
                UiUtil.copyLinkToClipBoard(requireContext(), result.url)
                true
            }

            //if auto-update after the card is open is off
            if (result.title.isEmpty() && currentDownloadItem == null && sharedPreferences.getBoolean("quick_download", false)) {
                (updateItem.parent as LinearLayout).visibility = View.VISIBLE
                updateItem.setOnClickListener {
                    (updateItem.parent as LinearLayout).visibility = View.GONE
                    initUpdateData()
                }
            }else{
                (updateItem.parent as LinearLayout).visibility = View.GONE
            }

        }else{
            link.visibility = View.GONE
            (updateItem.parent as LinearLayout).visibility = View.GONE
        }

        val incognitoBtn = view.findViewById<Button>(R.id.bottomsheet_incognito)
        incognitoBtn.alpha = if (incognito) 1f else 0.3f
        incognitoBtn.setOnClickListener {
            if (incognito) {
                it.alpha = 0.3f
            }else{
                it.alpha = 1f
            }

            incognito = !incognito
            fragmentAdapter.isIncognito = incognito
            val onOff = if (incognito) getString(R.string.ok) else getString(R.string.disabled)
            Snackbar.make(incognitoBtn, "${getString(R.string.incognito)}: $onOff", Snackbar.LENGTH_SHORT).show()
        }


        //update in the background if there is no data
        if (!disableUpdateData) {
            if(result.title.isEmpty() && currentDownloadItem == null && !sharedPreferences.getBoolean("quick_download", false) && type != DownloadType.command){
                initUpdateData()
            }else {
                val usingGenericFormatsOrEmpty = result.formats.isEmpty() || result.formats.any { it.format_note.contains("dfastgeneric") }
                if (usingGenericFormatsOrEmpty && sharedPreferences.getBoolean("update_formats", false) && !sharedPreferences.getBoolean("quick_download", false)){
                    initUpdateFormats(result)
                }
            }
        }

        lifecycleScope.launch {
            resultViewModel.uiState.collectLatest { res ->
                if (res.errorMessage != null){
                    kotlin.runCatching {
                        UiUtil.handleNoResults(requireActivity(), res.errorMessage!!,
                            url = result.url,
                            continueAnyway =  true,
                            continued = {},
                            cookieFetch = {
                                val myIntent = Intent(requireContext(), WebViewActivity::class.java)
                                myIntent.putExtra("url", "https://${URL(result.url).host}")
                                cookiesFetchedResultLauncher.launch(myIntent)
                            },
                            closed = {
                                dismiss()
                            }
                        )
                    }

                    resultViewModel.uiState.update {it.copy(errorMessage  = null) }
                }
            }
        }

        lifecycleScope.launch {
            resultViewModel.updatingData.collectLatest {
                kotlin.runCatching {
                    if (it){
                        title.visibility = View.GONE
                        subtitle.visibility = View.GONE
                        shimmerLoading.visibility = View.VISIBLE
                        shimmerLoadingSubtitle.visibility = View.VISIBLE
                        shimmerLoading.startShimmer()
                        shimmerLoadingSubtitle.startShimmer()
                        (updateItem.parent as LinearLayout).visibility = View.GONE
                    }else{
                        title.visibility = View.VISIBLE
                        subtitle.visibility = View.VISIBLE
                        shimmerLoading.visibility = View.GONE
                        shimmerLoadingSubtitle.visibility = View.GONE
                        shimmerLoading.stopShimmer()
                        shimmerLoadingSubtitle.stopShimmer()
                    }
                    refreshQuick()
                }
            }
        }

        lifecycleScope.launch {
            resultViewModel.updatingFormats.collectLatest {
                kotlin.runCatching {
                    if (it){
                        delay(500)
                        runCatching {
                            (fragmentAdapter.fragments[0] as DownloadAudioFragment).apply {
                                view?.findViewById<LinearProgressIndicator>(R.id.format_loading_progress)?.apply {
                                    isVisible = true
                                    isClickable = true
                                    setOnClickListener {
                                        lifecycleScope.launch {
                                            resultViewModel.cancelUpdateFormatsItemData()
                                        }
                                    }
                                }
                            }
                        }
                        runCatching {
                            (fragmentAdapter.fragments[1] as DownloadVideoFragment).apply {
                                view?.findViewById<LinearProgressIndicator>(R.id.format_loading_progress)?.apply {
                                    isVisible = true
                                    isClickable = true
                                    setOnClickListener {
                                        lifecycleScope.launch {
                                            resultViewModel.cancelUpdateFormatsItemData()
                                        }
                                    }
                                }
                            }
                        }
                    }else{
                        runCatching {
                            (fragmentAdapter.fragments[0] as DownloadAudioFragment).apply {
                                view?.findViewById<LinearProgressIndicator>(R.id.format_loading_progress)?.apply {
                                    isVisible = false
                                    isClickable = false
                                }
                            }
                        }
                        runCatching {
                            (fragmentAdapter.fragments[1] as DownloadVideoFragment).apply {
                                view?.findViewById<LinearProgressIndicator>(R.id.format_loading_progress)?.apply {
                                    isVisible = false
                                    isClickable = false
                                }
                            }
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            resultViewModel.updateResultData.collectLatest { result ->
                if (result == null) return@collectLatest
                kotlin.runCatching {
                    lifecycleScope.launch(Dispatchers.Main) {
                        if (result.size == 1 && result[0] != null) {
                            val res = result[0]!!
                            fragmentAdapter.setResultItem(res)

                            title.visibility = View.VISIBLE
                            subtitle.visibility = View.VISIBLE
                            shimmerLoading.visibility = View.GONE
                            shimmerLoadingSubtitle.visibility = View.GONE
                            shimmerLoading.stopShimmer()
                            shimmerLoadingSubtitle.stopShimmer()

                            val usingGenericFormatsOrEmpty = res.formats.isEmpty() || res.formats.any { it.format_note.contains("dfastgeneric") }
                            downloadCardViewModel.setResultItem(res)
                            refreshQuick()
                            if (usingGenericFormatsOrEmpty && (sharedPreferences.getBoolean("update_formats", false) || quickActive)){
                                initUpdateFormats(res)
                            }

                        }else if (result.size > 1) {
                            //open multi download card instead
                            if (activity is ShareActivity){
                                findNavController().navigate(R.id.action_downloadBottomSheetDialog_to_selectPlaylistItemsDialog, bundleOf(
                                    Pair("resultIDs", result.map { it!!.id }.toLongArray()),
                                ))
                            }else{
                                dismiss()
                            }
                        }

                        resultViewModel.updateResultData.emit(null)
                    }

                }
            }
        }

        lifecycleScope.launch {
            launch{
                downloadViewModel.alreadyExistsUiState.collectLatest { res ->
                    if (res.isNotEmpty() && activity is ShareActivity){
                        withContext(Dispatchers.Main){
                            val bundle = bundleOf(
                                Pair("duplicates", ArrayList(res))
                            )
                            delay(500)
                            findNavController().navigate(R.id.action_downloadBottomSheetDialog_to_downloadsAlreadyExistDialog2, bundle)
                        }
                        downloadViewModel.alreadyExistsUiState.value = mutableListOf()
                    }
                }
            }
        }

        lifecycleScope.launch {
            resultViewModel.updateFormatsResultData.collectLatest { formats ->
                if (formats == null) return@collectLatest
                kotlin.runCatching {
                    isAudioOnly = formats.isNotEmpty() && formats.none { !it.format_note.contains("audio") }
                    if (isAudioOnly){
                        (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(1)?.isClickable = true
                        (tabLayout.getChildAt(0) as? ViewGroup)?.getChildAt(1)?.alpha = 0.3f
                        Toast.makeText(context, getString(R.string.audio_only_item), Toast.LENGTH_SHORT).show()
                        tabLayout.getTabAt(0)!!.select()
                        viewPager2.setCurrentItem(0, false)
                    }

                    lifecycleScope.launch {
                        withContext(Dispatchers.Main){
                            runCatching {
                                val f1 = fragmentAdapter.fragments[0] as DownloadAudioFragment
                                val resultItem = downloadViewModel.createResultItemFromDownload(f1.downloadItem)
                                resultItem.formats = formats
                                fragmentAdapter.setResultItem(resultItem)
                                f1.view?.findViewById<LinearProgressIndicator>(R.id.format_loading_progress)?.visibility = View.GONE
                            }
                            runCatching {
                                val f1 = fragmentAdapter.fragments[1] as DownloadVideoFragment
                                val resultItem = downloadViewModel.createResultItemFromDownload(f1.downloadItem)
                                resultItem.formats = formats
                                fragmentAdapter.setResultItem(resultItem)
                                f1.view?.findViewById<LinearProgressIndicator>(R.id.format_loading_progress)?.visibility = View.GONE
                            }
                        }

                        if (formats.isNotEmpty()){
                            result.formats = formats
                            downloadCardViewModel.resultItem?.formats = formats
                            refreshQuick()
                        }
                        resultViewModel.updateFormatsResultData.emit(null)
                    }
                }
            }
        }
    }

    private var cookiesFetchedResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            sharedPreferences.edit().putBoolean("use_cookies", true).apply()
            updateItem.isVisible = true
            initUpdateData()
        }
    }

    private var quickActive = false
    private var sheetBg: android.graphics.drawable.Drawable? = null

    private class Lvl(val label: String, val fmt: dfast.app.database.models.Format, val bytes: Long, val approx: Boolean)
    private var levels: List<Lvl> = emptyList()
    private var lvlIndex = 0
    private var lvlSig = ""
    private var stSubs = true
    private var stChap = false
    private var stThumb = false
    private var pickedFmt: dfast.app.database.models.Format? = null

    private fun themeColor(attr: Int, fallback: Int): Int {
        val tv = android.util.TypedValue()
        return if (requireContext().theme.resolveAttribute(attr, tv, true)) tv.data else fallback
    }

    private fun roundBg(color: Int, radiusDp: Float, stroke: Int? = null): android.graphics.drawable.GradientDrawable {
        val dens = resources.displayMetrics.density
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(color); cornerRadius = radiusDp * dens
            if (stroke != null) setStroke((1 * dens).toInt(), stroke)
        }
    }

    private fun wordmark(tv: TextView) {
        val sp = android.text.SpannableString("DFast")
        sp.setSpan(android.text.style.ForegroundColorSpan(0xFF8E7CFF.toInt()), 0, 1, 0)
        tv.text = sp
    }

    private fun parseDur(d: String): Long {
        val parts = d.split(":").mapNotNull { it.trim().toLongOrNull() }
        if (parts.isEmpty() || parts.size != d.split(":").size) return 0
        return parts.fold(0L) { acc, v -> acc * 60 + v }
    }

    private fun targetContainer(audio: Boolean): String =
        (if (audio) sharedPreferences.getString("audio_format", "") else sharedPreferences.getString("video_format", "")).orEmpty()

    /** Real size when the source gives one (exact), else the source's own estimate, else bitrate x duration. approx = not exact. */
    private fun sizeOf(f: dfast.app.database.models.Format, dur: Long): Pair<Long, Boolean> {
        if (f.filesize > 1000) return Pair(f.filesize, f.isFilesizeApprox)
        val tbr = f.tbr?.toDoubleOrNull() ?: f.tbr?.removeSuffix("k")?.toDoubleOrNull()
        if (tbr != null && tbr > 0 && dur > 0) return Pair((tbr * 1000 / 8 * dur).toLong(), true)
        return Pair(0L, false)
    }

    private fun isGeneric(f: dfast.app.database.models.Format) = f.format_id.contains("generic") || f.format_note.contains("dfastgeneric") || f.format_id == "best" || f.format_id == "worst" || f.format_id == "ba" || f.format_id == "wa"
    private fun noVideo(f: dfast.app.database.models.Format) = f.vcodec.isBlank() || f.vcodec == "none"
    private fun hasRealFormats(res: ResultItem) = res.formats.any { !isGeneric(it) }
    private fun heightOf(f: dfast.app.database.models.Format) = f.height?.takeIf { it > 0 } ?: Regex("(\\d{3,4})p").find(f.format_note)?.groupValues?.get(1)?.toIntOrNull()

    private fun videoLvl(fu: dfast.app.util.FormatUtil, g: List<dfast.app.database.models.Format>, height: Int, bestAudio: dfast.app.database.models.Format?, dur: Long): Lvl {
        val best = runCatching { fu.sortVideoFormats(g).first() }.getOrDefault(g.first())
        var (sz, ap) = sizeOf(best, dur)
        if (sz > 0 && (best.acodec.isBlank() || best.acodec == "none") && bestAudio != null) {
            val (az, aa) = sizeOf(bestAudio, dur); sz += az; ap = ap || aa
        }
        return Lvl(if (height >= 2160) "4K" else "${height}p", best, sz, ap)
    }

    private fun audioLvl(f: dfast.app.database.models.Format, br: Int, dur: Long): Lvl {
        var (sz, ap) = sizeOf(f, dur)
        val target = targetContainer(true)
        // converting (for example to mp3) changes the size, so it is only an estimate then
        if (target.isNotBlank() && !target.equals(f.container, true) && !f.container.contains(target, true)) ap = true
        return Lvl("${br}k", f, sz, ap)
    }

    /** Levels for the chart, lowest first. Real formats of the item when we have them, generic presets otherwise. */
    private fun buildLevels(res: ResultItem, audio: Boolean): List<Lvl> {
        val fu = dfast.app.util.FormatUtil(requireActivity().application)
        val dur = parseDur(res.duration)
        val fm = res.formats.filter { !isGeneric(it) }
        val out = mutableListOf<Lvl>()
        if (!audio) {
            val vids = fm.filter { !noVideo(it) }
            val groups = vids.filter { heightOf(it) != null }.groupBy { heightOf(it)!! }.toSortedMap()
            val auds = fm.filter { noVideo(it) }
            val bestAudio = if (auds.isNotEmpty()) runCatching { fu.sortAudioFormats(auds, true).first() }.getOrNull() else null
            for ((height, g) in groups) out.add(videoLvl(fu, g, height, bestAudio, dur))
            if (out.isEmpty()) {
                val gen = fu.getGenericVideoFormats(resources).filter { it.format_id.endsWith("dfastgeneric") }.reversed()
                gen.forEach { g -> out.add(Lvl(g.format_note.removePrefix("~"), g, 0, false)) }
                fu.getGenericVideoFormats(resources).firstOrNull { it.format_id == "best" }?.let { out.add(Lvl(getString(R.string.best_quality), it, 0, false)) }
            }
        } else {
            val auds = fm.filter { noVideo(it) }
            val groups = auds.filter { (it.tbr?.removeSuffix("k")?.toDoubleOrNull() ?: 0.0) > 0 }.groupBy { Math.round(it.tbr!!.removeSuffix("k").toDouble()).toInt() }.toSortedMap()
            if (groups.size >= 2) {
                for ((br, g) in groups) {
                    val best = runCatching { fu.sortAudioFormats(g).first() }.getOrDefault(g.first())
                    out.add(audioLvl(best, br, dur))
                }
            } else {
                val all = fu.getGenericAudioFormats(resources)
                all.filter { it.format_id.endsWith("dfastgeneric") }.reversed().forEach { g ->
                    val k = Regex("(\\d{2,3})").find(g.format_note)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    out.add(Lvl(g.format_note.removePrefix("~").replace("kbps", "k"), g, 0, false))
                }
                all.firstOrNull { it.format_id == "ba" }?.let { out.add(Lvl(getString(R.string.best_quality), it, 0, false)) }
            }
        }
        return if (out.size > 7) out.takeLast(7) else out
    }

    private fun compactSize(bytes: Long, approx: Boolean): String {
        if (bytes <= 0) return ""
        val (n, u) = fmtSize(bytes)
        return (if (approx) "~" else "") + n + u
    }

    private fun pickSubLang(avail: List<String>): String {
        val dev = java.util.Locale.getDefault().language
        return avail.firstOrNull { it == dev || it.startsWith("$dev-") }
            ?: avail.firstOrNull { it == "en" || it.startsWith("en-") }
            ?: avail.first()
    }

    private fun currentRawPath(audio: Boolean): String =
        (if (audio) sharedPreferences.getString("music_path", "") else sharedPreferences.getString("video_path", "")).orEmpty()

    private fun folderName(raw: String): String = Uri.decode(raw).trimEnd('/').substringAfterLast('/').substringAfterLast(':').ifBlank { "DFast" }

    private var folderSheet: com.google.android.material.bottomsheet.BottomSheetDialog? = null

    private val quickPathLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val uri = r.data?.data ?: return@registerForActivityResult
            runCatching {
                activity?.contentResolver?.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            applyFolder(uri.toString())
            folderSheet?.dismiss()
        }
    }

    private fun applyFolder(path: String) {
        val audio = type == DownloadType.audio
        val separate = sharedPreferences.getBoolean("dl_folder_separate", true)
        sharedPreferences.edit {
            if (audio || !separate) putString("music_path", path)
            if (!audio || !separate) putString("video_path", path)
            val recent = (sharedPreferences.getString("dl_recent_folders", "") ?: "").split("\n").filter { it.isNotBlank() && it != path }
            putString("dl_recent_folders", (listOf(path) + recent).take(4).joinToString("\n"))
        }
        refreshQuick(false)
    }

    private fun showFolderSheet() {
        val ctx = requireContext()
        val dm = resources.displayMetrics
        val audio = type == DownloadType.audio
        val ink = themeColor(com.google.android.material.R.attr.colorOnSurface, 0xFFFFFFFF.toInt())
        val dlg = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)
        folderSheet = dlg
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * dm.density).toInt(), (18 * dm.density).toInt(), (20 * dm.density).toInt(), (20 * dm.density).toInt()) }
        fun label(t: String, sz: Float, bold: Boolean = false, alpha: Float = 1f) = TextView(ctx).apply { text = t; textSize = sz; this.alpha = alpha; setTextColor(ink); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD) }
        root.addView(label(getString(R.string.dl_save_location), 18f, true))
        val cur = currentRawPath(audio).ifBlank { if (audio) dfast.app.util.FileUtil.getDefaultAudioPath() else dfast.app.util.FileUtil.getDefaultVideoPath() }
        val free = dfast.app.util.FileUtil.convertFileSize(java.io.File(dfast.app.util.FileUtil.formatPath(cur)).freeSpace)
        root.addView(label(folderName(cur), 15f, true).apply { setPadding(0, (16 * dm.density).toInt(), 0, 0) })
        root.addView(label(dfast.app.util.FileUtil.formatPath(cur) + if (free != "?") " · " + getString(R.string.dl_free_space, free) else "", 11f, alpha = 0.6f))
        val recent = (sharedPreferences.getString("dl_recent_folders", "") ?: "").split("\n").filter { it.isNotBlank() && it != cur }
        if (recent.isNotEmpty()) {
            root.addView(label(getString(R.string.dl_recent_folders), 12f, alpha = 0.6f).apply { setPadding(0, (18 * dm.density).toInt(), 0, (4 * dm.density).toInt()) })
            recent.forEach { p ->
                root.addView(label(folderName(p), 14f).apply {
                    setPadding(0, (10 * dm.density).toInt(), 0, (10 * dm.density).toInt())
                    background = null
                    setOnClickListener { applyFolder(p); dlg.dismiss() }
                })
            }
        }
        root.addView(label(getString(R.string.dl_pick_folder), 14f, true).apply {
            setTextColor(0xFFB3A8FF.toInt())
            setPadding(0, (18 * dm.density).toInt(), 0, (14 * dm.density).toInt())
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                quickPathLauncher.launch(intent)
            }
        })
        val sw = com.google.android.material.materialswitch.MaterialSwitch(ctx).apply {
            text = getString(R.string.dl_separate_folders)
            isChecked = sharedPreferences.getBoolean("dl_folder_separate", true)
            setOnCheckedChangeListener { _, c -> sharedPreferences.edit { putBoolean("dl_folder_separate", c) } }
        }
        root.addView(sw)
        dlg.setContentView(root)
        dlg.show()
    }

    private fun styleSeg(on: TextView, off: TextView) {
        on.background = roundBg(0xFF8E7CFF.toInt(), 12f); on.setTextColor(0xFF14102E.toInt())
        off.background = roundBg(themeColor(com.google.android.material.R.attr.colorSurfaceContainerHighest, 0xFF2A2A36.toInt()), 12f)
        off.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface, 0xFFFFFFFF.toInt()))
    }

    private fun switchMode(audio: Boolean) {
        val newType = if (audio) DownloadType.audio else DownloadType.video
        if (type == newType) return
        type = newType; pickedFmt = null; lvlSig = ""
        refreshQuick()
    }

    private fun needsRealFormats(res: ResultItem) = type != DownloadType.command && currentDownloadItem == null && Patterns.WEB_URL.matcher(res.url).matches() && !hasRealFormats(res)

    private fun ensureRealFormats() {
        val res = downloadCardViewModel.resultItem ?: result
        if (!quickActive || res.title.isBlank() && resultViewModel.updatingData.value) return
        if (needsRealFormats(res)) initUpdateFormats(res)
    }

    private fun setupQuick() {
        val quickCard = view.findViewById<View>(R.id.quick_card)
        val full = view.findViewById<View>(R.id.full_content)
        val back = view.findViewById<View>(R.id.full_back)
        wordmark(view.findViewById(R.id.full_wordmark))
        wordmark(view.findViewById(R.id.qk_wordmark))
        val isUrl = Patterns.WEB_URL.matcher(result.url).matches()
        quickActive = currentDownloadItem == null && type != DownloadType.command && isUrl && !disableUpdateData
        if (!quickActive) {
            quickCard.visibility = View.GONE
            back.visibility = View.GONE
            refreshQuick()
            return
        }
        if (type != DownloadType.audio) type = DownloadType.video
        stSubs = true
        stChap = sharedPreferences.getBoolean("add_chapters", true)
        stThumb = true
        full.visibility = View.GONE
        view.post {
            val p = view.parent as? View
            sheetBg = p?.background
            runCatching { behavior = BottomSheetBehavior.from(p!!); behavior.skipCollapsed = true; behavior.state = BottomSheetBehavior.STATE_EXPANDED }
        }
        val dm = resources.displayMetrics
        (quickCard as? LinearLayout)?.minimumHeight = (dm.heightPixels * 0.82f).toInt()
        val surf = themeColor(com.google.android.material.R.attr.colorSurfaceContainerHighest, 0xFF2A2A36.toInt())
        val ink = themeColor(com.google.android.material.R.attr.colorOnSurface, 0xFFFFFFFF.toInt())

        // download buttons (first screen and formats page share the same action)
        val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val dlViews = listOf(R.id.qk_download).map { view.findViewById<TextView>(it) }
        val schViews = listOf(R.id.qk_schedule).map { view.findViewById<android.widget.ImageButton>(it) }
        val shown = sharedPreferences.getStringSet("modify_download_card", requireContext().resources.getStringArray(R.array.modify_download_card_values).toSet())!!
        dlViews.forEach { d ->
            d.background = SpeedBg(0xFF8E7CFF.toInt(), 0xFF14102E.toInt(), rtl, dm.density)
            d.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_down, 0, 0, 0)
            d.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(0xFF14102E.toInt())
        }
        schViews.forEach { sc ->
            sc.background = roundBg(surf, 18f)
            sc.imageTintList = android.content.res.ColorStateList.valueOf(ink)
            sc.visibility = if (shown.contains("schedule")) View.VISIBLE else View.GONE
        }

        val chart = view.findViewById<LedChartView>(R.id.qk_chart)
        chart.onChange = { i -> lvlIndex = i; pickedFmt = null; refreshQuick(false) }

        view.findViewById<View>(R.id.qk_seg_video).setOnClickListener { switchMode(false) }
        view.findViewById<View>(R.id.qk_seg_audio).setOnClickListener { switchMode(true) }

        fun queue(startTime: Long?) {
            lifecycleScope.launch {
                resultViewModel.cancelUpdateItemData()
                resultViewModel.cancelUpdateFormatsItemData()
                dlViews.forEach { it.isEnabled = false }; schViews.forEach { it.isEnabled = false }
                val res = downloadCardViewModel.resultItem ?: result
                val chosen = pickedFmt ?: levels.getOrNull(lvlIndex)?.fmt
                val queued = withContext(Dispatchers.IO) {
                    val item = downloadViewModel.createDownloadItemFromResult(res, res.url, type)
                    item.incognito = incognito
                    if (chosen != null) item.format = chosen.copy()
                    if (type == DownloadType.audio) {
                        // keep the audio defaults from settings: cover, metadata, lyrics. Never split by chapters here,
                        // that skips the whole metadata / cover / lyrics chain.
                        item.audioPreferences.splitByChapters = false
                    } else {
                        item.videoPreferences.addChapters = stChap
                        item.videoPreferences.embedThumbnail = stThumb
                        // subtitles: on by default whenever the video has uploader-provided ones
                        if (stSubs && res.availableSubtitles.isNotEmpty()) {
                            item.videoPreferences.writeSubs = true
                            item.videoPreferences.embedSubs = true
                            if (item.videoPreferences.subsLanguages.isBlank()) item.videoPreferences.subsLanguages = pickSubLang(res.availableSubtitles)
                        } else if (!stSubs) {
                            item.videoPreferences.embedSubs = false
                            item.videoPreferences.writeSubs = false
                        }
                    }
                    if (startTime != null) {
                        item.status = DownloadRepository.Status.Scheduled.toString()
                        item.downloadStartTime = startTime
                    }
                    downloadViewModel.queueDownloads(listOf(item), ignoreDuplicates)
                }
                if (queued.message.isNotBlank()) Toast.makeText(requireContext(), queued.message, Toast.LENGTH_LONG).show()
                handleDuplicatesAndDismiss(queued.duplicateDownloadIDs)
            }
        }
        dlViews.forEach { it.setOnClickListener { queue(null) } }
        schViews.forEach { it.setOnClickListener { UiUtil.showDatePicker(parentFragmentManager, sharedPreferences) { c -> queue(c.timeInMillis) } } }

        view.findViewById<View>(R.id.qk_folder_row).setOnClickListener { showFolderSheet() }
        lifecycleScope.launch { resultViewModel.updatingFormats.collectLatest { refreshQuick() } }
        refreshQuick()
        ensureRealFormats()
    }

    private fun fmtSize(bytes: Long): Pair<String, String> {
        val mb = bytes / 1048576.0
        return if (mb >= 1000) Pair("%.1f".format(mb / 1024), "GB") else if (mb >= 10) Pair("%d".format(Math.round(mb)), "MB") else Pair("%.1f".format(mb), "MB")
    }

    private fun refreshQuick(rebuild: Boolean = true) {
        if (!::view.isInitialized) return
        runCatching {
            val res = downloadCardViewModel.resultItem ?: result
            val audio = type == DownloadType.audio
            val updating = resultViewModel.updatingData.value
            val title = if (res.title.isNotBlank()) res.title else if (updating) getString(R.string.loading) else res.url
            view.findViewById<TextView>(R.id.qk_title)?.text = title
            val sub = listOf(res.author, res.duration).filter { it.isNotBlank() }.joinToString(" · ")
            view.findViewById<TextView>(R.id.qk_sub)?.text = if (updating && sub.isBlank()) getString(R.string.please_wait) else sub
            for (id in listOf(R.id.qk_hero, R.id.full_hero)) {
                view.findViewById<DlHeroView>(id)?.apply { this.audio = audio; duration = res.duration; setThumb(res.thumb) }
            }
            if (!quickActive) return@runCatching
            // segmented switches
            val sv = view.findViewById<TextView>(R.id.qk_seg_video); val sa = view.findViewById<TextView>(R.id.qk_seg_audio)
            if (audio) styleSeg(sa, sv) else styleSeg(sv, sa)

            val loadingFormats = resultViewModel.updatingFormats.value
            if (rebuild) {
                val sig = "$audio|${res.formats.size}|${res.duration}"
                if (sig != lvlSig) {
                    val prev = levels.getOrNull(lvlIndex)?.label
                    levels = buildLevels(res, audio)
                    lvlSig = sig
                    lvlIndex = levels.indexOfFirst { it.label == prev }.takeIf { it >= 0 } ?: (levels.size - 1).coerceAtLeast(0)
                }
            }
            val maxBytes = levels.maxOfOrNull { it.bytes } ?: 0L
            val cols = levels.mapIndexed { i, l ->
                val frac = if (maxBytes > 0 && l.bytes > 0) (l.bytes.toFloat() / maxBytes).coerceIn(0.1f, 1f) else (i + 1f) / levels.size.coerceAtLeast(1)
                LedChartView.Col(l.label, compactSize(l.bytes, l.approx), frac)
            }
            view.findViewById<LedChartView>(R.id.qk_chart).setColumns(cols, lvlIndex)

            val lv = levels.getOrNull(lvlIndex)
            val fmtName = targetContainer(audio).ifBlank { lv?.fmt?.container?.takeIf { it.isNotBlank() } ?: if (audio) "MP3" else "MP4" }
            val sizeTxt = view.findViewById<TextView>(R.id.qk_size_sub)
            sizeTxt.text = when {
                lv == null -> if (loadingFormats) getString(R.string.dl_measuring) else ""
                lv.bytes <= 0 -> lv.label + " · " + fmtName.uppercase() + if (loadingFormats) " · " + getString(R.string.dl_measuring) else ""
                else -> lv.label + " · " + fmtName.uppercase() + " · " + compactSize(lv.bytes, lv.approx) + if (loadingFormats) " · " + getString(R.string.dl_measuring) else ""
            }
            view.findViewById<TextView>(R.id.qk_legend).text = if (levels.any { it.bytes > 0 }) getString(R.string.dl_size_legend) else ""

            val raw = currentRawPath(audio)
            val folderTxt = getString(R.string.dl_saved_to, folderName(raw))
            view.findViewById<TextView>(R.id.qk_folder)?.text = folderTxt

        }
    }

    private fun getDownloadItem(selectedTabPosition: Int = tabLayout.selectedTabPosition) : DownloadItem {
        return fragmentAdapter.getDownloadItem(selectedTabPosition)
    }

    private fun getAlsoAudioDownloadItem(finished: (it: DownloadItem) -> Unit) {
        try {
            val ff = fragmentAdapter.fragments[0] as DownloadAudioFragment
            getDownloadItem(1).videoPreferences.audioFormatIDs.apply {
                if (this.isNotEmpty()) {
                    ff.updateSelectedAudioFormat(this.first())
                }
            }
            finished(ff.downloadItem)
        }catch (e: Exception){
            val fragmentLifecycleCallback = object:
                FragmentManager.FragmentLifecycleCallbacks() {

                override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
                    fragmentManager?.unregisterFragmentLifecycleCallbacks(this)
                    val ff = (f as DownloadAudioFragment)
                    ff.requireView().post {
                        ff.updateSelectedAudioFormat(getDownloadItem(1).videoPreferences.audioFormatIDs.first())
                        finished(ff.downloadItem)
                    }
                    super.onFragmentStarted(fm, f)
                }


            }

            fragmentManager?.registerFragmentLifecycleCallbacks(fragmentLifecycleCallback, true)
            viewPager2.setCurrentItem(0, true)
        }
    }

    private fun initUpdateData() {
        kotlin.runCatching {
            if (result.url.isBlank()) {
                dismiss()
                return
            }
            if (resultViewModel.updatingData.value) return

            lifecycleScope.launch(Dispatchers.IO) {
                resultViewModel.updateItemData(result)
            }
        }
    }

    private fun initUpdateFormats(res: ResultItem){
        kotlin.runCatching {
            if (resultViewModel.updatingFormats.value) return
            CoroutineScope(SupervisorJob()).launch(Dispatchers.IO) {
                resultViewModel.updateFormatItemData(res)
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        lifecycleScope.launch {
            resultViewModel.cancelUpdateItemData()
            resultViewModel.cancelUpdateFormatsItemData()
            super.onDismiss(dialog)
        }
    }

    private fun handleDuplicatesAndDismiss(res: List<DownloadViewModel.AlreadyExistsIDs>) {
        if (activity is ShareActivity && res.isNotEmpty()) {
            //let the lifecycle listener handle it
        }else{
            dismiss()
        }
    }
}

