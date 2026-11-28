package dfast.app.ui.home

import dfast.app.util.OrbitColors

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Context.CLIPBOARD_SERVICE
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Patterns
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.View.GONE
import android.view.View.OnClickListener
import android.view.View.OnTouchListener
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.os.bundleOf
import androidx.core.view.children
import androidx.core.view.forEach
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dfast.app.MainActivity
import dfast.app.R
import dfast.app.database.enums.DownloadType
import dfast.app.database.models.ResultItem
import dfast.app.database.models.SearchSuggestionItem
import dfast.app.database.models.SearchSuggestionType
import dfast.app.database.viewmodel.DownloadCardViewModel
import dfast.app.database.viewmodel.DownloadViewModel
import dfast.app.database.viewmodel.HistoryViewModel
import dfast.app.database.viewmodel.ResultViewModel
import dfast.app.ui.adapter.HomeAdapter
import dfast.app.ui.adapter.SearchSuggestionsAdapter
import dfast.app.ui.more.cookies.WebViewActivity
import dfast.app.util.Extensions.enableFastScroll
import dfast.app.util.Extensions.isURL
import dfast.app.util.NotificationUtil
import dfast.app.util.ThemeUtil
import dfast.app.core.RuntimeManager
import dfast.app.util.UiUtil
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.search.SearchBar
import com.google.android.material.search.SearchView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.paging.LoadState
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.badge.ExperimentalBadgeUtils
import com.google.android.material.badge.BadgeUtils
import com.google.android.material.badge.BadgeDrawable
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL


