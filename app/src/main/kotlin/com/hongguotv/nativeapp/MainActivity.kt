// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.widget.*
import android.text.TextUtils
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.hongguotv.core.*
import java.util.concurrent.Executors

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity: Activity() {
    private val bg=TvStyle.background; private val surface=TvStyle.surface
    private val accent=TvStyle.accent; private val white=TvStyle.text; private val muted=TvStyle.muted
    private val main=Handler(Looper.getMainLooper())
    private val io=Executors.newFixedThreadPool(3)
    private val repository=ContentRepository()
    private val networkCleanup=Executors.newSingleThreadExecutor()
    private val prefetchWorker=Executors.newSingleThreadExecutor()
    private var prefetchHttp: okhttp3.OkHttpClient?=null
    private var playbackHttp: okhttp3.OkHttpClient?=null
    private val preparedNext=PreparedSlot<RemoteVideo>({ android.os.SystemClock.elapsedRealtime() })
    private var prefetchJob: java.util.concurrent.Future<*>?=null
    private var prefetchScope: RequestScope?=null
    private var prefetchedFor=""
    private var readySince=0L
    private lateinit var artwork: ArtworkLoader
    private data class DeferredUi(val apply: ()->Unit,val discard: ()->Unit={})
    private var deferredWork: DeferredUi?=null
    private var initialCatalogPending=true
    private var restoreStoppedPage=false
    private var pendingDetail: Series?=null
    private var detailAutoplay=true
    private var libraryLoadError: Throwable?=null
    private var workScope: RequestScope?=null
    private var workJob: java.util.concurrent.Future<*>?=null
    private var playerListener: Player.Listener?=null
    private lateinit var playbackTitle: TextView
    private var directPlayback=false
    private var collection: String?=null
    private var collectionReturn: CatalogState?=null
    private var collectionFocus=""
    private var collectionBack: View?=null
    private var homeGridFocus=false
    private var homeResetDone=false
    /** True while a tab switch was triggered by focus landing on another nav button. */
    private var navSwitchFocus=false
    /** Guards against overlapping infinite-scroll page fetches. */
    private var loadingMore=false
    /**
     * True while an in-place grid rebuild (infinite scroll append) is swapping views.
     * Removing the focused card makes Android hand focus to the top nav bar, whose
     * focus-to-switch listener would then jump the app to the 首页 tab. This flag
     * suppresses that auto-switch until the rebuild restores focus on the grid.
     */
    private var rebuildingGrid=false
    /** Refresh button + status line for the global short/comic drama tabs. */
    private var tabRefresh: View?=null
    private var tabSubtitle: TextView?=null
    /** Theme sub-category bar for the short/comic tabs: "" = 全部 (default feed). */
    private var themeSlug=""
    private val themeButtons=mutableMapOf<String,View>()
    /** Set by switchTheme: after the new theme loads, focus stays on that theme button. */
    private var themeFocusSlug: String?=null
    /** Theme list per content type (session cache; fetched once, follows site changes). */
    private val themesByType=mutableMapOf<ContentType,List<Theme>>()
    /** Theme selection per tab (1=短剧, 2=漫剧): "" = 全部. */
    private val tabTheme=mutableMapOf<Int,String>()
    /** Horizontally scrolling theme bar container, tracked to keep it scrolled to the focused button. */
    private var themeScroll: HorizontalScrollView?=null
    /** Home shelves show both content types at once; each tab keeps its own catalog. */
    private var homeShort=emptyList<Series>()
    private var homeComic=emptyList<Series>()
    private fun tabContentType(): ContentType = when(tab) { 1 -> ContentType.SHORT; 2 -> ContentType.COMIC; 3 -> ContentType.AI; else -> library.contentType }
    private val pageSize=20
    private var catalogScroll: ScrollView?=null
    private var settingsScroll: ScrollView?=null
    private var catalogBody: LinearLayout?=null
    private var homePosition: HomeScreen.Position?=null
    private var catalogPageKey=""
    private val scrollPositions=mutableMapOf<String,Int>()
    private val catalogCards=linkedMapOf<String,View>()
    private val cardImages=linkedMapOf<String,Pair<ImageView,String>>()
    /** Incremental append state: the grid's row container, card row list, focus map and
     *  geometry, kept so loadMore can append new rows without rebuilding the grid. */
    private var gridList: LinearLayout?=null
    private var gridScroll: ScrollView?=null
    private var gridCards=mutableListOf<View>()
    private var gridFlatIndex=mutableMapOf<View,Int>()
    private var gridCount=6
    private var gridWidth=0
    private var gridLoadingRow: TextView?=null
    private var prefetchAttemptAt=0L
    private var prefetchAttempts=0
    private var homeScreen: HomeScreen?=null
    private var selectionPreview: SeriesPreview?=null
    private var homeFromCache=false
    private var homeUpdateCount=0
    private var launchStarted=android.os.SystemClock.elapsedRealtime()
    private val activityStarted=launchStarted
    private var homeContentLogged=false
    private lateinit var library: Library
    private lateinit var tvTools: TvTools
    private lateinit var updater: AppUpdater
    private lateinit var mediaSession: TvMediaSession
    private val sleepTimer=SleepTimer { android.os.SystemClock.elapsedRealtime() }
    private var sleepStopped=false
    private var playerView: PlayerView?=null
    private var danmakuOverlay: DanmakuOverlay?=null
    private lateinit var favoriteMonitor: FavoriteMonitor
    private val favoriteBadges=mutableMapOf<String,TextView>()
    private var favoriteStatus: TextView?=null
    private var favoriteCheck: TextView?=null
    private var episodePanel: EpisodePanel?=null
    private val recovery=RecoveryBudget()
    private var retryPending=false
    private var autoRecovery=false
    private var startPosition=0L
    private var retryPosition=0L
    private var requestedAutoplay=true
    private var retryAutoplay=true
    private var foreground=false
    private lateinit var connectivity: ConnectivityManager
    private var networkRegistered=false
    private val networkCallback=object: ConnectivityManager.NetworkCallback() {
        private fun changed() { main.post { if(connectivity.isActiveNetworkMetered || !networkAvailable()) cancelPrefetch(); if(foreground && screen=="player" && playError && autoRecovery) scheduleRecovery() } }
        override fun onAvailable(network: Network) { changed() }
        override fun onLost(network: Network) { changed() }
        override fun onCapabilitiesChanged(network: Network,capabilities: NetworkCapabilities) { changed() }
        override fun onBlockedStatusChanged(network: Network,blocked: Boolean) { changed() }
    }
    private val retryRunnable=Runnable {
        retryPending=false
        if(foreground && screen=="player" && playError && autoRecovery) {
            if(!networkAvailable()) scheduleRecovery()
            else if(recovery.consume()) {
                android.util.Log.i("HongguoTV","Automatic playback recovery ${recovery.attempts}/3 at ${retryPosition}ms")
                playEpisode(episodeIndex,retryPosition,retryAutoplay,recovering=true)
            }
        }
    }
    private lateinit var root: FrameLayout
    private var generation=0
    private var screen="catalog"
    private var tab=0
    private var page=1
    private var query=""
    private var catalog=emptyList<Series>()
    private var hasMore=false
    private var catalogFocus=""
    private data class CatalogState(val page: Int,val items: List<Series>,val hasMore: Boolean,val ranking: ComicRanking?)
    private var ranking: ComicRanking?=null
    private var rankRefresh: View?=null
    private var rankSubtitle: TextView?=null
    private val tabState=mutableMapOf<Int,CatalogState>()
    private val tabFocus=mutableMapOf<Int,String>()
    private val nav=mutableListOf<View>()
    private val rankButtons=mutableMapOf<RankBoard,View>()
    private var rankTitle: TextView?=null
    private var historyManage: View?=null
    // TV letter keyboard search state (phone-version TelevisionSearchDialog port).
    private val tvKeys=listOf("A","B","C","D","E","F","G","H","I","J","K","L","M","N","O","P","Q","R","S","T","U","V","W","X","Y","Z","1","2","3","4","5","6","7","8","9","0")
    private var tvQuery=""
    private var tvSuggests=emptyList<String>()
    private var tvSuggestList: LinearLayout?=null
    private var tvSuggestTitle: TextView?=null
    private var tvQueryView: EditText?=null
    private var tvSearchButton: TextView?=null
    private var tvBackspace: TextView?=null
    private var tvClear: TextView?=null
    private var tvSuggestJob: java.util.concurrent.Future<*>?=null
    private var tvSuggestTicket=0
    private val tvSuggestDebounce=Runnable { fetchTvSuggests() }
    private var tvFirstKey: View?=null
    private var tvMiddleColumn: LinearLayout?=null
    private var tvRightColumn: LinearLayout?=null
    private var tvHistoryFlow: FlowLayout?=null
    private var tvHistoryTitle: TextView?=null
    private var tvResultPanel: LinearLayout?=null
    private var tvResultTitle: TextView?=null
    private var tvResultList: LinearLayout?=null
    private var tvResultQuery=""
    /** Search-page scroll containers, tracked so the back key can snap them to the top. */
    private var tvSuggestScroll: ScrollView?=null
    private var tvHistoryScroll: ScrollView?=null
    private var tvResultScroll: ScrollView?=null
    /** 手机输入 button and the clear-history icon: focus-chain anchors on the search page. */
    private var tvPhoneButton: TextView?=null
    private var tvClearHistory: View?=null
    /** Set by switchRankBoard: after the new board loads, focus stays on that board's button. */
    private var rankFocusBoard: RankBoard?=null
    /** Session-level fetch cache (TTL 10 min): switching tabs/boards re-renders the cached
     *  list instead of re-fetching; the manual refresh buttons clear the entry first. */
    private val catalogCache=linkedMapOf<String,Pair<Long,CatalogState>>()
    private var homeFetchedAt=0L
    private val catalogTtlMs=10*60*1000L
    private fun cacheKeyFor(requestTab: Int,board: RankBoard?=null): String?=when {
        requestTab==1 -> if(themeSlug.isBlank()) "short" else "theme:short:${themeSlug}"
        requestTab==2 -> if(themeSlug.isBlank()) "comic" else "theme:comic:${themeSlug}"
        requestTab==3 -> if(themeSlug.isBlank()) "ai" else "theme:ai:${themeSlug}"
        requestTab==5 -> "rank:${(board ?: library.rankBoard).storedValue}"
        else -> null
    }
    private fun catalogCacheKey(): String?=if(collection!=null) null else cacheKeyFor(tab)
    private fun invalidateCatalogCache() { catalogCacheKey()?.let { catalogCache.remove(it) }; homeFetchedAt=0 }
    private var detail: Detail?=null
    private var episodeIndex=0
    private var group=0
    private var synopsisExpanded=false
    private var player: ExoPlayer?=null
    private var video: RemoteVideo?=null
    private var playbackReady=false
    private var pausedForLifecycle=false
    private lateinit var hud: LinearLayout
    private lateinit var playbackText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var controls: LinearLayout
    private var panel=false
    private var transportPlay: TextView?=null
    private var sleepButton: TextView?=null
    private var speedDialog: AlertDialog?=null
    private var pendingSeek: Long?=null
    private var playError=false
    private var lastSaved=0L
    private var quality=""
    private val tick=object: Runnable {
        override fun run() {
            if(!foreground || isDestroyed) return
            if(screen=="player") {
                if(sleepTimer.poll()) stopForSleep()
                if(hud.visibility==View.VISIBLE) updatePlaybackText()
                maybePrefetch()
                danmakuOverlay?.let { overlay ->
                    val p=player
                    if(p!=null && playbackReady && !playError) overlay.update(p.currentPosition,p.playbackParameters.speed,p.isPlaying)
                }
                val now=android.os.SystemClock.elapsedRealtime()
                if(player?.isPlaying==true && now-lastSaved>5000) { saveProgress(); lastSaved=now }
            }
            main.postDelayed(this,1000)
        }
    }
    private val hideHud=Runnable { if(screen=="player" && !panel && player?.isPlaying==true && !playError) hidePlaybackOverlay() }
    private val seekRunnable=Runnable { pendingSeek?.let { player?.seekTo(it) }; pendingSeek=null }
    private fun dp(value: Number)=(value.toFloat()*resources.displayMetrics.density).toInt()
    private fun widthDp()=resources.displayMetrics.widthPixels/resources.displayMetrics.density
    private fun lp(w: Int=LinearLayout.LayoutParams.MATCH_PARENT,h: Int=LinearLayout.LayoutParams.WRAP_CONTENT)=LinearLayout.LayoutParams(w,h)
    private fun rounded(color: Int,border: Int=Color.TRANSPARENT)=TvStyle.shape(this,color,border)
    private fun text(value: String,size: Float=16f,color: Int=white)=TextView(this).apply { text=value; textSize=size; setTextColor(color); includeFontPadding=false }
    private fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
    private fun focusStyle(view: View,selected: Boolean=false) {
        view.id=View.generateViewId(); view.isFocusable=true; view.isFocusableInTouchMode=true
        // Focus ring is white across all buttons, matching the top nav's focus colour.
        fun paint(focused: Boolean) { view.background=rounded(if(focused) TvStyle.raised else if(selected) Color.rgb(61,39,34) else surface,if(focused) white else Color.TRANSPARENT) }
        // Any button taking focus means no card is focused: collapse the title preview
        // row. Deterministic — does not depend on the card's focus-loss event firing.
        paint(false); view.setOnFocusChangeListener { _,focused -> paint(focused); if(focused) selectionPreview?.blank() }
    }
    private fun button(label: String,selected: Boolean=false,onClick: ()->Unit): TextView = text(label,15f).apply {
        gravity=Gravity.CENTER; setPadding(dp(14),dp(10),dp(14),dp(10)); minHeight=dp(42); focusStyle(this,selected); setOnClickListener { onClick() }
    }
    private fun addButton(parent: LinearLayout,label: String,selected: Boolean=false,onClick: ()->Unit): TextView {
        val view=button(label,selected,onClick); parent.addView(view,lp().apply { width=LinearLayout.LayoutParams.WRAP_CONTENT; rightMargin=dp(8) }); return view
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        library=Library(this); artwork=ArtworkLoader(this); tvTools=TvTools(this); root=FrameLayout(this).apply { setBackgroundColor(bg) }; setContentView(root)
        updater=AppUpdater(this) { foreground && library.isLoaded && screen=="catalog" && !tvTools.showing }
        updater.extraAction="交流反馈 · 加作者微信" to { showWechatDialog(null) }
        connectivity=getSystemService(ConnectivityManager::class.java)
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback); networkRegistered=true }
        favoriteMonitor=FavoriteMonitor(library,repository) { refreshFavoriteLabels() }
        mediaSession=TvMediaSession(this,{ if(foreground && screen=="player") requestPlayback(true) },{ if(foreground && screen=="player") requestPlayback(false) },{ position -> if(foreground && screen=="player") seekTo(position) },{ direction -> skipEpisode(direction) },{ if(foreground && screen=="player") returnToDetail() })
        message(base(),"正在读取本机记录…")
        loadLibrary()
    }
    private fun showLibraryError(problem: Throwable) {
        val body=base(); error(body,problem) { message(base(),"正在读取本机记录…"); loadLibrary() }
    }
    private fun loadLibrary() {
        libraryLoadError=null
        library.        loadAsync(onFailure={ problem ->
            if(!isDestroyed) { libraryLoadError=problem; if(foreground) showLibraryError(problem) }
        }) {
            // App start: the first screen is the home tab with focus on the top nav "首页".
            if(!isDestroyed && foreground) { showCatalog(load=true,focusNav=true); favoriteMonitor.check() }
        }
    }
    private fun base(title: String?=null): LinearLayout {
        root.removeAllViews(); root.setBackgroundColor(bg)
        val container=column(); val horizontal=dp(widthDp()*.05f); val vertical=(resources.displayMetrics.heightPixels*.05f).toInt()
        container.setPadding(horizontal,vertical,horizontal,vertical); root.addView(container,FrameLayout.LayoutParams(-1,-1))
        if(title!=null) { val heading=row(); addButton(heading,"‹ 返回") { goBack() }; heading.addView(text(title,22f).apply { setTypeface(null,Typeface.BOLD); gravity=Gravity.CENTER_VERTICAL },lp(0,dp(44)).apply { weight=1f }); container.addView(heading) }
        return container
    }
    private fun loadCover(view: ImageView,url: String) {
        var ancestor: View?=view
        var focused=false
        while(ancestor!=null && ancestor!==root) {
            if(ancestor.isFocused) { focused=true; break }
            ancestor=ancestor.parent as? View
        }
        artwork.load(view,url,if(focused) ArtworkLoader.PRIORITY_FOCUSED else ArtworkLoader.PRIORITY_VISIBLE)
    }
    private fun cancelWork() {
        deferredWork?.discard?.invoke(); deferredWork=null
        workScope?.cancel(); workScope=null
        workJob?.cancel(true); workJob=null
        (io as? java.util.concurrent.ThreadPoolExecutor)?.purge()
    }
    private fun invalidatePage() {
        generation++; cancelWork(); artwork.cancelPage(); loadingMore=false
    }
    private fun releaseCatalogViews() {
        homeScreen?.let { homePosition=it.capturePosition() }
        catalogScroll?.let { scrollPositions[catalogPageKey]=it.scrollY }
        catalogBody=null; gridList=null; gridScroll=null; gridCards.clear(); gridFlatIndex.clear(); gridLoadingRow=null
        homeScreen=null; selectionPreview=null; catalogScroll=null; settingsScroll=null
        nav.clear(); rankButtons.clear(); rankTitle=null
        historyManage=null; rankRefresh=null; rankSubtitle=null; collectionBack=null; favoriteBadges.clear()
        favoriteStatus=null; favoriteCheck=null; tabRefresh=null; tabSubtitle=null
        themeButtons.clear(); themeScroll=null
        catalogCards.clear(); cardImages.clear()
        tvSuggestList=null; tvSuggestTitle=null; tvQueryView=null; tvSearchButton=null; tvBackspace=null; tvClear=null; tvFirstKey=null
        tvSuggestScroll=null; tvHistoryScroll=null; tvResultScroll=null; tvPhoneButton=null; tvClearHistory=null
        tvMiddleColumn=null; tvRightColumn=null; tvHistoryFlow=null; tvHistoryTitle=null; tvResultPanel=null; tvResultTitle=null; tvResultList=null
        main.removeCallbacks(tvSuggestDebounce); tvSuggestJob?.cancel(true); tvSuggestJob=null; tvSuggestTicket++
    }
    private fun <T> work(action: ()->T,done: (T)->Unit,failed: (Throwable)->Unit) {
        cancelWork()
        val ticket=generation; val scope=RequestScope(); workScope=scope
        workJob=io.submit {
            try {
                val result=scope.run(action)
                main.post {
                    val discard={ if(result is RemoteVideo) result.close(); Unit }
                    if(isDestroyed || generation!=ticket) discard()
                    else if(foreground) done(result)
                    else { deferredWork?.discard?.invoke(); deferredWork=DeferredUi({ done(result) },discard) }
                }
            } catch(e: Exception) {
                main.post {
                    if(!isDestroyed && generation==ticket) {
                        if(foreground) failed(e) else deferredWork=DeferredUi({ failed(e) })
                    }
                }
            }
        }
    }
    private fun message(parent: LinearLayout,label: String) { parent.addView(text(label,17f,muted).apply { setPadding(0,dp(28),0,dp(18)) }) }
    private fun error(parent: LinearLayout,problem: Throwable,retry: ()->Unit) {
        message(parent,"暂时无法加载，请检查网络后重试。")
        val detail=if(problem is java.net.UnknownHostException) "无法连接内容网站" else if(problem is java.net.SocketTimeoutException) "连接超时" else problem.message.orEmpty().replace(Regex("https?://\\S+"),"[地址]").take(160)
        parent.addView(text(detail,13f,muted)); addButton(parent,"重试",onClick=retry).requestFocus()
    }
    /** Theme list for the current content type: session cache first, then repository. */
    private fun themesFor(type: ContentType): List<Theme> {
        themesByType[type]?.let { return it }
        val themes=runCatching { repository.themes(type) }.getOrDefault(emptyList())
        if(themes.isNotEmpty()) themesByType[type]=themes
        return themes
    }
    private fun switchTheme(slug: String) {
        if(themeSlug==slug || (tab!=1 && tab!=2 && tab!=3)) return
        // Focus-landing switch: defer the rebuild one frame so the focus transfer to the
        // new theme button settles first (same as switchRankBoard's direct-switch path);
        // rebuilding synchronously inside onFocusChange would detach the just-focused
        // button before the focus move commits, and Android hands focus back to the nav.
        // themeFocusSlug restores focus onto that button after the rebuild.
        if(!rebuildingGrid && themeButtons[slug]?.hasFocus()==true) {
            themeSlug=slug
            themeFocusSlug=slug
            tabState.remove(tab); tabFocus.remove(tab)
            page=1; catalog=emptyList(); hasMore=false; ranking=null; catalogFocus=""
            main.post { if((tab==1 || tab==2 || tab==3) && themeSlug==slug && screen=="catalog") showCatalog(load=true) }
            return
        }
        themeSlug=slug
        themeFocusSlug=slug
        tabState.remove(tab); tabFocus.remove(tab)
        rebuildingGrid=true
        try {
            releaseCatalogViews()
            page=1; catalog=emptyList(); hasMore=false; ranking=null; catalogFocus=""
            showCatalog(load=true)
        } finally { rebuildingGrid=false }
    }
    private fun switchRankBoard(board: RankBoard) {
        // Focus-landing switch: defer the rebuild one frame so the focus transfer to the
        // new board button settles first; the rebuild then restores focus onto that
        // button via rankFocusBoard. No visible flicker back to the old board.
        // showCatalog's session cache decides whether the board re-fetches (TTL 10 min)
        // or re-renders instantly from the cached list.
        if(!rebuildingGrid && rankButtons[board]?.hasFocus()==true) {
            library.rankBoard=board
            rankFocusBoard=board
            tabState.remove(5); tabFocus.remove(5)
            page=1; catalog=emptyList(); hasMore=false; ranking=null; catalogFocus=""
            main.post { if(tab==5 && library.rankBoard==board && screen=="catalog") showCatalog(load=true,focusType=true) }
            return
        }
        library.rankBoard=board
        rankFocusBoard=board
        tabState.remove(5); tabFocus.remove(5)
        rebuildingGrid=true
        try {
            releaseCatalogViews()
            page=1; catalog=emptyList(); hasMore=false; ranking=null; catalogFocus=""
            showCatalog(load=true)
        } finally { rebuildingGrid=false }
    }
    private fun switchTab(next: Int) {
        if(next==tab) return
        leaveCollection(); homeResetDone=false
        tabState[tab]=CatalogState(page,catalog,hasMore,ranking); tabFocus[tab]=catalogFocus
        // Theme selection is per-tab: remember the leaving tab's theme, restore the entering one.
        if(tab==1 || tab==2 || tab==3) tabTheme[tab]=themeSlug
        tab=next; val saved=tabState[next]; page=saved?.page ?: 1; catalog=saved?.items ?: emptyList(); hasMore=saved?.hasMore ?: false; ranking=saved?.ranking; catalogFocus=tabFocus[next].orEmpty()
        if(next==1 || next==2 || next==3) themeSlug=tabTheme[next].orEmpty()
        // Entering the search tab always starts fresh: keep history, drop query and results.
        if(next==4) { tvQuery=""; tvSuggests=emptyList(); tvResultQuery=""; catalog=emptyList(); hasMore=false; query="" }
        // Focus arrived on another nav button (D-pad left/right): the page switches under
        // it, focus stays on that nav button. focusNav keeps the grid from stealing focus.
        if(navSwitchFocus) { navSwitchFocus=false; showCatalog(load=(next!=4 && catalog.isEmpty() && !localCatalog()),focusNav=true) }
        else showCatalog(load=(next!=4 && next<=3 && catalog.isEmpty()))
    }
    private fun leaveCollection() {
        if(collection==null) return
        collection=null
        collectionReturn?.let { page=it.page; catalog=it.items; hasMore=it.hasMore; ranking=it.ranking }
        catalogFocus=collectionFocus; collectionReturn=null
    }
    private fun showCollection(kind: String) {
        collectionReturn=CatalogState(page,catalog,hasMore,ranking); collectionFocus=catalogFocus
        collection=kind; page=1; catalogFocus=""; showCatalog()
    }
    private fun localCatalog()=tab==6 || tab==7 || collection!=null
    private fun localItems(): List<Series> = when(collection) {
        "later" -> library.later()
        "updates" -> library.favorites().filter { (library.favoriteUpdate(it.id)?.added ?: 0)>0 }
        "resume" -> library.history().filterNot { library.watched(it.series.id) }.map { it.series }
        else -> if(tab==6) library.favorites() else library.history().map { it.series }
    }
    private fun showCatalog(load: Boolean=false,focusNav: Boolean=false,focusType: Boolean=false) {
        initialCatalogPending=false; restoreStoppedPage=false; pendingDetail=null
        var fetch=load
        // Session cache (10 min TTL): a fresh cached list renders directly without
        // re-fetching — tab/board switching feels instant. Manual refresh clears it.
        if(fetch && page==1 && collection==null) {
            val key=cacheKeyFor(tab)
            val cached=key?.let { catalogCache[it] }
            if(cached!=null && System.currentTimeMillis()-cached.first<catalogTtlMs) {
                page=cached.second.page; catalog=cached.second.items; hasMore=cached.second.hasMore; ranking=cached.second.ranking
                fetch=false
            } else if(tab==0 && homeFetchedAt>0 && System.currentTimeMillis()-homeFetchedAt<catalogTtlMs && (homeShort.isNotEmpty() || homeComic.isNotEmpty())) {
                // Home tab: the session fetch is still fresh — render the shelves from
                // the in-memory lists (homeShort/homeComic) without a background refresh.
                fetch=false
            }
        }
        // Never cache a new page number with the previous page's results while a request is pending.
        if(fetch) {
            // Theme mode: the persistent home cache holds the 全部 feed, not the theme's
            // list — showing it while loading would look like the filter is not applied.
            val cached=if((tab==0 || tab==1 || tab==2 || tab==3) && page==1 && themeSlug.isBlank()) library.cachedHome(tabContentType()) else null
            catalog=cached?.items ?: emptyList(); hasMore=cached?.hasMore ?: false; ranking=null; homeFromCache=cached!=null
            // Home shelves need both content types; seed them from the per-type caches.
            if(tab==0 && page==1) {
                homeShort=library.cachedHome(ContentType.SHORT)?.items ?: emptyList()
                homeComic=library.cachedHome(ContentType.COMIC)?.items ?: emptyList()
            }
        }
        invalidatePage(); screen="catalog"; releaseCatalogViews(); val container=base()
        if(foreground && library.isLoaded && !favoriteMonitor.running) main.post { if(foreground && screen=="catalog") favoriteMonitor.check() }
        val top=row().apply { setPadding(0,0,0,dp(8)) }
        val brand=row()
        brand.addView(ImageView(this).apply { setImageResource(com.hongguotv.nativeapp.R.drawable.app_logo_transparent) },lp(dp(28),dp(28)).apply { rightMargin=dp(9) })
        brand.addView(text("红果",22f).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) })
        top.addView(brand,lp(dp(120),dp(44)))
        listOf("首页","短剧","漫剧","AI剧","搜索","排行榜","收藏","最近观看","设置").forEachIndexed { index,label ->
            val item=addButton(top,label,index==tab) { switchTab(index) }
            fun paint(focused: Boolean) {
                item.setTextColor(if(focused) bg else if(index==tab) accent else muted)
                item.background=rounded(if(focused) white else if(index==tab) Color.rgb(48,32,29) else Color.TRANSPARENT)
                item.setTypeface(null,if(focused || index==tab) Typeface.BOLD else Typeface.NORMAL)
            }
            paint(false); item.setOnFocusChangeListener { _,focused ->
                paint(focused)
                // Focus on the nav bar (D-pad up from the cards / back key): collapse
                // the title preview row.
                if(focused) selectionPreview?.blank()
                // D-pad left/right across the global nav switches the page immediately,
                // no OK press needed. The focused nav button keeps focus (no jump).
                // Suppressed during in-place grid rebuilds (infinite scroll): focus is
                // only passing through the nav bar while the grid swaps its views.
                if(focused && index!=tab && !rebuildingGrid) { navSwitchFocus=true; switchTab(index); navSwitchFocus=false }
            }; nav+=item
        }
        container.addView(top)
        container.addView(View(this).apply { setBackgroundColor(TvStyle.outline) },lp(-1,dp(1)).apply { bottomMargin=dp(6) })
        if(tab==8) {
            // The settings scroll is tracked so the back key can snap it to the top.
            val scroll=ScrollView(this); settingsScroll=scroll; val body=column(); scroll.addView(body); container.addView(scroll,lp(-1,0).apply { weight=1f })
            settings(body); if(focusNav) nav[tab].requestFocus(); return
        }
        if(tab==1 || tab==2 || tab==3) {
            // Global short-drama / comic-drama tab: a plain card grid of that content type.
            // Theme bar right below the nav: 全部 (default feed) + the site's theme list.
            val themes=themesFor(tabContentType())
            if(themes.isNotEmpty()) {
                val bar=row().apply { setPadding(0,dp(8),0,dp(6)); gravity=Gravity.LEFT }
                val scroll=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; isFillViewport=false }
                val strip=row().apply { gravity=Gravity.LEFT }
                scroll.addView(strip)
                themeScroll=scroll
                val current=themeSlug
                themeButtons[""]=addButton(strip,"全部",current.isBlank()) { switchTheme("") }
                    .apply {
                        isSelected=current.isBlank(); nextFocusUpId=nav[tab].id
                        fun paint(focused: Boolean) { background=rounded(if(focused) TvStyle.raised else if(isSelected) Color.rgb(61,39,34) else surface,if(focused) white else Color.TRANSPARENT) }
                        paint(false)
                        setOnFocusChangeListener { _,focused ->
                            paint(focused)
                            // Focus landing on a theme button switches immediately (same
                            // direct-switch feel as the rank board bar); suppressed while
                            // the grid rebuilds in place.
                            if(focused && themeSlug!="" && !rebuildingGrid) switchTheme("")
                            // Focus back on the theme bar: collapse the title preview row.
                            if(focused) selectionPreview?.blank()
                        }
                    }
                themes.forEach { theme ->
                    themeButtons[theme.slug]=addButton(strip,theme.label,current==theme.slug) { switchTheme(theme.slug) }
                        .apply {
                            isSelected=current==theme.slug; nextFocusUpId=nav[tab].id
                            fun paint(focused: Boolean) { background=rounded(if(focused) TvStyle.raised else if(isSelected) Color.rgb(61,39,34) else surface,if(focused) white else Color.TRANSPARENT) }
                            paint(false)
                            setOnFocusChangeListener { _,focused ->
                                paint(focused)
                                if(focused && themeSlug!=theme.slug && !rebuildingGrid) switchTheme(theme.slug)
                                // Focus back on the theme bar: collapse the title preview row.
                                if(focused) selectionPreview?.blank()
                                // Keep the focused button inside the visible strip as
                                // focus moves right beyond the screen edge.
                                if(focused) scroll.post { scroll.smoothScrollTo((left-scroll.width+width+dp(12)).coerceAtLeast(0),0) }
                            }
                        }
                }
                // Explicit left/right chain: the first/last buttons lock (pressing right
                // past the last theme stays put) — Android's geometric search would
                // otherwise escape upward to the nav bar on a short strip.
                val ordered=themeButtons.values.toList()
                ordered.forEachIndexed { index,view ->
                    view.nextFocusLeftId=if(index==0) view.id else ordered[index-1].id
                    view.nextFocusRightId=if(index==ordered.lastIndex) view.id else ordered[index+1].id
                }
                bar.addView(scroll,lp(0,dp(46)).apply { weight=1f })
                container.addView(bar)
                nav.forEach { it.nextFocusDownId=themeButtons[current]?.id ?: themeButtons[""]!!.id }
            } else nav.forEach { it.nextFocusDownId=View.NO_ID }
        }
        if(tab==4) {
            // Global search: full-width three-column keyboard layout, no content-type filter.
            // Left ~30%: query box + search/clear + phone-input/delete + A-Z grid.
            // Middle ~30%: live suggestions. Right ~40%: history, or result cards after a search.
            container.addView(buildTvSearchScreen())
            nav.forEach { it.nextFocusDownId=tvQueryView?.id ?: nav[tab].id }
            // Returning from a detail page lands on the top nav "搜索" tab in the fresh three-column layout.
            if(focusNav) nav[tab].requestFocus()
            return
        } else if(tab==5) {
            val boards=row().apply { setPadding(0,dp(8),0,dp(6)) }
            boards.addView(text("片库",12f,muted),lp(dp(42),-2))
            val current=library.rankBoard
            RankBoard.entries.forEach { board ->
                rankButtons[board]=addButton(boards,board.label,current==board) { switchRankBoard(board) }
                    .apply {
                        isSelected=current==board; nextFocusUpId=nav[tab].id
                        // Focus landing on a board button switches the board immediately,
                        // no OK press needed — same direct-switch feel as the top nav.
                        // The paint (white focus ring) from focusStyle must be re-applied
                        // here: setting a new focus listener replaces the old one.
                        fun paint(focused: Boolean) { background=rounded(if(focused) TvStyle.raised else if(isSelected) Color.rgb(61,39,34) else surface,if(focused) white else Color.TRANSPARENT) }
                        paint(false)
                        setOnFocusChangeListener { _,focused ->
                            paint(focused)
                            // Focus landing on a board button switches the board immediately,
                            // no OK press needed — same direct-switch feel as the top nav.
                            // Focus back on the board bar: collapse the title preview row.
                            if(focused) selectionPreview?.blank()
                            if(focused && board!=library.rankBoard && !rebuildingGrid) switchRankBoard(board)
                        }
                    }
            }
            container.addView(boards)
            val heading=row().apply { setPadding(0,dp(8),0,dp(6)) }
            rankTitle=text(library.rankBoard.label,25f).apply { setTypeface(null,Typeface.BOLD) }
            heading.addView(rankTitle,lp(0,-2).apply { weight=1f })
            rankRefresh=addButton(heading,"刷新榜单") { page=1; catalogFocus=""; invalidateCatalogCache(); rankFocusBoard=library.rankBoard; showCatalog(true,focusType=true) }.apply { nextFocusUpId=rankButtons[current]?.id ?: nav[tab].id }
            container.addView(heading)
            rankSubtitle=text(if(fetch) "正在读取最新榜单…" else ranking?.updatedText?.ifBlank { "来源：红果${library.rankBoard.label}" } ?: "来源：红果${library.rankBoard.label}",13f,muted)
                .apply { setPadding(0,0,0,dp(8)) }
            container.addView(rankSubtitle)
            nav.forEach { it.nextFocusDownId=rankButtons[current]?.id ?: rankRefresh!!.id }
            rankButtons.values.forEach { it.nextFocusDownId=rankRefresh!!.id }
        } else if(tab>5) {
            val heading=row().apply { setPadding(0,dp(14),0,dp(6)) }
            heading.addView(text(if(tab==6) "我的收藏" else "接着上次看",25f).apply { setTypeface(null,Typeface.BOLD) },lp(0,-2).apply { weight=1f })
            if(tab==6) {
                favoriteCheck=addButton(heading,"检查更新") { favoriteMonitor.check(force=true) }.apply { nextFocusUpId=nav[tab].id }
            }
            if(tab==7 && library.history().isNotEmpty()) {
                historyManage=addButton(heading,"管理记录") {
                    val rows=library.history()
                    tvTools.choose("选择要管理的观看记录",rows.map { it.series.title },historyManage,{ index -> manageHistory(rows[index].series,historyManage) },"清空记录") {
                        tvTools.confirm("清空观看记录","将删除本机所有观看进度，收藏会保留。",historyManage) { library.clearHistory(); catalogFocus=""; showCatalog() }
                    }
                }.apply { nextFocusUpId=nav[tab].id }
            }
            container.addView(heading)
            if(tab==6) {
                favoriteStatus=text(favoriteMonitor.status,13f,muted).apply { setPadding(0,0,0,dp(6)) }; container.addView(favoriteStatus)
            }
        }
        val body=column(); catalogBody=body; container.addView(body,lp(-1,0).apply { weight=1f })
        if(localCatalog()) {
            // Local lists (favorites / history / collections) render as one infinite
            // grid, same as the drama tabs: no paging row, focus past the last row
            // appends the next local page. The current page window survives tab
            // switches (tabState restores page before this reloads the list).
            catalog=localItems(); ranking=null
            page=page.coerceIn(1,maxOf(1,(catalog.size+pageSize-1)/pageSize))
            hasMore=catalog.size>page*pageSize
        }
        if(fetch) {
            if(tab==0 || tab==1 || tab==2 || tab==3) catalogGrid(body,focusNav,focusType,loading=true)
            else {
                message(body,"正在加载…")
                // Board switch: focus the freshly selected board button while loading.
                if(focusType) rankButtons[rankFocusBoard ?: library.rankBoard]?.requestFocus() ?: nav[tab].requestFocus()
                else nav[tab].requestFocus()
            }
            val requestTab=tab; val requestPage=page; val requestQuery=query; val requestType=tabContentType(); val requestBoard=library.rankBoard
            val requestTheme=themeSlug; val requestThemeObj=if(requestTheme.isBlank()) null else themesByType[requestType]?.firstOrNull { it.slug==requestTheme }
            work({
                // The home tab fetches both content types at once so its two shelves
                // (热门发现·短剧 / 热门发现·漫剧) refresh together.
                when(requestTab) {
                    0 -> repository.home(requestPage,ContentType.SHORT) to repository.home(requestPage,ContentType.COMIC)
                    1 -> if(requestThemeObj!=null) repository.themeCatalog(ContentType.SHORT,requestThemeObj,requestPage) to CatalogPage(emptyList(),false)
                        else repository.home(requestPage,ContentType.SHORT) to CatalogPage(emptyList(),false)
                    // The pair is (shortResult, comicResult) and tab 2 reads the SECOND slot:
                    // a comic theme's list must go there, not into the first.
                    2 -> if(requestThemeObj!=null) CatalogPage(emptyList(),false) to repository.themeCatalog(ContentType.COMIC,requestThemeObj,requestPage)
                        else CatalogPage(emptyList(),false) to repository.home(requestPage,ContentType.COMIC)
                    3 -> if(requestThemeObj!=null) repository.themeCatalog(ContentType.AI,requestThemeObj,requestPage) to CatalogPage(emptyList(),false)
                        else repository.home(requestPage,ContentType.AI) to CatalogPage(emptyList(),false)
                    5 -> CatalogPage(emptyList(),false) to repository.ranking(requestBoard,requestPage)
                    else -> repository.search(requestQuery,requestPage,requestType) to CatalogPage(emptyList(),false)
                }
            }, { (shortResult,comicResult) ->
                if(requestPage==1) {
                    // Session cache: remember the fetched page so tab/board switches within
                    // the TTL re-render instead of re-fetching.
                    val cacheKey=when {
                        requestTab==1 && requestThemeObj!=null -> "theme:short:${requestTheme}"
                        requestTab==2 && requestThemeObj!=null -> "theme:comic:${requestTheme}"
                        requestTab==3 && requestThemeObj!=null -> "theme:ai:${requestTheme}"
                        else -> null
                    }
                    if(cacheKey!=null) {
                        val themeResult=if(requestTab==1 || requestTab==3) shortResult else comicResult
                        catalogCache[cacheKey]=System.currentTimeMillis() to CatalogState(requestPage,themeResult.items,themeResult.hasMore,null)
                    }
                    when(requestTab) {
                        1 -> if(requestThemeObj==null) catalogCache["short"]=System.currentTimeMillis() to CatalogState(requestPage,shortResult.items,shortResult.hasMore,shortResult.ranking)
                        2 -> if(requestThemeObj==null) catalogCache["comic"]=System.currentTimeMillis() to CatalogState(requestPage,comicResult.items,comicResult.hasMore,comicResult.ranking)
                        3 -> if(requestThemeObj==null) catalogCache["ai"]=System.currentTimeMillis() to CatalogState(requestPage,shortResult.items,shortResult.hasMore,shortResult.ranking)
                        5 -> catalogCache["rank:${requestBoard.storedValue}"]=System.currentTimeMillis() to CatalogState(requestPage,comicResult.items,comicResult.hasMore,comicResult.ranking)
                    }
                    if(requestTab==0) homeFetchedAt=System.currentTimeMillis()
                    when(requestTab) {
                        0 -> { library.cacheHome(ContentType.SHORT,shortResult.items,shortResult.hasMore); library.cacheHome(ContentType.COMIC,comicResult.items,comicResult.hasMore) }
                        1 -> if(requestThemeObj==null) library.cacheHome(ContentType.SHORT,shortResult.items,shortResult.hasMore)
                        2 -> if(requestThemeObj==null) library.cacheHome(ContentType.COMIC,comicResult.items,comicResult.hasMore)
                        3 -> if(requestThemeObj==null) library.cacheHome(ContentType.AI,shortResult.items,shortResult.hasMore)
                    }
                }
                if(requestTab==0 && requestPage==1) {
                    homeShort=shortResult.items; homeComic=comicResult.items
                    catalog=shortResult.items+comicResult.items; hasMore=shortResult.hasMore || comicResult.hasMore; ranking=shortResult.ranking
                    if(homeFromCache || tvTools.showing || updater.showing) {
                        homeScreen?.refresh?.text="热门已更新 · 按确认查看"
                        homeScreen?.refresh?.setOnClickListener { showCatalog() }
                    } else showCatalog()
                } else {
                    val result=if(requestTab==1 || requestTab==3) shortResult else if(requestTab==2) comicResult else if(requestTab==5) comicResult else shortResult
                    catalog=result.items; hasMore=result.hasMore; ranking=result.ranking
                    val keepNav=nav.any { it.hasFocus() }; val keepType=rankButtons.values.any { it.hasFocus() } || rankFocusBoard!=null
                    // Theme switch: focus is sitting on a theme button (the bar lives above
                    // the body, so it survives this rebuild) — keep it there, never push
                    // focus to the nav.
                    val keepTheme=themeFocusSlug!=null || themeButtons.values.any { it.hasFocus() }
                    rankTitle?.text=library.rankBoard.label
                    rankSubtitle?.text=ranking?.updatedText?.ifBlank { "来源：红果${library.rankBoard.label}" } ?: "来源：红果${library.rankBoard.label}"
                    tabSubtitle?.text="来源：红果${tabContentType().label}"
                    body.removeAllViews(); catalogGrid(body,keepNav || focusNav,keepType || focusType || keepTheme)
                }
            }, { problem ->
                if(requestTab==0 && requestPage==1) {
                    homeScreen?.refresh?.text=if(homeShort.isNotEmpty() || homeComic.isNotEmpty()) "当前显示上次内容 · 联网后按确认刷新" else "热门暂时无法加载 · 按确认重试"
                    homeScreen?.refresh?.setOnClickListener { showCatalog(true) }
                    return@work
                }
                catalog=emptyList(); hasMore=false; ranking=null
                if(requestTab==5) rankSubtitle?.text="榜单加载失败" else tabSubtitle?.text="内容加载失败"
                if(problem is SearchSessionExpiredException) {
                    page=1; catalogFocus=""
                    Toast.makeText(this,"搜索结果已过期，已回到第 1 页刷新",Toast.LENGTH_LONG).show()
                    showCatalog(true)
                } else {
                    body.removeAllViews()
                    error(body,problem) { showCatalog(true) }
                }
            })
        } else catalogGrid(body,focusNav,focusType)
    }
    private fun catalogGrid(body: LinearLayout,focusNav: Boolean,focusType: Boolean=false,loading: Boolean=false) {
        if(tab==0 && page==1 && collection==null) { renderHome(body,focusNav,focusType,loading); return }
        catalogCards.clear(); cardImages.clear(); collectionBack=null
        if(collection!=null) {
            val heading=row()
            collectionBack=addButton(heading,"‹ 返回首页") { leaveCollection(); showCatalog() }.apply {
                nextFocusUpId=nav[tab].id
                nextFocusDownId=id
            }
            heading.addView(text(when(collection) { "later" -> "稍后看"; "updates" -> "收藏有更新"; else -> "接着看" },22f))
            body.addView(heading)
        }
        // Infinite grids append pages in place, so their scroll key ignores the page number.
        catalogPageKey=if(tab==1 || tab==2 || tab==3 || tab==5 || localCatalog()) "$tab:${collection.orEmpty()}:$themeSlug:$query" else "$tab:${collection.orEmpty()}:$page:$query"
        val preview=SeriesPreview(this); selectionPreview=preview; body.addView(preview)
        // Collapsible preview everywhere (theme tabs, ranking, favorites, history):
        // the whole bar hides until a card actually takes focus and shows its title.
        preview.collapsible=true; if(catalogFocus.isBlank()) preview.blank()
        val scroll=ScrollView(this).apply { isFillViewport=false; isVerticalScrollBarEnabled=false; clipToPadding=false }; catalogScroll=scroll
        val list=column(); scroll.addView(list); body.addView(scroll,lp(-1,0).apply { weight=1f })
        var restoringScroll=true
        val displayed=if(localCatalog()) catalog.take(page*pageSize) else if(tab==1 || tab==2 || tab==3 || tab==5) catalog else if(tab==0) catalog.filterNot { it.id in library.hidden() } else catalog.take(30)
        if(displayed.isEmpty()) {
            message(list,if(loading) "正在加载推荐…" else when(tab) { 4 -> if(query.isBlank()) "输入关键词，用遥控器确认搜索" else "没有找到相关${library.contentType.label}，换个关键词试试"; 6 -> "在剧集详情中选择收藏，喜欢的剧就会出现在这里"; 7 -> "播放过的剧集会自动保存在这里"; else -> "本页没有更多内容" })
            if(tab==0 && page>1) addButton(list,"上一页") { page--; showCatalog(!localCatalog()) }
            if(tab==0 && hasMore) addButton(list,"下一页") { page++; showCatalog(!localCatalog()) }
            if(themeFocusSlug!=null) { val slug=themeFocusSlug; themeFocusSlug=null; themeButtons[slug]?.requestFocus() ?: nav[tab].requestFocus() }
            // Focus already sits on a theme button (empty theme result): leave it there.
            else if((tab==1 || tab==2 || tab==3) && themeButtons.values.any { it.hasFocus() }) Unit
            else if(focusType) rankButtons[rankFocusBoard ?: library.rankBoard]?.requestFocus() ?: nav[tab].requestFocus() else if(collectionBack!=null && !focusNav) collectionBack?.requestFocus() else nav[tab].requestFocus()
            return
        }
        val cards=mutableListOf<View>(); val count=6; val gap=dp(14); val width=((resources.displayMetrics.widthPixels*.9f-gap*(count-1))/count).toInt()
        // Infinite grids (short/comic/ai/ranking tabs + local lists) append pages as focus reaches the last row.
        val infiniteScroll=tab==1 || tab==2 || tab==3 || tab==5 || localCatalog()
        val flatIndex=mutableMapOf<View,Int>()
        // Register the grid geometry so loadMore can append rows incrementally (no rebuild).
        if(infiniteScroll) {
            gridList=list; gridScroll=scroll; gridCards=cards; gridFlatIndex=flatIndex
            gridCount=count; gridWidth=width; gridLoadingRow=null
        }
        displayed.chunked(count).forEach { items -> list.addView(buildCardRow(items,cards,flatIndex,count,width,gap,scroll,list,preview,infiniteScroll,{ restoringScroll }),lp(-1,-2).apply { bottomMargin=dp(10) }) }
        if(infiniteScroll) {
            // No paging row below: every card leads to the row under it, and the last
            // row keeps focus on DOWN; the next page loads before focus reaches it.
            cards.forEachIndexed { index,card -> card.nextFocusDownId=if(index+count<cards.size) cards[index+count].id else card.id }
            if(hasMore) {
                // Loading indicator at the bottom of an infinite grid; removed once the next
                // page arrives (the grid appends the new rows in place).
                gridLoadingRow=text("正在加载更多…",17f,muted).apply { setPadding(0,dp(28),0,dp(18)) }
                list.addView(gridLoadingRow)
            }
        } else {
            val paging=row(); paging.gravity=Gravity.CENTER
            if(tab<=5 || localCatalog()) {
                val prev=addButton(paging,"上一页") { if(page>1) { page--; catalogFocus=""; showCatalog(!localCatalog()) } }.apply { isEnabled=page>1; isFocusable=page>1; alpha=if(page>1) 1f else .4f }
                paging.addView(text(if(localCatalog()) "第 $page / ${maxOf(1,(catalog.size+pageSize-1)/pageSize)} 页" else if(tab==5 && ranking!=null) "第 $page / ${ranking!!.totalPages} 页" else "第 $page 页",14f,muted).apply { gravity=Gravity.CENTER },lp(dp(if(tab==5) 135 else 95),dp(44)))
                val next=addButton(paging,"下一页") { if(hasMore) { page++; catalogFocus=""; showCatalog(!localCatalog()) } }.apply { isEnabled=hasMore; isFocusable=hasMore; alpha=if(hasMore) 1f else .4f }
                list.addView(paging,lp(-1,dp(50)))
                cards.forEachIndexed { index,card ->
                    card.nextFocusDownId=if(index+count<cards.size) cards[index+count].id else if(index/count<cards.lastIndex/count) cards.last().id else next?.takeIf { it.isFocusable }?.id ?: prev?.takeIf { it.isFocusable }?.id ?: card.id
                }
            }
        }
        cards.forEachIndexed { index,card ->
            card.nextFocusLeftId=if(index%count==0) card.id else cards[index-1].id
            card.nextFocusRightId=if(index%count==count-1 || index==cards.lastIndex) card.id else cards[index+1].id
            card.nextFocusUpId=if(index<count) (collectionBack?.id ?: themeButtons[themeSlug]?.id ?: tabRefresh?.id ?: rankRefresh?.id ?: favoriteCheck?.id ?: historyManage?.id ?: nav[tab].id) else cards[index-count].id
        }
        nav.forEach { it.nextFocusDownId=if(infiniteScroll && tab!=5) (themeButtons[themeSlug]?.id ?: cards.first().id) else tabRefresh?.id ?: rankButtons[library.rankBoard]?.id ?: rankRefresh?.id ?: favoriteCheck?.id ?: historyManage?.id ?: cards.first().id }
        themeButtons.values.forEach { it.nextFocusDownId=cards.first().id }
        rankButtons.values.forEach { it.nextFocusDownId=rankRefresh?.id ?: cards.first().id }
        rankRefresh?.nextFocusDownId=cards.first().id
        tabRefresh?.nextFocusDownId=cards.first().id
        historyManage?.nextFocusDownId=cards.first().id
        favoriteCheck?.nextFocusDownId=cards.first().id
        collectionBack?.nextFocusDownId=cards.first().id
        when {
            homeGridFocus -> { homeGridFocus=false; cards.first().requestFocus() }
            rankFocusBoard!=null -> { val board=rankFocusBoard; rankFocusBoard=null; rankButtons[board]?.requestFocus() ?: nav[tab].requestFocus() }
            themeFocusSlug!=null -> { val slug=themeFocusSlug; themeFocusSlug=null; themeButtons[slug]?.requestFocus() ?: nav[tab].requestFocus() }
            // Focus already sits on a theme button (data arrived after a theme switch):
            // leave it alone — do not hand focus to the nav.
            (tab==1 || tab==2 || tab==3) && themeButtons.values.any { it.hasFocus() } -> Unit
            focusNav -> nav[tab].requestFocus()
            (tab==1 || tab==2 || tab==3) && catalogFocus.isBlank() -> nav[tab].requestFocus()
            else -> cards[displayed.indexOfFirst { it.id==catalogFocus }.coerceAtLeast(0)].requestFocus()
        }
        val savedScroll=scrollPositions[catalogPageKey]
        scroll.post {
            if(catalogScroll!==scroll) return@post
            cards.forEach { view ->
                val card=view as LinearLayout
                val image=card.getChildAt(0)
                val textHeight=card.paddingTop+card.paddingBottom+(1 until card.childCount).sumOf { card.getChildAt(it).measuredHeight }
                val target=minOf(((width-dp(8))/TvStyle.POSTER_ASPECT).toInt(),(scroll.height-textHeight-dp(4)).coerceAtLeast(dp(48)))
                if(image.layoutParams.height!=target) image.layoutParams=image.layoutParams.apply { height=target }
            }
            scroll.post settle@{
                if(catalogScroll!==scroll) return@settle
                savedScroll?.let { scroll.scrollTo(0,it) }
                (cards.firstOrNull { it.hasFocus() }?.parent as? View)?.let { line ->
                    val y=when {
                        line.top<scroll.scrollY -> line.top
                        line.bottom>scroll.scrollY+scroll.height -> if(line.height>=scroll.height) line.top else line.bottom-scroll.height
                        else -> scroll.scrollY
                    }
                    scroll.scrollTo(0,y.coerceAtLeast(0))
                }
                restoringScroll=false
            }
        }
    }
    private fun selectionStatus(series: Series): String {
        val progress=library.progress(series.id)
        return listOf(if(library.favorite(series.id)) "已收藏" else "",if(library.queued(series.id)) "稍后看" else "",
            if(library.watched(series.id)) "整剧已看完" else progress?.let { "上次第 ${it.episodeIndex+1} 集 · ${formatTime(it.position)}" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
    }
    /** One catalog card. Extracted from the row builder so appendRows can drop new cards
     *  into a trailing partial row's empty slots: pages arrive in any size (the feed
     *  returns 5, 14, 23… rows; the local 穿越 filter thins it further), and per-page
     *  chunking would litter the grid with 1-5 card rows. The card registers itself in
     *  cards/flatIndex; the caller places it in a row.
     *  restoring is a supplier: catalogGrid flips it to false once the initial scroll
     *  settles, and the closures must observe that change (a Boolean parameter would
     *  freeze the build-time value forever). */
    private fun buildCard(item: Series,line: LinearLayout,cards: MutableList<View>,flatIndex: MutableMap<View,Int>,count: Int,width: Int,scroll: ScrollView,list: LinearLayout,preview: SeriesPreview,infiniteScroll: Boolean,restoring: () -> Boolean): View {
        val card=column(); card.setPadding(dp(4),dp(4),dp(4),dp(9)); focusStyle(card); card.contentDescription=item.title; card.tag=item.id; catalogCards[item.id]=card
        // Ranking (tab 5) and comic (tab 2) pages carry heat metadata in
        // ranking.positions; no rank badge is drawn on the artwork.
        val position=if(tab==5 || tab==2) ranking?.positions?.get(item.id) else null
        val artwork=FrameLayout(this); card.addView(artwork,lp(-1,((width-dp(8))/TvStyle.POSTER_ASPECT).toInt()))
        val image=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }; artwork.addView(image,FrameLayout.LayoutParams(-1,-1)); loadCover(image,item.cover); cardImages[item.id]=image to item.cover
        card.addView(text(item.title,14f).apply { maxLines=2; minLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(7),dp(3),0) },lp(-1,-2).apply { weight=1f })
        val progress=if(tab==7 || collection=="resume") library.progress(item.id) else null
        // Unified card badge: episode count followed by heat when available
        // (e.g. "全40集   100万热度" / "更新至80集   100万热度" / "第1集·00:10   100万热度").
        val heat=if(tab==5 || tab==2) position?.heat else null
        val episodeText=if(tab==6) library.favoriteLabel(item.id) else if(progress!=null) if(library.watched(item.id)) "整剧已看完" else "第${progress.episodeIndex+1}集·${formatTime(progress.position)}" else item.badge
        val badgeText=if(heat.isNullOrBlank()) episodeText else listOf(episodeText,heat).filter { it.isNotBlank() }.joinToString("   ")
        val badge=text(badgeText,12f,if(tab==5 || tab==6) accent else muted).apply { maxLines=1; minLines=1; minHeight=dp(19); ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),0,0,0) }
        card.addView(badge,lp(-1,-2)); if(tab==6) favoriteBadges[item.id]=badge
        card.setOnClickListener { catalogFocus=item.id; openDetail(item,tab==7 || collection=="resume" || collection=="updates") }
        card.setOnLongClickListener { catalogFocus=item.id; quickActions(item,card); true }
        card.setOnKeyListener { _,key,event -> if(key==KeyEvent.KEYCODE_MENU) { if(event.action==KeyEvent.ACTION_UP) { catalogFocus=item.id; quickActions(item,card) }; true } else false }
        card.setOnFocusChangeListener { _,focused -> card.background=TvStyle.card(this,focused)
            if(focused) { catalogFocus=item.id; loadCover(image,item.cover); preview.show(item,selectionStatus(item));
                // Prefetch trigger: fire one row early (when focus enters the second-to-last
                // row), so the next page is usually in hand before focus reaches the bottom.
                if(infiniteScroll && hasMore && (flatIndex[card] ?: 0)+count*2>=cards.size) loadMore()
                // Row-aligned scrolling: the focused row lands at the top of the viewport
                // (same view as the first row on entry), so the previous row never peeks
                // partially under the preview bar. Rows already fully visible stay put.
                if(!restoring()) scroll.post { scroll.smoothScrollTo(0,when {
                    line.top<scroll.scrollY -> line.top
                    line.bottom>scroll.scrollY+scroll.height -> line.top.coerceAtMost((list.height-scroll.height).coerceAtLeast(0))
                    else -> scroll.scrollY }) } }
            // Focus left the cards (paging row / refresh button / nav bar): collapse
            // the title preview row again. Deferred one message: a card→card move
            // fires the old card's loss before the new card's gain.
            else if(preview.collapsible) main.post { if(!cards.any { it.hasFocus() }) preview.blank() }
        }
        cards+=card; flatIndex[card]=cards.size-1
        return card
    }
    /** One row of catalog cards, each stretched to the row's measured height so badges
     *  stay aligned. Extracted from catalogGrid so loadMore can append new rows
     *  incrementally instead of rebuilding the whole grid. */
    private fun buildCardRow(items: List<Series>,cards: MutableList<View>,flatIndex: MutableMap<View,Int>,count: Int,width: Int,gap: Int,scroll: ScrollView,list: LinearLayout,preview: SeriesPreview,infiniteScroll: Boolean,restoring: () -> Boolean): View {
        val line=row(); line.gravity=Gravity.TOP
        items.forEachIndexed { columnIndex,item ->
            line.addView(buildCard(item,line,cards,flatIndex,count,width,scroll,list,preview,infiniteScroll,restoring),lp(width,-1).apply { if(columnIndex<count-1) rightMargin=gap })
        }
        return line
    }
    /** Focus navigation for an infinite grid's cards: left/right within the row, up to
     *  the row above (or the header bar), down to the row below (self-loop on the last
     *  row — no paging row exists below an infinite grid). Runs after every append. */
    private fun patchCardNavigation(cards: List<View>,count: Int) {
        cards.forEachIndexed { index,card ->
            card.nextFocusLeftId=if(index%count==0) card.id else cards[index-1].id
            card.nextFocusRightId=if(index%count==count-1 || index==cards.lastIndex) card.id else cards[index+1].id
            card.nextFocusUpId=if(index<count) (collectionBack?.id ?: themeButtons[themeSlug]?.id ?: tabRefresh?.id ?: rankRefresh?.id ?: favoriteCheck?.id ?: historyManage?.id ?: nav[tab].id) else cards[index-count].id
            card.nextFocusDownId=if(index+count<cards.size) cards[index+count].id else card.id
        }
    }
    /** Infinite grids (short/comic/ranking tabs + local lists): fetch the next page and
     *  append it in place. The grid is not rebuilt — focus and scroll stay exactly where they are. */
    private fun loadMore() {
        if(!hasMore || loadingMore) return
        if(localCatalog()) {
            // Local list: page through the in-memory catalog on the UI thread, no network.
            page+=1; hasMore=catalog.size>page*pageSize
            appendRows(catalog.drop((page-1)*pageSize).take(pageSize))
            return
        }
        loadingMore=true
        val requestTab=tab; val requestBoard=library.rankBoard; val nextPage=page+1
        val requestType=tabContentType(); val requestTheme=themeSlug
        val requestThemeObj=if(requestTheme.isBlank() || (requestTab!=1 && requestTab!=2 && requestTab!=3)) null else themesByType[requestType]?.firstOrNull { it.slug==requestTheme }
        work({
            when(requestTab) {
                1 -> if(requestThemeObj!=null) repository.themeCatalog(ContentType.SHORT,requestThemeObj,nextPage) else repository.home(nextPage,ContentType.SHORT)
                2 -> if(requestThemeObj!=null) repository.themeCatalog(ContentType.COMIC,requestThemeObj,nextPage) else repository.home(nextPage,ContentType.COMIC)
                3 -> if(requestThemeObj!=null) repository.themeCatalog(ContentType.AI,requestThemeObj,nextPage) else repository.home(nextPage,ContentType.AI)
                5 -> repository.ranking(requestBoard,nextPage)
                else -> CatalogPage(emptyList(),false)
            }
        }, { result ->
            loadingMore=false
            if(requestTab!=tab || screen!="catalog") return@work
            // Board/theme switched while the request was in flight: drop the stale result.
            if(requestTab==5 && requestBoard!=library.rankBoard) return@work
            if((requestTab==1 || requestTab==2 || requestTab==3) && requestTheme!=themeSlug) return@work
            // Deduplicate against what is already on screen, then append.
            val known=catalog.map { it.id }.toHashSet()
            val fresh=result.items.filterNot { it.id in known }
            // A page that adds nothing new must end the scroll: appending nothing re-triggers
            // focus-driven loading and the grid jitters forever.
            catalog=catalog+fresh; hasMore=result.hasMore && fresh.isNotEmpty(); page=nextPage
            appendRows(fresh)
        }, { _ ->
            loadingMore=false
        })
    }
    /** Append one page of items to the infinite grid in place. New cards first fill the
     *  trailing partial row's empty slots (the feed returns any number of items per
     *  page — 5, 14, 23… — and the local 穿越 filter thins it further, so per-page
     *  chunking would litter the grid with 1-5 card rows), then new rows are appended
     *  below. No view is destroyed, so the focused card keeps focus, the scroll
     *  position never jumps, and in-flight cover downloads for existing cards are not
     *  cancelled. New covers start loading the moment the page arrives. */
    private fun appendRows(items: List<Series>) {
        val list=gridList ?: return
        val scroll=gridScroll ?: return
        val cards=gridCards
        val flatIndex=gridFlatIndex
        val count=gridCount; val width=gridWidth; val gap=dp(14)
        val preview=selectionPreview ?: return
        gridLoadingRow?.let { loading -> list.removeView(loading) }; gridLoadingRow=null
        val oldSize=cards.size
        // Fill the trailing partial row first: its empty slots take as many new cards
        // as they hold, added straight into the existing row view (no view moves).
        var pending=items
        val lastRow=list.getChildAt(list.childCount-1) as? LinearLayout
        if(lastRow!=null && lastRow.childCount<count && pending.isNotEmpty()) {
            // The card that used to end the row has no right gap; restore it now that
            // siblings follow it.
            (lastRow.getChildAt(lastRow.childCount-1).layoutParams as? LinearLayout.LayoutParams)?.rightMargin=gap
            val slot=count-lastRow.childCount
            pending.take(slot).forEach { item ->
                val card=buildCard(item,lastRow,cards,flatIndex,count,width,scroll,list,preview,true,{ false })
                // The row stretches siblings to a common height: the newcomer joins as
                // a stretched sibling (MATCH_PARENT height), with the standard gap.
                lastRow.addView(card,lp(width,-1).apply { rightMargin=gap })
            }
            pending=pending.drop(slot)
        }
        // Remaining cards form new rows below (the last one may be partial — the next
        // page will fill it the same way).
        pending.chunked(count).forEach { chunk ->
            val line=buildCardRow(chunk,cards,flatIndex,count,width,gap,scroll,list,preview,true,{ false })
            list.addView(line,lp(-1,-2).apply { bottomMargin=dp(10) })
        }
        // Patch focus navigation for every card: rows above the new last row lead down,
        // the last row keeps focus on DOWN (no paging row exists below an infinite grid).
        patchCardNavigation(cards,count)
        if(hasMore) {
            gridLoadingRow=text("正在加载更多…",17f,muted).apply { setPadding(0,dp(28),0,dp(18)) }
            list.addView(gridLoadingRow)
        }
        // New rows need the same poster-height pass the first rows got (the scroll view
        // measures them lazily; do it once here so the first frame after append is final).
        scroll.post {
            if(catalogScroll!==scroll) return@post
            cards.drop(oldSize).forEach { view ->
                val card=view as LinearLayout
                val image=card.getChildAt(0)
                val textHeight=card.paddingTop+card.paddingBottom+(1 until card.childCount).sumOf { card.getChildAt(it).measuredHeight }
                val target=minOf(((width-dp(8))/TvStyle.POSTER_ASPECT).toInt(),(scroll.height-textHeight-dp(4)).coerceAtLeast(dp(48)))
                if(image.layoutParams.height!=target) image.layoutParams=image.layoutParams.apply { height=target }
            }
        }
    }
    private fun renderHome(body: LinearLayout,focusNav: Boolean,focusType: Boolean,loading: Boolean) {
        homeUpdateCount=library.updatedFavorites()
        val preview=SeriesPreview(this); selectionPreview=preview; body.addView(preview)
        // Same collapsible preview as the other pages: hidden until a shelf card takes focus.
        preview.collapsible=true; if(catalogFocus.isBlank()) preview.blank()
        val top=nav[0]
        val home=HomeScreen(this,top,::loadCover,{ item,label -> preview.show(item,if(label.startsWith("第 ")) selectionStatus(item) else listOf(label,selectionStatus(item)).filter { it.isNotBlank() }.joinToString(" · ")) },
            { item,resume -> openDetail(item,resume) },::quickActions,{ catalogFocus=it },
            // Focus left every shelf card (nav bar / refresh / more): collapse the row.
            { preview.blank() })
        homeScreen=home; body.addView(home,lp(-1,0).apply { weight=1f })
        home.render(homeShelves(),catalogFocus,!focusNav && !focusType,null,homePosition)
        home.refresh.text=if(loading) if(homeFromCache) "已显示上次内容 · 正在更新热门…" else "正在更新热门…" else "刷新热门"
        home.refresh.setOnClickListener { invalidateCatalogCache(); showCatalog(true) }
        nav.forEach { it.nextFocusDownId=home.firstId() }
        if(focusNav) nav[0].requestFocus()
        if((homeShort.isNotEmpty() || homeComic.isNotEmpty()) && !homeContentLogged) {
            homeContentLogged=true
            body.post { android.util.Log.i("HongguoTV","Home content ready cached=$homeFromCache elapsedMs=${android.os.SystemClock.elapsedRealtime()-activityStarted}") }
        }
        if(launchStarted>0) {
            val started=launchStarted; launchStarted=0
            body.post { android.util.Log.i("HongguoTV","Home displayed cached=$homeFromCache elapsedMs=${android.os.SystemClock.elapsedRealtime()-started}") }
        }
    }
    private fun homeShelves(): List<HomeScreen.Shelf> {
        val recent=library.history().filterNot { library.watched(it.series.id) }
        val hidden=library.hidden()
        val short=homeShort.filterNot { it.id in hidden }
        val comic=homeComic.filterNot { it.id in hidden }
        return listOf(
            HomeScreen.Shelf("resume","接着看",recent.take(8).map { p -> HomeScreen.Entry(p.series,
                "第 ${p.episodeIndex+1} 集 · ${if(p.completed) "接着看下一集" else formatTime(p.position)}",true,
                if(p.duration>0) (p.position*100/p.duration).toInt().coerceIn(0,100) else null) },
                if(recent.size>8) { { showCollection("resume") } } else null),
            HomeScreen.Shelf("short","热门发现 · 短剧",short.take(10).map { HomeScreen.Entry(it,it.badge.ifBlank { "查看剧集" }) },
                if(short.size>10) { { switchTab(1) } } else null),
            HomeScreen.Shelf("comic","热门发现 · 漫剧",comic.take(10).map { HomeScreen.Entry(it,it.badge.ifBlank { "查看剧集" }) },
                if(comic.size>10) { { switchTab(2) } } else null))
    }
    private fun refreshLibraryCards(changedId: String,removed: Boolean=false) {
        if(screen!="catalog") return
        val home=homeScreen
        if(home!=null) { home.updateShelves(homeShelves(),requestFocus=true); home.refreshSelection(); return }
        if(removed && (localCatalog() || tab==0)) {
            val oldIds=catalogCards.keys.toList(); val index=oldIds.indexOf(changedId).coerceAtLeast(0)
            if(localCatalog()) {
                // Local lists are one infinite grid now: reload the in-memory list and
                // keep the current window (page*pageSize) without re-paging.
                catalog=localItems(); page=page.coerceIn(1,maxOf(1,(catalog.size+pageSize-1)/pageSize)); hasMore=catalog.size>page*pageSize
            }
            val remaining=if(localCatalog()) catalog.take(page*pageSize) else catalog.filterNot { it.id in library.hidden() }
            catalogFocus=remaining.getOrNull(index.coerceAtMost((remaining.size-1).coerceAtLeast(0)))?.id.orEmpty()
            val y=catalogScroll?.scrollY ?: 0; scrollPositions[catalogPageKey]=y
            artwork.cancelPage(); favoriteBadges.clear()
            catalogBody?.let { body -> body.removeAllViews(); catalogGrid(body,false) }
        } else {
            // Only re-show the title row while its card actually holds focus; otherwise
            // the collapsed bar would re-expand behind a dialog/nav-bar focus.
            catalog.firstOrNull { it.id==changedId }?.takeIf { catalogCards[it.id]?.hasFocus()==true }?.let { selectionPreview?.show(it,selectionStatus(it)) }
            favoriteBadges[changedId]?.text=library.favoriteLabel(changedId)
        }
    }
    private fun quickActions(series: Series,anchor: View) {
        val labels=mutableListOf(if(library.progress(series.id)!=null) "继续观看" else "直接播放",
            if(library.favorite(series.id)) "取消收藏" else "收藏这部剧",
            if(library.queued(series.id)) "移出稍后看" else "加入稍后看","查看剧集详情")
        if(tab==0 || tab==1 || tab==2 || tab==3) labels+="不在热门推荐中显示"
        if(tab==7) labels+="管理观看记录"
        tvTools.choose(series.title,labels,anchor,{ choice ->
            when(choice) {
                0 -> openDetail(series,true)
                1 -> { val added=library.toggle(series); Toast.makeText(this,if(added) "已收藏" else "已取消收藏",Toast.LENGTH_SHORT).show(); refreshLibraryCards(series.id,!added && (tab==6 || collection=="updates")); if(added) favoriteMonitor.check() }
                2 -> { val added=library.toggleLater(series); Toast.makeText(this,if(added) "已加入稍后看" else "已移出稍后看",Toast.LENGTH_SHORT).show(); refreshLibraryCards(series.id,!added && collection=="later") }
                3 -> openDetail(series)
                4 -> if(tab==7) manageHistory(series,anchor) else { library.hide(series.id); Toast.makeText(this,"已隐藏热门推荐，可在设置中恢复",Toast.LENGTH_SHORT).show(); refreshLibraryCards(series.id,true) }
            }
        })
    }
    private fun refreshFavoriteLabels() {
        if(screen!="catalog" || !library.isLoaded) return
        favoriteBadges.forEach { (id,label) -> val value=library.favoriteLabel(id); if(label.text.toString()!=value) label.text=value }
        favoriteStatus?.text=favoriteMonitor.status
        favoriteCheck?.text=if(favoriteMonitor.running) "正在检查…" else "检查更新"
        val home=homeScreen
        if(screen=="catalog" && tab==0 && page==1 && collection==null && home!=null && library.updatedFavorites()!=homeUpdateCount) {
            // A stable resume key can be restored without moving the user's selection.
            if(!tvTools.showing && !updater.showing && currentFocus?.tag?.toString()?.startsWith("resume:")==true) {
                homeUpdateCount=library.updatedFavorites(); home.updateShelves(homeShelves())
            } else {
                home.refresh.text="收藏更新 ${library.updatedFavorites()} 部 · 按确认查看"
                home.refresh.setOnClickListener { showCatalog() }
            }
        }
    }
    private fun settings(parent: LinearLayout) {
        parent.addView(text("按你的习惯播放",25f).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL); setPadding(0,dp(15),0,dp(7)) })
        parent.addView(text("偏好自动保存，下一部剧继续使用。",13f,muted).apply { setPadding(0,0,0,dp(18)) })
        val groups=row().apply { gravity=Gravity.TOP }
        val playback=column(); val device=column()
        listOf(playback,device).forEachIndexed { i,group ->
            group.setPadding(dp(16),dp(16),dp(16),dp(16)); group.background=rounded(surface)
            groups.addView(group,lp(0,-2).apply { weight=1f; if(i==0) rightMargin=dp(16) })
        }
        parent.addView(groups)
        fun heading(group: LinearLayout,title: String,subtitle: String) {
            group.addView(text(title,18f).apply { setTypeface(null,Typeface.BOLD) })
            group.addView(text(subtitle,12f,muted).apply { setPadding(0,dp(5),0,dp(15)) })
        }
        fun setting(group: LinearLayout,label: String,action: ()->Unit): TextView {
            val view=button(label,onClick=action).apply {
                gravity=Gravity.CENTER_VERTICAL; setPadding(dp(13),dp(11),dp(13),dp(11))
                background=rounded(bg)
                setOnFocusChangeListener { _,focused -> background=rounded(if(focused) TvStyle.raised else bg,if(focused) accent else Color.TRANSPARENT) }
            }
            group.addView(view,lp(-1,-2).apply { bottomMargin=dp(8) })
            return view
        }
        heading(playback,"播放偏好","所有剧集共用，播放中也可调整")
        heading(device,"片单与设备","保管记录，保持应用更新")
        lateinit var qualityButton: TextView
        qualityButton=setting(playback,"默认清晰度  ·  ${library.maxQuality}P") { showQualityPicker(qualityButton,false) }
        lateinit var defaultSpeed: TextView
        defaultSpeed=setting(playback,"默认倍速  ·  ${PlaybackSpeed.label(library.playbackSpeed)}") { showSpeedPicker(defaultSpeed,false) }
        lateinit var frame: TextView
        frame=setting(playback,"画面模式  ·  ${library.frameMode.label}") { showFramePicker(frame,false) }
        lateinit var autoNextButton: TextView
        autoNextButton=setting(playback,"自动连播  ·  ${if(library.autoNext) "开启" else "关闭"}") {
            library.autoNext=!library.autoNext
            autoNextButton.text="自动连播  ·  ${if(library.autoNext) "开启" else "关闭"}"
        }
        lateinit var danmakuButton: TextView
        danmakuButton=setting(playback,"弹幕  ·  ${if(library.danmaku) "开启" else "关闭"}") {
            library.danmaku=!library.danmaku
            danmakuButton.text="弹幕  ·  ${if(library.danmaku) "开启" else "关闭"}"
        }
        lateinit var danmakuStyleButton: TextView
        danmakuStyleButton=setting(playback,"弹幕样式  ·  ${danmakuStyleSummary()}") { showDanmakuStylePicker(danmakuStyleButton,false) }
        lateinit var backup: TextView
        backup=setting(device,"手机备份与恢复") {
            runCatching { LibraryBackup.encode(library.snapshot()) }.onSuccess { encoded ->
                tvTools.libraryTransfer(encoded,backup) { data,restoreSettings ->
                    favoriteMonitor.stop(); library.restore(data,restoreSettings)
                    tabState.clear(); tabFocus.clear(); catalogFocus=""; page=1
                    showCatalog(); favoriteMonitor.check()
                    Toast.makeText(this,"记录已合并恢复",Toast.LENGTH_LONG).show()
                }
            }.onFailure { tvTools.info("无法生成备份","本机记录暂时无法导出，请重试。",backup) }
        }
        lateinit var hidden: TextView
        hidden=setting(device,"恢复隐藏推荐  ·  ${library.hidden().size} 部") {
            library.unhideAll(); hidden.text="恢复隐藏推荐  ·  0 部"; Toast.makeText(this,"热门推荐已恢复",Toast.LENGTH_SHORT).show()
        }
        lateinit var updates: TextView
        updates=setting(device,"版本与更新  ·  ${BuildConfig.VERSION_NAME}") { showUpdateWithFeedback(updates) }
        val license=setting(device,"开源许可") {
            AlertDialog.Builder(this).setTitle("开源许可").setMessage("红果TV以 GPL-3.0 发布。\n派生自 N3urda/hongguoTV（GPL-3.0），接口链路参考 HongguoTV-Phone-TV-NoAds（Kotlin 重写），内容协议与加密处理移植自 drpys（22261ad）。\nAndroidX Media3 / OkHttp：Apache-2.0\nKotlin：Apache-2.0\nBouncy Castle：MIT\n完整源码和许可证见 GitHub：fyworld/hongguoTV。").setPositiveButton("关闭",null).show()
        }
        val left=listOf(qualityButton,defaultSpeed,frame,autoNextButton,danmakuButton,danmakuStyleButton); val right=listOf(backup,hidden,updates,license)
        listOf(left,right).forEach { items -> items.forEachIndexed { index,view ->
            view.nextFocusUpId=items.getOrNull(index-1)?.id ?: nav[tab].id
            view.nextFocusDownId=items.getOrNull(index+1)?.id ?: view.id
        } }
        left.forEachIndexed { i,v -> v.nextFocusLeftId=v.id; v.nextFocusRightId=right[i.coerceAtMost(right.lastIndex)].id }
        right.forEachIndexed { i,v -> v.nextFocusLeftId=left[i.coerceAtMost(left.lastIndex)].id; v.nextFocusRightId=v.id }
        nav.forEach { it.nextFocusDownId=qualityButton.id }
        parent.addView(text("红果TV v${BuildConfig.VERSION_NAME} by Dong    ·    收藏与进度保存在本机",12f,muted).apply { setPadding(dp(2),dp(18),0,dp(10)) })
        nav[tab].requestFocus()
    }

    /** Update dialog with the merged feedback entry (WeChat QR). */
    private fun showUpdateWithFeedback(anchor: View?) {
        updater.extraAction="交流反馈 · 加作者微信" to { showWechatDialog(anchor) }
        updater.show(anchor)
    }

    /** WeChat contact QR: scan to add the author as friend, remark "红果TV". */
    private fun showWechatDialog(anchor: View?) {
        val dp=resources.displayMetrics.density
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_HORIZONTAL; setPadding(dp(16),dp(12),dp(16),dp(8)) }
        body.addView(ImageView(this).apply {
            setImageResource(com.hongguotv.nativeapp.R.drawable.wechat_qr)
            scaleType=ImageView.ScaleType.FIT_CENTER
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            adjustViewBounds=true
        },LinearLayout.LayoutParams(dp(230),dp(230)))
        body.addView(text("扫码加作者微信",16f).apply { gravity=Gravity.CENTER; setTypeface(null,Typeface.BOLD); setPadding(0,dp(10),0,dp(2)) })
        body.addView(text("添加时请备注：红果TV",14f,accent).apply { gravity=Gravity.CENTER; setPadding(0,0,0,dp(4)) })
        body.addView(text("可反馈问题、提功能建议",13f,muted).apply { gravity=Gravity.CENTER })
        AlertDialog.Builder(this).setTitle("交流反馈").setView(ScrollView(this).apply { addView(body) })
            .setNegativeButton("关闭",null).create().apply {
                setOnShowListener { getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus() }
                show()
            }
    }
    // ---------- TV letter keyboard search (phone-version TelevisionSearchDialog port) ----------

    /**
     * Full search screen: left keyboard column (~30%), middle live suggestions (~25%),
     * right history (~45%) with flow-wrapped entries. After a search is confirmed the
     * middle+right area becomes a single ~70% result-card column (4 cards per row).
     */
    private fun buildTvSearchScreen(): View {
        val screen=row().apply { setPadding(0,dp(14),0,0) }
        val total=resources.displayMetrics.widthPixels
        val gap=dp(16)
        val leftWidth=(total*.30f).toInt()
        // The query box aligns with the keyboard's right edge (F key), i.e. the column width
        // minus the key grid's trailing gap.
        val keyGap=dp(6)
        val queryWidth=leftWidth-keyGap
        // Left column: query box, action rows, A-Z grid.
        val left=column()
        // Editable query box: the system IME (soft/voice keyboard) can type into it directly,
        // while the on-screen A-Z grid writes through tvQuery as before.
        tvQueryView=EditText(this).apply {
            id=View.generateViewId(); setPadding(dp(12),dp(12),dp(12),dp(12))
            textSize=16f; setTextColor(white); setHintTextColor(muted)
            hint="拼音首字母搜索（如 BFLC）"
            background=rounded(surface,if(tvQuery.isEmpty()) Color.TRANSPARENT else accent)
            maxLines=1; ellipsize=TextUtils.TruncateAt.END
            isFocusable=true; isFocusableInTouchMode=true
            isCursorVisible=true; setTextIsSelectable(false)
            if(tvQuery.isNotEmpty()) setText(tvQuery)
            setSelection(tvQuery.length)
        }
        tvQueryView?.setOnFocusChangeListener { view,focused ->
            view.background=rounded(if(focused) TvStyle.raised else surface,if(focused) accent else if(tvQuery.isEmpty()) Color.TRANSPARENT else accent)
            // Touch focus opens the system IME; D-pad focus keeps it closed for remote-only use.
            if(focused && !view.isInTouchMode) runCatching { (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(view.windowToken,0) }
        }
        // Typing in the box mirrors into tvQuery + live suggestions.
        tvQueryView?.addTextChangedListener(object: android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
            override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val typed=s?.toString()?.trim().orEmpty()
                if(typed==tvQuery) return
                tvQuery=typed
                tvQueryView?.background=rounded(if(tvQueryView?.hasFocus()==true) TvStyle.raised else surface,if(typed.isEmpty()) Color.TRANSPARENT else accent)
                if(typed.isEmpty() && tvResultQuery.isNotBlank()) tvClearAll()
                else scheduleTvSuggests()
            }
        })
        // IME action = run the search directly.
        tvQueryView?.setOnEditorActionListener { _,actionId,event ->
            if(actionId==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH || (event!=null && event.action==KeyEvent.ACTION_UP && (event.keyCode==KeyEvent.KEYCODE_ENTER || event.keyCode==KeyEvent.KEYCODE_DPAD_CENTER))) { tvSubmit(); true } else false
        }
        left.addView(tvQueryView,lp(queryWidth,dp(46)))
        val row1=row().apply { setPadding(0,dp(8),0,0) }
        tvSearchButton=button("搜索",selected=true) { tvSubmit() }.apply { gravity=Gravity.CENTER }
        tvClear=button("清空") { tvClearAll() }.apply { gravity=Gravity.CENTER }
        listOf(tvSearchButton!!,tvClear!!).forEach { row1.addView(it,lp(0,dp(44)).apply { weight=1f; rightMargin=dp(8) }) }
        left.addView(row1)
        val row2=row().apply { setPadding(0,dp(8),0,dp(10)) }
        lateinit var phone: TextView
        phone=button("手机输入") { tvTools.phoneInput(phone) { tvSubmitText(it) } }.apply { gravity=Gravity.CENTER }
        tvPhoneButton=phone
        tvBackspace=button("删除") { tvBackspace() }.apply { gravity=Gravity.CENTER }
        listOf(phone,tvBackspace!!).forEach { row2.addView(it,lp(0,dp(44)).apply { weight=1f; rightMargin=dp(8) }) }
        left.addView(row2)
        left.addView(buildTvKeyGrid())
        screen.addView(left,lp(leftWidth,android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin=gap })
        // Middle column: live suggestions (hidden once results are shown).
        tvMiddleColumn=column()
        tvSuggestTitle=text("匹配结果",16f,accent).apply { setTypeface(null,Typeface.BOLD); setPadding(0,0,0,dp(8)) }
        tvMiddleColumn?.addView(tvSuggestTitle)
        val suggestScroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false; isFocusable=false }
        tvSuggestScroll=suggestScroll
        tvSuggestList=column(); suggestScroll.addView(tvSuggestList)
        tvMiddleColumn?.addView(suggestScroll,lp(-1,0).apply { weight=1f })
        screen.addView(tvMiddleColumn,lp((total*.20f).toInt(),android.view.ViewGroup.LayoutParams.MATCH_PARENT).apply { rightMargin=gap })
        // Right column: history with flow-wrapped entries (hidden once results are shown).
        tvRightColumn=column()
        val historyHeading=row()
        tvHistoryTitle=text("搜索历史",16f,accent).apply { setTypeface(null,Typeface.BOLD) }
        historyHeading.addView(tvHistoryTitle,lp(0,-2).apply { weight=1f })
        lateinit var clearHistory: View
        clearHistory=ImageView(this).apply {
            id=View.generateViewId(); isFocusable=true; isFocusableInTouchMode=true
            setImageResource(com.hongguotv.nativeapp.R.drawable.ic_trash)
            contentDescription="清空搜索历史"
            scaleType=ImageView.ScaleType.FIT_CENTER
            setPadding(dp(7),dp(7),dp(7),dp(7))
            fun paint(focused: Boolean) { background=rounded(if(focused) TvStyle.raised else Color.TRANSPARENT,if(focused) accent else Color.TRANSPARENT) }
            paint(false); setOnFocusChangeListener { _,focused -> paint(focused) }
            setOnClickListener { tvTools.confirm("清空搜索历史","清除本机保存的搜索词？",clearHistory) { library.clearSearches(); renderTvHistory() } }
        }
        tvClearHistory=clearHistory
        // Down from the clear icon goes to the first history entry (rewired in
        // renderTvHistory once entries exist); up keeps the default nav-bar search.
        clearHistory.nextFocusDownId=tvHistoryFlow?.let { flow -> if(flow.childCount>0) flow.getChildAt(0).id else View.NO_ID } ?: View.NO_ID
        historyHeading.addView(clearHistory,lp(dp(34),dp(34)).apply { leftMargin=dp(8) })
        tvRightColumn?.addView(historyHeading)
        val historyScroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false; isFocusable=false }
        tvHistoryScroll=historyScroll
        tvHistoryFlow=FlowLayout(this)
        historyScroll.addView(tvHistoryFlow)
        tvRightColumn?.addView(historyScroll,lp(-1,0).apply { weight=1f })
        screen.addView(tvRightColumn,lp(0,android.view.ViewGroup.LayoutParams.MATCH_PARENT).apply { weight=1f })
        // Result panel: spans the middle+right area (~70%) once a search is confirmed.
        tvResultPanel=column().apply { visibility=View.GONE }
        tvResultTitle=text("",16f,accent).apply { setTypeface(null,Typeface.BOLD); setPadding(0,0,0,dp(8)) }
        tvResultPanel?.addView(tvResultTitle)
        val resultScroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false; isFocusable=false }
        tvResultScroll=resultScroll
        tvResultList=column(); resultScroll.addView(tvResultList)
        tvResultPanel?.addView(resultScroll,lp(-1,0).apply { weight=1f })
        screen.addView(tvResultPanel,lp(0,android.view.ViewGroup.LayoutParams.MATCH_PARENT).apply { weight=1f })
        renderTvSuggests(); renderTvHistory(); applyTvLayoutMode()
        return screen
    }

    /** Three-column mode vs result mode: results take over the middle+right area. */
    private fun applyTvLayoutMode() {
        val showingResults=tvResultQuery.isNotBlank()
        tvMiddleColumn?.visibility=if(showingResults) View.GONE else View.VISIBLE
        tvRightColumn?.visibility=if(showingResults) View.GONE else View.VISIBLE
        tvResultPanel?.visibility=if(showingResults) View.VISIBLE else View.GONE
    }

    private fun buildTvKeyGrid(): View {
        val grid=column(); val columns=6; val gap=dp(6)
        val keys=tvKeys.chunked(columns)
        val keyViews=mutableListOf<View>()
        keys.forEach { lineKeys ->
            val line=row()
            lineKeys.forEach { char ->
                val key=button(char) { tvPress(char) }.apply { gravity=Gravity.CENTER; textSize=18f }
                line.addView(key,lp(0,dp(46)).apply { weight=1f; rightMargin=gap })
                keyViews+=key
            }
            repeat(columns-lineKeys.size) { line.addView(Space(this),lp(0,dp(46)).apply { weight=1f; rightMargin=gap }) }
            grid.addView(line,lp(-1,dp(52)))
        }
        tvFirstKey=keyViews.first()
        // Focus wiring: top row goes up to the 手机输入/删除 action row (not the query
        // box — the box is reached from that row); right edge of the grid hands focus to
        // the middle suggestion column (filled by renderTvSuggests).
        keyViews.forEachIndexed { index,view ->
            view.nextFocusUpId=if(index<columns) tvPhoneButton?.id ?: view.id else keyViews[index-columns].id
            view.nextFocusDownId=if(index+columns<keyViews.size) keyViews[index+columns].id else view.id
            view.nextFocusLeftId=if(index%columns==0) view.id else keyViews[index-1].id
            view.nextFocusRightId=if(index%columns==columns-1) view.id else keyViews[index+1].id
        }
        return grid
    }

    private fun tvPress(char: String) {
        if(tvQuery.length>=80) return
        tvQuery+=char; updateTvQueryView(); scheduleTvSuggests()
    }

    private fun tvBackspace() {
        if(tvQuery.isEmpty()) return
        tvQuery=tvQuery.dropLast(1); updateTvQueryView()
        if(tvQuery.isEmpty()) { tvClearAll(); return }
        scheduleTvSuggests()
    }

    /** Left-column clear: wipes the query box AND the result cards, back to three columns. */
    private fun tvClearAll() {
        tvQuery=""; tvSuggests=emptyList(); tvResultQuery=""; catalog=emptyList(); hasMore=false
        updateTvQueryView(); main.removeCallbacks(tvSuggestDebounce)
        renderTvSuggests(); renderTvHistory(); applyTvLayoutMode()
        tvQueryView?.requestFocus()
    }

    private fun updateTvQueryView() {
        // EditText shows the placeholder via hint; only sync real text and keep the caret at the end.
        tvQueryView?.let { view ->
            if(view.text.toString()!=tvQuery) { view.setText(if(tvQuery.isEmpty()) "" else tvQuery); view.setSelection(tvQuery.length) }
        }
        tvQueryView?.background=rounded(if(tvQueryView?.hasFocus()==true) TvStyle.raised else surface,if(tvQuery.isEmpty()) Color.TRANSPARENT else accent)
    }

    /** 250ms debounce, mirrors the Flutter _onQueryChanged. */
    private fun scheduleTvSuggests() {
        main.removeCallbacks(tvSuggestDebounce)
        if(tvQuery.isBlank()) { tvSuggests=emptyList(); renderTvSuggests(); return }
        main.postDelayed(tvSuggestDebounce,250)
    }

    private fun fetchTvSuggests() {
        val keyword=tvQuery.trim(); if(keyword.isBlank()) return
        tvSuggestTicket++; val ticket=tvSuggestTicket
        tvSuggestJob?.cancel(true)
        tvSuggestJob=io.submit {
            val result=runCatching { repository.suggestions(keyword) }.getOrDefault(emptyList())
            main.post { if(ticket==tvSuggestTicket && screen=="catalog" && tab==4) { tvSuggests=result; renderTvSuggests() } }
        }
    }

    private fun renderTvSuggests() {
        val list=tvSuggestList ?: return
        list.removeAllViews()
        tvSuggestTitle?.text=if(tvQuery.isBlank()) "匹配结果" else "匹配结果（${tvSuggests.size}）"
        val items=mutableListOf<View>()
        if(tvQuery.isBlank()) {
            list.addView(text("输入拼音首字母后\n这里会实时显示联想",14f,muted).apply { setPadding(0,dp(10),0,dp(10)) })
        } else if(tvSuggests.isEmpty()) {
            list.addView(text("正在匹配…\n或按【搜索】直接查找 “$tvQuery”",14f,muted).apply { setPadding(0,dp(10),0,dp(10)) })
        } else {
            tvSuggests.forEach { name ->
                val item=button(name) { tvSubmitText(name) }.apply { gravity=Gravity.CENTER_VERTICAL or Gravity.START }
                list.addView(item,lp(-1,dp(42)).apply { bottomMargin=dp(5) }); items+=item
            }
        }
        // Focus chain: vertical navigation, up from first goes to the query box, left to the
        // keyboard, right edge hands focus to the history column (first entry or clear icon).
        items.forEachIndexed { index,view ->
            view.nextFocusUpId=if(index==0) tvQueryView?.id ?: view.id else items[index-1].id
            view.nextFocusDownId=if(index==items.lastIndex) view.id else items[index+1].id
            view.nextFocusLeftId=tvFirstKey?.id ?: view.id
            view.nextFocusRightId=tvHistoryEntryFocus() ?: view.id
        }
        // The keyboard's right edge hands focus to the first suggestion (or history when empty).
        val rightTarget=items.firstOrNull()?.id ?: tvHistoryEntryFocus() ?: View.NO_ID
        if(rightTarget!=View.NO_ID) wireKeyboardRightFocus(rightTarget)
    }

    /** Points the rightmost keyboard keys at [targetId] so → moves from the grid to the lists. */
    private fun wireKeyboardRightFocus(targetId: Int) {
        val first=tvFirstKey ?: return
        var view: View?=first
        val grid=(first.parent as? View)?.parent as? View
        // Walk all key rows: the last key of each row gets the right target.
        if(grid is LinearLayout) {
            for(rowIndex in 0 until grid.childCount) {
                val line=grid.getChildAt(rowIndex) as? LinearLayout ?: continue
                val last=line.getChildAt(line.childCount-1)
                if(last is TextView && last.isFocusable) last.nextFocusRightId=targetId
            }
        }
    }

    private fun renderTvHistory() {
        val flow=tvHistoryFlow ?: return
        flow.removeAllViews()
        val history=library.searches()
        if(history.isEmpty()) { flow.addView(text("暂无历史搜索\n用左侧键盘按首字母搜剧",14f,muted).apply { setPadding(0,dp(10),0,dp(10)) }); return }
        val items=mutableListOf<View>()
        history.forEach { entry ->
            val item=button(entry.query) { tvSubmitText(entry.query) }
            flow.addView(item,ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT)); items+=item
        }
        // Focus chain: reading order (left-to-right, top-to-bottom), left edge goes to the
        // keyboard, up from the first row goes to the clear-history icon (then the nav bar).
        items.forEachIndexed { index,view ->
            view.nextFocusLeftId=if(index==0) tvFirstKey?.id ?: view.id else items[index-1].id
            view.nextFocusRightId=if(index==items.lastIndex) view.id else items[index+1].id
            view.nextFocusUpId=tvClearHistory?.id ?: view.id
            view.nextFocusDownId=view.id
        }
        // Keep the clear icon's DOWN pointing at the (possibly rebuilt) first entry.
        tvClearHistory?.nextFocusDownId=items.firstOrNull()?.id ?: View.NO_ID
    }

    /** Right-hand target from the suggestion list: first history entry, or the clear icon
     *  when the history is empty (so → always lands somewhere meaningful). */
    private fun tvHistoryEntryFocus(): Int? {
        val flow=tvHistoryFlow ?: return null
        if(flow.childCount>0) return flow.getChildAt(0).id
        return tvClearHistory?.id
    }

    private fun renderTvResults() {
        val list=tvResultList ?: return
        if(tvResultQuery.isBlank()) { applyTvLayoutMode(); return }
        applyTvLayoutMode()
        if(catalog.isEmpty()) {
            tvResultTitle?.text="搜索结果 · $tvResultQuery"
            list.removeAllViews()
            list.addView(text("没有找到相关剧集，换个关键词试试",14f,muted).apply { setPadding(0,dp(14),0,dp(14)) })
            return
        }
        tvResultTitle?.text="搜索结果 · $tvResultQuery（${catalog.size}）"
        list.removeAllViews()
        val items=mutableListOf<View>()
        val count=4; val gap=dp(14)
        // Card width must match the result panel's REAL width: screen minus page padding (2*5%),
        // the left column (30%) and its trailing gap. Using screen*70% overflowed the panel and
        // clipped the 4th card, so measure the panel itself instead.
        val panelWidth=tvResultPanel?.width?.takeIf { it>0 } ?: ((resources.displayMetrics.widthPixels*.60f)-gap).toInt()
        val width=((panelWidth-gap*(count-1))/count).coerceAtLeast(dp(120))
        catalog.take(40).chunked(count).forEach { rowItems ->
            val line=row(); line.gravity=Gravity.TOP
            rowItems.forEachIndexed { columnIndex,series ->
                val card=column().apply { setPadding(dp(6),dp(6),dp(6),dp(8)); focusStyle(this); contentDescription=series.title }
                val artwork=FrameLayout(this)
                card.addView(artwork,lp(-1,dp(150)))
                val image=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                artwork.addView(image,FrameLayout.LayoutParams(-1,-1)); loadCover(image,series.cover)
                card.addView(text(series.title,13f).apply { gravity=Gravity.CENTER; maxLines=2; minLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(2),dp(5),dp(2),0) })
                card.addView(text(series.badge.ifBlank { "查看剧集" },11f,muted).apply { gravity=Gravity.CENTER; maxLines=1; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(2),0,dp(2),0) })
                card.setOnClickListener { openDetail(series) }
                card.setOnLongClickListener { quickActions(series,card); true }
                line.addView(card,lp(width,-1).apply { if(columnIndex<count-1) rightMargin=gap }); items+=card
            }
            list.addView(line,lp(-1,android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin=dp(12) })
        }
        // Focus chain: grid navigation, left edge goes back to the keyboard.
        items.forEachIndexed { index,view ->
            view.nextFocusLeftId=if(index%count==0) tvFirstKey?.id ?: view.id else items[index-1].id
            view.nextFocusRightId=if(index%count==count-1 || index==items.lastIndex) view.id else items[index+1].id
            view.nextFocusUpId=if(index<count) tvQueryView?.id ?: view.id else items[index-count].id
            view.nextFocusDownId=if(index+count<items.size) items[index+count].id else view.id
        }
        if(items.isNotEmpty()) items.first().requestFocus()
        // Result cards take over the middle+right area: point the keyboard's right edge here too.
        items.firstOrNull()?.let { wireKeyboardRightFocus(it.id) }
    }

    /** Mirrors the Flutter _submit: pure pinyin/letters take the first suggestion's full name. */
    private fun tvSubmit() {
        var target=tvQuery.trim()
        if(target.isEmpty()) return
        if(tvSuggests.isNotEmpty() && Regex("^[a-zA-Z0-9]+$").matches(target)) target=tvSuggests.first()
        tvSubmitText(target)
    }

    /** Runs the search and shows result cards in the middle+right area (two-column mode). */
    private fun tvSubmitText(value: String) {
        val clean=value.trim(); if(clean.isEmpty() || clean.length>80) return
        tvResultQuery=clean; library.rememberSearch(clean,library.contentType); query=clean; page=1
        tvResultTitle?.text="搜索结果 · $clean"; tvResultList?.removeAllViews()
        tvResultList?.addView(text("正在搜索…",14f,muted).apply { setPadding(0,dp(10),0,dp(10)) })
        // The layout switch below hides the suggestion/history columns. Focus is usually
        // sitting on a suggestion entry inside them — when that view disappears Android
        // would hand focus to the top nav (首页), whose focus-to-switch listener then
        // leaves the search tab. Park focus on the query box first and suppress the nav
        // listener during the swap, exactly like the infinite-grid rebuild.
        tvQueryView?.requestFocus()
        rebuildingGrid=true
        try { applyTvLayoutMode() } finally { rebuildingGrid=false }
        // The panel just switched from GONE to VISIBLE, so its width is not measured yet.
        // Re-layout now so renderTvResults() can read the real panel width for card sizing.
        tvResultPanel?.requestLayout()
        work({ repository.searchEnhanced(clean) }, { result ->
            catalog=result.items; hasMore=result.hasMore
            if(result.warning.isNotBlank()) Toast.makeText(this,result.warning,Toast.LENGTH_LONG).show()
            renderTvResults()
        }, { problem ->
            tvResultList?.removeAllViews()
            tvResultList?.addView(text(if(problem is java.net.UnknownHostException) "无法连接内容网站" else if(problem is java.net.SocketTimeoutException) "连接超时，请重试" else "搜索失败，请重试",14f,muted).apply { setPadding(0,dp(10),0,dp(10)) })
            val retry=button("重试") { tvSubmitText(clean) }
            tvResultList?.addView(retry,lp(-1,dp(42))); retry.requestFocus()
        })
    }

    private fun manageHistory(series: Series,anchor: View?) {
        tvTools.choose(series.title,listOf("继续观看",if(library.watched(series.id)) "恢复未看状态" else "标记整剧已看","删除这条记录"),anchor,{ index ->
            when(index) {
                0 -> openDetail(series,true)
                1 -> { library.setWatched(series.id,!library.watched(series.id)); catalogFocus=series.id; showCatalog() }
                2 -> tvTools.confirm("删除观看记录","删除《${series.title}》的本机观看进度？收藏会保留。",anchor) { library.removeHistory(series.id); showCatalog() }
            }
        })
    }
    private fun openDetail(series: Series,resume: Boolean=false,autoplay: Boolean=true) {
        pendingDetail=series; detailAutoplay=autoplay
        invalidatePage(); releaseCatalogViews(); screen="detail"; directPlayback=resume; detail=null; synopsisExpanded=false
        val body=base(series.title); message(body,"正在加载剧集…")
        work({ repository.detail(series.id,tabContentType()) }, { result ->
            detail=result; library.observeFavorite(series.id,result.episodes.size,System.currentTimeMillis(),acknowledge=true)
            val progress=library.progress(series.id)
            val target=ResumePlayback.target(result.episodes,progress?.episodeId,progress?.position ?: 0,progress?.completed==true)
            episodeIndex=target.index; group=episodeIndex/20
            if(resume) playEpisode(target.index,target.position,autoplay=detailAutoplay) else showDetail(false)
        }, { problem -> error(body,problem) { openDetail(series,resume,detailAutoplay) } })
    }
    private fun showDetail(focusEpisode: Boolean) {
        invalidatePage(); releaseCatalogViews(); screen="detail"; val data=detail ?: return showCatalog()
        val body=base("剧集详情")
        val scroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false }; val content=column(); scroll.addView(content); body.addView(scroll,lp(-1,0).apply { weight=1f })
        val hero=row(); hero.gravity=Gravity.TOP; hero.setPadding(0,dp(12),0,dp(12))
        val image=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER }; hero.addView(image,lp(dp(123),dp(174)).apply { rightMargin=dp(24) }); loadCover(image,data.series.cover)
        val info=column(); hero.addView(info,lp(0,-2).apply { weight=1f })
        info.addView(text(data.series.title,26f).apply { setTypeface(null,Typeface.BOLD); maxLines=2; ellipsize=TextUtils.TruncateAt.END })
        info.addView(text("${data.episodes.size} 集  ·  ${data.series.tags}",13f,muted).apply { maxLines=1; setPadding(0,dp(8),0,dp(8)) })
        val description=data.series.description.ifBlank { "选择剧集开始观看" }
        val synopsis=text(description,15f,muted).apply { maxLines=if(synopsisExpanded) 20 else 2; ellipsize=TextUtils.TruncateAt.END }
        info.addView(synopsis)
        val actions=row(); actions.setPadding(0,dp(12),0,0); info.addView(actions)
        val progress=library.progress(data.series.id)
        val resumeIndex=progress?.let { data.episodes.indexOf(it.episodeId).takeIf { n -> n>=0 } } ?: 0
        val resumePosition=progress?.takeIf { !it.completed && resumeIndex==it.episodeIndex }?.position ?: 0
        val watched=library.watched(data.series.id)
        val play=addButton(actions,if(watched) "重新观看" else if(progress!=null && !progress.completed) "继续第 ${resumeIndex+1} 集" else "开始观看") { playEpisode(if(watched) 0 else if(progress?.completed==true) (resumeIndex+1).coerceAtMost(data.episodes.lastIndex) else resumeIndex,if(watched) 0 else resumePosition) }
        lateinit var favorite: TextView
        favorite=addButton(actions,if(library.favorite(data.series.id)) "已收藏" else "收藏") {
            favoriteMonitor.stop()
            val saved=library.toggle(data.series)
            if(saved) library.observeFavorite(data.series.id,data.episodes.size,System.currentTimeMillis(),acknowledge=true)
            favorite.text=if(saved) "已收藏" else "收藏"
        }
        lateinit var synopsisButton: TextView
        synopsisButton=addButton(actions,if(synopsisExpanded) "收起简介" else "完整简介") {
            synopsisExpanded=!synopsisExpanded
            synopsis.maxLines=if(synopsisExpanded) 20 else 2
            synopsisButton.text=if(synopsisExpanded) "收起简介" else "完整简介"
            synopsisButton.post { synopsisButton.requestRectangleOnScreen(android.graphics.Rect(0,0,synopsisButton.width,synopsisButton.height),false) }
        }
        content.addView(hero)
        val groupRow=row(); groupRow.setPadding(0,dp(3),0,dp(5))
        val maxGroup=data.episodes.lastIndex/20; group=group.coerceIn(0,maxGroup)
        val previous=addButton(groupRow,"‹ 上一组") { if(group>0) { group--; showDetail(true) } }.apply { isEnabled=group>0; isFocusable=group>0; alpha=if(group>0) 1f else .4f }
        groupRow.addView(text("第 ${group*20+1}—${minOf((group+1)*20,data.episodes.size)} 集",16f).apply { gravity=Gravity.CENTER },lp(dp(190),dp(42)))
        addButton(groupRow,"下一组 ›") { if(group<maxGroup) { group++; showDetail(true) } }.apply { isEnabled=group<maxGroup; isFocusable=group<maxGroup; alpha=if(group<maxGroup) 1f else .4f }
        lateinit var jump: TextView
        jump=addButton(groupRow,"跳转集数") { tvTools.episodePicker(data.episodes.size,episodeIndex,jump) { target -> episodeIndex=target; group=target/20; showDetail(true) } }
        content.addView(groupRow)
        val episodes=mutableListOf<View>()
        (group*20 until minOf((group+1)*20,data.episodes.size)).toList().chunked(10).forEach { numbers ->
            val line=row()
            numbers.forEach { index -> val item=button("${index+1}",index==episodeIndex) { playEpisode(index,if(progress?.episodeId==data.episodes[index] && !progress.completed) progress.position else 0) }; item.contentDescription="第 ${index+1} 集"; line.addView(item,lp(0,dp(42)).apply { weight=1f; rightMargin=dp(6) }); episodes+=item }
            repeat(10-numbers.size) { line.addView(Space(this),lp(0,dp(42)).apply { weight=1f; rightMargin=dp(6) }) }
            content.addView(line,lp(-1,dp(49)))
        }
        episodes.forEachIndexed { i,v -> v.nextFocusLeftId=if(i%10==0) v.id else episodes[i-1].id; v.nextFocusRightId=if(i%10==9 || i==episodes.lastIndex) v.id else episodes[i+1].id; if(i>=10) v.nextFocusUpId=episodes[i-10].id; v.nextFocusDownId=if(i+10<episodes.size) episodes[i+10].id else v.id }
        if(focusEpisode) episodes.getOrNull((episodeIndex-group*20).coerceIn(0,episodes.lastIndex))?.requestFocus() else play.requestFocus()
    }
    private fun playEpisode(index: Int,position: Long=0,autoplay: Boolean=true,recovering: Boolean=false) {
        updater.interrupt()
        val data=detail ?: return
        val playbackStarted=android.os.SystemClock.elapsedRealtime()
        val reuse=screen=="player" && player!=null && playerView!=null && !recovering && !playError
        library.setWatched(data.series.id,false)
        val selectedIndex=index.coerceIn(0,data.episodes.lastIndex)
        val prefetched=if(position==0L && !recovering) preparedNext.take("${data.episodes[selectedIndex]}:${library.maxQuality}") else null
        val claimedHttp=if(prefetched!=null) prefetchHttp.also { prefetchHttp=null } else null
        saveProgress(); library.flush(); invalidatePage(); favoriteMonitor.stop(); releaseCatalogViews()
        if(reuse) {
            cancelRecovery(); cancelPrefetch(); tvTools.close()
            episodePanel?.dismiss(); episodePanel=null; speedDialog?.dismiss(); speedDialog=null
            playerListener?.let { player?.removeListener(it) }; playerListener=null
            player?.stop(); player?.clearMediaItems()
            video?.close(); video=null; closeTransport(playbackHttp); playbackHttp=null
            main.removeCallbacks(hideHud); main.removeCallbacks(seekRunnable); pendingSeek=null
        } else releasePlayer()
        artwork.cancelPage(clearMemory=true)
        playbackHttp=claimedHttp; video=prefetched
        var firstReady=true; var firstFrame=true
        if(!recovering) recovery.reset()
        autoRecovery=false; readySince=0; startPosition=position.coerceAtLeast(0); requestedAutoplay=autoplay
        if(autoplay) sleepStopped=false
        screen="player"; episodeIndex=selectedIndex; group=episodeIndex/20
        panel=false; playError=false; playbackReady=false; quality=""; pausedForLifecycle=!autoplay
        player?.playWhenReady=requestedAutoplay
        prefetchAttempts=0; prefetchAttemptAt=0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if(!reuse) {
            root.removeAllViews(); root.setBackgroundColor(Color.BLACK)
            val view=PlayerView(this).apply {
                useController=false; resizeMode=frameResizeMode(); isFocusable=false
                setKeepContentOnPlayerReset(true); setShutterBackgroundColor(Color.BLACK); setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            }
            playerView=view; root.addView(view,FrameLayout.LayoutParams(-1,-1))
            if(library.danmaku) {
                danmakuOverlay=DanmakuOverlay(this).apply { restyle(currentDanmakuParams()); loadDemoData() }
                root.addView(danmakuOverlay,FrameLayout.LayoutParams(-1,-1))
            }
            hud=column().apply { setPadding(dp(widthDp()*.05f),dp(20),dp(widthDp()*.05f),(resources.displayMetrics.heightPixels*.05f).toInt()); background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.TRANSPARENT,Color.argb(240,11,15,21))) }
            playbackTitle=text("",23f).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END; setTypeface(null,Typeface.BOLD) }; hud.addView(playbackTitle)
            playbackText=text("",15f,muted).apply { setPadding(0,dp(10),0,dp(9)) }; hud.addView(playbackText)
            progressBar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=1000; progressTintList=android.content.res.ColorStateList.valueOf(accent); progressBackgroundTintList=android.content.res.ColorStateList.valueOf(surface) }; hud.addView(progressBar,lp(-1,dp(4)))
            hud.addView(text("确认 暂停/播放    左右 快退/快进    ↓ 选集    ↑ 更多    返回 收起 / 退出",13f,muted).apply { setPadding(0,dp(12),0,0) })
            controls=column().apply { setPadding(0,dp(12),0,0); visibility=View.GONE }; hud.addView(controls)
            root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled=false; addView(hud) },FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        }
        playbackTitle.text="${data.series.title}  ·  第 ${episodeIndex+1} 集"
        playbackText.text="正在获取播放地址…"; progressBar.progress=0; progressBar.visibility=View.VISIBLE; hidePlaybackOverlay()
        controls.removeAllViews(); controls.visibility=View.GONE; transportPlay=null; sleepButton=null
        syncMediaSession()
        val requestedId=data.episodes[episodeIndex]; val maxQuality=library.maxQuality; val ticket=generation
        work({ prefetched ?: RemoteVideo(repository.http,repository.stream(requestedId,maxQuality,data.contentType)).prepare() }, { remote ->
            video=remote; quality=remote.info.quality
            val p=player ?: run {
                val load=DefaultLoadControl.Builder().setBufferDurationsMs(15000,30000,1000,2000).setTargetBufferBytes(12*1024*1024).build()
                val renderers=androidx.media3.exoplayer.DefaultRenderersFactory(this).setEnableDecoderFallback(true)
                ExoPlayer.Builder(this,renderers).setLoadControl(load).build().also {
                    player=it; playerView?.player=it
                    it.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
                    it.setHandleAudioBecomingNoisy(true)
                }
            }
            p.setPlaybackSpeed(library.playbackSpeed)
            val listener=object: Player.Listener {
                private fun active()=player===p && generation==ticket && p.currentMediaItem?.mediaId==requestedId
                override fun onPlaybackStateChanged(state: Int) {
                    if(!active()) return
                    if(state==Player.STATE_READY) {
                        if(!p.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) {
                            playerFailure(PlaybackException("资源没有可解码的视频轨道",null,PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
                            return
                        }
                        playbackReady=true
                        if(firstReady) { firstReady=false; readySince=android.os.SystemClock.elapsedRealtime(); android.util.Log.i("HongguoTV","Playback ready episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${readySince-playbackStarted}") }
                        if(hud.visibility==View.VISIBLE) updatePlaybackText()
                    }
                    syncMediaSession()
                    if(state==Player.STATE_ENDED) {
                        saveProgress(true)
                        if(sleepTimer.episodeEnded()) stopForSleep()
                        else if(foreground && requestedAutoplay && !pausedForLifecycle && library.autoNext && episodeIndex<data.episodes.lastIndex) playEpisode(episodeIndex+1)
                        else { requestedAutoplay=false; p.pause(); playbackText.text="本集已结束"; showPanel() }
                    }
                }
                override fun onRenderedFirstFrame() {
                    if(active() && firstFrame) {
                        firstFrame=false
                        android.util.Log.i("HongguoTV","Playback first frame episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${android.os.SystemClock.elapsedRealtime()-playbackStarted}")
                    }
                }
                override fun onIsPlayingChanged(playing: Boolean) { if(active()) { if(hud.visibility==View.VISIBLE) showHud(); syncMediaSession() } }
                override fun onPlaybackParametersChanged(parameters: PlaybackParameters) { if(active()) syncMediaSession() }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,newPosition: Player.PositionInfo,reason: Int) { if(active()) syncMediaSession() }
                override fun onPlayerError(error: PlaybackException) { if(active()) playerFailure(error) }
            }
            playerListener=listener; p.addListener(listener)
            val source=ProgressiveMediaSource.Factory { VideoDataSource(remote) }.createMediaSource(MediaItem.Builder().setMediaId(requestedId).setUri("hongguotv://episode/$requestedId").setMimeType(MimeTypes.VIDEO_MP4).build())
            p.setMediaSource(source); p.seekTo(position.coerceAtLeast(0)); p.prepare(); p.playWhenReady=foreground && requestedAutoplay && !pausedForLifecycle && episodePanel==null
        }, { problem -> playerFailure(problem) })
    }
    private fun closeTransport(http: okhttp3.OkHttpClient?) {
        if(http==null) return
        // Closing an idle TLS connection can write close_notify; keep it off the UI thread.
        networkCleanup.execute { http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    }
    private fun cancelPrefetch() {
        prefetchScope?.cancel(); prefetchScope=null
        preparedNext.clear(); prefetchJob?.cancel(true); prefetchJob=null
        closeTransport(prefetchHttp); prefetchHttp=null; prefetchedFor=""
    }
    private fun maybePrefetch() {
        val p=player ?: return; val data=detail ?: return
        if(!foreground || !p.isPlaying || !library.autoNext || playError || sleepStopped || episodeIndex>=data.episodes.lastIndex) return
        if(!PrefetchPolicy.nearEnd(p.currentPosition,p.duration,p.playbackParameters.speed)) return
        val id=data.episodes[episodeIndex+1]; val quality=library.maxQuality; val key="$id:$quality"; val type=data.contentType
        if(preparedNext.isFresh(key) || prefetchJob?.isDone==false) return
        val now=android.os.SystemClock.elapsedRealtime()
        if(readySince==0L || now-readySince<3000 || p.bufferedPosition-p.currentPosition<5000) return
        if(prefetchAttempts>=3 || (prefetchAttempts>0 && now-prefetchAttemptAt<15_000) || connectivity.isActiveNetworkMetered || !networkAvailable()) return
        cancelPrefetch(); prefetchedFor=key; prefetchAttemptAt=now; prefetchAttempts++
        val ticket=preparedNext.begin(key)
        val http=okhttp3.OkHttpClient.Builder().connectTimeout(8,java.util.concurrent.TimeUnit.SECONDS).readTimeout(15,java.util.concurrent.TimeUnit.SECONDS).callTimeout(20,java.util.concurrent.TimeUnit.SECONDS).build()
        val source=ContentRepository(http); prefetchHttp=http
        val scope=RequestScope(); prefetchScope=scope
        prefetchJob=prefetchWorker.submit {
            var remote: RemoteVideo?=null
            try {
                val prepared=scope.run {
                    RemoteVideo(http,source.stream(id,quality,type)).also { remote=it; it.warm() }
                }
                if(preparedNext.complete(ticket,key,prepared)) android.util.Log.i("HongguoTV","Next episode prepared near end")
            } catch(_: Exception) { remote?.close() }
        }
    }
    private fun networkAvailable(): Boolean = connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true
    private fun currentPosition(): Long = pendingSeek ?: player?.currentPosition?.takeIf { playbackReady } ?: startPosition
    private fun cancelRecovery() { main.removeCallbacks(retryRunnable); retryPending=false; autoRecovery=false }
    private fun scheduleRecovery() {
        if(!foreground || !playError || !autoRecovery || retryPending) return
        if(!recovery.remaining) { autoRecovery=false; playbackText.text="已自动重试 3 次，仍无法播放。可手动重试或切换清晰度。"; return }
        if(!networkAvailable()) { playbackText.text="网络已断开，联网后自动续播 · 第 ${episodeIndex+1} 集 ${formatTime(retryPosition)}"; return }
        val delay=recovery.delayMillis ?: return
        playbackText.text="${if(retryAutoplay) "准备恢复播放" else "准备恢复暂停位置"} · ${delay/1000f} 秒后重试 ${recovery.attempts+1}/3 · ${formatTime(retryPosition)}"
        retryPending=true; main.postDelayed(retryRunnable,delay)
    }
    private fun playerFailure(problem: Throwable) {
        retryPosition=currentPosition().coerceAtLeast(0); retryAutoplay=requestedAutoplay && !sleepStopped
        saveProgress(); cancelRecovery(); cancelPrefetch()
        playError=true; player?.pause(); progressBar.visibility=View.GONE; hud.visibility=View.VISIBLE; controls.removeAllViews(); controls.visibility=View.VISIBLE; panel=true
        val decoding=problem is PlaybackException && problem.errorCode in 3000..4999
        val retryable=if(problem is PlaybackException) problem.errorCode in 2000..2999 || problem.errorCode==PlaybackException.ERROR_CODE_TIMEOUT else problem is java.io.IOException
        playbackText.text=if(decoding) "电视无法解码当前视频，可尝试更低清晰度。" else "播放失败，可重试或切换清晰度。"
        val actions=row(); controls.addView(actions)
        addButton(actions,"立即重试") { playEpisode(episodeIndex,retryPosition,retryAutoplay) }.requestFocus()
        addButton(actions,"尝试 720P") { library.maxQuality=720; playEpisode(episodeIndex,retryPosition,retryAutoplay) }
        addButton(actions,"返回选集") { returnToDetail() }
        if(retryable && !sleepStopped) {
            addButton(controls,"停止自动重试") { cancelRecovery(); playbackText.text="自动重试已停止，观看位置已保留。" }
            autoRecovery=true; scheduleRecovery()
        }
        android.util.Log.w("HongguoTV","Playback failure: ${problem.javaClass.simpleName}"+(if(problem is PlaybackException) " code=${problem.errorCodeName}" else ""))
    }
    private fun hidePlaybackOverlay() {
        main.removeCallbacks(hideHud)
        panel=false; controls.visibility=View.GONE; controls.clearFocus(); hud.visibility=View.GONE
    }
    private fun showHud() {
        if(screen!="player") return
        hud.visibility=View.VISIBLE; updatePlaybackText(); main.removeCallbacks(hideHud)
        if(!panel && player?.isPlaying==true) main.postDelayed(hideHud,4500)
    }
    private fun updatePlaybackText() {
        transportPlay?.text=if(player?.playWhenReady==true) "暂停" else "播放"
        sleepButton?.text="定时 ${sleepTimer.label()}"
        if(screen!="player" || playError) return
        if(sleepStopped) { playbackText.text="定时停止已生效 · 观看位置已保存 · 按播放可继续"; return }
        if(!playbackReady) { playbackText.text=(if(!requestedAutoplay) "准备后保持暂停 · " else "")+if(video==null) "正在获取播放地址…" else "正在缓冲…"; return }
        val p=player ?: return
        val duration=p.duration.coerceAtLeast(0); val position=pendingSeek ?: p.currentPosition
        val state=when { p.playbackState==Player.STATE_BUFFERING -> "缓冲中"; p.playbackState==Player.STATE_ENDED -> "本集已结束"; !p.playWhenReady -> "已暂停"; else -> "正在播放" }
        playbackText.text="$state  ·  ${formatTime(position)} / ${formatTime(duration)}  ·  $quality  ·  ${PlaybackSpeed.label(p.playbackParameters.speed)}"+(if(sleepTimer.active) "  ·  定时 ${sleepTimer.label()}" else "")
        progressBar.progress=if(duration>0) (position*1000/duration).toInt().coerceIn(0,1000) else 0
    }
    private fun requestPlayback(play: Boolean) {
        requestedAutoplay=play; pausedForLifecycle=!play
        if(play) sleepStopped=false
        if(!play) { player?.pause(); retryAutoplay=false; saveProgress() }
        else if(playError) { playEpisode(episodeIndex,retryPosition,true); return }
        else if(episodePanel==null) { if(player?.playbackState==Player.STATE_ENDED) player?.seekTo(0); player?.play() }
        if(!play) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showHud(); updatePlaybackText(); syncMediaSession(); if(panel && episodePanel==null) showPanel()
    }
    private fun togglePlayback()=requestPlayback(!(if(playbackReady) player?.playWhenReady ?: requestedAutoplay else requestedAutoplay))
    private fun seekTo(position: Long) {
        val p=player ?: return; if(!playbackReady || p.duration<=0) return
        main.removeCallbacks(seekRunnable); pendingSeek=null
        p.seekTo(position.coerceIn(0,(p.duration-500).coerceAtLeast(0))); updatePlaybackText(); syncMediaSession(); showHud()
    }
    private fun skipEpisode(direction: Int) {
        if(!foreground || screen!="player") return
        val count=detail?.episodes?.size ?: return
        val target=episodeIndex+direction
        if(target in 0 until count) playEpisode(target)
    }
    private fun syncMediaSession() {
        if(!foreground || screen!="player") { mediaSession.deactivate(); return }
        val data=detail ?: return; val p=player
        val state=when {
            playError -> android.media.session.PlaybackState.STATE_ERROR
            sleepStopped || !requestedAutoplay || (p!=null && !p.playWhenReady) -> android.media.session.PlaybackState.STATE_PAUSED
            p==null || !playbackReady || p.playbackState==Player.STATE_BUFFERING -> android.media.session.PlaybackState.STATE_BUFFERING
            p.playbackState==Player.STATE_ENDED -> android.media.session.PlaybackState.STATE_STOPPED
            p.isPlaying -> android.media.session.PlaybackState.STATE_PLAYING
            else -> android.media.session.PlaybackState.STATE_PAUSED
        }
        mediaSession.update(data.series.title,episodeIndex,p?.duration?.coerceAtLeast(0) ?: 0,currentPosition(),library.playbackSpeed,state,episodeIndex>0,episodeIndex<data.episodes.lastIndex)
    }
    private fun stopForSleep() {
        sleepStopped=true; requestedAutoplay=false; retryAutoplay=false; pausedForLifecycle=true
        cancelRecovery(); player?.pause(); saveProgress()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playbackText.text="定时停止已生效 · 观看位置已保存 · 按播放可继续"
        showHud(); if(!playError && episodePanel==null) showPanel(); syncMediaSession()
        Toast.makeText(this,"定时停止已生效",Toast.LENGTH_LONG).show()
    }
    private fun showSleepPicker(anchor: TextView) {
        val labels=listOf("关闭定时停止","15 分钟后","30 分钟后","60 分钟后","90 分钟后","本集播完","再播完 3 集","再播完 5 集")
        tvTools.choose("定时停止 · ${sleepTimer.label()}",labels,anchor,{ choice ->
            when(choice) { 0 -> sleepTimer.cancel(); in 1..4 -> sleepTimer.afterMinutes(listOf(15,30,60,90)[choice-1]); else -> sleepTimer.afterEpisodes(listOf(1,3,5)[choice-5]) }
            if(anchor.tag!="playback-settings") anchor.text="定时 ${sleepTimer.label()}"; updatePlaybackText(); showHud()
        })
    }
    private fun frameResizeMode()=when(library.frameMode) {
        VideoFrameMode.FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        VideoFrameMode.ZOOM -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        VideoFrameMode.FILL -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
    }
    private fun showFramePicker(anchor: TextView,inPlayer: Boolean) {
        tvTools.choose("全局画面模式",listOf("完整画面 · 保留比例与全部内容","等比铺满 · 会裁掉部分画面与字幕","拉伸铺满 · 画面比例会改变"),anchor,{ index ->
            library.frameMode=VideoFrameMode.entries[index]; playerView?.resizeMode=frameResizeMode()
            if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "画面 " else "画面模式  ·  ")+library.frameMode.label
        })
    }
    private fun seek(direction: Int,repeat: Int) {
        val p=player ?: return; if(!playbackReady) return
        val duration=p.duration; if(duration<=0) return
        val step=if(repeat>4) 30000 else 10000
        pendingSeek=((pendingSeek ?: p.currentPosition)+direction*step).coerceIn(0,(duration-500).coerceAtLeast(0))
        main.removeCallbacks(seekRunnable); main.postDelayed(seekRunnable,250); updatePlaybackText(); showHud()
    }
    private fun showPanel() {
        if(playError) return
        panel=true; showHud(); controls.visibility=View.VISIBLE; controls.removeAllViews()
        val transport=row(); controls.addView(transport)
        val play=addButton(transport,if(player?.playWhenReady==true) "暂停" else "播放") { togglePlayback() }.also { transportPlay=it }
        addButton(transport,"上一集") { if(episodeIndex>0) playEpisode(episodeIndex-1) }.apply { isEnabled=episodeIndex>0; isFocusable=episodeIndex>0; alpha=if(episodeIndex>0) 1f else .4f }
        addButton(transport,"下一集") { if(episodeIndex<(detail?.episodes?.lastIndex ?: 0)) playEpisode(episodeIndex+1) }.apply { val enabled=episodeIndex<(detail?.episodes?.lastIndex ?: 0); isEnabled=enabled; isFocusable=enabled; alpha=if(enabled) 1f else .4f }
        val options=row().apply { setPadding(0,dp(8),0,0) }; controls.addView(options)
        lateinit var episodes: TextView
        episodes=addButton(options,"选集") { showEpisodePanel(episodes) }
        lateinit var more: TextView
        more=addButton(options,"播放设置") {
            tvTools.choose("播放设置",listOf("倍速 ${PlaybackSpeed.label(library.playbackSpeed)}","清晰度 ${library.maxQuality}P","画面 ${library.frameMode.label}","弹幕 ${if(library.danmaku) "开" else "关"}","弹幕设置","定时 ${sleepTimer.label()}","从头播放"),more,{ choice ->
                main.post {
                    if(screen=="player" && more.isAttachedToWindow) when(choice) {
                        0 -> showSpeedPicker(more)
                        1 -> showQualityPicker(more,true)
                        2 -> showFramePicker(more,true)
                        3 -> toggleDanmakuInPlayer()
                        4 -> showDanmakuStylePicker(more,true)
                        5 -> showSleepPicker(more)
                        6 -> { seekTo(0); hidePanel() }
                    }
                }
            })
        }.apply { tag="playback-settings" }
        play.requestFocus()
    }
    /** 弹幕开关（播放中即时生效：开=挂层+假数据，关=卸层）。 */
    private fun toggleDanmakuInPlayer() {
        library.danmaku=!library.danmaku
        if(library.danmaku) {
            if(danmakuOverlay==null) {
                danmakuOverlay=DanmakuOverlay(this).apply { restyle(currentDanmakuParams()); loadDemoData() }
                root.addView(danmakuOverlay,root.indexOfChild(hud.parent as View).coerceAtLeast(1))
            }
            Toast.makeText(this,"弹幕已开启（预览数据）",Toast.LENGTH_SHORT).show()
        } else {
            danmakuOverlay?.let { root.removeView(it) }
            danmakuOverlay=null
            Toast.makeText(this,"弹幕已关闭",Toast.LENGTH_SHORT).show()
        }
    }
    /** 弹幕样式摘要（设置页与菜单共用）。 */
    private fun danmakuStyleSummary() =
        "${DanmakuPrefs.sizeLabel(library.danmakuSize)} · ${DanmakuPrefs.speedLabel(library.danmakuSpeed)} · ${DanmakuPrefs.rowsLabel(library.danmakuRows)} · ${DanmakuPrefs.alphaLabel(library.danmakuAlpha)}"
    private fun currentDanmakuParams() =
        DanmakuOverlay.Params(DanmakuPrefs.size(library.danmakuSize),DanmakuPrefs.travelSec(library.danmakuSpeed),DanmakuPrefs.rows(library.danmakuRows),library.danmakuAlpha)
    /** 弹幕样式一页式面板（设置页与播放菜单共用）：四项同屏，点档位即时生效，面板不关闭。 */
    private fun showDanmakuStylePicker(anchor: TextView,inPlayer: Boolean) {
        tvTools.close()
        val content=column().apply { setPadding(dp(6),dp(2),dp(6),dp(4)) }
        content.addView(text("选择后立即生效，按完成退出。",12f,muted).apply { setPadding(dp(2),0,0,dp(4)) })
        fun addRow(title: String,count: Int,current: Int,label: (Int)->String,pick: (Int)->Unit): List<TextView> {
            content.addView(text(title,14f,muted).apply { setPadding(dp(2),dp(10),0,dp(6)) })
            val line=row(); content.addView(line)
            var selected=current
            val views=mutableListOf<TextView>()
            fun repaint()=views.forEachIndexed { i,v ->
                val focused=v.isFocused
                v.background=rounded(if(focused) TvStyle.raised else if(i==selected) Color.rgb(61,39,34) else surface,if(focused) white else Color.TRANSPARENT)
            }
            // 行首占位（1 份）：与行尾对称，整组居中。
            line.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
            for(index in 0 until count) {
                if(index>0) line.addView(View(this),LinearLayout.LayoutParams(0,1,2f))  // 按钮间隔 2 占位
                val view=text(label(index),14f).apply { gravity=Gravity.CENTER; setPadding(dp(12),dp(9),dp(12),dp(9)); minHeight=dp(40) }
                view.id=View.generateViewId(); view.isFocusable=true
                view.setOnFocusChangeListener { _,_ -> repaint() }
                view.setOnClickListener { if(selected!=index) { selected=index; pick(index); repaint() } }
                // 权重 6：按钮宽保持 1/4 行宽（上轮 25% 收窄不回退），间隔 2 占位、行首尾各 1 占位。
                line.addView(view,LinearLayout.LayoutParams(0,-2,6f))
                views+=view
            }
            // 行尾占位（1 份）。
            line.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
            repaint()
            return views
        }
        val sizeRow=addRow("字号",DanmakuPrefs.textSizes.size,library.danmakuSize,{ DanmakuPrefs.sizeLabel(it) }) { library.danmakuSize=it; afterDanmakuStyleChange(anchor,inPlayer) }
        addRow("速度",DanmakuPrefs.travelSecs.size,library.danmakuSpeed,{ DanmakuPrefs.speedLabel(it) }) { library.danmakuSpeed=it; afterDanmakuStyleChange(anchor,inPlayer) }
        addRow("行数",DanmakuPrefs.rowCounts.size,library.danmakuRows,{ DanmakuPrefs.rowsLabel(it) }) { library.danmakuRows=it; afterDanmakuStyleChange(anchor,inPlayer) }
        addRow("透明度",DanmakuPrefs.alphas.size,library.danmakuAlpha,{ DanmakuPrefs.alphaLabel(it) }) { library.danmakuAlpha=it; afterDanmakuStyleChange(anchor,inPlayer) }
        val dialog=AlertDialog.Builder(this).setTitle("弹幕样式")
            .setView(ScrollView(this).apply { isVerticalScrollBarEnabled=false; addView(content) })
            .setPositiveButton("完成",null).create()
        dialog.setOnDismissListener { if(anchor.isAttachedToWindow) { anchor.requestFocus(); if(inPlayer) showHud() } }
        dialog.show()
        sizeRow[library.danmakuSize.coerceIn(0,sizeRow.lastIndex)].requestFocus()
    }
    /** 档位变化后：热更新渲染层 + 刷新设置页摘要。 */
    private fun afterDanmakuStyleChange(anchor: TextView,inPlayer: Boolean) {
        danmakuOverlay?.restyle(currentDanmakuParams())
        if(anchor.tag!="playback-settings" && anchor.isAttachedToWindow) anchor.text="弹幕样式  ·  ${danmakuStyleSummary()}"
        if(inPlayer) showHud()
    }
    private fun showSpeedPicker(anchor: TextView,inPlayer: Boolean=true) {        if(speedDialog!=null) return
        val speeds=PlaybackSpeed.options
        val selected=speeds.indexOf(library.playbackSpeed)
        val dialog=AlertDialog.Builder(this).setTitle("全局默认倍速")
            .setSingleChoiceItems(speeds.map(PlaybackSpeed::label).toTypedArray(),selected) { popup,index ->
                library.playbackSpeed=speeds[index]
                if(inPlayer) player?.setPlaybackSpeed(library.playbackSpeed)
                if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "倍速 " else "默认倍速  ·  ")+PlaybackSpeed.label(library.playbackSpeed)
                if(inPlayer) updatePlaybackText()
                popup.dismiss()
            }.setNegativeButton("取消",null).create()
        speedDialog=dialog
        dialog.setOnDismissListener {
            speedDialog=null
            if(anchor.isAttachedToWindow) { anchor.requestFocus(); if(inPlayer) showHud() }
        }
        dialog.show(); dialog.listView.setSelection(selected); dialog.listView.requestFocus()
    }
    private fun showQualityPicker(anchor: TextView,inPlayer: Boolean) {
        val options=PlaybackQuality.options
        val dialog=AlertDialog.Builder(this).setTitle("全局默认清晰度")
            .setSingleChoiceItems(options.map { "${it}P" }.toTypedArray(),options.indexOf(library.maxQuality)) { popup,index ->
                val target=options[index]; val changed=library.maxQuality!=target
                library.maxQuality=target; popup.dismiss()
                if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "清晰度 " else "默认清晰度  ·  ")+"${target}P"
                if(inPlayer && changed) playEpisode(episodeIndex,currentPosition(),player?.playWhenReady ?: requestedAutoplay)
            }.setNegativeButton("取消",null).create()
        dialog.setOnDismissListener { if(anchor.isAttachedToWindow) anchor.requestFocus() }
        dialog.show()
    }
    private fun showEpisodePanel(anchor: View) {
        val data=detail ?: return
        if(episodePanel!=null) return
        val ticket=generation
        val overlayWasVisible=hud.visibility==View.VISIBLE
        requestedAutoplay=player?.playWhenReady ?: requestedAutoplay
        player?.pause(); saveProgress()
        var resumeOnClose=true
        val next=EpisodePanel(this,data.episodes.size,episodeIndex,
            { current,view,located -> tvTools.episodePicker(data.episodes.size,current,view,located) },
            { selected ->
                if(selected==episodeIndex) episodePanel?.dismiss()
                else { resumeOnClose=false; episodePanel?.dismiss(); playEpisode(selected) }
            },
            {
                episodePanel=null
                if(resumeOnClose && foreground && generation==ticket && screen=="player") {
                    if(requestedAutoplay && !sleepStopped && !pausedForLifecycle) player?.play()
                    if(anchor.isAttachedToWindow) anchor.requestFocus()
                    if(overlayWasVisible) showHud() else hidePlaybackOverlay()
                }
            })
        episodePanel=next; next.show()
    }
    private fun hidePanel() { if(playError) return; panel=false; controls.visibility=View.GONE; controls.clearFocus(); showHud() }
    private fun formatTime(millis: Long): String { val seconds=(millis.coerceAtLeast(0)/1000); return "%02d:%02d".format(seconds/60,seconds%60) }
    private fun saveProgress(completed: Boolean=false) {
        val p=player ?: return; val data=detail ?: return; if(!playbackReady || episodeIndex !in data.episodes.indices) return
        library.save(WatchProgress(data.series,data.episodes[episodeIndex],episodeIndex,p.currentPosition.coerceAtLeast(0),p.duration.coerceAtLeast(0),completed || p.playbackState==Player.STATE_ENDED,System.currentTimeMillis()))
    }
    private fun releasePlayer() {
        cancelRecovery(); cancelPrefetch(); mediaSession.deactivate(); tvTools.close()
        val wasForeground=foreground; foreground=false
        val oldPanel=episodePanel; episodePanel=null; oldPanel?.dismiss(); foreground=wasForeground
        speedDialog?.dismiss(); speedDialog=null
        main.removeCallbacks(hideHud); main.removeCallbacks(seekRunnable); pendingSeek=null
        playerListener?.let { player?.removeListener(it) }; playerListener=null
        video?.close(); video=null; playerView?.player=null; playerView=null; danmakuOverlay?.shutdown(); danmakuOverlay=null; player?.release(); player=null; playbackReady=false; transportPlay=null; sleepButton=null
        closeTransport(playbackHttp); playbackHttp=null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun returnToDetail() {
        sleepTimer.cancel(); sleepStopped=false; saveProgress(); library.flush(); invalidatePage(); releasePlayer()
        // Back from playback always lands on the episode detail page first
        // (even when playback was entered directly via resume cards);
        // backing out of detail then returns to the originating page.
        directPlayback=false; showDetail(true)
    }
    /** Back-to-home always lands on the initial home state (page 1, no saved focus/scroll). */
    private fun resetHomeState() {
        tabState.remove(0); tabFocus.remove(0)
        homePosition=null; scrollPositions.clear()
    }
    private fun goBack() {
        if(!library.isLoaded) { finish(); return }
        when(screen) {
            "player" -> if(hud.visibility==View.VISIBLE && !playError) hidePlaybackOverlay() else returnToDetail()
            "detail" -> if(tab==4) {
                // Leaving a drama opened from search results: back to the fresh three-column
                // search layout, focus on the top nav "搜索" tab, query text dropped.
                tvResultQuery=""; catalog=emptyList(); hasMore=false; tvQuery=""; tvSuggests=emptyList()
                showCatalog(focusNav=true)
            } else showCatalog()
            else -> if(collection!=null) { leaveCollection(); showCatalog() }
            else if(tab==0 && page>1) {
                // Backing out of the home paged grid returns to page 1 (home shelves).
                page=1; catalogFocus=""; homePosition=null; showCatalog(focusNav=true)
            }
            else if(nav.any { it.hasFocus() }) {
                // Focus on the top nav of a non-home tab: back goes home (initial state).
                if(tab!=0) { resetHomeState(); switchTab(0); nav[0].requestFocus() } else finish()
            }
            else if(tab==4) {
                // Search page: one back press snaps all three scroll columns to the top
                // (suggestions / history / results, whichever is visible) and moves focus
                // up to the nav bar tab. The query and results stay as they were.
                tvSuggestScroll?.scrollTo(0,0); tvHistoryScroll?.scrollTo(0,0); tvResultScroll?.scrollTo(0,0)
                nav[tab].requestFocus()
            }
            else if(tab==1 || tab==2 || tab==3) {
                // Short/comic/AI theme pages: two-step chain — cards → theme bar → nav bar
                // (mirrors the ranking board chain). Focus on a card (or the list
                // scrolled): back snaps the list to the top and puts focus on the
                // current theme button; focus already on the theme bar: back moves
                // focus up to the nav bar tab.
                if(themeButtons[themeSlug]?.hasFocus()!=true) {
                    catalogScroll?.scrollTo(0,0)
                    scrollPositions.remove(catalogPageKey)
                    catalogFocus=catalog.firstOrNull()?.id.orEmpty()
                    themeButtons[themeSlug]?.requestFocus() ?: nav[tab].requestFocus()
                } else nav[tab].requestFocus()
            }
            else if(tab==6 || tab==7 || tab==8) {
                // Card/settings pages: one back press does both — snap the page back to
                // the top and move focus up to the nav bar tab. scrollTo (not smooth)
                // so long lists jump instantly; the saved scroll position is cleared so
                // a later grid rebuild (tab switch / loadMore) does not restore the
                // deep scroll offset again.
                catalogScroll?.scrollTo(0,0); settingsScroll?.scrollTo(0,0)
                scrollPositions.remove(catalogPageKey)
                catalogFocus=catalog.firstOrNull()?.id.orEmpty()
                nav[tab].requestFocus()
            }
            else if(tab==5) {
                // Ranking: three-step chain — cards → board bar → nav bar → home.
                // Focus on a card (or the list scrolled): back snaps the list to the
                // top and puts focus on the current board button; focus already on a
                // board button: back moves focus up to the nav bar tab.
                if(rankButtons[library.rankBoard]?.hasFocus()!=true) {
                    catalogScroll?.scrollTo(0,0)
                    scrollPositions.remove(catalogPageKey)
                    catalogFocus=catalog.firstOrNull()?.id.orEmpty()
                    rankButtons[library.rankBoard]?.requestFocus() ?: nav[tab].requestFocus()
                } else nav[tab].requestFocus()
            }
            else if(homeResetDone) {
                // The reset already ran (focus was moved back to the cards afterwards):
                // re-run the reset instead of exiting — exit only happens from the nav tab.
                if(tab!=0) { resetHomeState(); switchTab(0); nav[0].requestFocus() }
                else { homeResetDone=true; catalogFocus=""; homePosition=null; scrollPositions.clear(); showCatalog(focusNav=true) }
            }
            else if(tab!=0) { resetHomeState(); switchTab(0); nav[0].requestFocus() }
            else {
                // Focus on a home card: reset to the initial home state (shelves, scroll
                // reset) and move focus up to the nav bar — the same two-step chain as
                // every other page (cards → bar → nav → exit). Keeping focus on the
                // first card after the reset made the next back press (homeResetDone)
                // exit the app, which felt like a random exit.
                homeResetDone=true
                catalogFocus=""; homePosition=null; scrollPositions.clear(); showCatalog(focusNav=true)
            }
        }
    }
    @Deprecated("TV remote back is handled through the activity")
    override fun onBackPressed()=goBack()
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if(screen=="player") {
            val key=event.keyCode
            if(key==KeyEvent.KEYCODE_BACK) { if(event.action==KeyEvent.ACTION_UP) goBack(); return true }
            if(key in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,KeyEvent.KEYCODE_MEDIA_PLAY,KeyEvent.KEYCODE_MEDIA_PAUSE,KeyEvent.KEYCODE_MEDIA_NEXT,KeyEvent.KEYCODE_MEDIA_PREVIOUS,KeyEvent.KEYCODE_MEDIA_STOP,KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,KeyEvent.KEYCODE_MEDIA_REWIND)) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) when(key) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> requestPlayback(true)
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> requestPlayback(false)
                    KeyEvent.KEYCODE_MEDIA_NEXT -> skipEpisode(1)
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> skipEpisode(-1)
                    KeyEvent.KEYCODE_MEDIA_STOP -> returnToDetail()
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(1,0)
                    KeyEvent.KEYCODE_MEDIA_REWIND -> seek(-1,0)
                    else -> togglePlayback()
                }
                return true
            }
            if(!panel) {
                if(key in listOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_MENU)) {
                    if(event.action==KeyEvent.ACTION_DOWN) when(key) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> seek(-1,event.repeatCount)
                        KeyEvent.KEYCODE_DPAD_RIGHT -> seek(1,event.repeatCount)
                        KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER -> if(event.repeatCount==0) togglePlayback()
                        KeyEvent.KEYCODE_DPAD_DOWN -> if(event.repeatCount==0) showEpisodePanel(hud)
                        KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_MENU -> if(event.repeatCount==0) showPanel()
                        else -> showHud()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if(!library.isLoaded) return
        when(screen) {
            "player" -> { val position=currentPosition(); val autoplay=player?.playWhenReady==true && !pausedForLifecycle; playEpisode(episodeIndex,position,autoplay) }
            "detail" -> if(detail!=null) showDetail(false)
            else -> showCatalog()
        }
    }
    override fun onResume() {
        super.onResume(); foreground=true; main.removeCallbacks(tick); main.post(tick)
        updater.resume()
        if(library.isLoaded) {
            if(initialCatalogPending) showCatalog(load=true,focusNav=true)
            else if(restoreStoppedPage) {
                restoreStoppedPage=false
                when(screen) {
                    "player" -> playEpisode(episodeIndex,startPosition,autoplay=false)
                    "detail" -> if(detail!=null) showDetail(false) else pendingDetail?.let { openDetail(it,resume=false,autoplay=false) } ?: showCatalog()
                    else -> showCatalog(load=!localCatalog() && catalog.isEmpty() && (tab<=3 || tab==5))
                }
            } else {
                val waiting=deferredWork; deferredWork=null; waiting?.apply?.invoke()
            }
            if(screen=="catalog") favoriteMonitor.check()
            if(screen=="player") syncMediaSession()
        } else libraryLoadError?.let(::showLibraryError)
    }
    override fun onPause() {
        updater.pause()
        foreground=false; main.removeCallbacks(tick); cancelRecovery(); cancelPrefetch(); mediaSession.deactivate()
        sleepTimer.cancel(); favoriteMonitor.stop(); detailAutoplay=false; requestedAutoplay=false; pausedForLifecycle=true
        player?.pause(); saveProgress(); library.flush(); super.onPause()
    }
    override fun onStop() {
        restoreStoppedPage=true; generation++; cancelWork(); tvTools.close()
        if(screen=="player") { startPosition=currentPosition(); saveProgress(); releasePlayer() }
        artwork.cancelPage(clearMemory=true); library.flush(); super.onStop()
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if(::artwork.isInitialized) artwork.trimMemory(level)
        if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) cancelPrefetch()
    }
    override fun onDestroy() {
        generation++; cancelWork()
        if(networkRegistered) connectivity.unregisterNetworkCallback(networkCallback)
        favoriteMonitor.destroy(); tvTools.destroy(); updater.destroy(); saveProgress(); releasePlayer(); library.close()
        artwork.close(); releaseCatalogViews(); mediaSession.release(); main.removeCallbacksAndMessages(null)
        io.shutdownNow(); prefetchWorker.shutdownNow(); networkCleanup.shutdown()
        Thread({ repository.http.dispatcher.cancelAll(); repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown() },"hongguotv-network-cleanup").start()
        super.onDestroy()
    }
}

/** Simple left-to-right wrap layout: children keep their own width, wrapping to a new line when full. */
internal class FlowLayout(context: Context): ViewGroup(context) {
    private val horizontalGap=dp(6); private val verticalGap=dp(6)
    private fun dp(value: Int)=(value*resources.displayMetrics.density).toInt()
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        val width=MeasureSpec.getSize(widthMeasureSpec)
        var x=paddingLeft; var y=paddingTop; var lineHeight=0
        for(i in 0 until childCount) {
            val child=getChildAt(i)
            if(child.visibility==View.GONE) continue
            measureChild(child,widthMeasureSpec,heightMeasureSpec)
            val cw=child.measuredWidth; val ch=child.measuredHeight
            if(x>paddingLeft && x+cw+paddingRight>width) { x=paddingLeft; y+=lineHeight+verticalGap; lineHeight=0 }
            child.tag=android.graphics.Point(x,y)
            x+=cw+horizontalGap; lineHeight=maxOf(lineHeight,ch)
        }
        val height=if(MeasureSpec.getMode(heightMeasureSpec)==MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec)
            else y+lineHeight+paddingBottom
        setMeasuredDimension(width,resolveSize(height,heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean,left: Int,top: Int,right: Int,bottom: Int) {
        for(i in 0 until childCount) {
            val child=getChildAt(i)
            if(child.visibility==View.GONE) continue
            val point=child.tag as? android.graphics.Point ?: continue
            child.layout(point.x,point.y,point.x+child.measuredWidth,point.y+child.measuredHeight)
        }
    }
}