class HomeFragment : Fragment(), HomeAdapter.OnItemClickListener, SearchSuggestionsAdapter.OnItemClickListener, OnClickListener,
    FailedFetchesBottomSheetDialog.Listener, UpdatesDialog.Listener {
    private var inputQueries: MutableList<String>? = null
    private lateinit var homeAdapter: HomeAdapter
    private var totalCount: Int = 0
    private var firstResult: ResultItem? = null
    private var searchSuggestionsAdapter: SearchSuggestionsAdapter? = null
    private var queriesConstraint: ConstraintLayout? = null

    private var downloadSelectedFab: ExtendedFloatingActionButton? = null
    private var downloadAllFab: ExtendedFloatingActionButton? = null
    private var clipboardFab: ExtendedFloatingActionButton? = null
    private var homeFabs: LinearLayout? = null
    private var notificationUtil: NotificationUtil? = null
    private var downloadQueue: ArrayList<ResultItem>? = null

    private lateinit var playlistNameFilterScrollView: HorizontalScrollView
    private lateinit var playlistNameFilterChipGroup: ChipGroup

    private lateinit var resultViewModel : ResultViewModel
    private lateinit var downloadViewModel : DownloadViewModel
    private lateinit var historyViewModel : HistoryViewModel
    private lateinit var downloadCardViewModel : DownloadCardViewModel

    private var fragmentView: View? = null
    private var activity: Activity? = null
    private var mainActivity: MainActivity? = null
    private var fragmentContext: Context? = null
    private var layoutinflater: LayoutInflater? = null
    private var shimmerCards: ShimmerFrameLayout? = null
    private var syncOrbRef: (() -> Unit)? = null
    private var searchBar: SearchBar? = null
    private var searchView: SearchView? = null
    private var providersChipGroup: ChipGroup? = null
    private var chipGroupDivider: View? = null
    private var queriesChipGroup: ChipGroup? = null
    private var recyclerView: RecyclerView? = null
    private var searchSuggestionsRecyclerView: RecyclerView? = null
    private var uiHandler: Handler? = null
    private var quickLaunchSheet = false
    private var sharedPreferences: SharedPreferences? = null
    private var actionMode: ActionMode? = null
    private var appBarLayout: AppBarLayout? = null
    private var materialToolbar: MaterialToolbar? = null
    private var loadingItems: Boolean = false
    private var queryList = mutableListOf<String>()

    private var showDownloadAllFab: Boolean = false
    private var showClipboardFab: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        fragmentView = inflater.inflate(R.layout.fragment_home, container, false)
        activity = getActivity()
        mainActivity = activity as MainActivity?
        quickLaunchSheet = false
        notificationUtil = NotificationUtil(requireContext())
        return fragmentView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        fragmentContext = context
        layoutinflater = LayoutInflater.from(context)
        uiHandler = Handler(Looper.getMainLooper())

        downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]
        historyViewModel = ViewModelProvider(this)[HistoryViewModel::class.java]
        downloadCardViewModel = ViewModelProvider(requireActivity())[DownloadCardViewModel::class.java]

        downloadQueue = ArrayList()

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())

        //initViews
        searchBar = view.findViewById(R.id.search_bar)
        searchView = view.findViewById(R.id.search_view)
        appBarLayout = view.findViewById(R.id.home_appbarlayout)
        materialToolbar = view.findViewById(R.id.home_toolbar)
        materialToolbar?.let { tb ->
            tb.title = ""
            val dd = resources.displayMetrics.density
            tb.addView(android.widget.ImageView(requireContext()).apply { setImageResource(R.drawable.ic_wordmark_h2); adjustViewBounds = true; contentDescription = getString(R.string.app_name) },
                androidx.appcompat.widget.Toolbar.LayoutParams((92 * dd).toInt(), (32 * dd).toInt(), android.view.Gravity.CENTER))
        }
        queriesChipGroup = view.findViewById(R.id.queries)
        queriesConstraint = view.findViewById(R.id.queries_constraint)
        homeFabs = view.findViewById(R.id.home_fabs)
        downloadSelectedFab = homeFabs!!.findViewById(R.id.download_selected_fab)
        downloadAllFab = homeFabs!!.findViewById(R.id.download_all_fab)
        clipboardFab = homeFabs!!.findViewById(R.id.copied_url_fab)
        playlistNameFilterScrollView = view.findViewById(R.id.playlist_selection_chips_scrollview)
        playlistNameFilterChipGroup = view.findViewById(R.id.playlist_selection_chips)

        runCatching { materialToolbar!!.title = "" }

        homeAdapter =
            HomeAdapter(
                this,
                requireActivity()
            )
        recyclerView = view.findViewById(R.id.recyclerViewHome)
        recyclerView?.layoutManager = GridLayoutManager(context, resources.getInteger(R.integer.grid_size))
        recyclerView?.adapter = homeAdapter
        recyclerView?.enableFastScroll()

        shimmerCards = view.findViewById(R.id.shimmer_results_framelayout)

        val orbContainer = view.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.orb_container)
        val feedScroll = view.findViewById<androidx.core.widget.NestedScrollView>(R.id.home_feed_scroll)
        fun buildFeed() {
            feedScroll?.removeAllViews()
            feedScroll?.addView(HomeTabs.build(requireContext(), viewLifecycleOwner) { topic ->
                searchView?.setText(topic); searchView?.let { initSearch(it) }
            })
        }
        buildFeed()
        orbContainer?.setOnRefreshListener {
            HomeFeed.clearCache(); buildFeed()
            orbContainer.postDelayed({ orbContainer.isRefreshing = false }, 900)
        }
        syncOrbRef = {
            val idle = homeAdapter.itemCount == 0 && !loadingItems && searchBar?.text.isNullOrBlank()
            orbContainer?.visibility = if (idle) View.VISIBLE else View.GONE
        }
        val syncOrb = { syncOrbRef?.invoke(); Unit }
        homeAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() { syncOrb() }
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { syncOrb() }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { syncOrb() }
        })



        searchSuggestionsAdapter = SearchSuggestionsAdapter(
                this,
                requireActivity()
            )
        searchSuggestionsRecyclerView = view.findViewById(R.id.search_suggestions_recycler)
        searchSuggestionsRecyclerView?.layoutManager = LinearLayoutManager(context)
        searchSuggestionsRecyclerView?.adapter = searchSuggestionsAdapter
        searchSuggestionsRecyclerView?.itemAnimator = null
        searchSuggestionsRecyclerView?.isNestedScrollingEnabled = false

        val progressBar = view.findViewById<View>(R.id.progress)

        resultViewModel = ViewModelProvider(requireActivity())[ResultViewModel::class.java]

        initToolbarMenu()

        lifecycleScope.launch {
            // while the in-app download sheet is open, Home keeps showing its own page (the sheet's row is not a search result)
            // flatMapLatest, not combine: combine re-emitted the SAME PagingData when the sheet closed, and Paging allows a PagingData to be collected once (crash "collect twice")
            dfast.app.util.InAppSheet.active.flatMapLatest { inApp ->
                if (inApp) kotlinx.coroutines.flow.flowOf(androidx.paging.PagingData.empty()) else resultViewModel.paginatedItems
            }.collectLatest { data -> homeAdapter.submitData(data) }
        }

        homeAdapter.addLoadStateListener { loadStates ->
            val isNotLoading = loadStates.refresh is LoadState.NotLoading
            if (isNotLoading) {
                val size = resultViewModel.totalCount.value;
                val firstResult = resultViewModel.firstResult.value;

                progressBar.isVisible = loadingItems && size > 0
                if(resultViewModel.repository.itemCount.value > 1 || resultViewModel.repository.itemCount.value == -1){
                    showDownloadAllFab = size > 1 && firstResult!!.playlistTitle.isNotEmpty() && !loadingItems
                    downloadAllFab!!.isVisible = showDownloadAllFab
                }else if (resultViewModel.repository.itemCount.value == 1){
                    if (sharedPreferences!!.getBoolean("download_card", true)){
                        if(size == 1 && quickLaunchSheet && !dfast.app.util.InAppSheet.active.value && parentFragmentManager.findFragmentByTag("downloadSingleSheet") == null){
                            showSingleDownloadSheet(
                                firstResult!!,
                                DownloadType.valueOf(sharedPreferences!!.getString("preferred_download_type", "video")!!)
                            )
                        }
                    }
                }else{
                    showDownloadAllFab = false
                    downloadAllFab!!.visibility = GONE
                }
                quickLaunchSheet = true
            }
        }

        lifecycleScope.launch {
            resultViewModel.totalCount.collectLatest {
                totalCount = it
                searchBar?.menu?.findItem(R.id.select_range)?.isVisible = it >= 3
            }
        }

        lifecycleScope.launch {
            resultViewModel.firstResult.collectLatest {
                firstResult = it
            }
        }

        lifecycleScope.launch {
            resultViewModel.playlistResults.collectLatest {
                updateMultiplePlaylistResults(it
                    .filter { it2 -> it2 != "DFAST_SEARCH" }
                )
            }
        }

        initMenu()
        downloadSelectedFab?.tag = "downloadSelected"
        downloadSelectedFab?.setOnClickListener(this)
        downloadAllFab?.tag = "downloadAll"
        downloadAllFab?.setOnClickListener(this)

        if (arguments?.getString("url") != null){
            val url = requireArguments().getString("url")
            if (inputQueries == null) inputQueries = mutableListOf()
            searchBar?.setText(url)
            val argList = url!!.split("\n").filter { it.isURL() }.toMutableList()
            argList.removeAll(listOf("", null))
            inputQueries!!.addAll(argList)
        }

        if (inputQueries != null) {
            lifecycleScope.launch(Dispatchers.IO){
                resultViewModel.deleteAll()
                resultViewModel.parseQueries(inputQueries!!){}
                inputQueries = null
            }
        }

        searchView?.addTransitionListener { _, _, newState ->
            if (newState == SearchView.TransitionState.SHOWING){
                mainActivity?.hideBottomNavigation()
            }else if (newState == SearchView.TransitionState.HIDING){
                mainActivity?.showBottomNavigation()
            } else if (newState == SearchView.TransitionState.HIDDEN) {
                appBarLayout?.setExpanded(true, true)
            }
        }

        mainActivity?.onBackPressedDispatcher?.addCallback(this) {
            if (searchView?.isShowing == true) {
                searchView?.hide()
            }else{
                mainActivity?.finishAffinity()
            }
        }

        lifecycleScope.launch {
            launch{
                resultViewModel.uiState.collectLatest { res ->
                    if (res.errorMessage != null){
                        val isSingleQueryAndURL = queryList.size == 1 && Patterns.WEB_URL.matcher(queryList.first()).matches()

                        runCatching {
                            UiUtil.handleNoResults(requireActivity(), res.errorMessage!!,
                                url = if (isSingleQueryAndURL) queryList.first() else null,
                                continueAnyway = isSingleQueryAndURL,
                                continued = {
                                    resultViewModel.removeFailedQuery(queryList.first())
                                    onDownloadAnyway(queryList.first())
                                },
                                cookieFetch = {
                                    val myIntent = Intent(requireContext(), WebViewActivity::class.java)
                                    myIntent.putExtra("url", "https://${URL(queryList.first()).host}")
                                    cookiesFetchedResultLauncher.launch(myIntent)
                                },
                                closed = {}
                            )
                        }
                        resultViewModel.uiState.update {it.copy(errorMessage  = null) }
                    }

                    loadingItems = res.processing
                    syncOrbRef?.invoke()
                    progressBar.isVisible = loadingItems && totalCount > 0
                    if (res.processing){
                        recyclerView?.setPadding(0,0,0,0)
                        shimmerCards!!.startShimmer()
                        shimmerCards!!.visibility = VISIBLE
                    }else{
                        recyclerView?.setPadding(0,0,0,100)
                        shimmerCards!!.stopShimmer()
                        shimmerCards!!.visibility = GONE

                        showDownloadAllFab = totalCount > 1 && firstResult?.playlistTitle.orEmpty().isNotEmpty()
                        downloadAllFab!!.isVisible = showDownloadAllFab
                    }
                }
            }
        }

        lifecycleScope.launch {
            launch{
                downloadViewModel.alreadyExistsUiState.collectLatest { res ->
                    if (res.isNotEmpty()){
                        withContext(Dispatchers.Main){
                            val bundle = bundleOf(
                                Pair("duplicates", ArrayList(res))
                            )
                            delay(500)
                            findNavController().navigate(R.id.downloadsAlreadyExistDialog, bundle)
                        }
                        downloadViewModel.alreadyExistsUiState.value = mutableListOf()
                    }
                }
            }
        }

    }

    private var cookiesFetchedResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            sharedPreferences?.edit()?.putBoolean("use_cookies", true)?.apply()
            startSearch()
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch(Dispatchers.IO) {
            val st = dfast.app.util.DbInAppRowStore(requireContext().applicationContext)
            if (dfast.app.util.InAppSheet.recover(st)) android.util.Log.d("DFastInApp", "home start: removed a row left by a closed or killed sheet")
        }
        activity?.intent?.let { i -> if (i.getBooleanExtra("dfast_update", false)) { i.removeExtra("dfast_update"); view?.post { maybeAutoShowUpdate(true) } } else view?.post { maybeAutoShowUpdate(false) } }
        if(arguments?.getString("url") == null){
            if (!resultViewModel.uiState.value.processing){
                resultViewModel.checkTrending()
            }
        }else{
            arguments?.remove("url")
        }

        if (arguments?.getBoolean("showDownloadsWithUpdatedFormats") == true){
            notificationUtil?.cancelDownloadNotification(NotificationUtil.FORMAT_UPDATING_FINISHED_NOTIFICATION_ID)
            arguments?.remove("showDownloadsWithUpdatedFormats")
            CoroutineScope(Dispatchers.IO).launch {
                val ids = arguments?.getLongArray("downloadIds") ?: return@launch
                downloadViewModel.turnDownloadItemsToProcessingDownloads(ids.toList(), deleteExisting = true)
                withContext(Dispatchers.Main){
                    findNavController().navigate(R.id.downloadMultipleBottomSheetDialog2)
                }
            }
        }
        
        if(arguments?.getBoolean("search") == true){
            arguments?.remove("search")
            requireView().post {
                searchBar?.performClick()
            }
        }

        if (searchView?.currentTransitionState == SearchView.TransitionState.SHOWN){
            updateSearchViewItems(searchView?.editText?.text.toString())
        }

        requireView().post {
            checkClipboard().apply {
                this?.apply {
                    showClipboardFab = this.isNotEmpty()
                    clipboardFab?.isVisible = showClipboardFab
                    clipboardFab?.setOnClickListener {
                        if (this.size == 1){
                            searchView?.setText(this.first())
                            showClipboardFab = false
                            clipboardFab?.isVisible = false
                            initSearch(searchView!!)
                        }else{
                            searchBar?.performClick()
                            lifecycleScope.launch {
                                withContext(Dispatchers.IO){
                                    delay(500)
                                }
                                this@apply.forEach {
                                    onSearchSuggestionAdd(it)
                                }
                            }
                        }
                    }
                }

                lifecycleScope.launch {
                    clipboardFab?.extend()
                    delay(1000)
                    clipboardFab?.shrink()
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initMenu() {
        val queriesConstraint = requireView().findViewById<ConstraintLayout>(R.id.queries_constraint)
        val queriesInitStartBtn = queriesConstraint.findViewById<MaterialButton>(R.id.init_search_query)
        val isRightToLeft = resources.getBoolean(R.bool.is_right_to_left)

        providersChipGroup = searchView!!.findViewById(R.id.providers)
        (providersChipGroup?.parent?.parent as? LinearLayout)?.let { par ->
            searchHomeBox = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            par.addView(searchHomeBox, 0)
        }
        val providers = resources.getStringArray(R.array.search_engines)
        val providersValues = resources.getStringArray(R.array.search_engines_values).toMutableList()

        for(i in providersValues.indices){
            val provider = providers[i]
            val providerValue = providersValues[i]
            val tmp = layoutinflater!!.inflate(R.layout.filter_chip, providersChipGroup, false) as Chip
            tmp.text = provider
            tmp.id = i
            tmp.tag = providersValues[i]

            tmp.setOnClickListener {
                val editor = sharedPreferences?.edit()
                editor?.putString("search_engine", providerValue)
                editor?.apply()

            }

            providersChipGroup?.addView(tmp)
        }

        chipGroupDivider = requireView().findViewById(R.id.chipGroupDivider)

        searchView!!.addTransitionListener { _, _, newState ->
            if (newState == SearchView.TransitionState.SHOWN) {
                val currentProvider = sharedPreferences?.getString("search_engine", "ytsearch")
                providersChipGroup?.children?.forEach {
                    val tmp = providersChipGroup?.findViewById<Chip>(it.id)
                    if (tmp?.tag?.equals(currentProvider) == true) {
                        tmp.isChecked = true
                        return@forEach
                    }
                }

                if (Patterns.WEB_URL.matcher(searchBar!!.text.toString()).matches() && searchBar!!.text.isNotBlank()){
                    providersChipGroup?.visibility = GONE
                    chipGroupDivider?.visibility = GONE
                }else{
                    providersChipGroup?.visibility = VISIBLE
                    chipGroupDivider?.visibility = VISIBLE
                }

                updateSearchViewItems(searchView!!.editText.text.toString())
                refreshSearchHome()
            }
        }

        searchView!!.editText.doAfterTextChanged {
            if (searchView!!.currentTransitionState != SearchView.TransitionState.SHOWN) return@doAfterTextChanged
            updateSearchViewItems(it.toString())
            refreshSearchHome()
        }

        searchView!!.editText.setOnTouchListener(OnTouchListener { _, event ->
            try{
                val drawableLeft = 0
                val drawableRight = 2
                if (event.action == MotionEvent.ACTION_UP) {
                    if (
                        (isRightToLeft && (event.x < (searchView!!.editText.left - searchView!!.editText.compoundDrawables[drawableLeft].bounds.width()))) ||
                        (!isRightToLeft && (event.x > (searchView!!.editText.right - searchView!!.editText.compoundDrawables[drawableRight].bounds.width())))
                        ){

                        val present = queriesChipGroup!!.children.firstOrNull { (it as Chip).text.toString() == searchView!!.editText.text.toString() }
                        if (present == null) {
                            val chip = layoutinflater!!.inflate(R.layout.input_chip, queriesChipGroup, false) as Chip
                            chip.text = searchView!!.editText.text
                            chip.chipBackgroundColor = ColorStateList.valueOf(MaterialColors.getColor(requireContext(), R.attr.colorSecondaryContainer, Color.BLACK))
                            chip.setOnClickListener {
                                queriesChipGroup!!.removeView(chip)
                                if (queriesChipGroup!!.childCount == 0) queriesConstraint.visibility = GONE
                            }
                            queriesChipGroup!!.addView(chip)
                        }
                        if (queriesChipGroup!!.childCount == 0) queriesConstraint.visibility = GONE
                        else queriesConstraint.visibility = VISIBLE
                        searchView!!.editText.setText("")
                        return@OnTouchListener true

                    }
                }
            }catch (ignored: Exception){}
            false
        })

        searchView!!.editText.setOnEditorActionListener { _, _, _ ->
            submitTyped()
            true
        }

        searchBar!!.setOnMenuItemClickListener { m: MenuItem ->
            when (m.itemId) {
                R.id.copy_urls -> {
                    lifecycleScope.launch {
                        val urls = withContext(Dispatchers.IO){
                            resultViewModel.getURLs()
                        }

                        UiUtil.copyToClipboard(urls.distinct().joinToString("\n"), requireActivity())
                    }
                }
                R.id.delete_results -> {
                    resultViewModel.clearResults()
                    searchBar!!.setText(""); syncOrbRef?.invoke()
                    showDownloadAllFab = false
                    downloadAllFab!!.visibility = GONE
                    downloadSelectedFab!!.visibility = GONE
                }
                R.id.select_range -> {
                    UiUtil.showSelectRangeDialog(requireActivity(), totalCount) { range ->
                        lifecycleScope.launch {
                            val ids = withContext(Dispatchers.IO) {
                                resultViewModel.getAllIds()
                            }
                            val itemsBetween = ids.filterIndexed { index, _ -> index >= range.first && index <= range.second }
                            if (itemsBetween.isEmpty()) return@launch

                            homeAdapter.checkMultipleItems(itemsBetween)
                            if (actionMode == null) actionMode = (getActivity() as AppCompatActivity?)!!.startSupportActionMode(contextualActionBar)
                            actionMode?.title = "${itemsBetween.size} ${getString(R.string.selected)}"
                            actionMode?.menu?.findItem(R.id.select_between)?.isVisible = false
                        }
                    }
                }
                R.id.delete_search -> {
                    resultViewModel.deleteAllSearchQueryHistory()
                    searchSuggestionsAdapter?.submitList(listOf())
                }
            }
            true
        }
        queriesChipGroup!!.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (queriesChipGroup!!.childCount == 0) queriesConstraint.visibility = GONE
            else queriesConstraint.visibility = VISIBLE
        }

        queriesInitStartBtn.setOnClickListener {
            initSearch(searchView!!)
        }
    }

    @SuppressLint("InflateParams")

    private var searchHomeBox: LinearLayout? = null
    private var trendingCache: List<String>? = null
    private var homeToken = 0

    private fun submitTyped() {
        val typed = searchView!!.editText.text.toString().trim()
        val engine = sharedPreferences?.getString("search_engine", "ytsearch")
        if (typed.isNotEmpty() && !typed.contains("\n") && !Patterns.WEB_URL.matcher(typed).matches() && !typed.startsWith("http") &&
            (queriesChipGroup?.childCount ?: 0) == 0 && (engine == null || engine == "ytsearch")) {
            val appCtx = requireContext().applicationContext
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { dfast.app.database.DBManager.getInstance(appCtx).searchHistoryDao.insert(dfast.app.database.models.SearchHistoryItem(0, typed)) }
            }
            startActivity(dfast.app.ui.play.SearchResultsActivity.intent(requireContext(), typed))
            searchView!!.hide()
        } else {
            initSearch(searchView!!)
        }
    }

    /** Recent searches, trending and Browse sites, shown while the search bar is focused and empty. */
    private fun refreshSearchHome() {
        val box = searchHomeBox ?: return
        val ctx = context ?: return
        val typing = searchView?.editText?.text?.isNotEmpty() == true
        box.visibility = if (typing) GONE else VISIBLE
        if (typing) return
        val tk = ++homeToken
        val d = resources.displayMetrics.density
        fun head(t: String, onTrash: (() -> Unit)?): View {
            val r = LinearLayout(ctx).apply { gravity = android.view.Gravity.CENTER_VERTICAL; setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (6 * d).toInt()) }
            r.addView(TextView(ctx).apply { text = t; textSize = 13f; setTextColor(dfast.app.util.OrbitColors.inkSoft(ctx)) }, LinearLayout.LayoutParams(0, -2, 1f))
            if (onTrash != null) r.addView(dfast.app.util.OIcons.view(ctx, R.drawable.ic_o_delete, 20, dfast.app.util.OrbitColors.inkSoft(ctx), 8) { onTrash() })
            return r
        }
        fun chips(list: List<String>): View {
            val g = com.google.android.material.chip.ChipGroup(ctx).apply { setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0) }
            list.forEach { q ->
                g.addView(com.google.android.material.chip.Chip(ctx).apply {
                    text = q; isCheckable = false
                    setOnClickListener { searchView!!.editText.setText(q); submitTyped() }
                })
            }
            return g
        }
        lifecycleScope.launch {
            val hist = withContext(Dispatchers.IO) {
                runCatching { resultViewModel.getSearchHistory().map { it.query }.filter { !it.contains("\n") && !it.startsWith("http") }.distinct().take(8) }.getOrDefault(emptyList())
            }
            if (tk != homeToken) return@launch
            box.removeAllViews()
            if (hist.isNotEmpty()) {
                box.addView(head(getString(R.string.o_recent_searches)) { resultViewModel.deleteAllSearchQueryHistory(); refreshSearchHome() })
                box.addView(chips(hist))
            }
            val trendBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            box.addView(trendBox)
            // browse sites
            box.addView(head(getString(R.string.o_browse_sites), null))
            val row = LinearLayout(ctx).apply { setPadding((12 * d).toInt(), 0, (12 * d).toInt(), (8 * d).toInt()) }
            fun item(icon: Int, color: Int, label: String, click: () -> Unit) {
                val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER; setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0) }
                col.addView(android.widget.ImageView(ctx).apply {
                    setImageResource(icon); setColorFilter(OrbitColors.iconDark(android.graphics.Color.WHITE)); val pp = (11 * d).toInt(); setPadding(pp, pp, pp, pp)
                    background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(color) }
                }, LinearLayout.LayoutParams((46 * d).toInt(), (46 * d).toInt()))
                col.addView(TextView(ctx).apply { text = label; textSize = 11f; setTextColor(dfast.app.util.OrbitColors.inkSoft(ctx)); setPadding(0, (4 * d).toInt(), 0, 0) })
                col.setOnClickListener { click() }
                row.addView(col, LinearLayout.LayoutParams((68 * d).toInt(), -2))
            }
            dfast.app.util.SitesStore.all(ctx).take(5).forEach { st ->
                item(dfast.app.util.SitesStore.iconRes(ctx, st), st.color, st.name.substringBefore(" ")) { startActivity(dfast.app.ui.play.SiteBrowserActivity.intent(ctx, st.name, st.url)) }
            }
            item(R.drawable.ic_o_more_h, 0xFFF6C90E.toInt(), getString(R.string.o_view_all)) { startActivity(android.content.Intent(ctx, dfast.app.ui.play.SitesActivity::class.java)) }
            box.addView(android.widget.HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(row) })
            // trending (titles of trending videos)
            val cached = trendingCache
            val tr = cached ?: withContext(Dispatchers.IO) {
                runCatching { dfast.app.util.extractors.newpipe.NewPipeUtil(ctx).getTrending().map { it.title.take(32) }.filter { it.isNotBlank() }.take(8) }.getOrDefault(emptyList())
            }
            if (tr.isNotEmpty()) trendingCache = tr
            if (tk == homeToken && tr.isNotEmpty()) {
                trendBox.addView(head(getString(R.string.o_trending_searches), null))
                trendBox.addView(chips(tr))
            }
        }
    }

    private fun updateSearchViewItems(searchQuery: String) = lifecycleScope.launch(Dispatchers.Main) {
        lifecycleScope.launch {
            if (searchView!!.editText.text.isEmpty()){
                searchView!!.editText.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
            }else{
                searchView!!.editText.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_plus, 0)
            }

            val combinedList = mutableListOf<SearchSuggestionItem>()

            val history = withContext(Dispatchers.IO){
                resultViewModel.getSearchHistory().map { it.query }.filter { searchQuery.isNotEmpty() && it.contains(searchQuery, ignoreCase = true) }
            }.map {
                SearchSuggestionItem(it, SearchSuggestionType.HISTORY)
            }
            val suggestions = if (sharedPreferences!!.getBoolean("search_suggestions", false)){
                withContext(Dispatchers.IO){
                    resultViewModel.getSearchSuggestions(searchQuery)
                }
            }else{
                emptyList()
            }.map {
                SearchSuggestionItem(it, SearchSuggestionType.SUGGESTION)
            }

            combinedList.addAll(history)
            combinedList.addAll(suggestions)

            val url = checkClipboard()
            url?.apply {
                var alreadyHasThem = this.all { queriesChipGroup?.children?.any { c -> (c as Chip).text.contains(it) } == true }
                if (this.isNotEmpty() && !alreadyHasThem){
                    combinedList.add(0, SearchSuggestionItem(this.joinToString("\n"), SearchSuggestionType.CLIPBOARD))
                }
            }

            searchSuggestionsAdapter?.submitList(combinedList)

//            history.forEach { s ->
//                val v = LayoutInflater.from(fragmentContext).inflate(R.layout.search_suggestion_item, null)
//                val textView = v.findViewById<TextView>(R.id.suggestion_text)
//                textView.text = s
//                textView.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_restore, 0, 0, 0)
//                Handler(Looper.getMainLooper()).post {
//                    searchHistoryLinearLayout!!.addView(
//                        v
//                    )
//                }
//                textView.setOnClickListener {
//                    searchView!!.setText(s)
//                    initSearch(searchView!!)
//                }
//                textView.setOnLongClickListener {
//                    val deleteDialog = MaterialAlertDialogBuilder(requireContext())
//                    deleteDialog.setTitle(getString(R.string.you_are_going_to_delete) + " \"" + s + "\"!")
//                    deleteDialog.setNegativeButton(getString(R.string.cancel)) { dialogInterface: DialogInterface, _: Int -> dialogInterface.cancel() }
//                    deleteDialog.setPositiveButton(getString(R.string.ok)) { _: DialogInterface?, _: Int ->
//                        searchHistoryLinearLayout!!.removeView(v)
//                        resultViewModel.removeSearchQueryFromHistory(s)
//                    }
//                    deleteDialog.show()
//                    true
//                }
//
//                val mb = v.findViewById<ImageButton>(R.id.set_search_query_button)
//                mb.setOnClickListener {
//                    searchView!!.editText.setText(s)
//                    searchView!!.editText.setSelection(searchView!!.editText.length())
//                }
//            }
//            searchHistoryLinearLayout!!.isVisible = history.isNotEmpty()
//            if (linkYouCopied.findViewById<TextView>(R.id.suggestion_text).text.isNotEmpty()){
//                linkYouCopied.visibility = VISIBLE
//                clipboardFab?.isVisible = true
//            }
//
//            suggestions.forEach { s ->
//                val v = LayoutInflater.from(fragmentContext)
//                    .inflate(R.layout.search_suggestion_item, null)
//                val textView = v.findViewById<TextView>(R.id.suggestion_text)
//                textView.text = s
//                Handler(Looper.getMainLooper()).post {
//                    searchSuggestionsLinearLayout!!.addView(
//                        v
//                    )
//                }
//                textView.setOnClickListener {
//                    searchView!!.setText(s)
//                    initSearch(searchView!!)
//                }
//                val mb = v.findViewById<ImageButton>(R.id.set_search_query_button)
//                mb.setOnClickListener {
//                    searchView!!.editText.setText(s)
//                    searchView!!.editText.setSelection(searchView!!.editText.length())
//                }
//            }
//            searchSuggestionsLinearLayout!!.isVisible = suggestions.isNotEmpty()

            if (Patterns.WEB_URL.matcher(searchView!!.editText.text).matches()){
                providersChipGroup?.visibility = GONE
                chipGroupDivider?.visibility = GONE
            }else{
                providersChipGroup?.visibility = VISIBLE
                chipGroupDivider?.visibility = VISIBLE
            }
        }

    }

    private fun initSearch(searchView: SearchView){

        queryList = mutableListOf()
        if (queriesChipGroup!!.childCount > 0){
            queriesChipGroup!!.children.forEach {
                val query = (it as Chip).text.toString().trim {it2 -> it2 <= ' '}
                if (query.isNotEmpty()){
                    queryList.add(query)
                }
            }
            queriesChipGroup!!.removeAllViews()
        }
        if (searchView.editText.text.isNotBlank()) {
            queryList.add(searchView.editText.text.toString())
        }

        if (queryList.isEmpty()) return
        if (queryList.size == 1){
            searchBar!!.setText(searchView.text); syncOrbRef?.invoke()
        }

        searchView.hide()
        if(!sharedPreferences!!.getBoolean("incognito", false)){
            queryList.forEach { q ->
                resultViewModel.addSearchQueryToHistory(q)
            }
        }
        startSearch()
    }

    private fun startSearch() {
        lifecycleScope.launch(Dispatchers.IO){
            resultViewModel.deleteAll()
            if(sharedPreferences!!.getBoolean("quick_download", false) || sharedPreferences!!.getString("preferred_download_type", "video") == "command"){
                if (queryList.size == 1 && Patterns.WEB_URL.matcher(queryList.first()).matches()){
                    if (sharedPreferences!!.getBoolean("download_card", true)) {
                        withContext(Dispatchers.Main){
                            showSingleDownloadSheet(
                                resultItem = downloadViewModel.createEmptyResultItem(queryList.first()),
                                type = DownloadType.valueOf(sharedPreferences!!.getString("preferred_download_type", "video")!!)
                            )
                        }
                    } else {
                        val downloadItem = downloadViewModel.createDownloadItemFromResult(
                            result = downloadViewModel.createEmptyResultItem(queryList.first()),
                            givenType = DownloadType.valueOf(sharedPreferences!!.getString("preferred_download_type", "video")!!)
                        )
                        downloadViewModel.queueDownloads(listOf(downloadItem))
                    }

                }else{
                    resultViewModel.parseQueries(queryList){}
                }
            }else{
                resultViewModel.parseQueries(queryList){}
            }
        }
    }

    fun scrollToTop() {
        recyclerView!!.scrollToPosition(0)
        runCatching { (searchBar!!.parent as AppBarLayout).setExpanded(true, true) }
    }

    @SuppressLint("ResourceType")
    override fun onButtonClick(item: ResultItem, type: DownloadType?) {
        if (sharedPreferences!!.getBoolean("download_card", true)) {
            showSingleDownloadSheet(item, type!!)
        } else {
            lifecycleScope.launch{
                val downloadItem = withContext(Dispatchers.IO){
                    downloadViewModel.createDownloadItemFromResult(
                        result = item,
                        givenType = type!!)
                }
                downloadViewModel.queueDownloads(listOf(downloadItem))
            }
        }
    }

    override fun onQualityDownload(item: ResultItem, format: dfast.app.database.models.Format) {
        lifecycleScope.launch {
            val downloadItem = withContext(Dispatchers.IO) {
                downloadViewModel.createDownloadItemFromResult(result = item, givenType = DownloadType.video).also { it.format = format }
            }
            downloadViewModel.queueDownloads(listOf(downloadItem))
        }
    }

    override fun onLongButtonClick(item: ResultItem, type: DownloadType?) {
        showSingleDownloadSheet(item, type!!)
    }

    @OptIn(ExperimentalBadgeUtils::class)
    private fun initToolbarMenu() {
        val toolbar = materialToolbar ?: return
        toolbar.setNavigationIcon(R.drawable.ic_menu_drawer)
        toolbar.setNavigationOnClickListener { (activity as? dfast.app.MainActivity)?.openDrawer() }
        toolbar.inflateMenu(R.menu.home_toolbar_menu)
        val failedItem = toolbar.menu.findItem(R.id.failed_fetches)
        val updatesItem = toolbar.menu.findItem(R.id.app_updates)
        // the button is a custom action view, so it doesn't reach the menu item listener
        updatesItem.actionView?.findViewById<View>(R.id.update_button)?.setOnClickListener { showAvailableUpdates() }
        toolbar.setOnMenuItemClickListener { m ->
            when (m.itemId) {
                R.id.failed_fetches -> {
                    showFailedFetches()
                    true
                }
                R.id.app_updates -> {
                    showAvailableUpdates()
                    true
                }
                else -> false
            }
        }

        val badge = BadgeDrawable.create(requireContext())
        var badgeAttached = false
        val updateBadge = BadgeDrawable.create(requireContext())
        var updateBadgeAttached = false

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    resultViewModel.availableUpdates.collect { list ->
                        updatesItem.isVisible = list.isNotEmpty()
                        // bell C: a red number badge with the count of pending updates
                        updateBadge.number = list.size
                        updateBadge.backgroundColor = 0xFFFF3B45.toInt()
                        updateBadge.badgeTextColor = 0xFFFFFFFF.toInt()
                        if (list.isNotEmpty() && !updateBadgeAttached) {
                            toolbar.post {
                                if (updatesItem.isVisible && !updateBadgeAttached) {
                                    BadgeUtils.attachBadgeDrawable(updateBadge, toolbar, R.id.app_updates); updateBadgeAttached = true
                                }
                            }
                        } else if (list.isEmpty() && updateBadgeAttached) {
                            BadgeUtils.detachBadgeDrawable(updateBadge, toolbar, R.id.app_updates); updateBadgeAttached = false
                        }
                        maybeAutoShowUpdate(false)
                    }
                }
                launch {
                    resultViewModel.failedQueries.collect { list ->
                        failedItem.isVisible = list.isNotEmpty()
                        badge.number = list.size
                        badge.badgeTextColor = requireContext().getColor(R.color.black)
                        badge.backgroundColor = requireContext().getColor(R.color.white)

                        if (list.isNotEmpty() && !badgeAttached) {
                            // the action view only exists after the toolbar lays out the now visible item
                            toolbar.post {
                                if (failedItem.isVisible && !badgeAttached) {
                                    BadgeUtils.attachBadgeDrawable(badge, toolbar, R.id.failed_fetches)
                                    badgeAttached = true
                                }
                            }
                        } else if (list.isEmpty() && badgeAttached) {
                            BadgeUtils.detachBadgeDrawable(badge, toolbar, R.id.failed_fetches)
                            badgeAttached = false
                        }
                    }
                }
                launch {
                    resultViewModel.failedQueriesEvent.collect { count ->
                        Snackbar.make(requireView(), resources.getQuantityString(R.plurals.items_failed_to_fetch, count, count), Snackbar.LENGTH_LONG)
                            .setAction(R.string.view) { showFailedFetches() }
                            .show()
                    }
                }
            }
        }
    }

    /**
     * Opens the update dialog by itself, on Home only, following UpdateReminder (at once, then 24 h, then 3 days, then never).
     * A tap on the update notification forces it. Android 13+: asks for the notification permission once, with the reason, first.
     */
    private fun maybeAutoShowUpdate(force: Boolean) {
        val act = activity as? dfast.app.MainActivity ?: return
        if (!isResumed || act.isFinishing || act.isDestroyed || autoUpdateBusy) return
        val app = resultViewModel.availableUpdates.value.filterIsInstance<ResultViewModel.AvailableUpdate.App>().firstOrNull() ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(act)
        val tag = app.release.tag_name
        val st = dfast.app.util.UpdateReminder.State(prefs.getString("upd_remind_tag", "") ?: "", prefs.getInt("upd_remind_count", 0), prefs.getLong("upd_remind_last", 0L))
        val now = System.currentTimeMillis()
        if (!force && !dfast.app.util.UpdateReminder.shouldShow(tag, st, now)) return
        autoUpdateBusy = true
        val n = dfast.app.util.UpdateReminder.afterShown(tag, st, now)
        prefs.edit().putString("upd_remind_tag", n.tag).putInt("upd_remind_count", n.shown).putLong("upd_remind_last", n.lastShownAt).apply()
        val open = { autoUpdateBusy = false; if (isResumed) onUpdateSelected(app) }
        val needPerm = android.os.Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("upd_notif_asked", false) &&
            androidx.core.content.ContextCompat.checkSelfPermission(act, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!needPerm) { open(); return }
        prefs.edit().putBoolean("upd_notif_asked", true).apply()
        MaterialAlertDialogBuilder(act).setMessage(R.string.o_update_notify_why).setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                androidx.core.app.ActivityCompat.requestPermissions(act, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4711); open()
            }
            .setNegativeButton(R.string.o_update_later) { _, _ -> open() }.show()
    }
    private var autoUpdateBusy = false

    private fun showAvailableUpdates() {
        if (childFragmentManager.findFragmentByTag("updates") != null) return
        UpdatesDialog().show(childFragmentManager, "updates")
    }

    override fun onUpdateSelected(update: ResultViewModel.AvailableUpdate) {
        val activity = requireActivity() as MainActivity
        when (update) {
            is ResultViewModel.AvailableUpdate.App -> UiUtil.showNewAppUpdateDialog(
                update.release, activity, update.updateUtil, activity,
                PreferenceManager.getDefaultSharedPreferences(activity), activity.installLauncher
            )
            is ResultViewModel.AvailableUpdate.Package -> UiUtil.showNewReleaseUpdateDialog(
                update.release, update.item, activity, activity,
                activity.findViewById(R.id.frame_layout),
                activity.findViewById<View>(R.id.frame_layout),
                activity.installLauncher
            ) { result ->
                result.onSuccess {
                    resultViewModel.dismissUpdate(update)
                    RuntimeManager.reInit(activity)
                }.onFailure { f ->
                    Snackbar.make(activity.findViewById(R.id.frame_layout), f.message ?: "", Snackbar.LENGTH_LONG).apply {
                        show()
                    }
                }
            }
        }
    }

    private fun showFailedFetches() {
        if (childFragmentManager.findFragmentByTag("failedFetches") != null) return
        FailedFetchesBottomSheetDialog().show(childFragmentManager, "failedFetches")
    }

    override fun onDownloadAnyway(url: String) {
        lifecycleScope.launch {
            val type = DownloadType.valueOf(sharedPreferences!!.getString("preferred_download_type", "video")!!)
            if (sharedPreferences!!.getBoolean("download_card", true)) {
                withContext(Dispatchers.Main){
                    showSingleDownloadSheet(
                        resultItem = downloadViewModel.createEmptyResultItem(url),
                        type = type,
                        disableUpdateData = true
                    )
                }
            } else {
                val downloadItem = downloadViewModel.createDownloadItemFromResult(
                    result = downloadViewModel.createEmptyResultItem(url),
                    givenType = type
                )
                downloadViewModel.queueDownloads(listOf(downloadItem))
            }
        }
    }

    @SuppressLint("RestrictedApi")
    private fun showSingleDownloadSheet(
        resultItem: ResultItem,
        type: DownloadType,
        disableUpdateData : Boolean = false
    ){
        if(findNavController().currentBackStack.value.firstOrNull {it.destination.id == R.id.downloadBottomSheetDialog} == null &&
            findNavController().currentDestination?.id == R.id.homeFragment
            ){
            //show the fragment if its not in the backstack
            val bundle = Bundle()
            downloadCardViewModel.setResultItem(resultItem)
            downloadCardViewModel.setDownloadItem(null)
            bundle.putSerializable("type", downloadViewModel.getDownloadType(type, resultItem.url))
            if (disableUpdateData) {
                bundle.putBoolean("disableUpdateData", true)
            }
            findNavController().navigate(R.id.downloadBottomSheetDialog, bundle)
        }
    }

    override fun onCardClick(item: ResultItem, add: Boolean) {
        lifecycleScope.launch {
            val selectedObjects = homeAdapter.getSelectedObjectsCount(totalCount)
            if (actionMode == null) actionMode = (getActivity() as AppCompatActivity?)!!.startSupportActionMode(contextualActionBar)
            actionMode?.apply {
                if (selectedObjects == 0){
                    this.finish()
                }else{
                    actionMode?.title = "$selectedObjects ${getString(R.string.selected)}"
                    this.menu.findItem(R.id.select_between).isVisible = false
                    if(selectedObjects == 2){
                        val selectedIDs = contextualActionBar.getSelectedIDs().sortedBy { it }
                        val resultsInMiddle = withContext(Dispatchers.IO){
                            resultViewModel.getResultsBetweenTwoItems(selectedIDs.first(), selectedIDs.last())
                        }.toMutableList()
                        this.menu.findItem(R.id.select_between).isVisible = resultsInMiddle.isNotEmpty()
                    }
                }
            }
        }
    }

    override fun onCardDetailsClick(item: ResultItem) {
        startActivity(dfast.app.ui.play.PlayerActivity.intent(requireActivity(),
            dfast.app.util.MediaRef(item.url, item.title, item.author, item.thumb, item.duration), emptyList(), item))
    }

    override fun onClick(v: View) {
        val viewIdName: String = try {
            v.tag.toString()
        } catch (e: Exception) {""}
        if (viewIdName.isNotEmpty()) {
            if (viewIdName == "downloadAll") {
                val showDownloadCard = sharedPreferences!!.getBoolean("download_card", true)

                lifecycleScope.launch {
                    val resultIds = withContext(Dispatchers.IO) {
                        resultViewModel.getAllIds()
                    }

                    downloadViewModel.turnResultItemsToProcessingDownloads(resultIds, downloadNow = !showDownloadCard)
                    if (showDownloadCard){
                        findNavController().navigate(R.id.downloadMultipleBottomSheetDialog2)
                    }
                }


            }
        }
    }

    private val contextualActionBar = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            mode!!.menuInflater.inflate(R.menu.main_menu_context, menu)
            mode.title = "${homeAdapter.getSelectedObjectsCount(totalCount)} ${getString(R.string.selected)}"
            searchBar!!.isEnabled = false
            playlistNameFilterChipGroup.children.forEach { it.isEnabled = false }
            searchBar!!.menu.forEach { it.isEnabled = false }
            (activity as MainActivity).disableBottomNavigation()
            downloadAllFab!!.isVisible = false
            clipboardFab!!.isVisible = false
            return true
        }

        override fun onPrepareActionMode(
            mode: ActionMode?,
            menu: Menu?
        ): Boolean {
            return false
        }

        override fun onActionItemClicked(
            mode: ActionMode?,
            item: MenuItem?
        ): Boolean {
            return when (item!!.itemId) {
                R.id.select_between -> {
                    lifecycleScope.launch {
                        val selectedIDs = getSelectedIDs().toMutableList()
                        val resultsInMiddle = withContext(Dispatchers.IO){
                            resultViewModel.getResultsBetweenTwoItems(selectedIDs.first(), selectedIDs.last())
                        }.toMutableList()
                        if (resultsInMiddle.isNotEmpty()){
                            selectedIDs.addAll(resultsInMiddle.map { it.id })
                            homeAdapter.checkMultipleItems(selectedIDs)
                            actionMode?.title = "${selectedIDs.count()} ${getString(R.string.selected)}"
                        }
                        mode?.menu?.findItem(R.id.select_between)?.isVisible = false
                    }
                    true
                }
                R.id.delete_results -> {
                    val deleteDialog = MaterialAlertDialogBuilder(fragmentContext!!)
                    deleteDialog.setTitle(getString(R.string.you_are_going_to_delete_multiple_items))
                    deleteDialog.setNegativeButton(getString(R.string.cancel)) { dialogInterface: DialogInterface, _: Int -> dialogInterface.cancel() }
                    deleteDialog.setPositiveButton(getString(R.string.ok)) { _: DialogInterface?, _: Int ->
                        lifecycleScope.launch {
                            val selectedObjects = getSelectedIDs()
                            if (selectedObjects.size == totalCount) {
                                resultViewModel.deleteAll()
                            } else {
                                resultViewModel.deleteSelected(selectedObjects)
                            }
                            homeAdapter.clearCheckedItems()
                            actionMode?.finish()
                        }
                    }
                    deleteDialog.show()
                    true
                }
                R.id.download -> {
                    lifecycleScope.launch {
                        val showDownloadCard = sharedPreferences!!.getBoolean("download_card", true)
                        val selectedObjects = getSelectedIDs()

                        if (showDownloadCard && selectedObjects.size == 1) {
                            var resultItem = withContext(Dispatchers.IO) {
                                resultViewModel.getByID(selectedObjects.first())
                            }

                            resultItem?.apply {
                                showSingleDownloadSheet(
                                    resultItem,
                                    downloadViewModel.getDownloadType(url = resultItem.url)
                                )
                            }
                        }else{
                            downloadViewModel.turnResultItemsToProcessingDownloads(selectedObjects, downloadNow = !showDownloadCard)
                            if (showDownloadCard){
                                findNavController().navigate(R.id.downloadMultipleBottomSheetDialog2)
                            }
                        }
                        homeAdapter.clearCheckedItems()
                        actionMode?.finish()
                    }
                    true
                }
                R.id.select_all -> {
                    homeAdapter.checkAll()
                    val selectedCount = homeAdapter.getSelectedObjectsCount(totalCount)
                    mode?.title = "(${selectedCount}) ${resources.getString(R.string.all_items_selected)}"
                    true
                }
                R.id.invert_selected -> {
                    homeAdapter.invertSelected()
                    val selectedCount = homeAdapter.getSelectedObjectsCount(totalCount)
                    actionMode!!.title = "$selectedCount ${getString(R.string.selected)}"
                    if (selectedCount == 0) actionMode?.finish()
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode?) {
            actionMode = null
            (activity as MainActivity).enableBottomNavigation()
            homeAdapter.clearCheckedItems()
            searchBar!!.isEnabled = true
            playlistNameFilterChipGroup.children.forEach { it.isEnabled = true }
            searchBar!!.menu.forEach { it.isEnabled = true }
            searchBar?.expand(appBarLayout!!)

            downloadAllFab!!.isVisible = showDownloadAllFab
            clipboardFab!!.isVisible = showClipboardFab
        }

        suspend fun getSelectedIDs() : List<Long>{
            return if (homeAdapter.inverted || homeAdapter.checkedItems.isEmpty()){
                withContext(Dispatchers.IO){
                    resultViewModel.getItemIDsNotPresentIn(homeAdapter.checkedItems.toList())
                }
            }else{
                homeAdapter.checkedItems.toList()
            }
        }
    }

    private fun checkClipboard(): List<String>?{
        val checkClipboard = sharedPreferences!!.getBoolean("check_clipboard_home", true)
        if (!checkClipboard) return null

        return runCatching {
            val clipboard = requireContext().getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip!!.getItemAt(0).text
            return clip.split("\r","\n").map { it.trim() }.filter { Patterns.WEB_URL.matcher(it).matches() }
        }.getOrNull()
    }


    companion object {
        private const val TAG = "HomeFragment"
    }

    override fun onSearchSuggestionClick(text: String) {
        val res = text.split("\n")
        if (res.size == 1){
            showClipboardFab = false
            clipboardFab?.isVisible = false
            searchView!!.setText(text)
            initSearch(searchView!!)
        }else{
            res.forEach {
                onSearchSuggestionAdd(it)
            }
        }

    }

    override fun onSearchSuggestionAdd(text: String) {
        val items = text.split("\n")

        items.forEach {t ->
            val present = queriesChipGroup!!.children.firstOrNull { (it as Chip).text.toString() == t }
            if (present == null) {
                val chip = layoutinflater!!.inflate(R.layout.input_chip, queriesChipGroup, false) as Chip
                chip.text = t
                chip.chipBackgroundColor = ColorStateList.valueOf(MaterialColors.getColor(requireContext(), R.attr.colorSecondaryContainer, Color.BLACK))
                chip.setOnClickListener {
                    if (queriesChipGroup!!.childCount == 1) queriesConstraint!!.visibility = GONE
                    queriesChipGroup!!.removeView(chip)
                }
                queriesChipGroup!!.addView(chip)
            }

        }

        searchView!!.editText.setText("")
        queriesConstraint?.isVisible = queriesChipGroup?.childCount!! > 0

        searchSuggestionsAdapter?.getList()?.apply {
            if (this.isNotEmpty()) {
                if (this.first().type == SearchSuggestionType.CLIPBOARD){
                    val newList = this.toMutableList().drop(1)
                    searchSuggestionsAdapter?.submitList(newList)
                }
            }

        }
    }

    override fun onSearchSuggestionLongClick(text: String, position: Int) {
        val deleteDialog = MaterialAlertDialogBuilder(requireContext())
        deleteDialog.setTitle(getString(R.string.you_are_going_to_delete) + " \"" + text + "\"!")
        deleteDialog.setNegativeButton(getString(R.string.cancel)) { dialogInterface: DialogInterface, _: Int -> dialogInterface.cancel() }
        deleteDialog.setPositiveButton(getString(R.string.ok)) { _: DialogInterface?, _: Int ->
            resultViewModel.removeSearchQueryFromHistory(text)
            updateSearchViewItems(searchView!!.editText.text.toString())
        }
        deleteDialog.show()
    }

    override fun onSearchSuggestionAddToSearchBar(text: String) {
          searchView!!.editText.setText(text)
          searchView!!.editText.setSelection(searchView!!.editText.length())
    }


    private fun updateMultiplePlaylistResults(playlistTitles: List<String>) {
        playlistNameFilterChipGroup.children.filter { it.tag != "all" }.forEach {
            playlistNameFilterChipGroup.removeView(it)
        }

        if (playlistTitles.isEmpty() || playlistTitles.size == 1) {
            playlistNameFilterScrollView.isVisible = false
            return
        }

        playlistNameFilterChipGroup.children.first().setOnClickListener {
            resultViewModel.setPlaylistFilter("")
        }

        for (t in playlistTitles) {
            val tmp = layoutinflater!!.inflate(R.layout.filter_chip, playlistNameFilterChipGroup, false) as Chip
            tmp.text = t
            tmp.tag = t
            tmp.setOnClickListener {
                resultViewModel.setPlaylistFilter(t)
            }

            playlistNameFilterChipGroup.addView(tmp)
        }

        if (playlistNameFilterChipGroup.children.all { !(it as Chip).isChecked }) {
            (playlistNameFilterChipGroup.children.first() as Chip).isChecked = true
        }

        playlistNameFilterScrollView.isVisible = true
    }
}