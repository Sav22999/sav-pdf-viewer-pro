package com.saverio.pdfviewer

import RealPathUtil
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.link.DefaultLinkHandler
import com.github.barteksc.pdfviewer.listener.OnErrorListener
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.saverio.pdfviewer.db.BookmarksModel
import com.saverio.pdfviewer.db.DatabaseHandler
import com.saverio.pdfviewer.db.FilesModel
import com.saverio.pdfviewer.ui.BookmarksItemAdapter
import com.saverio.pdfviewer.ui.SavPdfViewerLinkHandler
import java.net.URLDecoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.HashMap
import android.content.pm.ActivityInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.roundToInt


class PDFViewer : AppCompatActivity() {
    private data class MenuActionLayoutSnapshot(
        val width: Int,
        val height: Int,
        val weight: Float
    )

    private enum class ScrollMode {
        VERTICAL_TOP_TO_BOTTOM,
        VERTICAL_BOTTOM_TO_TOP,
        HORIZONTAL_LEFT_TO_RIGHT,
        HORIZONTAL_RIGHT_TO_LEFT
    }

    private enum class OverlayPanel {
        MENU,
        SEARCH,
        GOTO,
        SELECTION
    }

    lateinit var pdfViewer: PDFView
    val PDF_SELECTION_CODE = 100

    var fileOpened: String? = ""
    var fileId: String = ""
    var uriOpened: Uri? = null

    val timesAfterOpenReviewMessage = 500
    val timesAfterShowFollowApp = 5
    val timesAfterLiberaPay = 5000

    var isFullscreenEnabled = false
    private var openedExternally = true
    var showingTopBar = true
    var menuOpened = false
    private var compactTopBarVisible = false

    var isSupportedShareFeature = false
    var isSupportedGoTop = true
    var isSupportedScrollbarButton = true

    var passwordRequired = false
    var passwordToUse = ""

    var totalPages = 0
    var savedCurrentPageOld = 0
    var savedCurrentPage = 0

    var horizontal = false
    var reverseScroll = false
    private var scrollMode = ScrollMode.VERTICAL_TOP_TO_BOTTOM

    private val scrollModePreferenceName = "scroll_mode"
    private val scrollModePreferenceKey = "scroll_mode"
    private val legacyPdfOptionsPreferenceName = "pdf_options"
    private val legacyMigrationPreferenceName = "legacy_pdf_options_migration"
    private var zoomToRestore = 1.0F

    var single_page = false
    var night_mode = false
    var rotation_locked = false
    var contrastOverlayEnabled = false

    var zoom_value = 0.2F

    var hideTopBarCounter = 0
    var dialog: BottomSheetDialog? = null
    private val topBarUiHandler by lazy { Handler(Looper.getMainLooper()) }
    private val scheduleUiHandler by lazy { Handler(Looper.getMainLooper()) }
    private val topBarAutoHideDelayMs = 5_000L
    private val scheduledAppearanceCheckDelayMs = 60_000L
    private var topBarAutoHideDeadlineAtMs = 0L
    private val topBarAutoHideRunnable: Runnable = Runnable {
        handleTopBarAutoHideTick()
    }
    private fun handleTopBarAutoHideTick() {
        val remainingDelayMs = topBarAutoHideDeadlineAtMs - SystemClock.uptimeMillis()
        if (remainingDelayMs > 0L) {
            if (showingTopBar && !menuOpened && !searchPanelVisible) {
                topBarUiHandler.postDelayed(topBarAutoHideRunnable, remainingDelayMs)
            }
            return
        }
        if (showingTopBar && !menuOpened && !searchPanelVisible) {
            topBarAutoHideDeadlineAtMs = 0L
            hideTopBar(fullHiding = true)
        }
    }
    private var lastGoToVisibleState: Boolean? = null
    private var lastGoToBottomIconState: Boolean? = null
    private var skipNextInitialPageScrollHide = false
    private val goToUiHandler by lazy { Handler(Looper.getMainLooper()) }
    private val goToVisibilityDebounceMs = 60L
    private val goToVisibilityRunnable = Runnable {
        updateGoToEdgeButtonVisibilityInternal()
    }
    private val scheduledAppearanceRunnable = Runnable {
        reevaluateScheduledAppearance(refreshViewer = true)
        restartScheduledAppearanceChecks()
    }

    // ── OCR Search ─────────────────────────────────────────────
    private val ocrEngine: PdfOcrEngine by lazy { PdfOcrEngine(this) }
    private var searchResults: List<PdfOcrEngine.SearchResult> = emptyList()
    private var searchResultIndex: Int = 0
    private var searchPanelVisible: Boolean = false
    private var currentSearchQuery: String = ""
    private var searchCaseSensitive: Boolean = false
    private var searchWholeWord: Boolean = false
    private var minZoomForCurrentLayout: Float = 0.1F
    private val menuActionDefaultLayoutParams = HashMap<Int, MenuActionLayoutSnapshot>()
    private val menuSectionDefaultWeightSum = HashMap<Int, Float>()
    private val highlightPaint = Paint().apply {
        style = Paint.Style.FILL
    }
    private val highlightBorderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val activeHighlightPaint = Paint().apply {
        style = Paint.Style.FILL
    }
    private val activeHighlightBorderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val highlightInsetPx = 2f

    private fun withAlpha(color: Int, alpha: Int): Int {
        return android.graphics.Color.argb(
            alpha.coerceIn(0, 255),
            android.graphics.Color.red(color),
            android.graphics.Color.green(color),
            android.graphics.Color.blue(color)
        )
    }

    private fun applySearchHighlightThemeColors() {
        val lightRed = ContextCompat.getColor(this, R.color.light_red)
        val darkRed = ContextCompat.getColor(this, R.color.dark_red)
        val darkDarkRed = ContextCompat.getColor(this, R.color.dark_dark_red)

        highlightPaint.color = withAlpha(lightRed, 120)
        highlightBorderPaint.color = withAlpha(darkRed, 175)
        activeHighlightPaint.color = withAlpha(darkRed, 155)
        activeHighlightBorderPaint.color = withAlpha(darkDarkRed, 210)
    }

    private fun hasExplicitZoomPreference(): Boolean {
        return zoomToRestore > 0f && abs(zoomToRestore - 1f) > 0.01f
    }

    private fun computeZoomForCurrentOrientation(viewerPage: Int): Float {
        val pageSize = pdfViewer.getPageSize(viewerPage)
        if (pageSize == null) return minZoomForCurrentLayout

        val availableWidth =
            (pdfViewer.width - pdfViewer.paddingLeft - pdfViewer.paddingRight).toFloat().coerceAtLeast(1f)
        val availableHeight =
            (pdfViewer.height - pdfViewer.paddingTop - pdfViewer.paddingBottom).toFloat().coerceAtLeast(1f)
        val fitWidthZoom = availableWidth / pageSize.width.coerceAtLeast(1f)
        val fitHeightZoom = availableHeight / pageSize.height.coerceAtLeast(1f)

        return if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            fitHeightZoom
        } else {
            fitWidthZoom
        }.coerceAtLeast(0.1f)
    }

    private fun updateZoomBoundsForCurrentOrientation(viewerPage: Int) {
        val minZoom = computeZoomForCurrentOrientation(viewerPage).coerceAtMost(10.0f)
        minZoomForCurrentLayout = minZoom
        pdfViewer.setMinZoom(minZoom)
        pdfViewer.setMidZoom(maxOf(2.5f, minZoom + 0.5f))
        pdfViewer.setMaxZoom(10.0f)
    }

    private fun fitPageForCurrentOrientation(viewerPage: Int) {
        if (hasExplicitZoomPreference()) return
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            pdfViewer.zoomTo(computeZoomForCurrentOrientation(viewerPage))
        } else {
            // fitToWidth also recenters horizontally in portrait
            pdfViewer.fitToWidth(viewerPage)
        }
    }

    private fun currentSearchOptions(): PdfOcrEngine.SearchOptions {
        return PdfOcrEngine.SearchOptions(
            caseSensitive = searchCaseSensitive,
            wholeWord = searchWholeWord
        )
    }

    // ── Text Selection ────────────────────────────────────────
    private val textSelectionManager: TextSelectionManager by lazy {
        TextSelectionManager(this).also {
            it.ocrEngine = ocrEngine
            it.pdfView = pdfViewer
            it.viewerToLogicalPage = ::mapViewerPageToLogical
            it.logicalToViewerPage = ::mapLogicalPageToViewer
        }
    }
    private enum class SelectionDragState { NONE, RUBBER_BAND, START_HANDLE, END_HANDLE }
    private var selectionDragState = SelectionDragState.NONE
    private var selectionDragPage  = -1
    private var selectionDownViewX = Float.NaN
    private var selectionDownViewY = Float.NaN
    private lateinit var selectionGestureDetector: GestureDetector
    // ──────────────────────────────────────────────────────────

    var residualViewConfigurationConfigurated = false
    var residualViewConfiguration: HashMap<String, HashMap<String, Int>> =
        hashMapOf(
            "landscape" to hashMapOf("width" to 0, "height" to 0),
            "portrait" to hashMapOf("width" to 0, "height" to 0)
        ) // {"landscape": [width, height], "portrait": [width, height]}
    var minPositionScrollbar: Float = 0F
    var maxPositionScrollbar: Float = 0F
    var startY = 0F

    var minPositionScrollbarHorizontal: Float = 0F
    var maxPositionScrollbarHorizontal: Float = 0F
    var startX = 0F
    private val scrollbarSafetyMarginPx by lazy { 0F }
    private val scrollbarMinimumLengthPx by lazy { dpToPx(50F) }

    private var applyingPdfOptions = false
    private val selectedOptionAlpha = 1.0F
    private val unselectedOptionAlpha = 0.45F
    private var pendingSinglePageCenterLogicalPage: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // On Android 15+ (edge-to-edge enforced), apply the status-bar inset
        // only to the toolbar container so it doesn't overlap the system bar,
        // without affecting fullView / residualView measurement.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)

        setContentView(R.layout.activity_pdf_viewer)
        applySearchHighlightThemeColors()

        // Extra safety: pad the toolbar for the status bar inset
        val toolbarContainer = findViewById<View>(R.id.toolbarContainer)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(toolbarContainer) { v, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, systemBars.top, v.paddingRight, v.paddingBottom)
            insets
        }

        pdfViewer = findViewById(R.id.pdfView)
        loadScrollModePreference()
        var uriToUse: String? = ""

        val parameters = intent.extras
        if (parameters != null) {
            uriToUse = parameters.getString(MainActivity.EXTRA_URI, "")
            openedExternally = parameters.getBoolean(
                MainActivity.EXTRA_OPENED_EXTERNALLY,
                intent?.action == Intent.ACTION_VIEW || intent?.data != null
            )
        } else {
            openedExternally = intent?.action == Intent.ACTION_VIEW || intent?.data != null
        }

        try {
            val intent = intent
            if (intent != null && intent.data != null && intent.data.toString()
                    .contains("content://")
            ) {
                uriToUse = intent.data.toString()
                //println(uriToUse)
                uriOpened = intent.data
            }

            //println(intent.data)

            try {
                fileOpened = RealPathUtil.getRealPath(this, intent.data!!)
                //println("File opened\t" + fileOpened)
            } catch (e: Exception) {
                println("Exception Z2\n" + e.message)
                fileOpened = null
            }
            isSupportedShareFeature = true
        } catch (e: Exception) {
            println("Exception Z1\n" + e.message)
            uriToUse = ""
        }
        if (fileOpened == null) {
            try {
                val tempUrl = URLDecoder.decode(intent.data.toString(), "UTF-8").split("/")
                fileOpened = ""
                var storageFound = false
                tempUrl.forEach {
                    if (storageFound || it == "storage") {
                        storageFound = true
                        fileOpened += "/" + it
                    }
                }

            } catch (e: Exception) {
                fileOpened = intent.data.toString()
                println("Exception Z3\n" + e.message)
            }
        }
        if (uriToUse == null || uriToUse == "") {
            //if (getLastFileOpened() == "") {
            //open a new file
            openFromStorage()
            /*} else {
            //open the last file opened
            println(getLastFileOpened())
            openFromStorage(Uri.parse(getLastFileOpened()))
        }*/
        } else {
            //open a recent file
            openFromStorage(Uri.parse(uriToUse))
        }

        checkReviewFollowApp()

        val backButton: ImageView = findViewById(R.id.buttonGoBackToolbar)
        updateBackButtonUi()
        backButton.setOnClickListener {
            resetHideTopBarCounter()
            if (openedExternally) {
                finish()
            } else {
                startActivity(
                    Intent(this, MainActivity::class.java).apply {
                        putExtra(MainActivity.EXTRA_FORCE_HOME_TAB, true)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                )
                finish()
            }
        }
        backButton.setOnLongClickListener {
            showTooltip(if (openedExternally) R.string.tooltip_close_app else R.string.tooltip_back_to_home)
            resetHideTopBarCounter()
            true
        }

        val shareButton: ImageView = findViewById(R.id.buttonShareToolbar)
        shareButton.setOnClickListener {
            setShareButton()
            resetHideTopBarCounter()
            hideMenuPanel()
        }
        shareButton.setOnLongClickListener {
            showTooltip(R.string.tooltip_share_file)
            true
        }

        val rotationButton: ImageView = findViewById(R.id.buttonRotationToolbar)
        rotationButton.setOnClickListener {
            setRotationLockState(locked = true)
            resetHideTopBarCounter()
            hideMenuPanel()
        }
        rotationButton.setOnLongClickListener {
            showTooltip(R.string.tooltip_lock_rotation)
            true
        }

        val rotationUnlockedButton: ImageView = findViewById(R.id.buttonRotationUnlockedMode)
        rotationUnlockedButton.setOnClickListener {
            setRotationLockState(locked = false)
            resetHideTopBarCounter()
            hideMenuPanel()
        }
        rotationUnlockedButton.setOnLongClickListener {
            showTooltip(R.string.tooltip_unlock_rotation)
            true
        }

        val fullScreenButton: ImageView = findViewById(R.id.buttonFullScreenToolbar)
        fullScreenButton.setOnClickListener {
            setFullscreenButton()
            resetHideTopBarCounter()
            hideMenuPanel()
        }
        fullScreenButton.setOnLongClickListener {
            if (isFullscreenEnabled) showTooltip(R.string.tooltip_full_screen_off)
            else showTooltip(R.string.tooltip_full_screen_on)
            true
        }

        val goTopButton: ImageView = findViewById(R.id.buttonGoTopToolbar)
        goTopButton.setOnClickListener {
            goToPage(0, true)
            resetHideTopBarCounter()
        }
        goTopButton.setOnLongClickListener {
            if (isVerticalBottomToTopMode()) showTooltip(R.string.tooltip_go_to_bottom)
            else showTooltip(R.string.tooltip_go_to_top)
            true
        }
        updateGoToEdgeButton()

        val currentPage: TextView = findViewById(R.id.totalPagesToolbar)
        currentPage.setOnClickListener {
            if (findViewById<ConstraintLayout>(R.id.messageGoTo).isGone) {
                val currentPosition1 = pdfViewer.positionOffset
                Handler().postDelayed({
                    val currentPosition2 = pdfViewer.positionOffset
                    showGoToDialog(x = currentPosition1, y = currentPosition2)
                }, 100)
            } else hideGoToDialog()
            resetHideTopBarCounter()
        }
        currentPage.setOnLongClickListener {
            showTooltip(R.string.tooltip_go_to_feature)
            true
        }

        val openButton: ImageView = findViewById(R.id.buttonOpenToolbar)
        openButton.setOnClickListener {
            openFromStorage()
            resetHideTopBarCounter()
            hideMenuPanel()
        }
        openButton.setOnLongClickListener {
            showTooltip(R.string.tooltip_open_new_file)
            true
        }

        val helpButton: ImageView = findViewById(R.id.buttonGetHelpToolbar)
        helpButton.setOnClickListener {
            openGetHelp()
            resetHideTopBarCounter()
            hideMenuPanel()
        }
        helpButton.setOnLongClickListener {
            showTooltip(R.string.tooltip_get_help)
            true
        }

        val selectTextButton: ImageView = findViewById(R.id.buttonSelectTextToolbar)
        selectTextButton.setOnClickListener {
            // Text selection is activated via long-press on the document
            Toast.makeText(this, getString(R.string.text_selection_mode_hint), Toast.LENGTH_SHORT).show()
            resetHideTopBarCounter()
        }
        selectTextButton.setOnLongClickListener {
            showTooltip(R.string.tooltip_select_text)
            true
        }


        val zoomInButton: ImageView = findViewById(R.id.buttonZoomInToolbar)
        zoomInButton.setOnClickListener {
            zoomIn()
            resetHideTopBarCounter()
        }
        zoomInButton.setOnLongClickListener {
            showTooltip(R.string.tooltip_zoom_in)
            true
        }
        val resetZoomButton: TextView = findViewById(R.id.buttonResetZoomToolbar)
        resetZoomButton.setOnClickListener {
            resetZoom()
            resetHideTopBarCounter()
        }
        resetZoomButton.setOnLongClickListener {
            showTooltip(R.string.tooltip_reset_zoom)
            true
        }
        val zoomOutButton: ImageView = findViewById(R.id.buttonZoomOutToolbar)
        zoomOutButton.setOnClickListener {
            zoomOut()
            resetHideTopBarCounter()
            hideMenuPanel()
        }
        zoomOutButton.setOnLongClickListener {
            showTooltip(R.string.tooltip_zoom_out)
            true
        }
        // Keep zoom menu row stable even before the first menu open/layout pass.
        zoomOutButton.isGone = false
        resetZoomButton.isGone = false

        val lightButton: ImageView = findViewById(R.id.buttonNightDayToolbar)
        lightButton.setOnClickListener {
            applyContrastOverlayState(!contrastOverlayEnabled, persist = true)
            resetHideTopBarCounter()
            hideMenuPanel()
        }
        lightButton.setOnLongClickListener {
            if (!contrastOverlayEnabled) showTooltip(R.string.tooltip_night_light_on)
            else showTooltip(R.string.tooltip_night_light_off)
            true
        }
        lightButton.isGone = false


        val buttonSinglePage: ImageView = findViewById(R.id.buttonSinglePage)
        buttonSinglePage.setOnClickListener {
            setSinglePageMode(enabled = true)
            resetHideTopBarCounter()
            hideMenuPanel()
        }
        buttonSinglePage.setOnLongClickListener {
            showTooltip(R.string.tooltip_single_page_scroll)
            true
        }
        buttonSinglePage.isGone = false

        val buttonContinuousPage: ImageView = findViewById(R.id.buttonContinuousPage)
        buttonContinuousPage.setOnClickListener {
            setSinglePageMode(enabled = false)
            resetHideTopBarCounter()
            hideMenuPanel()
        }
        buttonContinuousPage.setOnLongClickListener {
            showTooltip(R.string.tooltip_single_page_scroll_disabled)
            true
        }
        buttonContinuousPage.isGone = false

        val buttonDarkFilter: ImageView = findViewById(R.id.buttonDarkFilter)
        buttonDarkFilter.setOnClickListener {
            applyNightModeState(!night_mode, persist = true, refreshViewer = true)
            resetHideTopBarCounter()
            hideMenuPanel()
        }
        buttonDarkFilter.setOnLongClickListener {
            if (!night_mode) showTooltip(R.string.tooltip_force_dark_filter)
            else showTooltip(R.string.tooltip_force_dark_filter_disable)
            true
        }
        buttonDarkFilter.isGone = false

        val buttonScrollVerticalTopToBottom: ImageView =
            findViewById(R.id.buttonScrollVerticalTopToBottom)
        buttonScrollVerticalTopToBottom.setOnClickListener {
            setScrollMode(ScrollMode.VERTICAL_TOP_TO_BOTTOM)
            resetHideTopBarCounter()
            hideMenuPanel()
        }
        buttonScrollVerticalTopToBottom.setOnLongClickListener {
            showTooltip(R.string.tooltip_scroll_vertical_top_to_bottom)
            true
        }

        val buttonScrollVerticalBottomToTop: ImageView =
            findViewById(R.id.buttonScrollVerticalBottomToTop)
        buttonScrollVerticalBottomToTop.setOnClickListener {
            setScrollMode(ScrollMode.VERTICAL_BOTTOM_TO_TOP)
            resetHideTopBarCounter()
            hideMenuPanel()
        }
        buttonScrollVerticalBottomToTop.setOnLongClickListener {
            showTooltip(R.string.tooltip_scroll_vertical_bottom_to_top)
            true
        }

        val buttonScrollHorizontalLeftToRight: ImageView =
            findViewById(R.id.buttonScrollHorizontalLeftToRight)
        buttonScrollHorizontalLeftToRight.setOnClickListener {
            setScrollMode(ScrollMode.HORIZONTAL_LEFT_TO_RIGHT)
            resetHideTopBarCounter()
            hideMenuPanel()
        }
        buttonScrollHorizontalLeftToRight.setOnLongClickListener {
            showTooltip(R.string.tooltip_scroll_horizontal_left_to_right)
            true
        }

        val buttonScrollHorizontalRightToLeft: ImageView =
            findViewById(R.id.buttonScrollHorizontalRightToLeft)
        buttonScrollHorizontalRightToLeft.setOnClickListener {
            setScrollMode(ScrollMode.HORIZONTAL_RIGHT_TO_LEFT)
            resetHideTopBarCounter()
            hideMenuPanel()
        }
        buttonScrollHorizontalRightToLeft.setOnLongClickListener {
            showTooltip(R.string.tooltip_scroll_horizontal_right_to_left)
            true
        }

        updateScrollModeButtons()
        updateSinglePageModeButtons()
        updateRotationModeButtons()

        val buttonMenu: ImageView = findViewById(R.id.buttonMenuToolbar)
        buttonMenu.setOnClickListener {
            if (menuOpened) hideMenuPanel()
            else showMenuPanel()
            resetHideTopBarCounter()
        }
        buttonMenu.setOnLongClickListener {
            if (menuOpened) showTooltip(R.string.tooltip_hide_menu_panel)
            else showTooltip(R.string.tooltip_open_menu_panel)
            true
        }
        buttonMenu.isGone = false

        setupSearch()
        setupTextSelection()
        setupGestures()
        findViewById<View>(R.id.messageMenuPanel).post {
            configureMenuPanelRowsForOrientation()
        }
        restartScheduledAppearanceChecks()
    }

    private fun showTooltip(string: Int) {
        Toast.makeText(this, string, Toast.LENGTH_LONG).show()
    }

    private fun openFromStorage(uri: Uri? = null) {
        if (uri == null) selectPdfFromStorage()
        else selectPdfFromURI(uri)
    }

    private fun selectPdfFromStorage() {
        val browserStorage = Intent(Intent.ACTION_OPEN_DOCUMENT)
        browserStorage.type = "application/pdf"
        browserStorage.addCategory(Intent.CATEGORY_OPENABLE)
        browserStorage.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivityForResult(
            browserStorage,
            PDF_SELECTION_CODE
        )
    }

    fun incrementHideTopBarCounter() {
        restartTopBarAutoHideCountdown()
    }

    fun resetHideTopBarCounter() {
        hideTopBarCounter = 0
        restartTopBarAutoHideCountdown()
    }

    private fun restartTopBarAutoHideCountdown() {
        topBarUiHandler.removeCallbacks(topBarAutoHideRunnable)
        if (showingTopBar && !menuOpened && !searchPanelVisible) {
            topBarAutoHideDeadlineAtMs = SystemClock.uptimeMillis() + topBarAutoHideDelayMs
            topBarUiHandler.postDelayed(topBarAutoHideRunnable, topBarAutoHideDelayMs)
        } else {
            topBarAutoHideDeadlineAtMs = 0L
        }
    }

    private fun cancelTopBarAutoHideCountdown() {
        topBarAutoHideDeadlineAtMs = 0L
        topBarUiHandler.removeCallbacks(topBarAutoHideRunnable)
    }

    private fun restartScheduledAppearanceChecks() {
        scheduleUiHandler.removeCallbacks(scheduledAppearanceRunnable)
        scheduleUiHandler.postDelayed(scheduledAppearanceRunnable, scheduledAppearanceCheckDelayMs)
    }

    private fun updateBackButtonUi() {
        val buttonClose: ImageView = findViewById(R.id.buttonGoBackToolbar)
        if (openedExternally) {
            buttonClose.setImageResource(R.drawable.ic_close)
            buttonClose.contentDescription = getString(R.string.tooltip_close_app)
        } else {
            buttonClose.setImageResource(R.drawable.ic_back)
            buttonClose.contentDescription = getString(R.string.tooltip_back_to_home)
        }
    }

    private fun currentMinuteOfDay(): Int {
        val calendar = Calendar.getInstance()
        return (calendar.get(Calendar.HOUR_OF_DAY) * 60) + calendar.get(Calendar.MINUTE)
    }

    private fun isWithinScheduledRange(currentMinute: Int, startMinute: Int, endMinute: Int): Boolean {
        if (startMinute == endMinute) return true
        return if (startMinute < endMinute) {
            currentMinute in startMinute until endMinute
        } else {
            currentMinute >= startMinute || currentMinute < endMinute
        }
    }

    private fun applyNightModeState(enabled: Boolean, persist: Boolean = true, refreshViewer: Boolean = true) {
        night_mode = enabled
        if (refreshViewer) {
            pdfViewer.setNightMode(enabled)
            if (totalPages > 0) {
                pdfViewer.jumpTo(pdfViewer.currentPage, true)
            }
        }

        val buttonDarkFilter: ImageView = findViewById(R.id.buttonDarkFilter)
        buttonDarkFilter.setImageResource(
            if (enabled) R.drawable.ic_dark_filter_disabled else R.drawable.ic_dark_filter
        )
        buttonDarkFilter.contentDescription = if (enabled) {
            getString(R.string.tooltip_force_dark_filter_disable)
        } else {
            getString(R.string.tooltip_force_dark_filter)
        }
        pdfViewer.setBackgroundResource(if (enabled) R.color.spacingPageDark else R.color.spacingPage)

        if (persist && !applyingPdfOptions) {
            saveCurrentPdfOptions()
        }
    }

    private fun applyContrastOverlayState(enabled: Boolean, persist: Boolean = true) {
        contrastOverlayEnabled = enabled
        val comfortView: View = findViewById(R.id.nightThemeBackground)
        val lightButton: ImageView = findViewById(R.id.buttonNightDayToolbar)
        comfortView.isGone = !enabled
        lightButton.setImageResource(if (enabled) R.drawable.ic_light_off else R.drawable.ic_light_on)
        lightButton.contentDescription = if (enabled) {
            getString(R.string.tooltip_night_light_off)
        } else {
            getString(R.string.tooltip_night_light_on)
        }
        if (persist && !applyingPdfOptions) {
            saveCurrentPdfOptions()
        }
    }

    private fun applyFullscreenState(enabled: Boolean, persist: Boolean = true, showCompactBar: Boolean = true) {
        val button: ImageView = findViewById(R.id.buttonFullScreenToolbar)
        if (enabled) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
            )
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
            button.setImageResource(R.drawable.ic_exit_fullscreen)
            button.contentDescription = getString(R.string.tooltip_full_screen_off)
            isFullscreenEnabled = true
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            button.setImageResource(R.drawable.ic_fullscreen)
            button.contentDescription = getString(R.string.tooltip_full_screen_on)
            isFullscreenEnabled = false
        }

        if (persist && !applyingPdfOptions) {
            saveCurrentPdfOptions()
        }
        if (showCompactBar) {
            showCompactTopBar(force = true)
        }
    }

    private fun reevaluateScheduledAppearance(refreshViewer: Boolean) {
        val defaults = ViewerDefaultsStore.load(this)
        val currentMinute = currentMinuteOfDay()
        if (defaults.darkFilterAuto) {
            applyNightModeState(
                isWithinScheduledRange(currentMinute, defaults.darkFilterStartMinute, defaults.darkFilterEndMinute),
                persist = false,
                refreshViewer = refreshViewer
            )
        }
        if (defaults.nightLightAuto) {
            applyContrastOverlayState(
                isWithinScheduledRange(currentMinute, defaults.nightLightStartMinute, defaults.nightLightEndMinute),
                persist = false
            )
        }
    }

    fun selectPdfFromURI(uri: Uri?) {
        try {
            if (uri == null) {
                selectPdfFromStorage()
                return
            }
            if (uri.scheme == "content") {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    // ignore: not all providers/current states allow taking persistent grants here
                }

                if (!canReadPdfUri(uri)) {
                    Toast.makeText(
                        this,
                        "Access not permitted. Please reselect the document.",
                        Toast.LENGTH_LONG
                    ).show()
                    selectPdfFromStorageWithInitialUri(uri)
                    return
                }
            }
            //Toast.makeText(this, fileOpened, Toast.LENGTH_LONG).show()
            //Toast.makeText(this, uri.toString(), Toast.LENGTH_LONG).show()
            cancelTopBarAutoHideCountdown()
            resetHideTopBarCounter()
            var lastPosition = 0
            fileId = (fileOpened ?: uri?.toString() ?: "").toString()
            textSelectionManager.clearPageGeometryCache()
            loadCurrentPdfOptions()
            applyFullscreenState(isFullscreenEnabled, persist = false, showCompactBar = false)
            skipNextInitialPageScrollHide = true
            // Keep gesture and button zoom limits consistent until page metrics are available.
            pdfViewer.setMinZoom(0.1f)
            pdfViewer.setMidZoom(2.5f)
            pdfViewer.setMaxZoom(10.0f)
            //Toast.makeText(this, fileId, Toast.LENGTH_LONG).show()

            pdfViewer.fromUri(uri)
                .enableSwipe(true) //leave as "true" (it causes a bug with scrolling when zoom is "100%")
                .swipeHorizontal(horizontal) //horizontal scrolling disabled/enabled
                .enableDoubletap(true)
                //.defaultPage(getPdfPage(uri.toString()))
                .enableAnnotationRendering(true) // render annotations (such as comments, colors or forms)
                .password(passwordToUse).scrollHandle(null)
                .enableAntialiasing(true) // improve rendering a little bit on low-res screens
                .autoSpacing(single_page)
                .spacing(5)
                .pageSnap(single_page)
                .pageFling(single_page)
                .nightMode(night_mode)
                .linkHandler(SavPdfViewerLinkHandler(pdfViewer))

                .onTap {
                    showTopBar()
                    true
                }
                //.onPageError { page, t -> println(page) }
                .onPageChange { page, pageCount ->
                    run {
                        totalPages = pageCount
                        updateZoomBoundsForCurrentOrientation(page)
                        val logicalPage = mapViewerPageToLogical(page)
                        updatePdfPage(fileId, logicalPage)
                        if (logicalPage == 0) {
                            showTopBar(showGoTop = false)
                        }
                        updateGoToEdgeButtonVisibility()
                        //setPositionScrollbarByPage(page.toFloat())
                        // Pre-index OCR words for text selection (temporarily disabled)
                        // if (textSelectionManager.active) {
                        //     ocrEngine.ensurePageIndexedAsync(page)
                        // }
                    }
                }
                .onPageScroll { page, positionOffset ->
                    if (skipNextInitialPageScrollHide) {
                        skipNextInitialPageScrollHide = false
                    } else if (getCurrentLogicalPage() == 0) {
                        // At first page always keep the classic top bar.
                        showTopBar(showGoTop = false)
                    } else {
                        hideTopBar(fullHiding = false)
                    }
                    hideGoToDialog()
                    hideMenuPanel()

                    updateScrollbarButtonsVisibility()
                    requestGoToEdgeButtonVisibilityUpdate(debounced = true)
                }
                .onDrawAll { canvas, pageWidth, pageHeight, displayedPage ->
                    // Record page dimensions for text selection
                    textSelectionManager.recordPageSize(displayedPage, pageWidth, pageHeight)
                    textSelectionManager.recordPageDrawGeometry(displayedPage, canvas, pageWidth, pageHeight)

                    // Draw search highlight rectangles on this page
                    if (currentSearchQuery.isNotBlank() && searchResults.isNotEmpty()) {
                        val logicalDisplayedPage = mapViewerPageToLogical(displayedPage)
                        val rects =
                            ocrEngine.getHighlightsForPage(logicalDisplayedPage, currentSearchQuery, currentSearchOptions())
                        // Determine which occurrence index within this page is active
                        val activeResult = searchResults.getOrNull(searchResultIndex)
                        val activeOnThisPage =
                            activeResult != null && activeResult.pageIndex == logicalDisplayedPage
                        // Find which local rect index corresponds to the active global index
                        var activeLocalIdx = -1
                        if (activeOnThisPage) {
                            // Count how many results before searchResultIndex are on this same page
                            var localIdx = 0
                            for (i in 0 until searchResultIndex) {
                                if (searchResults[i].pageIndex == logicalDisplayedPage) localIdx++
                            }
                            activeLocalIdx = localIdx
                        }
                        for ((localIdx, r) in rects.withIndex()) {
                            val left = (r.left * pageWidth) - highlightInsetPx
                            val top = (r.top * pageHeight) - highlightInsetPx
                            val right = (r.right * pageWidth) + highlightInsetPx
                            val bottom = (r.bottom * pageHeight) + highlightInsetPx
                            if (right <= left || bottom <= top) continue
                            if (localIdx == activeLocalIdx) {
                                canvas.drawRect(left, top, right, bottom, activeHighlightPaint)
                            } else {
                                canvas.drawRect(left, top, right, bottom, highlightPaint)
                            }
                        }
                    }

                    // Draw text selection highlights + handles
                    textSelectionManager.drawOnPage(canvas, pageWidth, pageHeight, displayedPage)
                }
                .onLoad {
                    lastPosition = getPdfPage(fileId)
                    /*pdfViewer.positionOffset = 1F
                    totalPages = pdfViewer.currentPage + 1*/
                    //TODO
                    /*
                    println("title: " + pdfViewer.documentMeta.title)
                    println("author: " + pdfViewer.documentMeta.author)
                    println("keywords: " + pdfViewer.documentMeta.keywords)
                    println("creator: " + pdfViewer.documentMeta.creator)
                    println("modifiedDate: " + pdfViewer.documentMeta.modDate)
                    println("producer: " + pdfViewer.documentMeta.producer)
                    println("subject: " + pdfViewer.documentMeta.subject)
                    println("creationDate: " + pdfViewer.documentMeta.creationDate)
                    println("table of contents: " + pdfViewer.tableOfContents)
                    */
                }
                .onRender { nbPages ->
                    totalPages = nbPages
                    if (lastPosition >= totalPages) lastPosition = (totalPages - 1)

                    // Notify OCR engine of the newly opened document
                    if (uri != null) ocrEngine.open(uri, totalPages)

                    val buttonSideScroll: TextView = findViewById(R.id.buttonSideScroll)
                    val buttonBottomScroll: TextView = findViewById(R.id.buttonBottomScroll)

                    val nowLandscape = resources.configuration.orientation

                    // Compute measurements from parent container and toolbar
                    val parentView: View = findViewById(R.id.pdfViewerContainer)
                    val toolbarContainer: View = findViewById(R.id.toolbarContainer)
                    val fullW = parentView.measuredWidth
                    val fullH = parentView.measuredHeight
                    val toolbarH = toolbarContainer.measuredHeight
                    val residualW = fullW
                    val residualH = fullH - toolbarH

                    var currentStatus = "landscape"
                    if (nowLandscape == Configuration.ORIENTATION_LANDSCAPE) {
                        currentStatus = "landscape"

                        if (!residualViewConfigurationConfigurated) {
                            residualViewConfiguration["landscape"] =
                                hashMapOf(
                                    "width" to residualW,
                                    "height" to residualH
                                )
                            residualViewConfiguration["portrait"] =
                                hashMapOf(
                                    "width" to residualH + toolbarH * (3 / 2),
                                    "height" to residualW - toolbarH * 2
                                )
                            residualViewConfigurationConfigurated = true
                        }
                    } else {
                        currentStatus = "portrait"

                        if (!residualViewConfigurationConfigurated) {
                            residualViewConfiguration["landscape"] =
                                hashMapOf(
                                    "width" to residualH + toolbarH,
                                    "height" to residualW - toolbarH * 2
                                )
                            residualViewConfiguration["portrait"] =
                                hashMapOf(
                                    "width" to residualW - toolbarH / 2,
                                    "height" to residualH
                                )
                            residualViewConfigurationConfigurated = true
                        }
                    }

                    if (minPositionScrollbar == 0F) minPositionScrollbar = buttonSideScroll.y
                    maxPositionScrollbar =
                        residualViewConfiguration[currentStatus]!!["height"]!!.toInt() - minPositionScrollbar
                    startY = minPositionScrollbar

                    if (minPositionScrollbarHorizontal == 0F) minPositionScrollbarHorizontal =
                        buttonBottomScroll.x
                    maxPositionScrollbarHorizontal =
                        residualViewConfiguration[currentStatus]!!["width"]!!.toInt() - minPositionScrollbarHorizontal
                    startX = minPositionScrollbarHorizontal

                    updatePdfPage(fileId, lastPosition)
                    saveCurrentPdfOptions()
                    if (totalPages == 1) {
                        isSupportedGoTop = false
                        isSupportedScrollbarButton = false
                        findViewById<ImageView>(R.id.buttonGoTopToolbar).isGone = true
                        buttonSideScroll.isGone = true
                        buttonBottomScroll.isGone = true
                    } else {
                        isSupportedGoTop = true
                        isSupportedScrollbarButton = true
                    }
                    val targetViewerPage = mapLogicalPageToViewer(lastPosition)
                    updateZoomBoundsForCurrentOrientation(targetViewerPage)
                    if (hasExplicitZoomPreference()) {
                        pdfViewer.zoomTo(zoomToRestore)
                    } else {
                        fitPageForCurrentOrientation(targetViewerPage)
                    }
                    // Re-apply page position after zoom/fit so horizontal offset is stable at first render.
                    pdfViewer.jumpTo(targetViewerPage, false)
                    val pageToCenter = pendingSinglePageCenterLogicalPage
                    if (single_page && pageToCenter != null) {
                        pendingSinglePageCenterLogicalPage = null
                        pdfViewer.post {
                            pdfViewer.jumpTo(mapLogicalPageToViewer(pageToCenter), true)
                        }
                    }
                    setCurrentZoomStatus()
                    configureMenuPanelRowsForOrientation()
                    showTopBar(showGoTop = lastPosition > 0)

                    checkFirstTimeShowMessageGuide()

                    setScrollBarSide()
                    setScrollBarBottom()

                    if (horizontal) {
                        buttonSideScroll.isGone = true
                        buttonBottomScroll.isGone = false
                    } else {
                        buttonSideScroll.isGone = false
                        buttonBottomScroll.isGone = true
                    }
                }.onError(OnErrorListener {
                    if (it.message.toString()
                            .contains("Password required or incorrect password.")
                    ) {
                        var passwordWrong = false
                        if (passwordRequired) passwordWrong = true
                        passwordRequired = true
                        askThePassword(uri, passwordWrong)
                    } else {
                        val errorMessage = it.message.toString()
                        if (
                            errorMessage.contains("Access", ignoreCase = true) &&
                            errorMessage.contains("Permitted", ignoreCase = true)
                        ) {
                            Toast.makeText(
                                this@PDFViewer,
                                "Access not permitted. Please reselect the document.",
                                Toast.LENGTH_LONG
                            ).show()
                            selectPdfFromStorageWithInitialUri(uri)
                        } else {
                            println("PDF load error: ${it.message}")
                            Toast.makeText(
                                this@PDFViewer,
                                "Error: ${it.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    //PdfPasswordException
                }).load()
        } catch (e: Exception) {
            println("Exception 1: ${e.message}")
        }
    }

    private fun canReadPdfUri(uri: Uri): Boolean {
        return try {
            contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun selectPdfFromStorageWithInitialUri(uri: Uri?) {
        val browserStorage = Intent(Intent.ACTION_OPEN_DOCUMENT)
        browserStorage.type = "application/pdf"
        browserStorage.addCategory(Intent.CATEGORY_OPENABLE)
        browserStorage.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (uri != null) {
            browserStorage.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, uri)
        }
        startActivityForResult(browserStorage, PDF_SELECTION_CODE)
    }

    fun askThePassword(uri: Uri?, passwordWrong: Boolean = false) {
        showMessagePassword(passwordWrong)

        val buttonOpen: TextView = findViewById(R.id.buttonOpenPassword)
        val buttonClose: TextView = findViewById(R.id.buttonClosePassword)

        val textboxPassword: EditText = findViewById(R.id.textboxPassword)
        showSoftKeyboard(textboxPassword)

        textboxPassword.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            } else {
                hideKeyboard(v)
            }
        }
        textboxPassword.setOnKeyListener(View.OnKeyListener { v, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP) {
                hideMessagePassword()
                passwordToUse = textboxPassword.text.toString()
                selectPdfFromURI(uri)
                return@OnKeyListener true
            }
            false
        })

        buttonOpen.setOnClickListener {
            hideMessagePassword()
            passwordToUse = textboxPassword.text.toString()
            selectPdfFromURI(uri)
            resetHideTopBarCounter()
        }
        buttonOpen.setOnLongClickListener {
            showTooltip(R.string.tooltip_open_file_password)
            resetHideTopBarCounter()
            true
        }

        buttonClose.setOnClickListener {
            finishAffinity()
            resetHideTopBarCounter()
        }
        buttonClose.setOnLongClickListener {
            showTooltip(R.string.tooltip_close_app)
            resetHideTopBarCounter()
            true
        }
    }

    fun showMessagePassword(passwordWrong: Boolean = false) {
        hideGoToDialog()
        hideMenuPanel()
        hideMessageGuide1()
        val toolbar: View = findViewById(R.id.toolbar)
        val toolbarInvisible: View = findViewById(R.id.toolbarInvisible)
        val buttonClose: ImageView = findViewById(R.id.buttonGoBackToolbar)
        val buttonGoTop: ImageView = findViewById(R.id.buttonGoTopToolbar)
        val currentPage: TextView = findViewById(R.id.totalPagesToolbar)
        val buttonMenu: ImageView = findViewById(R.id.buttonMenuToolbar)
        val buttonBookmark: ImageView = findViewById(R.id.buttonBookmarkToolbar)
        toolbar.isGone = true
        buttonClose.isGone = true
        buttonGoTop.isGone = true
        currentPage.isGone = true
        toolbarInvisible.isGone = true
        buttonMenu.isGone = true
        buttonBookmark.isGone = true


        val background: View = findViewById(R.id.passwordBackgroundScreen)
        val message: ConstraintLayout = findViewById(R.id.messagePassword)

        background.isGone = false
        message.isGone = false

        val textboxPassword: EditText = findViewById(R.id.textboxPassword)
        textboxPassword.setText(passwordToUse)

        val labelPasswordInsertedWrong: TextView = findViewById(R.id.messageTextPasswordWrong)
        if (passwordWrong) {
            labelPasswordInsertedWrong.isGone = false
        } else {
            labelPasswordInsertedWrong.isGone = true
        }
    }

    fun hideMessagePassword() {
        val background: View = findViewById(R.id.passwordBackgroundScreen)
        val message: ConstraintLayout = findViewById(R.id.messagePassword)

        background.isGone = true
        message.isGone = true
    }


    override fun onConfigurationChanged(newConfig: Configuration) {
        textSelectionManager.clearPageGeometryCache()
        selectionDragState = SelectionDragState.NONE
        selectionDownViewX = Float.NaN
        selectionDownViewY = Float.NaN
        var currentStatus: String = "portrait"
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            //LANDSCAPE
            currentStatus = "landscape"
        } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            //PORTRAIT
            currentStatus = "portrait"
        }
        val savedPageToUse = savedCurrentPage
        Handler().postDelayed({
            val viewerPage = mapLogicalPageToViewer(savedCurrentPage)
            updateZoomBoundsForCurrentOrientation(viewerPage)
            fitPageForCurrentOrientation(viewerPage)
            val buttonSideScroll: TextView = findViewById(R.id.buttonSideScroll)
            val buttonBottomScroll: TextView = findViewById(R.id.buttonBottomScroll)
            if (minPositionScrollbar == 0F) minPositionScrollbar = buttonSideScroll.y
            maxPositionScrollbar =
                residualViewConfiguration[currentStatus]!!["height"]!!.toInt() - minPositionScrollbar
            startY = minPositionScrollbar

            if (minPositionScrollbarHorizontal == 0F) minPositionScrollbarHorizontal =
                buttonBottomScroll.x
            maxPositionScrollbarHorizontal =
                residualViewConfiguration[currentStatus]!!["width"]!!.toInt() - minPositionScrollbarHorizontal
            startX = minPositionScrollbarHorizontal


            setScrollBarSide()
            setScrollBarBottom()

            //restore the visited page
            goToPage(savedPageToUse, animation = true)
            pdfViewer.invalidate()
            pdfViewer.isEnabled = true
        }, 100)

        pdfViewer.isEnabled = false

        if (getCurrentLogicalPage() == 0) showTopBar(showGoTop = false)
        else hideTopBar()

        super.onConfigurationChanged(newConfig)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PDF_SELECTION_CODE && resultCode == Activity.RESULT_OK && data != null) {
            try {
                val selectedPdf = data.data

                // Take persistable URI permission so the content URI stays readable
                if (selectedPdf != null) {
                    try {
                        contentResolver.takePersistableUriPermission(
                            selectedPdf,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {
                        // Not all providers support persistable permissions – that's OK
                    }
                }

                val shareButton: ImageView = findViewById(R.id.buttonShareToolbar)
                val fullscreenButton: ImageView = findViewById(R.id.buttonFullScreenToolbar)
                val goTopButton: ImageView = findViewById(R.id.buttonGoTopToolbar)
                val openButton: ImageView = findViewById(R.id.buttonOpenToolbar)
                val menuButton: ImageView = findViewById(R.id.buttonMenuToolbar)
                val bookmarkButton: ImageView = findViewById(R.id.buttonBookmarkToolbar)
                val zoomInButton: ImageView = findViewById(R.id.buttonZoomInToolbar)
                val resetZoomButton: TextView = findViewById(R.id.buttonResetZoomToolbar)
                val zoomOutButton: ImageView = findViewById(R.id.buttonZoomOutToolbar)
                shareButton.isGone = true
                menuButton.isGone = true
                fullscreenButton.isGone = true
                bookmarkButton.isGone = true
                uriOpened = selectedPdf
                if (uriOpened != null) {
                    try {
                        fileOpened = RealPathUtil.getRealPath(this, uriOpened!!)
                    } catch (e: Exception) {
                        //println("!! Exception 01 !!")
                    }
                    shareButton.isGone = false
                    fullscreenButton.isGone = false
                    if (isSupportedGoTop) goTopButton.isGone = false
                    isSupportedShareFeature = true
                    openButton.isGone = false
                    bookmarkButton.isGone = false
                }
                val pagesNumber: TextView = findViewById(R.id.totalPagesToolbar)
                pagesNumber.isGone = true
                updateRotationModeButtons()

                //setTitle(getTheFileName(selectedPdf.toString(), -1))

                selectPdfFromURI(selectedPdf)
            } catch (e: Exception) {
                println("Exception 4: Loading failed – ${e.message}")
                Toast.makeText(this, "Loading failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            //file not selected
            finish()
        }
    }

    fun setTitle(title: String) {
        val titleElement: TextView = findViewById(R.id.titleToolbar)
        var titleTemp = title
        if (titleTemp.length > 40) {
            titleTemp = titleTemp.substring(0, 15) + " ... " + titleTemp.substring(
                titleTemp.length - 16, titleTemp.length - 1
            )
        }
        titleElement.text = titleTemp
    }

    override fun onBackPressed() {
        super.onBackPressed()
    }

    private fun updatePdfPage(pathName: String, currentPage: Int) {
        val pathNameTemp = getTheFileName(pathName, 0).toMD5() //file-id
        val databaseHandler = DatabaseHandler(this)
        val file = findStoredFileRecord(databaseHandler, pathNameTemp, pathName)
        if (file != null) {
            //already exists -> update
            file.lastPage = currentPage //update the lastPage variable
            file.lastUpdate = getNow() //update the lastUpdate variable
            databaseHandler.updateFile(file = file)
        } else {
            //not exists -> add
            val file = FilesModel(
                id = pathNameTemp,
                date = getNow(),
                lastUpdate = getNow(),
                path = pathName,
                lastPage = currentPage,
                scrollMode = scrollMode.name,
                singlePage = single_page,
                nightMode = night_mode,
                contrastOverlay = contrastOverlayEnabled,
                zoom = if (pdfViewer.zoom > 0F) pdfViewer.zoom else zoomToRestore,
                rotationLocked = rotation_locked,
                fullscreen = isFullscreenEnabled,
                notes = ""
            )
            databaseHandler.add(file = file)
        }
        /*getSharedPreferences(pathNameTemp, Context.MODE_PRIVATE).edit()
            .putInt(pathNameTemp, currentPage).apply()*/

        val currentPageText: TextView = findViewById(R.id.totalPagesToolbar)
        currentPageText.text = (currentPage + 1).toString() + "/" + totalPages.toString()
        currentPageText.isGone = false
        savedCurrentPageOld = savedCurrentPage
        savedCurrentPage = currentPage
        //println("current page: $savedCurrentPage")
        updateButtonBookmark(pathName = pathName, currentPage = currentPage)

        if (!horizontal) setPositionScrollbarByPage((currentPage + 1).toFloat())
        else setPositionBottomScrollbarByPage((currentPage + 1).toFloat())
    }

    fun updateButtonBookmark(pathName: String, currentPage: Int) {
        val pathNameTemp = getTheFileName(pathName, 0).toMD5() //file-id
        val bookmarkButton: ImageView = findViewById(R.id.buttonBookmarkToolbar)

        val databaseHandler = DatabaseHandler(this)

        if (databaseHandler.checkBookmark(fileId = pathNameTemp, page = currentPage)) {
            //there is the bookmark
            bookmarkButton.setImageResource(R.drawable.ic_yes_bookmark)
            bookmarkButton.contentDescription = getString(R.string.tooltip_remove_from_bookmarks)
            bookmarkButton.setOnClickListener {
                //remove bookmark
                databaseHandler.deleteBookmark(
                    databaseHandler.getBookmarks(
                        fileId = pathNameTemp, page = currentPage
                    )[0].id!!
                )
                //Toast.makeText(this, getString(R.string.toast_bookmark_removed), Toast.LENGTH_SHORT).show()
                updateButtonBookmark(pathName, currentPage)
            }
        } else {
            //no bookmark
            bookmarkButton.setImageResource(R.drawable.ic_no_bookmark)
            bookmarkButton.contentDescription = getString(R.string.tooltip_add_to_bookmarks)
            bookmarkButton.setOnClickListener {
                //add bookmark
                val bookmark = BookmarksModel(
                    id = null, date = getNow(), file = pathNameTemp, page = currentPage, ""
                )
                databaseHandler.add(bookmark = bookmark)
                //Toast.makeText(this, getString(R.string.toast_bookmark_added), Toast.LENGTH_SHORT).show()
                updateButtonBookmark(pathName, currentPage)
            }
        }

        bookmarkButton.setOnLongClickListener {
            showAllBookmarks(pathName)
            true
        }

        val allBookmarksButton: ImageView = findViewById(R.id.buttonAllBookmarksToolbar)
        allBookmarksButton.setOnClickListener {
            showAllBookmarks(pathName)
            resetHideTopBarCounter()
            hideMenuPanel()
        }
        allBookmarksButton.setOnLongClickListener {
            showTooltip(R.string.tooltip_see_all_bookmarks)
            true
        }

        resetHideTopBarCounter()
    }

    fun showAllBookmarks(pathName: String) {
        val pathNameTemp = getTheFileName(pathName, 0).toMD5() //file-id
        val databaseHandler = DatabaseHandler(this)
        val bookmarks = databaseHandler.getBookmarks(fileId = pathNameTemp)
        dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_bookmarks, null)
        view.setBackgroundResource(R.drawable.border_bottomsheet)
        dialog!!.setContentView(view)
        dialog!!.dismissWithAnimation = true
        dialog!!.setCancelable(true)
        dialog!!.setOnShowListener {
            val bottomSheet = dialog!!.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            val bottomSheetBehavior = bottomSheet?.let { BottomSheetBehavior.from(it) }
            val bookmarkItemsList: RecyclerView = view.findViewById(R.id.bookmarksList)
            val noBookmarksPresent: TextView = view.findViewById(R.id.noBookmarksPresentText)
            val loadingBookmarks: TextView = view.findViewById(R.id.loadingPreviewOfBookmarksText)
            val constraintMessageGuide: ConstraintLayout =
                view.findViewById(R.id.constraintMessageGuide)
            val buttonHideMessageGuide: TextView = view.findViewById(R.id.buttonHideGuideBookmarks)

            if (bookmarks.size > 0) {
                noBookmarksPresent.visibility = View.GONE
                bookmarkItemsList.visibility = View.VISIBLE

                bookmarkItemsList.layoutManager = LinearLayoutManager(this)
                bookmarkItemsList.setHasFixedSize(false)
                val itemAdapter = BookmarksItemAdapter(this, bookmarks)
                bookmarkItemsList.adapter = itemAdapter

                val swipeCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
                    override fun onMove(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                        target: RecyclerView.ViewHolder
                    ): Boolean = false

                    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                        val position = viewHolder.adapterPosition
                        if (position == RecyclerView.NO_POSITION) {
                            itemAdapter.notifyDataSetChanged()
                            return
                        }

                        val bookmark = itemAdapter.getItemAt(position)
                        val bookmarkId = bookmark?.id
                        if (bookmark == null || bookmarkId == null) {
                            itemAdapter.notifyItemChanged(position)
                            return
                        }

                        databaseHandler.deleteBookmark(bookmarkId)
                        itemAdapter.removeItemAt(position)

                        if (itemAdapter.itemCount == 0) {
                            hideBottomSheet()
                        }
                    }

                    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                        super.onSelectedChanged(viewHolder, actionState)
                        bottomSheetBehavior?.isDraggable = actionState != ItemTouchHelper.ACTION_STATE_SWIPE
                        bookmarkItemsList.parent?.requestDisallowInterceptTouchEvent(
                            actionState == ItemTouchHelper.ACTION_STATE_SWIPE
                        )
                    }

                    override fun onChildDraw(
                        c: Canvas,
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                        dX: Float,
                        dY: Float,
                        actionState: Int,
                        isCurrentlyActive: Boolean
                    ) {
                        val foregroundView =
                            viewHolder.itemView.findViewById<View>(R.id.cardViewBookmark)
                        val backgroundCard =
                            viewHolder.itemView.findViewById<CardView>(R.id.cardViewBookmarkRemoved)
                        val activeSwipeColor = ContextCompat.getColor(this@PDFViewer, R.color.dark_dark_red)
                        val idleSwipeColor = ContextCompat.getColor(this@PDFViewer, R.color.red)
                        if (dX >= 0F) {
                            backgroundCard.setCardBackgroundColor(idleSwipeColor)
                            ItemTouchHelper.Callback.getDefaultUIUtil().onDraw(
                                c,
                                recyclerView,
                                foregroundView,
                                0F,
                                dY,
                                actionState,
                                isCurrentlyActive
                            )
                            return
                        }
                        val maxSwipe = foregroundView.width * 0.90F
                        val clampedDx = dX.coerceIn(-maxSwipe, 0F)
                        val thresholdPx = foregroundView.width * getSwipeThreshold(viewHolder)
                        val isDeleteActionArmed = kotlin.math.abs(clampedDx) >= thresholdPx
                        backgroundCard.setCardBackgroundColor(
                            if (isDeleteActionArmed) activeSwipeColor else idleSwipeColor
                        )
                        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                            recyclerView.parent?.requestDisallowInterceptTouchEvent(
                                isCurrentlyActive || clampedDx != 0F
                            )
                        }

                        ItemTouchHelper.Callback.getDefaultUIUtil().onDraw(
                            c,
                            recyclerView,
                            foregroundView,
                            clampedDx,
                            dY,
                            actionState,
                            isCurrentlyActive
                        )
                    }

                    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                        val foregroundView =
                            viewHolder.itemView.findViewById<View>(R.id.cardViewBookmark)
                        val backgroundCard =
                            viewHolder.itemView.findViewById<CardView>(R.id.cardViewBookmarkRemoved)
                        ItemTouchHelper.Callback.getDefaultUIUtil().clearView(foregroundView)
                        backgroundCard.setCardBackgroundColor(
                            ContextCompat.getColor(this@PDFViewer, R.color.red)
                        )
                        bottomSheetBehavior?.isDraggable = true
                        recyclerView.parent?.requestDisallowInterceptTouchEvent(false)
                        super.clearView(recyclerView, viewHolder)
                    }

                    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float {
                        // Keep it forgiving enough for short rows in bottom sheet.
                        return 0.24F
                    }

                    override fun getSwipeEscapeVelocity(defaultValue: Float): Float {
                        return defaultValue * 0.65F
                    }

                    override fun getSwipeVelocityThreshold(defaultValue: Float): Float {
                        return defaultValue * 0.85F
                    }
                }
                ItemTouchHelper(swipeCallback).attachToRecyclerView(bookmarkItemsList)

                loadingBookmarks.isGone = true

                if (getBooleanData("firstTimeSeeAllBookmarks", true)) {
                    constraintMessageGuide.isGone = false
                    buttonHideMessageGuide.setOnClickListener {
                        saveBooleanData("firstTimeSeeAllBookmarks", false)
                        constraintMessageGuide.isGone = true
                    }
                }
            } else {
                noBookmarksPresent.visibility = View.VISIBLE
                bookmarkItemsList.visibility = View.GONE
                loadingBookmarks.isGone = true
            }
        }
        dialog!!.setOnDismissListener {
            showTopBar(showGoTop = !(pdfViewer.currentYOffset == 0F))
            updateButtonBookmark(pathName, getCurrentLogicalPage())
            dialog = null
        }
        dialog!!.show()
    }

    fun hideBottomSheet() {
        try {
            if (dialog != null) {
                dialog!!.dismiss()
                dialog = null
            }
        } catch (e: Exception) {
            println("Exception 12")
        }
    }

    @SuppressLint("SimpleDateFormat")
    fun getNow(): String {
        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm:ss")
        return sdf.format(Date())
    }

    private fun getPdfPage(pathName: String): Int {
        val pathNameTemp = getTheFileName(pathName, 0).toMD5() //file-id
        //return getSharedPreferences(pathNameTemp, Context.MODE_PRIVATE).getInt(pathNameTemp, 0)
        val databaseHandler = DatabaseHandler(this)
        return if (databaseHandler.checkFile(pathNameTemp)) {
            databaseHandler.getFiles(id = pathNameTemp)[0].lastPage
        } else {
            0
        }
    }

    fun getTheFileName(path: String, type: Int = 0): String {
        try {
            var pathTemp = path
            pathTemp = pathTemp.replace("%3A", ":").replace("%2F", "/").replace("content://", "")

            var pathName = ""
            if (pathTemp.contains(":/")) {
                pathName = pathTemp.split(":/")[1]
            } else {
                pathName = pathTemp
            }
            val paths = pathName.split("/")
            val fileName = paths[paths.size - 1]

            when (type) {
                0 -> {
                    //path name
                    return "/" + pathName
                }

                1 -> {
                    //file name
                    return fileName
                }

                2 -> {
                    //path (also content://)
                    return "content://" + pathTemp
                }

                else -> {
                    //file name without ".pdf"
                    return fileName.replace(".pdf", "")
                }
            }
        } catch (e: Exception) {
            println("Exception 2 : ${e.toString()}")
        }
        return ""
    }

    fun String.toMD5(): String {
        val bytes = MessageDigest.getInstance("MD5").digest(this.toByteArray())
        return bytes.toHex()
    }

    fun ByteArray.toHex(): String {
        return joinToString("") { "%02x".format(it) }
    }

    fun setShareButton() {
        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_STREAM, uriOpened)
            putExtra(Intent.EXTRA_TITLE, "CustomFileName.pdf") // Add your custom title here
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            type = "application/pdf"
        }

        startActivity(
            Intent.createChooser(
                shareIntent,
                getString(R.string.share_file_intent)
            )
        )
    }

    fun setSaveButton() {
        //TODO

    }

    fun setPrintButton() {
        //TODO
    }

    fun setRotationLock() {
        setRotationLockState(!rotation_locked)
    }

    fun setFullscreenButton() {
        applyFullscreenState(!isFullscreenEnabled, persist = true, showCompactBar = true)
    }

    fun showTopBar(showGoTop: Boolean = true, x: Float = 0F, y: Float = 0F) {
        val toolbar: View = findViewById(R.id.toolbar)
        val toolbarInvisible: View = findViewById(R.id.toolbarInvisible)
        val buttonClose: ImageView = findViewById(R.id.buttonGoBackToolbar)
        val buttonShare: ImageView = findViewById(R.id.buttonShareToolbar)
        val buttonSearch: ImageView = findViewById(R.id.buttonSearchToolbar)
        val buttonFullscreen: ImageView = findViewById(R.id.buttonFullScreenToolbar)
        val buttonGoTop: ImageView = findViewById(R.id.buttonGoTopToolbar)
        val currentPage: TextView = findViewById(R.id.totalPagesToolbar)
        val buttonOpen: ImageView = findViewById(R.id.buttonOpenToolbar)
        val buttonMenu: ImageView = findViewById(R.id.buttonMenuToolbar)
        val buttonNightDay: ImageView = findViewById(R.id.buttonNightDayToolbar)
        val buttonBookmark: ImageView = findViewById(R.id.buttonBookmarkToolbar)
        val buttonSideScroll: TextView = findViewById(R.id.buttonSideScroll)
        val buttonBottomScroll: TextView = findViewById(R.id.buttonBottomScroll)
        if (x == y) {
            showingTopBar = true
            compactTopBarVisible = false
            resetHideTopBarCounter()
            clearPendingGoToEdgeButtonVisibilityUpdate()

            toolbar.isGone = false
            buttonClose.isGone = false
            if (isSupportedShareFeature) buttonShare.isGone = false
            buttonSearch.isGone = false
            buttonFullscreen.isGone = false
            if (isSupportedGoTop && showGoTop && getCurrentLogicalPage() > 0) buttonGoTop.isGone =
                false
            buttonOpen.isGone = false
            buttonMenu.isGone = false
            buttonNightDay.isGone = false
            buttonBookmark.isGone = false

            currentPage.isGone = false
            currentPage.setTextColor(ContextCompat.getColor(applicationContext, R.color.white))
            toolbarInvisible.setBackgroundResource(R.color.transparent_red)
            toolbarInvisible.isGone = true
            updateScrollbarButtonsVisibility()

            updateGoToEdgeButtonVisibility()

            hideMessageGuide1()

            checkFirstTimeShowMessageGuide()
        }
    }

    fun checkFirstTimeShowMessageGuide() {
        if (getBooleanData("firstTimeShowTopBar", true) && showingTopBar) {
            val message: ConstraintLayout = findViewById(R.id.messageGuide1)
            val arrow: View = findViewById(R.id.arrowRight2)
            val messageText: TextView = findViewById(R.id.messageTextGuide1)

            messageText.setText(getString(R.string.text_tap_here_to_show_go_to_dialog))
            message.isGone = false
            arrow.isGone = false

            val pageNumberTextViewToolbar: TextView = findViewById(R.id.totalPagesToolbar)
            pageNumberTextViewToolbar.isGone = false
            Handler().postDelayed({
                arrow.animate()
                    .x(pageNumberTextViewToolbar.x + (pageNumberTextViewToolbar.width / 2) - (arrow.width / 2))
                    .setDuration(0).start()
            }, 0)

            val button: TextView = findViewById(R.id.buttonHideGuide1)
            button.setOnClickListener {
                message.isGone = true
                arrow.isGone = true
                saveBooleanData("firstTimeShowTopBar", false)

                checkFirstTimeShowMessageGuide()
            }
        } else if (getBooleanData("firstTimeShowTopBarMenu", true) && showingTopBar) {
            val message: ConstraintLayout = findViewById(R.id.messageGuide1)
            val arrow: View = findViewById(R.id.arrowRight3)
            val messageText: TextView = findViewById(R.id.messageTextGuide1)

            val showMenuPanelImageViewToolbar: ImageView = findViewById(R.id.buttonMenuToolbar)
            showMenuPanelImageViewToolbar.isGone = false
            Handler().postDelayed({
                arrow.animate()
                    .x(showMenuPanelImageViewToolbar.x + (showMenuPanelImageViewToolbar.width / 2) - (arrow.width / 2) - 25)
                    .setDuration(0).start()
            }, 0)

            messageText.setText(getString(R.string.text_tap_here_to_show_menu_panel))
            message.isGone = false
            arrow.isGone = false

            val button: TextView = findViewById(R.id.buttonHideGuide1)
            button.setOnClickListener {
                message.isGone = true
                arrow.isGone = true
                saveBooleanData("firstTimeShowTopBarMenu", false)

                checkFirstTimeShowMessageGuide()
            }
        } else if (getBooleanData("firstTimeBookmarks", true) && showingTopBar) {
            val message: ConstraintLayout = findViewById(R.id.messageGuide1)
            val arrow: View = findViewById(R.id.arrowRight2)
            val messageText: TextView = findViewById(R.id.messageTextGuide1)
            messageText.setText(getString(R.string.text_tap_here_to_add_or_remove_the_current_page_to_bookmarks))

            val bookmarkButtonToolbar: ImageView = findViewById(R.id.buttonBookmarkToolbar)
            bookmarkButtonToolbar.isGone = false
            Handler().postDelayed({
                arrow.animate()
                    .x(bookmarkButtonToolbar.x + (bookmarkButtonToolbar.width / 2) - (arrow.width / 2))
                    .setDuration(0).start()
            }, 0)

            message.isGone = false
            arrow.isGone = false

            val button: TextView = findViewById(R.id.buttonHideGuide1)
            button.setOnClickListener {
                message.isGone = true
                arrow.isGone = true
                saveBooleanData("firstTimeBookmarks", false)

                checkFirstTimeShowMessageGuide()
            }
        }
    }

    fun hideTopBar(fullHiding: Boolean = false, x: Float = 0F, y: Float = 0F) {
        if (x == y) {
            if (!fullHiding) {
                showCompactTopBar()
                return
            }
            if (fullHiding) {
                cancelTopBarAutoHideCountdown()
                clearPendingGoToEdgeButtonVisibilityUpdate()
            }
            val message: ConstraintLayout = findViewById(R.id.messageGuide1)
            val messageGoTo: ConstraintLayout = findViewById(R.id.messageGoTo)
            val menuPanel: ConstraintLayout = findViewById(R.id.messageMenuPanel)
            val toolbar: View = findViewById(R.id.toolbar)
            val toolbarInvisible: View = findViewById(R.id.toolbarInvisible)
            val buttonClose: ImageView = findViewById(R.id.buttonGoBackToolbar)
            val buttonShare: ImageView = findViewById(R.id.buttonShareToolbar)
            val buttonSearch: ImageView = findViewById(R.id.buttonSearchToolbar)
            val buttonFullscreen: ImageView = findViewById(R.id.buttonFullScreenToolbar)
            val buttonGoTop: ImageView = findViewById(R.id.buttonGoTopToolbar)
            val currentPage: TextView = findViewById(R.id.totalPagesToolbar)
            val buttonOpen: ImageView = findViewById(R.id.buttonOpenToolbar)
            val buttonMenu: ImageView = findViewById(R.id.buttonMenuToolbar)
            val buttonNightDay: ImageView = findViewById(R.id.buttonNightDayToolbar)
            val buttonBookmark: ImageView = findViewById(R.id.buttonBookmarkToolbar)
            val buttonSideScroll: TextView = findViewById(R.id.buttonSideScroll)
            val buttonBottomScroll: TextView = findViewById(R.id.buttonBottomScroll)


            if (!showingTopBar) {
                //hideMenuPanel()

                currentPage.setTextColor(
                    ContextCompat.getColor(
                        applicationContext, R.color.dark_red
                    )
                )

                hideMessageGuide1()

                if (getBooleanData("firstTimeHideTopBar", true)) {
                    val message: ConstraintLayout = findViewById(R.id.messageGuide1)
                    val arrow: View = findViewById(R.id.arrowLeft)

                    val messageText: TextView = findViewById(R.id.messageTextGuide1)
                    messageText.setText(getString(R.string.text_tap_here_to_show_the_top_bar))
                    message.isGone = false
                    arrow.isGone = false

                    val arrowLeft: View = findViewById(R.id.arrowLeft)
                    arrowLeft.isGone = true

                    toolbarInvisible.setBackgroundResource(R.color.transparent_red_2)
                    currentPage.setTextColor(
                        ContextCompat.getColor(
                            applicationContext, R.color.white
                        )
                    )

                    val button: TextView = findViewById(R.id.buttonHideGuide1)
                    button.setOnClickListener {
                        message.isGone = true
                        arrow.isGone = true
                        saveBooleanData("firstTimeHideTopBar", false)
                        toolbarInvisible.setBackgroundResource(R.color.transparent_red)
                        currentPage.setTextColor(
                            ContextCompat.getColor(
                                applicationContext, R.color.dark_red
                            )
                        )
                    }
                }

                toolbar.isGone = true
                buttonClose.isGone = true
                buttonShare.isGone = true
                buttonFullscreen.isGone = true
                buttonGoTop.isGone = true
                buttonOpen.isGone = true
                buttonMenu.isGone = true
                buttonNightDay.isGone = true
                buttonBookmark.isGone = true

                if (message.isGone && messageGoTo.isGone && menuPanel.isGone) {
                    toolbarInvisible.isGone = fullHiding
                    currentPage.isGone = fullHiding
                    if (isSupportedScrollbarButton) {
                        buttonSideScroll.isGone = fullHiding
                        buttonBottomScroll.isGone = fullHiding
                    }

                    if (fullHiding) {
                        showHideAfterFiveSeconds()
                    }
                } else {
                    toolbarInvisible.isGone = false
                    currentPage.isGone = false

                    if (isSupportedScrollbarButton) {
                        if (horizontal) {
                            buttonSideScroll.isGone = true
                            buttonBottomScroll.isGone = false
                        } else {
                            buttonSideScroll.isGone = false
                            buttonBottomScroll.isGone = true
                        }
                    }
                }
            } else {
                if (message.isGone && messageGoTo.isGone && menuPanel.isGone && fullHiding) {
                    showingTopBar = false;
                    compactTopBarVisible = false
                    lastGoToVisibleState = false
                    toolbar.isGone = true
                    buttonClose.isGone = true
                    buttonShare.isGone = true
                    buttonFullscreen.isGone = true
                    buttonGoTop.isGone = true
                    buttonOpen.isGone = true
                    buttonMenu.isGone = true
                    buttonNightDay.isGone = true
                    toolbarInvisible.isGone = true
                    currentPage.isGone = true
                    buttonBookmark.isGone = true
                    buttonSideScroll.isGone = true
                    buttonBottomScroll.isGone = true

                    showHideAfterFiveSeconds()
                }
            }
        }
    }

    fun showHideAfterFiveSeconds() {
        hideMessageGuide1()
        if (getBooleanData("firstTimeHideTotallyTopBar", true)) {
            val message: ConstraintLayout = findViewById(R.id.messageGuide1)
            val arrow: View = findViewById(R.id.arrowLeft)
            val messageText: TextView = findViewById(R.id.messageTextGuide1)
            val buttonSideScroll: TextView = findViewById(R.id.buttonSideScroll)
            val buttonBottomScroll: TextView = findViewById(R.id.buttonSideScroll)
            messageText.setText(getString(R.string.text_scroll_to_show_the_top_bar_again))
            message.isGone = false
            arrow.isGone = true
            buttonSideScroll.isGone = true
            buttonBottomScroll.isGone = true

            val button: TextView = findViewById(R.id.buttonHideGuide1)
            button.setOnClickListener {
                message.isGone = true
                saveBooleanData("firstTimeHideTotallyTopBar", false)
            }
        }
    }

    fun hideMessageGuide1() {
        val message: ConstraintLayout = findViewById(R.id.messageGuide1)
        val arrow0: View = findViewById(R.id.arrowLeft)
        val arrow1: View = findViewById(R.id.arrowRight)
        val arrow2: View = findViewById(R.id.arrowRight2)
        val arrow3: View = findViewById(R.id.arrowRight3)
        message.isGone = true
        arrow0.isGone = true
        arrow1.isGone = true
        arrow2.isGone = true
        arrow3.isGone = true
    }

    fun checkReviewFollowApp() {
        var timesOpened = getSharedPreferences(
            "app_opened_times", Context.MODE_PRIVATE
        ).getInt("app_opened_times", 0)

        val alreadyReviewed = getSharedPreferences(
            "already_reviewed_app", Context.MODE_PRIVATE
        ).getBoolean("already_reviewed_app", false)

        val alreadyFollow = getSharedPreferences(
            "already_follow_app", Context.MODE_PRIVATE
        ).getBoolean("already_follow_app", false)

        val donateLiberaPay = getSharedPreferences(
            "donate_liberapay", Context.MODE_PRIVATE
        ).getBoolean("donate_liberapay", false)

        val buttonReviewNowReview: TextView = findViewById(R.id.buttonReviewNowReview)
        val messageContainerReview: ConstraintLayout = findViewById(R.id.messageContainerReview)
        val buttonHideMessageReview: ImageView = findViewById(R.id.buttonHideMessageDialogReview)

        val buttonFollowNowInstagram: TextView = findViewById(R.id.buttonFollowNowInstagram)
        val messageContainerInstagram: ConstraintLayout =
            findViewById(R.id.messageContainerInstagram)
        val buttonHideMessageInstagram: ImageView =
            findViewById(R.id.buttonHideMessageDialogInstagram)

        val buttonDonateLiberaPay: TextView = findViewById(R.id.buttonLiberaPay)
        val messageContainerLiberaPay: ConstraintLayout =
            findViewById(R.id.messageContainerLiberaPay)
        val messageTextLiberaPay: TextView =
            findViewById(R.id.messageTextLiberaPay)
        val buttonHideMessageLiberaPay: ImageView =
            findViewById(R.id.buttonHideMessageDialogLiberaPay)

        buttonReviewNowReview.setOnClickListener {
            if (openOnGooglePlay()) {
                messageContainerReview.isGone = true
                getSharedPreferences("already_reviewed_app", Context.MODE_PRIVATE).edit()
                    .putBoolean("already_reviewed_app", true).apply()
            }
        }

        buttonHideMessageReview.setOnClickListener {
            messageContainerReview.isGone = true
        }

        buttonDonateLiberaPay.setOnClickListener {
            if (openLiberaPay()) {
                messageContainerLiberaPay.isGone = true
                getSharedPreferences("donate_liberapay", Context.MODE_PRIVATE).edit()
                    .putBoolean("donate_liberapay", true).apply()
            }
        }

        buttonHideMessageLiberaPay.setOnClickListener {
            messageContainerLiberaPay.isGone = true
        }

        buttonFollowNowInstagram.setOnClickListener {
            if (openInstagram()) {
                messageContainerInstagram.isGone = true
                getSharedPreferences("already_follow_app", Context.MODE_PRIVATE).edit()
                    .putBoolean("already_follow_app", true).apply()
            }
        }

        buttonHideMessageInstagram.setOnClickListener {
            messageContainerInstagram.isGone = true
        }

        //check whether show "review on google play" message
        if (!alreadyReviewed) {
            if ((timesOpened % timesAfterOpenReviewMessage) == 0 && timesOpened >= timesAfterOpenReviewMessage) {
                messageContainerReview.isGone = false
            } else {
                messageContainerReview.isGone = true
            }
        } else {
            messageContainerReview.isGone = true
        }

        //check whether show "follow on instagram" message
        /*//DISABLED FOR NOW
        if (!alreadyFollow) {
            if ((timesOpened % timesAfterShowFollowApp) == 0 && timesOpened >= timesAfterShowFollowApp) {
                messageContainerInstagram.isGone = false
            } else {
                messageContainerInstagram.isGone = true
            }
        } else {
            messageContainerInstagram.isGone = true
        }*/

        //check whether show "donate on liberapay" message
        if (!donateLiberaPay) {
            if ((timesOpened % timesAfterLiberaPay) == 0 && timesOpened >= timesAfterLiberaPay) {
                messageTextLiberaPay.setText(
                    getString(R.string.text_donate_liberapay).replace(
                        "{n_times}",
                        timesOpened.toString()
                    )
                )
                messageContainerLiberaPay.isGone = false
            } else {
                messageContainerLiberaPay.isGone = true
            }
        } else {
            messageContainerLiberaPay.isGone = true
        }

        timesOpened++
        getSharedPreferences("app_opened_times", Context.MODE_PRIVATE).edit()
            .putInt("app_opened_times", timesOpened).apply()
    }

    fun openOnGooglePlay(): Boolean {
        var valueToReturn = true
        try {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW, Uri.parse("market://details?id=com.saverio.pdfviewer")
                )
            )
        } catch (e: Exception) {
            println("Exception 3: " + e.toString())
            valueToReturn = false
        }

        return valueToReturn
    }

    fun openLiberaPay(): Boolean {
        var valueToReturn = true
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.savpdfviewer.com/donate/"))
            )
        } catch (e: Exception) {
            e.printStackTrace()
            valueToReturn = false
        }

        return valueToReturn
    }

    fun openInstagram(): Boolean {
        var valueToReturn = true
        try {
            startActivity(
                Intent(instagramIntent(this))
            )
        } catch (e: Exception) {
            println("Exception 10: " + e.toString())
            valueToReturn = false
        }

        return valueToReturn
    }

    private fun instagramIntent(context: Context): Intent {
        val instaId = "savpdfviewer"
        val appResolver = "instagram://user?username="
        val webResolver = "https://instagram.com/"
        val instaPackageName = "com.instagram.android"
        val instaLitePackName = "com.instagram.lite"
        return try {
            context.packageManager.getPackageInfo(instaPackageName, 0)
            Intent(Intent.ACTION_VIEW, Uri.parse(appResolver + instaId))
        } catch (e1: PackageManager.NameNotFoundException) {
            //println("Instagram not found")
            try {
                context.packageManager.getPackageInfo(instaLitePackName, 0)
                Intent(Intent.ACTION_VIEW, Uri.parse(appResolver + instaId))
            } catch (e2: PackageManager.NameNotFoundException) {
                //println("Instagram and Instagram lite not found")
                Intent(Intent.ACTION_VIEW, Uri.parse(webResolver + instaId))
            }
        }
    }

    fun showGoToDialog(x: Float = 0F, y: Float = 0F) {
        if (x == y) {
            if (getCurrentLogicalPage() == 0) showTopBar(showGoTop = false)
            else showTopBar()

            hideMessageGuide1()
            closeOverlayPanelsExcept(OverlayPanel.GOTO)

            val buttonHide: ImageView = findViewById(R.id.buttonHideMessageGoTo)
            val textAllPages: TextView = findViewById(R.id.textAllPagesGoTo)
            val textbox: EditText = findViewById(R.id.textboxGoTo)
            val buttonGoTo: TextView = findViewById(R.id.buttonGoTo)

            textAllPages.text = "/ $totalPages"
            textbox.setText((getCurrentLogicalPage() + 1).toString())

            val message: ConstraintLayout = findViewById(R.id.messageGoTo)
            val arrow: View = findViewById(R.id.arrowMessageGoTo)
            message.isGone = false
            arrow.isGone = false

            val pageNumberTextViewToolbar: TextView = findViewById(R.id.totalPagesToolbar)
            pageNumberTextViewToolbar.isGone = false
            Handler().postDelayed({
                arrow.animate()
                    .x(pageNumberTextViewToolbar.x + (pageNumberTextViewToolbar.width / 2) - (arrow.width / 2))
                    .setDuration(0).start()
            }, 0)

            textbox.requestFocus()
            textbox.hasFocus()
            showSoftKeyboard(textbox)
            textbox.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                } else {
                    hideKeyboard(v)
                }
            }

            buttonGoTo.setOnClickListener {
                goToFeature(textbox)
                hideGoToDialog()
                resetHideTopBarCounter()
            }

            buttonHide.setOnClickListener {
                hideGoToDialog()
                resetHideTopBarCounter()
            }

            textbox.addTextChangedListener {
                val valueTemp = textbox.text.toString().replace(" ", "")
                if (valueTemp != "" && valueTemp != "-") {
                    if (valueTemp.toInt() < 0) {
                        textbox.setText((valueTemp.toInt() * (-1)).toString())
                    }
                }
            }
            textbox.setOnKeyListener(View.OnKeyListener { v, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP) {
                    goToFeature(textbox)
                    //hideKeyboard()
                    return@OnKeyListener true
                }
                false
            })
        }
    }

    fun goToFeature(textbox: EditText) {
        var valueToGo = getCurrentLogicalPage() + 1

        val valueTemp = textbox.text.toString().replace(" ", "")
        if (valueTemp != "" && valueTemp != "-") {
            if (valueTemp.toInt() < 0) {
                valueToGo = 0
            } else if (valueTemp.toInt() > totalPages) {
                valueToGo = totalPages
            } else {
                valueToGo = valueTemp.toInt() - 1
            }
        }
        try {
            goToPage(valueToGo, true)
            textbox.clearFocus()
        } catch (e: Exception) {
            println("Exception 11")
        }
    }

    fun goToPage(valueToGo: Int, animation: Boolean = true) {
        if (totalPages <= 0) return
        val target = mapLogicalPageToViewer(valueToGo.coerceIn(0, totalPages - 1))
        pdfViewer.jumpTo(target, animation)
        if (dialog != null) dialog!!.dismiss()
    }

    private fun setScrollMode(mode: ScrollMode, reloadPdf: Boolean = true) {
        scrollMode = mode
        horizontal = mode == ScrollMode.HORIZONTAL_LEFT_TO_RIGHT || mode == ScrollMode.HORIZONTAL_RIGHT_TO_LEFT
        reverseScroll = mode == ScrollMode.VERTICAL_BOTTOM_TO_TOP || mode == ScrollMode.HORIZONTAL_RIGHT_TO_LEFT
        saveScrollModePreference()
        updateScrollModeButtons()
        updateGoToEdgeButton()

        if (!applyingPdfOptions) {
            saveCurrentPdfOptions()
        }

        if (reloadPdf && uriOpened != null) {
            selectPdfFromURI(uriOpened)
        }
    }

    private fun loadScrollModePreference() {
        val modeName = getSharedPreferences(scrollModePreferenceName, Context.MODE_PRIVATE)
            .getString(scrollModePreferenceKey, ScrollMode.VERTICAL_TOP_TO_BOTTOM.name)
        val mode = try {
            ScrollMode.valueOf(modeName ?: ScrollMode.VERTICAL_TOP_TO_BOTTOM.name)
        } catch (_: Exception) {
            ScrollMode.VERTICAL_TOP_TO_BOTTOM
        }
        setScrollMode(mode, reloadPdf = false)
    }

    private fun saveScrollModePreference() {
        getSharedPreferences(scrollModePreferenceName, Context.MODE_PRIVATE).edit()
            .putString(scrollModePreferenceKey, scrollMode.name)
            .apply()
    }

    private fun updateScrollModeButtons() {
        val verticalTopToBottom: ImageView = findViewById(R.id.buttonScrollVerticalTopToBottom)
        val verticalBottomToTop: ImageView = findViewById(R.id.buttonScrollVerticalBottomToTop)
        val horizontalLeftToRight: ImageView = findViewById(R.id.buttonScrollHorizontalLeftToRight)
        val horizontalRightToLeft: ImageView = findViewById(R.id.buttonScrollHorizontalRightToLeft)

        verticalTopToBottom.alpha =
            if (scrollMode == ScrollMode.VERTICAL_TOP_TO_BOTTOM) selectedOptionAlpha else unselectedOptionAlpha
        verticalBottomToTop.alpha =
            if (scrollMode == ScrollMode.VERTICAL_BOTTOM_TO_TOP) selectedOptionAlpha else unselectedOptionAlpha
        horizontalLeftToRight.alpha =
            if (scrollMode == ScrollMode.HORIZONTAL_LEFT_TO_RIGHT) selectedOptionAlpha else unselectedOptionAlpha
        horizontalRightToLeft.alpha =
            if (scrollMode == ScrollMode.HORIZONTAL_RIGHT_TO_LEFT) selectedOptionAlpha else unselectedOptionAlpha
    }

    private fun setSinglePageMode(enabled: Boolean) {
        val switchingFromContinuousToSingle = enabled && !single_page
        if (single_page == enabled && uriOpened != null) {
            updateSinglePageModeButtons()
            return
        }

        if (switchingFromContinuousToSingle && totalPages > 0) {
            pendingSinglePageCenterLogicalPage = getCurrentLogicalPage()
        }

        single_page = enabled
        pdfViewer.setPageSnap(single_page)
        pdfViewer.setPageFling(single_page)
        updateSinglePageModeButtons()

        if (!applyingPdfOptions) {
            saveCurrentPdfOptions()
            if (uriOpened != null) {
                selectPdfFromURI(uriOpened)
            }
        }
    }

    private fun updateSinglePageModeButtons() {
        val singlePageButton: ImageView = findViewById(R.id.buttonSinglePage)
        val continuousPageButton: ImageView = findViewById(R.id.buttonContinuousPage)

        singlePageButton.alpha = if (single_page) selectedOptionAlpha else unselectedOptionAlpha
        continuousPageButton.alpha = if (single_page) unselectedOptionAlpha else selectedOptionAlpha

        singlePageButton.contentDescription = getString(R.string.tooltip_single_page_scroll)
        continuousPageButton.contentDescription = getString(R.string.tooltip_single_page_scroll_disabled)
    }

    private fun setRotationLockState(locked: Boolean, persist: Boolean = true) {
        rotation_locked = locked
        requestedOrientation = if (rotation_locked) {
            ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        updateRotationModeButtons()
        if (persist && !applyingPdfOptions) {
            saveCurrentPdfOptions()
        }
    }

    private fun updateRotationModeButtons() {
        val lockButton: ImageView = findViewById(R.id.buttonRotationToolbar)
        val unlockButton: ImageView = findViewById(R.id.buttonRotationUnlockedMode)

        lockButton.alpha = if (rotation_locked) selectedOptionAlpha else unselectedOptionAlpha
        unlockButton.alpha = if (rotation_locked) unselectedOptionAlpha else selectedOptionAlpha

        lockButton.contentDescription = getString(R.string.tooltip_lock_rotation)
        unlockButton.contentDescription = getString(R.string.tooltip_unlock_rotation)
    }

    private fun isVerticalBottomToTopMode(): Boolean {
        return !horizontal && reverseScroll
    }

    private fun updateGoToEdgeButton() {
        val buttonGoTop: ImageView = findViewById(R.id.buttonGoTopToolbar)
        val shouldUseBottomIcon = isVerticalBottomToTopMode()
        if (lastGoToBottomIconState != shouldUseBottomIcon) {
            if (shouldUseBottomIcon) {
                buttonGoTop.setImageResource(R.drawable.ic_go_to_bottom)
                buttonGoTop.contentDescription = getString(R.string.tooltip_go_to_bottom)
            } else {
                buttonGoTop.setImageResource(R.drawable.ic_go_to_top)
                buttonGoTop.contentDescription = getString(R.string.tooltip_go_to_top)
            }
            lastGoToBottomIconState = shouldUseBottomIcon
        }
        updateGoToEdgeButtonVisibility()
    }

    private fun updateGoToEdgeButtonVisibility() {
        requestGoToEdgeButtonVisibilityUpdate(debounced = false)
    }

    private fun requestGoToEdgeButtonVisibilityUpdate(debounced: Boolean) {
        clearPendingGoToEdgeButtonVisibilityUpdate()
        if (debounced && showingTopBar && !compactTopBarVisible) {
            goToUiHandler.postDelayed(goToVisibilityRunnable, goToVisibilityDebounceMs)
        } else {
            goToVisibilityRunnable.run()
        }
    }

    private fun clearPendingGoToEdgeButtonVisibilityUpdate() {
        goToUiHandler.removeCallbacks(goToVisibilityRunnable)
    }

    private fun updateGoToEdgeButtonVisibilityInternal() {
        val buttonGoTop: ImageView = findViewById(R.id.buttonGoTopToolbar)
        val shouldBeVisible = showingTopBar && !compactTopBarVisible && isSupportedGoTop && getCurrentLogicalPage() > 0
        if (lastGoToVisibleState == shouldBeVisible) return

        buttonGoTop.isGone = !shouldBeVisible
        lastGoToVisibleState = shouldBeVisible
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).roundToInt()
    }

    private fun updateScrollbarButtonsVisibility() {
        val buttonSideScroll: TextView = findViewById(R.id.buttonSideScroll)
        val buttonBottomScroll: TextView = findViewById(R.id.buttonBottomScroll)

        if (!isSupportedScrollbarButton || totalPages <= 1) {
            buttonSideScroll.isGone = true
            buttonBottomScroll.isGone = true
            return
        }

        if (horizontal) {
            buttonSideScroll.isGone = true
            buttonBottomScroll.isGone = false
        } else {
            buttonSideScroll.isGone = false
            buttonBottomScroll.isGone = true
        }
    }

    private fun showCompactTopBar(force: Boolean = false) {
        if (!force && (menuOpened || searchPanelVisible)) return

        val toolbar: View = findViewById(R.id.toolbar)
        val toolbarInvisible: View = findViewById(R.id.toolbarInvisible)
        val buttonClose: ImageView = findViewById(R.id.buttonGoBackToolbar)
        val buttonShare: ImageView = findViewById(R.id.buttonShareToolbar)
        val buttonSearch: ImageView = findViewById(R.id.buttonSearchToolbar)
        val buttonFullscreen: ImageView = findViewById(R.id.buttonFullScreenToolbar)
        val buttonGoTop: ImageView = findViewById(R.id.buttonGoTopToolbar)
        val currentPage: TextView = findViewById(R.id.totalPagesToolbar)
        val buttonOpen: ImageView = findViewById(R.id.buttonOpenToolbar)
        val buttonMenu: ImageView = findViewById(R.id.buttonMenuToolbar)
        val buttonNightDay: ImageView = findViewById(R.id.buttonNightDayToolbar)
        val buttonBookmark: ImageView = findViewById(R.id.buttonBookmarkToolbar)

        showingTopBar = true
        compactTopBarVisible = true
        resetHideTopBarCounter()
        clearPendingGoToEdgeButtonVisibilityUpdate()
        lastGoToVisibleState = false

        toolbar.isGone = true
        buttonClose.isGone = true
        buttonShare.isGone = true
        buttonSearch.isGone = true
        buttonFullscreen.isGone = true
        buttonGoTop.isGone = true
        buttonOpen.isGone = true
        buttonMenu.isGone = true
        buttonNightDay.isGone = true
        buttonBookmark.isGone = true

        currentPage.isGone = false
        currentPage.setTextColor(ContextCompat.getColor(applicationContext, R.color.dark_red))
        toolbarInvisible.setBackgroundResource(R.color.transparent_red)
        toolbarInvisible.isGone = false

        updateScrollbarButtonsVisibility()
        hideMessageGuide1()
    }

    private data class ScrollbarTrackMetrics(
        val start: Float,
        val end: Float,
        val travelLength: Float
    )

    private fun computeScrollbarThumbLengthPx(availableSpan: Float): Int {
        val maxLength = availableSpan.roundToInt().coerceAtLeast(1)
        val minLength = scrollbarMinimumLengthPx.coerceAtMost(maxLength)
        if (totalPages <= 1) return maxLength

        val proposedLength = (availableSpan / totalPages.coerceAtLeast(1)).roundToInt()
        return proposedLength.coerceIn(minLength, maxLength)
    }

    private fun applyVerticalScrollbarThumbLength(button: TextView) {
        val availableSpan = maxPositionScrollbar.coerceAtLeast(1F)
        val layoutParams = button.layoutParams
        layoutParams.height = computeScrollbarThumbLengthPx(availableSpan)
        button.layoutParams = layoutParams
    }

    private fun applyHorizontalScrollbarThumbLength(button: TextView) {
        val availableSpan =
            (maxPositionScrollbarHorizontal - minPositionScrollbarHorizontal).coerceAtLeast(1F)
        val layoutParams = button.layoutParams
        layoutParams.width = computeScrollbarThumbLengthPx(availableSpan)
        button.layoutParams = layoutParams
    }

    private fun getVerticalScrollbarTrackMetrics(button: TextView): ScrollbarTrackMetrics {
        val trackStart = minPositionScrollbar
        val trackEnd = (maxPositionScrollbar + minPositionScrollbar - button.height)
            .coerceAtLeast(trackStart)
        return ScrollbarTrackMetrics(
            start = trackStart,
            end = trackEnd,
            travelLength = (trackEnd - trackStart).coerceAtLeast(1F)
        )
    }

    private fun getHorizontalScrollbarTrackMetrics(button: TextView): ScrollbarTrackMetrics {
        val trackStart = minPositionScrollbarHorizontal
        val trackEnd = (maxPositionScrollbarHorizontal - button.width)
            .coerceAtLeast(trackStart)
        return ScrollbarTrackMetrics(
            start = trackStart,
            end = trackEnd,
            travelLength = (trackEnd - trackStart).coerceAtLeast(1F)
        )
    }

    private fun alignSideScrollLabelToThumb(container: ConstraintLayout, thumb: TextView, thumbTop: Float, animationDuration: Long = 0L) {
        val labelHeight = if (container.height > 0) container.height else dpToPx(50F)
        val centeredY = thumbTop + ((thumb.height - labelHeight) / 2F)
        container.animate().y(centeredY).setDuration(animationDuration).start()
    }

    private fun alignBottomScrollLabelToThumb(container: ConstraintLayout, thumb: TextView, thumbLeft: Float, animationDuration: Long = 0L) {
        val labelWidth = if (container.width > 0) container.width else thumb.width
        val centeredX = thumbLeft + ((thumb.width - labelWidth) / 2F)
        container.animate().x(centeredX).setDuration(animationDuration).start()
    }

    private fun loadCurrentPdfOptions() {
        if (fileId.isBlank()) return

        applyingPdfOptions = true
        try {
            val defaults = ViewerDefaultsStore.load(this)
            try {
                setScrollMode(ScrollMode.valueOf(defaults.scrollMode), reloadPdf = false)
            } catch (_: Exception) {
                setScrollMode(ScrollMode.VERTICAL_TOP_TO_BOTTOM, reloadPdf = false)
            }
            single_page = defaults.singlePage
            night_mode = defaults.nightMode
            contrastOverlayEnabled = defaults.contrastOverlay
            rotation_locked = defaults.rotationLocked
            isFullscreenEnabled = defaults.fullscreen
            zoomToRestore = if (defaults.zoomMode == ViewerDefaultsStore.ZOOM_MODE_PERCENT) {
                (defaults.zoomPercent.coerceIn(10, 500) / 100f)
            } else {
                1.0f
            }

            val fileKey = getTheFileName(fileId, 0).toMD5()
            val databaseHandler = DatabaseHandler(this)
            val file = findStoredFileRecord(databaseHandler, fileKey, fileId)

            if (file != null) {
                try {
                    setScrollMode(ScrollMode.valueOf(file.scrollMode), reloadPdf = false)
                } catch (_: Exception) {
                    setScrollMode(ScrollMode.VERTICAL_TOP_TO_BOTTOM, reloadPdf = false)
                }

                single_page = file.singlePage
                night_mode = file.nightMode
                contrastOverlayEnabled = file.contrastOverlay
                rotation_locked = file.rotationLocked
                isFullscreenEnabled = file.fullscreen
                zoomToRestore = file.zoom
            }

            migrateLegacyPreferencesIfNeeded(fileKey, databaseHandler)

            updateSinglePageModeButtons()

            setRotationLockState(rotation_locked, persist = false)
            applyNightModeState(night_mode, persist = false, refreshViewer = false)
            applyContrastOverlayState(contrastOverlayEnabled, persist = false)
            reevaluateScheduledAppearance(refreshViewer = false)
        } finally {
            applyingPdfOptions = false
        }
    }

    private fun migrateLegacyPreferencesIfNeeded(fileKey: String, databaseHandler: DatabaseHandler) {
        val migrationPreferences = getSharedPreferences(legacyMigrationPreferenceName, Context.MODE_PRIVATE)
        val migrationKey = "migrated_$fileKey"
        if (migrationPreferences.getBoolean(migrationKey, false)) return

        val oldPdfPreferences = getSharedPreferences(legacyPdfOptionsPreferenceName, Context.MODE_PRIVATE)
        val oldGlobalScrollMode = getSharedPreferences(scrollModePreferenceName, Context.MODE_PRIVATE)
            .getString(scrollModePreferenceKey, null)

        var hasLegacyData = false

        val legacyScrollMode = oldPdfPreferences.getString("scroll_mode_$fileKey", oldGlobalScrollMode)
        if (!legacyScrollMode.isNullOrBlank()) {
            try {
                setScrollMode(ScrollMode.valueOf(legacyScrollMode), reloadPdf = false)
                hasLegacyData = true
            } catch (_: Exception) {
                // ignore invalid legacy values
            }
        }

        if (oldPdfPreferences.contains("single_page_$fileKey")) {
            single_page = oldPdfPreferences.getBoolean("single_page_$fileKey", single_page)
            hasLegacyData = true
        }
        if (oldPdfPreferences.contains("night_mode_$fileKey")) {
            night_mode = oldPdfPreferences.getBoolean("night_mode_$fileKey", night_mode)
            hasLegacyData = true
        }
        if (oldPdfPreferences.contains("zoom_$fileKey")) {
            zoomToRestore = oldPdfPreferences.getFloat("zoom_$fileKey", zoomToRestore)
            hasLegacyData = true
        }
        if (oldPdfPreferences.contains("rotation_locked_$fileKey")) {
            rotation_locked = oldPdfPreferences.getBoolean("rotation_locked_$fileKey", rotation_locked)
            hasLegacyData = true
        }

        if (hasLegacyData && databaseHandler.checkFile(fileKey)) {
            val file = databaseHandler.getFiles(fileKey).firstOrNull()
            if (file != null) {
                file.scrollMode = scrollMode.name
                file.singlePage = single_page
                file.nightMode = night_mode
                file.contrastOverlay = contrastOverlayEnabled
                file.zoom = zoomToRestore
                file.rotationLocked = rotation_locked
                file.fullscreen = isFullscreenEnabled
                file.lastUpdate = getNow()
                databaseHandler.updateFile(file)
            }
        }

        migrationPreferences.edit().putBoolean(migrationKey, true).apply()
    }

    private fun saveCurrentPdfOptions() {
        if (fileId.isBlank() || applyingPdfOptions) return

        val databaseHandler = DatabaseHandler(this)
        val fileKey = getTheFileName(fileId, 0).toMD5()
        val existing = findStoredFileRecord(databaseHandler, fileKey, fileId)

        if (existing != null) {
            val file = existing
            file.scrollMode = scrollMode.name
            file.singlePage = single_page
            file.nightMode = night_mode
            file.contrastOverlay = contrastOverlayEnabled
            file.zoom = if (pdfViewer.zoom > 0F) pdfViewer.zoom else zoomToRestore
            file.rotationLocked = rotation_locked
            file.fullscreen = isFullscreenEnabled
            file.lastUpdate = getNow()
            if (file.path.isBlank()) {
                file.path = fileId
            }
            databaseHandler.updateFile(file)
        } else {
            val file = FilesModel(
                id = fileKey,
                date = getNow(),
                lastUpdate = getNow(),
                path = fileId,
                lastPage = savedCurrentPage,
                scrollMode = scrollMode.name,
                singlePage = single_page,
                nightMode = night_mode,
                contrastOverlay = contrastOverlayEnabled,
                zoom = if (pdfViewer.zoom > 0F) pdfViewer.zoom else zoomToRestore,
                rotationLocked = rotation_locked,
                fullscreen = isFullscreenEnabled,
                notes = ""
            )
            databaseHandler.add(file)
        }
    }

    private fun mapViewerPageToLogical(viewerPage: Int): Int {
        if (!reverseScroll || totalPages <= 0) return viewerPage
        return (totalPages - 1 - viewerPage).coerceIn(0, totalPages - 1)
    }

    private fun mapLogicalPageToViewer(logicalPage: Int): Int {
        if (totalPages <= 0) return logicalPage
        val clamped = logicalPage.coerceIn(0, totalPages - 1)
        if (!reverseScroll) return clamped
        return (totalPages - 1 - clamped).coerceIn(0, totalPages - 1)
    }

    private fun getCurrentLogicalPage(): Int {
        return mapViewerPageToLogical(pdfViewer.currentPage)
    }

    fun hideKeyboard(view: View) {
        val manager = getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
        if (manager.isActive) manager.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    fun showSoftKeyboard(view: View) {
        if (view.requestFocus()) {
            val inputMethodManager: InputMethodManager =
                getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun hideGoToDialog() {
        val message: ConstraintLayout = findViewById(R.id.messageGoTo)
        val arrow: View = findViewById(R.id.arrowMessageGoTo)
        message.isGone = true
        arrow.isGone = true
    }

    fun showMenuPanel() {
        hideMessageGuide1()
        closeOverlayPanelsExcept(OverlayPanel.MENU)
        cancelTopBarAutoHideCountdown()

        val message: ConstraintLayout = findViewById(R.id.messageMenuPanel)
        val arrow: View = findViewById(R.id.arrowMenuPanel)
        message.isGone = false
        arrow.isGone = false

        setCurrentZoomStatus()

        val showMenuPanelToolbar: ImageView = findViewById(R.id.buttonMenuToolbar)
        showMenuPanelToolbar.isGone = false
        Handler().postDelayed({
            arrow.animate()
                .x(showMenuPanelToolbar.x + (showMenuPanelToolbar.width / 2) - (arrow.width / 2) - 25)
                .setDuration(0).start()
        }, 0)

        menuOpened = true

        val buttonOpen: ImageView = findViewById(R.id.buttonOpenToolbar)
        val buttonAllBookmarks: ImageView = findViewById(R.id.buttonAllBookmarksToolbar)
        val buttonNightLight: ImageView = findViewById(R.id.buttonNightDayToolbar)
        val buttonFullScreen: ImageView = findViewById(R.id.buttonFullScreenToolbar)
        val buttonShare: ImageView = findViewById(R.id.buttonShareToolbar)
        val zoomInButton: ImageView = findViewById(R.id.buttonZoomInToolbar)
        val resetZoomButton: TextView = findViewById(R.id.buttonResetZoomToolbar)
        val zoomOutButton: ImageView = findViewById(R.id.buttonZoomOutToolbar)
        /*
        if (isSupportedShareFeature) {
            (buttonNightLight.layoutParams as LinearLayout.LayoutParams).weight = 30F
            (buttonFullScreen.layoutParams as LinearLayout.LayoutParams).weight = 30F
            (buttonShare.layoutParams as LinearLayout.LayoutParams).weight = 30F
        } else {
            (buttonNightLight.layoutParams as LinearLayout.LayoutParams).weight = 45F
            (buttonFullScreen.layoutParams as LinearLayout.LayoutParams).weight = 45F
        }
        findViewById<LinearLayout>(R.id.menuPanelSection1).requestLayout()
        */

        buttonOpen.isGone = false
        buttonAllBookmarks.isGone = false
        zoomInButton.isGone = false
        zoomOutButton.isGone = false
        resetZoomButton.isGone = false

        // Configure after final visibility state to avoid wrong weight distribution on first open.
        configureMenuPanelRowsForOrientation()
    }

    private fun buildMenuVerticalSeparator(): View {
        val separatorWidth = resources.getDimensionPixelSize(R.dimen.menu_panel_vertical_separator_width)
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(separatorWidth, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(resolveThemeColor(com.google.android.material.R.attr.colorSecondary, R.color.light_red))
        }
    }

    private fun resolveThemeColor(attrRes: Int, fallbackColorRes: Int): Int {
        val typedValue = TypedValue()
        if (theme.resolveAttribute(attrRes, typedValue, true)) {
            return if (typedValue.resourceId != 0) {
                ContextCompat.getColor(this, typedValue.resourceId)
            } else {
                typedValue.data
            }
        }
        return ContextCompat.getColor(this, fallbackColorRes)
    }

    private fun applyMenuPanelAdaptiveDimensions() {
        val iconSize = resources.getDimensionPixelSize(R.dimen.menu_panel_icon_size)
        val iconPadding = resources.getDimensionPixelSize(R.dimen.menu_panel_icon_padding)
        val searchIconPadding = resources.getDimensionPixelSize(R.dimen.menu_panel_icon_padding_compact)
        val zoomTextSize = resources.getDimension(R.dimen.menu_panel_zoom_text_size)
        val section6SeparatorWidth = resources.getDimensionPixelSize(R.dimen.menu_panel_vertical_separator_width)

        val iconIds = intArrayOf(
            R.id.buttonDarkFilter,
            R.id.buttonNightDayToolbar,
            R.id.buttonFullScreenToolbar,
            R.id.buttonGetHelpToolbar,
            R.id.buttonOpenToolbar,
            R.id.buttonAllBookmarksToolbar,
            R.id.buttonSelectTextToolbar,
            R.id.buttonShareToolbar,
            R.id.buttonSearchToolbar,
            R.id.buttonZoomOutToolbar,
            R.id.buttonZoomInToolbar,
            R.id.buttonScrollVerticalTopToBottom,
            R.id.buttonScrollVerticalBottomToTop,
            R.id.buttonScrollHorizontalLeftToRight,
            R.id.buttonScrollHorizontalRightToLeft,
            R.id.buttonSinglePage,
            R.id.buttonContinuousPage,
            R.id.buttonRotationToolbar,
            R.id.buttonRotationUnlockedMode
        )

        iconIds.forEach { id ->
            val button = findViewById<ImageView>(id)
            (button.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.width = iconSize
                lp.height = iconSize
                button.layoutParams = lp
            }
            val targetPadding = if (id == R.id.buttonSearchToolbar) searchIconPadding else iconPadding
            button.setPadding(targetPadding, targetPadding, targetPadding, targetPadding)
        }

        findViewById<TextView>(R.id.buttonResetZoomToolbar).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.width = iconSize
                lp.height = iconSize
                layoutParams = lp
            }
            textSize = zoomTextSize / resources.displayMetrics.scaledDensity
        }

        findViewById<View>(R.id.menuPanelSection6VerticalSeparator).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.width = section6SeparatorWidth
                layoutParams = lp
            }
            setBackgroundColor(resolveThemeColor(com.google.android.material.R.attr.colorSecondary, R.color.light_red))
        }
    }

    private fun captureMenuActionDefaultLayoutParamsIfNeeded() {
        if (menuActionDefaultLayoutParams.isNotEmpty()) return

        val actionIds = intArrayOf(
            R.id.buttonDarkFilter,
            R.id.buttonNightDayToolbar,
            R.id.buttonFullScreenToolbar,
            R.id.buttonZoomOutToolbar,
            R.id.buttonResetZoomToolbar,
            R.id.buttonZoomInToolbar,
            R.id.buttonScrollVerticalTopToBottom,
            R.id.buttonScrollVerticalBottomToTop,
            R.id.buttonScrollHorizontalLeftToRight,
            R.id.buttonScrollHorizontalRightToLeft,
            R.id.buttonSinglePage,
            R.id.buttonContinuousPage,
            R.id.buttonRotationToolbar,
            R.id.buttonRotationUnlockedMode
        )

        actionIds.forEach { id ->
            val view = findViewById<View>(id)
            val lp = view.layoutParams as? LinearLayout.LayoutParams ?: return@forEach
            menuActionDefaultLayoutParams[id] = MenuActionLayoutSnapshot(
                width = lp.width,
                height = lp.height,
                weight = lp.weight
            )
        }
    }

    private fun captureMenuSectionDefaultWeightSumIfNeeded() {
        if (menuSectionDefaultWeightSum.isNotEmpty()) return
        val sectionIds = intArrayOf(
            R.id.menuPanelSection1,
            R.id.menuPanelSection3,
            R.id.menuPanelSection5,
            R.id.menuPanelSection6
        )
        sectionIds.forEach { id ->
            menuSectionDefaultWeightSum[id] = findViewById<LinearLayout>(id).weightSum
        }
    }

    private fun safeNormalizedPath(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return getTheFileName(value, 0)
    }

    private fun findStoredFileRecord(
        databaseHandler: DatabaseHandler,
        fileKey: String,
        pathHint: String
    ): FilesModel? {
        val byId = databaseHandler.getFiles(fileKey).firstOrNull()
        if (byId != null) return byId

        val normalizedHint = safeNormalizedPath(pathHint)
        val normalizedCurrent = safeNormalizedPath(fileId)
        val normalizedUri = safeNormalizedPath(uriOpened?.toString())

        return databaseHandler.getFiles().firstOrNull { stored ->
            val storedNorm = safeNormalizedPath(stored.path)
            stored.path == pathHint ||
                stored.path == fileId ||
                stored.path == uriOpened?.toString() ||
                (normalizedHint.isNotBlank() && storedNorm == normalizedHint) ||
                (normalizedCurrent.isNotBlank() && storedNorm == normalizedCurrent) ||
                (normalizedUri.isNotBlank() && storedNorm == normalizedUri)
        }
    }

    private fun normalizeMenuActionLayoutParams(view: View, useFixedActionWidth: Boolean) {
        if (view !is ImageView && view !is TextView) return

        (view.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            if (useFixedActionWidth) {
                val defaults = menuActionDefaultLayoutParams[view.id]
                lp.width = 0
                lp.weight = defaults?.weight?.coerceAtLeast(1F) ?: 1F
            } else {
                val defaults = menuActionDefaultLayoutParams[view.id] ?: return@let
                lp.width = defaults.width
                lp.height = defaults.height
                lp.weight = defaults.weight
            }
            view.layoutParams = lp
        }
    }

    private fun hasVisibleMenuActions(section: LinearLayout): Boolean {
        for (i in 0 until section.childCount) {
            val child = section.getChildAt(i)
            if (!child.isGone && (child is ImageView || child is TextView)) {
                return true
            }
        }
        return false
    }

    private fun normalizeVisibleMenuWeightSum(section: LinearLayout) {
        val visibleWeight = (0 until section.childCount)
            .map { section.getChildAt(it) }
            .filter { child -> !child.isGone }
            .mapNotNull { child ->
                (child.layoutParams as? LinearLayout.LayoutParams)?.weight
            }
            .sum()
        if (visibleWeight > 0F) {
            section.weightSum = visibleWeight
        }
    }

    private fun updateMenuRowAndSeparatorVisibility() {
        val section1: LinearLayout = findViewById(R.id.menuPanelSection1)
        val section2: LinearLayout = findViewById(R.id.menuPanelSection2)
        val section3: LinearLayout = findViewById(R.id.menuPanelSection3)
        val section4: LinearLayout = findViewById(R.id.menuPanelSection4)
        val section5: LinearLayout = findViewById(R.id.menuPanelSection5)
        val section6: LinearLayout = findViewById(R.id.menuPanelSection6)

        val sep1_2: LinearLayout = findViewById(R.id.menuPanelSectionSeparator1_2)
        val sep2_3: LinearLayout = findViewById(R.id.menuPanelSectionSeparator2_3)
        val sep3_4: LinearLayout = findViewById(R.id.menuPanelSectionSeparator3_4)
        val sep4_5: LinearLayout = findViewById(R.id.menuPanelSectionSeparator4_5)
        val sep5_6: LinearLayout = findViewById(R.id.menuPanelSectionSeparator5_6)

        normalizeVisibleMenuWeightSum(section1)
        normalizeVisibleMenuWeightSum(section2)
        normalizeVisibleMenuWeightSum(section3)
        normalizeVisibleMenuWeightSum(section4)
        normalizeVisibleMenuWeightSum(section5)
        normalizeVisibleMenuWeightSum(section6)

        val has1 = hasVisibleMenuActions(section1)
        val has2 = hasVisibleMenuActions(section2)
        val has3 = hasVisibleMenuActions(section3)
        val has4 = hasVisibleMenuActions(section4)
        val has5 = hasVisibleMenuActions(section5)
        val has6 = hasVisibleMenuActions(section6)
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        section4.isGone = !has4

        if (isLandscape) {
            // Landscape has three effective rows: (1+3), 2, (5+6).
            sep1_2.isGone = !(has1 && has2)
            sep2_3.isGone = !(has2 && has5)
            sep3_4.isGone = true
            sep4_5.isGone = true
            sep5_6.isGone = true
        } else {
            sep1_2.isGone = !(has1 && has2)
            sep2_3.isGone = !(has2 && has3)
            val showCollapsed3To5Separator = has3 && !has4 && has5
            sep3_4.isGone = !(showCollapsed3To5Separator || (has3 && has4))
            sep4_5.isGone = !(has4 && has5)
            sep5_6.isGone = !(has5 && has6)
        }
    }

    private fun rebuildRow(
        container: LinearLayout,
        orderedViews: List<View>,
        withSeparatorAfter: Int? = null,
        useFixedActionWidth: Boolean = false
    ) {
        container.removeAllViews()
        orderedViews.forEachIndexed { index, v ->
            (v.parent as? ViewGroup)?.removeView(v)
            normalizeMenuActionLayoutParams(v, useFixedActionWidth)
            container.addView(v)
            if (withSeparatorAfter != null && index == withSeparatorAfter) {
                container.addView(buildMenuVerticalSeparator())
            }
        }

        if (useFixedActionWidth) {
            val dynamicWeightSum = (0 until container.childCount)
                .mapNotNull { idx ->
                    (container.getChildAt(idx).layoutParams as? LinearLayout.LayoutParams)?.weight
                }
                .sum()
            container.weightSum = dynamicWeightSum.coerceAtLeast(1F)
        } else {
            container.weightSum = menuSectionDefaultWeightSum[container.id] ?: container.weightSum
        }
    }

    private fun configureMenuPanelRowsForOrientation() {
        captureMenuActionDefaultLayoutParamsIfNeeded()
        captureMenuSectionDefaultWeightSumIfNeeded()
        applyMenuPanelAdaptiveDimensions()

        val section1: LinearLayout = findViewById(R.id.menuPanelSection1)
        val section3: LinearLayout = findViewById(R.id.menuPanelSection3)
        val section5: LinearLayout = findViewById(R.id.menuPanelSection5)
        val section6: LinearLayout = findViewById(R.id.menuPanelSection6)
        val darkFilter: ImageView = findViewById(R.id.buttonDarkFilter)
        val nightDay: ImageView = findViewById(R.id.buttonNightDayToolbar)
        val fullScreen: ImageView = findViewById(R.id.buttonFullScreenToolbar)
        val zoomOut: ImageView = findViewById(R.id.buttonZoomOutToolbar)
        val zoomReset: TextView = findViewById(R.id.buttonResetZoomToolbar)
        val zoomIn: ImageView = findViewById(R.id.buttonZoomInToolbar)

        val scrollVTop: ImageView = findViewById(R.id.buttonScrollVerticalTopToBottom)
        val scrollVBottom: ImageView = findViewById(R.id.buttonScrollVerticalBottomToTop)
        val scrollHLeft: ImageView = findViewById(R.id.buttonScrollHorizontalLeftToRight)
        val scrollHRight: ImageView = findViewById(R.id.buttonScrollHorizontalRightToLeft)
        val singlePage: ImageView = findViewById(R.id.buttonSinglePage)
        val continuousPage: ImageView = findViewById(R.id.buttonContinuousPage)
        val rotationLocked: ImageView = findViewById(R.id.buttonRotationToolbar)
        val rotationUnlocked: ImageView = findViewById(R.id.buttonRotationUnlockedMode)
        val section6VerticalSep: View = findViewById(R.id.menuPanelSection6VerticalSeparator)

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        if (isLandscape) {
            rebuildRow(
                section1,
                listOf(darkFilter, nightDay, fullScreen, zoomOut, zoomReset, zoomIn),
                withSeparatorAfter = 2,
                useFixedActionWidth = true
            )
            section3.isGone = true

            rebuildRow(
                section5,
                listOf(
                    scrollVTop,
                    scrollVBottom,
                    scrollHLeft,
                    scrollHRight,
                    singlePage,
                    continuousPage,
                    section6VerticalSep,
                    rotationLocked,
                    rotationUnlocked
                ),
                withSeparatorAfter = 3,
                useFixedActionWidth = true
            )
            section6.isGone = true
        } else {
            rebuildRow(section1, listOf(darkFilter, nightDay, fullScreen))
            rebuildRow(section3, listOf(zoomOut, zoomReset, zoomIn))
            section3.isGone = false

            rebuildRow(section5, listOf(scrollVTop, scrollVBottom, scrollHLeft, scrollHRight))
            rebuildRow(section6, listOf(singlePage, continuousPage, section6VerticalSep, rotationLocked, rotationUnlocked))
            section6.isGone = false
        }

        updateMenuRowAndSeparatorVisibility()
    }

    fun hideMenuPanel() {
        val message: ConstraintLayout = findViewById(R.id.messageMenuPanel)
        val arrow: View = findViewById(R.id.arrowMenuPanel)
        message.isGone = true
        arrow.isGone = true

        menuOpened = false
        if (showingTopBar) {
            restartTopBarAutoHideCountdown()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun setupGestures() {
        //conflict with PDFView class
        val toolbarInvisible: View = findViewById(R.id.toolbarInvisible)
        toolbarInvisible.setOnTouchListener(object : OnSwipeTouchListener(this@PDFViewer) {

            override fun onSingleTapUp() {
                val currentPosition1 = pdfViewer.positionOffset
                Handler().postDelayed({
                    val currentPosition2 = pdfViewer.positionOffset
                    showTopBar(x = currentPosition1, y = currentPosition2)
                }, 100)
            }
        })
    }

    fun getBooleanData(variable: String, default: Boolean = false): Boolean {
        return getSharedPreferences(variable, Context.MODE_PRIVATE).getBoolean(
            variable, default
        )
    }

    fun saveBooleanData(variable: String, value: Boolean) {
        getSharedPreferences(variable, Context.MODE_PRIVATE).edit().putBoolean(variable, value)
            .apply()
    }

    @SuppressLint("ClickableViewAccessibility")
    fun setScrollBarSide(animation: Boolean = true) {
        if (isSupportedScrollbarButton) {
            val button: TextView = findViewById(R.id.buttonSideScroll)
            val textPage: TextView = findViewById(R.id.textSideScroll)
            val container: ConstraintLayout = findViewById(R.id.containerSideScroll)
            var touchOffsetY: Float? = null
            var selectedLogicalPage = getCurrentLogicalPage()

            applyVerticalScrollbarThumbLength(button)

            button.setOnTouchListener(View.OnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        selectedLogicalPage = getCurrentLogicalPage()
                        textPage.text = (selectedLogicalPage + 1).toString()
                        touchOffsetY = event.rawY - view.y
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (totalPages <= 1 || maxPositionScrollbar <= 0F) return@OnTouchListener true
                        hideTopBar(fullHiding = false)

                        button.layoutParams.width = 60;
                        applyVerticalScrollbarThumbLength(button)
                        button.isGone = true
                        button.isGone = false
                        val trackMetrics = getVerticalScrollbarTrackMetrics(button)

                        if (touchOffsetY == null) touchOffsetY = event.rawY - view.y
                        val desiredY = event.rawY - (touchOffsetY ?: 0F)
                        val clampedY = desiredY.coerceIn(trackMetrics.start, trackMetrics.end)

                        view.animate().y(clampedY).setDuration(0).start()
                        alignSideScrollLabelToThumb(container, button, clampedY)

                        val progress = ((clampedY - trackMetrics.start) / trackMetrics.travelLength).coerceIn(0F, 1F)
                        val pageN = (progress * (totalPages - 1)).roundToInt()
                            .coerceIn(0, totalPages - 1)
                        val logicalPage = mapViewerPageToLogical(pageN)
                        selectedLogicalPage = logicalPage
                        textPage.text = (logicalPage + 1).toString()
                        container.isGone = false
                    }

                    MotionEvent.ACTION_UP -> {
                        if (totalPages <= 1 || maxPositionScrollbar <= 0F) return@OnTouchListener true
                        button.layoutParams.width = 30;
                        applyVerticalScrollbarThumbLength(button)
                        button.isGone = true
                        button.isGone = false
                        touchOffsetY = null

                        goToPage(selectedLogicalPage, animation)
                        container.isGone = true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        if (totalPages <= 1 || maxPositionScrollbar <= 0F) return@OnTouchListener true
                        button.layoutParams.width = 30;
                        applyVerticalScrollbarThumbLength(button)
                        button.isGone = true
                        button.isGone = false
                        touchOffsetY = null

                        goToPage(selectedLogicalPage, animation)
                        container.isGone = true
                    }
                }

                // required to by-pass lint warning
                view.performClick()
                return@OnTouchListener true
            })

            updateScrollbarButtonsVisibility()
        }
    }

    fun setPositionScrollbarByPage(page: Float, animationDuration: Long = 0) {
        if (isSupportedScrollbarButton) {
            val button: TextView = findViewById(R.id.buttonSideScroll)
            val textPage: TextView = findViewById(R.id.textSideScroll)
            val container: ConstraintLayout = findViewById(R.id.containerSideScroll)
            button.layoutParams.width = 30;
            applyVerticalScrollbarThumbLength(button)
            button.isGone = true
            button.isGone = false
            if (!page.isNaN() && minPositionScrollbar != 0F) {
                if (totalPages <= 1) return
                var pageToUse = 0F
                if (page >= 0 && page <= totalPages) pageToUse = page
                val logicalPageForPosition = (pageToUse.toInt() - 1).coerceIn(0, totalPages - 1)
                val viewerPageForPosition = mapLogicalPageToViewer(logicalPageForPosition) + 1
                val trackMetrics = getVerticalScrollbarTrackMetrics(button)
                var initialPosition =
                    (((viewerPageForPosition - 1) * trackMetrics.travelLength) / (totalPages - 1)) + trackMetrics.start
                if (initialPosition.isNaN()) initialPosition = 0F
                button.animate().y(initialPosition).setDuration(animationDuration).start()
                alignSideScrollLabelToThumb(container, button, initialPosition, animationDuration)
                textPage.text = pageToUse.toInt().toString()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun setScrollBarBottom(animation: Boolean = true) {
        if (isSupportedScrollbarButton) {
            val button: TextView = findViewById(R.id.buttonBottomScroll)
            val textPage: TextView = findViewById(R.id.textBottomScroll)
            val container: ConstraintLayout = findViewById(R.id.containerBottomScroll)
            var touchOffsetX: Float? = null
            var selectedLogicalPage = getCurrentLogicalPage()

            applyHorizontalScrollbarThumbLength(button)

            button.setOnTouchListener(View.OnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        selectedLogicalPage = getCurrentLogicalPage()
                        textPage.text = (selectedLogicalPage + 1).toString()
                        touchOffsetX = event.rawX - view.x
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (totalPages <= 1) return@OnTouchListener true
                        hideTopBar(fullHiding = false)

                        button.layoutParams.height = 60;
                        applyHorizontalScrollbarThumbLength(button)
                        button.isGone = true
                        button.isGone = false
                        val trackMetrics = getHorizontalScrollbarTrackMetrics(button)
                        if (trackMetrics.travelLength <= 0F) return@OnTouchListener true

                        if (touchOffsetX == null) touchOffsetX = event.rawX - view.x
                        val desiredX = event.rawX - (touchOffsetX ?: 0F)
                        val clampedX = desiredX.coerceIn(trackMetrics.start, trackMetrics.end)

                        view.animate().x(clampedX).setDuration(0).start()
                        alignBottomScrollLabelToThumb(container, button, clampedX)

                        val progress = ((clampedX - trackMetrics.start) / trackMetrics.travelLength).coerceIn(0F, 1F)
                        val pageN = (progress * (totalPages - 1)).roundToInt()
                            .coerceIn(0, totalPages - 1)
                        val logicalPage = mapViewerPageToLogical(pageN)
                        selectedLogicalPage = logicalPage
                        textPage.text = (logicalPage + 1).toString()
                        container.isGone = false
                    }

                    MotionEvent.ACTION_UP -> {
                        if (totalPages <= 1) return@OnTouchListener true
                        button.layoutParams.height = 30;
                        applyHorizontalScrollbarThumbLength(button)
                        button.isGone = true
                        button.isGone = false
                        touchOffsetX = null

                        goToPage(selectedLogicalPage, animation)
                        container.isGone = true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        if (totalPages <= 1) return@OnTouchListener true
                        button.layoutParams.height = 30;
                        applyHorizontalScrollbarThumbLength(button)
                        button.isGone = true
                        button.isGone = false
                        touchOffsetX = null

                        goToPage(selectedLogicalPage, animation)
                        container.isGone = true
                    }
                }

                // required to by-pass lint warning
                view.performClick()
                return@OnTouchListener true
            })

            updateScrollbarButtonsVisibility()
        }
    }

    fun setPositionBottomScrollbarByPage(page: Float, animationDuration: Long = 0) {
        if (isSupportedScrollbarButton) {
            val button: TextView = findViewById(R.id.buttonBottomScroll)
            val textPage: TextView = findViewById(R.id.textBottomScroll)
            val container: ConstraintLayout = findViewById(R.id.containerBottomScroll)
            button.layoutParams.height = 30;
            applyHorizontalScrollbarThumbLength(button)
            button.isGone = true
            button.isGone = false
            if (!page.isNaN() && minPositionScrollbarHorizontal != 0F) {
                if (totalPages <= 1) return
                var pageToUse = 0F
                if (page >= 0 && page <= totalPages) pageToUse = page
                val logicalPageForPosition = (pageToUse.toInt() - 1).coerceIn(0, totalPages - 1)
                val viewerPageForPosition = mapLogicalPageToViewer(logicalPageForPosition) + 1
                val trackMetrics = getHorizontalScrollbarTrackMetrics(button)
                if (trackMetrics.travelLength <= 0F) return
                var initialPosition =
                    (((viewerPageForPosition - 1) * trackMetrics.travelLength) / (totalPages - 1)) + trackMetrics.start
                if (initialPosition.isNaN()) initialPosition = 0F
                button.animate().x(initialPosition).setDuration(animationDuration).start()
                alignBottomScrollLabelToThumb(container, button, initialPosition, animationDuration)
                textPage.text = pageToUse.toInt().toString()
            }
        }
    }

    fun openGetHelp() {
        this.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.savpdfviewer.com/help/")
            )
        )
    }

    // ═══════════════════════════════════════════════════════════════
    //  OCR SEARCH
    // ═══════════════════════════════════════════════════════════════

    fun setupSearch() {
        fun updateSearchOptionButtonsUi() {
            val caseSensitiveButton: ImageView = findViewById(R.id.buttonSearchCaseSensitive)
            val wholeWordButton: ImageView = findViewById(R.id.buttonSearchWholeWord)
            caseSensitiveButton.alpha = if (searchCaseSensitive) 1.0f else 0.45f
            wholeWordButton.alpha = if (searchWholeWord) 1.0f else 0.45f
        }

        // Wire the "Search" button in the menu panel
        val buttonSearch: ImageView = findViewById(R.id.buttonSearchToolbar)
        buttonSearch.setOnClickListener {
            showSearchPanel()
            resetHideTopBarCounter()
            hideMenuPanel()
        }
        buttonSearch.setOnLongClickListener {
            showTooltip(R.string.tooltip_search)
            true
        }

        // Wire panel controls
        val buttonClose: ImageView = findViewById(R.id.buttonSearchClose)
        buttonClose.setOnClickListener {
            hideSearchPanel()
            resetHideTopBarCounter()
        }

        val buttonPrev: ImageView = findViewById(R.id.buttonSearchPrev)
        buttonPrev.setOnClickListener {
            navigateSearchResult(forward = false)
            resetHideTopBarCounter()
        }
        buttonPrev.setOnLongClickListener {
            showTooltip(R.string.tooltip_search_prev)
            true
        }

        val buttonNext: ImageView = findViewById(R.id.buttonSearchNext)
        buttonNext.setOnClickListener {
            navigateSearchResult(forward = true)
            resetHideTopBarCounter()
        }
        buttonNext.setOnLongClickListener {
            showTooltip(R.string.tooltip_search_next)
            true
        }

        val buttonCaseSensitive: ImageView = findViewById(R.id.buttonSearchCaseSensitive)
        buttonCaseSensitive.setOnClickListener {
            searchCaseSensitive = !searchCaseSensitive
            updateSearchOptionButtonsUi()
            if (currentSearchQuery.isNotBlank()) {
                triggerSearch()
            }
            resetHideTopBarCounter()
        }
        buttonCaseSensitive.setOnLongClickListener {
            showTooltip(R.string.tooltip_search_case_sensitive)
            true
        }

        val buttonWholeWord: ImageView = findViewById(R.id.buttonSearchWholeWord)
        buttonWholeWord.setOnClickListener {
            searchWholeWord = !searchWholeWord
            updateSearchOptionButtonsUi()
            if (currentSearchQuery.isNotBlank()) {
                triggerSearch()
            }
            resetHideTopBarCounter()
        }
        buttonWholeWord.setOnLongClickListener {
            showTooltip(R.string.tooltip_search_whole_word)
            true
        }

        updateSearchOptionButtonsUi()

        val textbox: EditText = findViewById(R.id.textboxSearch)
        textbox.setOnEditorActionListener { _, _, _ ->
            triggerSearch()
            true
        }
        textbox.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP) {
                triggerSearch()
                true
            } else false
        }

        // Wire OCR engine callbacks
        ocrEngine.onIndexingPage = { page, total ->
            val status: TextView = findViewById(R.id.textSearchStatus)
            status.text = String.format(getString(R.string.search_indexing), page + 1, total)
        }
        ocrEngine.onResults = { results, finished ->
            searchResults = results
            updateSearchStatus(finished)
            // Always redraw so old highlights are cleared when filters change/no results.
            pdfViewer.invalidate()
            // Navigate to first result automatically
            if (results.isNotEmpty() && searchResultIndex == 0) {
                if (getCurrentLogicalPage() != results[0].pageIndex) {
                    goToPage(results[0].pageIndex, true)
                }
            }
        }
    }

    private fun triggerSearch() {
        val textbox: EditText = findViewById(R.id.textboxSearch)
        hideKeyboard(textbox)
        searchResults = emptyList()
        searchResultIndex = 0
        currentSearchQuery = textbox.text.toString().trim()
        // Clear previous highlights immediately before running a filtered search.
        pdfViewer.invalidate()
        val status: TextView = findViewById(R.id.textSearchStatus)
        status.text = String.format(getString(R.string.search_indexing), 0, totalPages)
        ocrEngine.search(currentSearchQuery, currentSearchOptions())
    }

    private fun updateSearchStatus(finished: Boolean) {
        val status: TextView = findViewById(R.id.textSearchStatus)
        when {
            searchResults.isEmpty() && finished ->
                status.text = getString(R.string.search_no_results)

            searchResults.isNotEmpty() -> {
                val display = searchResultIndex + 1
                status.text = String.format(
                    getString(R.string.search_result_status),
                    display,
                    searchResults.size
                )
            }

            else -> { /* keep "indexing…" text */
            }
        }
    }

    private fun navigateSearchResult(forward: Boolean) {
        if (searchResults.isEmpty()) return
        if (forward) {
            searchResultIndex = (searchResultIndex + 1) % searchResults.size
        } else {
            searchResultIndex = (searchResultIndex - 1 + searchResults.size) % searchResults.size
        }
        updateSearchStatus(true)
        val page = searchResults[searchResultIndex].pageIndex
        goToPage(page, true)
        pdfViewer.invalidate() // force redraw to update highlights
    }

    fun showSearchPanel() {
        closeOverlayPanelsExcept(OverlayPanel.SEARCH)
        cancelTopBarAutoHideCountdown()
        val panel: ConstraintLayout = findViewById(R.id.messageSearch)
        panel.isGone = false
        searchPanelVisible = true
        val textbox: EditText = findViewById(R.id.textboxSearch)
        textbox.requestFocus()
        showSoftKeyboard(textbox)
    }

    private fun clearSearchState() {
        currentSearchQuery = ""
        searchResults = emptyList()
        searchResultIndex = 0
        pdfViewer.invalidate() // remove highlights
    }

    fun hideSearchPanel(clearState: Boolean = true) {
        val panel: ConstraintLayout = findViewById(R.id.messageSearch)
        panel.isGone = true
        searchPanelVisible = false
        if (clearState) {
            clearSearchState()
        }
        val textbox: EditText = findViewById(R.id.textboxSearch)
        hideKeyboard(textbox)
        if (showingTopBar) {
            restartTopBarAutoHideCountdown()
        }
    }

    override fun onDestroy() {
        goToUiHandler.removeCallbacks(goToVisibilityRunnable)
        topBarUiHandler.removeCallbacks(topBarAutoHideRunnable)
        scheduleUiHandler.removeCallbacks(scheduledAppearanceRunnable)
        ocrEngine.close()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        reevaluateScheduledAppearance(refreshViewer = totalPages > 0)
        restartScheduledAppearanceChecks()
    }

    override fun onPause() {
        scheduleUiHandler.removeCallbacks(scheduledAppearanceRunnable)
        super.onPause()
    }

    // ═══════════════════════════════════════════════════════════════

    fun zoomIn() {
        textSelectionManager.clearPageGeometryCache()
        if (pdfViewer.zoom <= (10.0F - zoom_value)) pdfViewer.zoomWithAnimation(pdfViewer.zoom + zoom_value)
        setCurrentZoomStatus()
        saveCurrentPdfOptions()
    }

    fun zoomOut() {
        textSelectionManager.clearPageGeometryCache()
        val targetZoom = (pdfViewer.zoom - zoom_value).coerceAtLeast(minZoomForCurrentLayout)
        if (targetZoom < pdfViewer.zoom - 0.001F) {
            pdfViewer.zoomWithAnimation(targetZoom)
        }
        setCurrentZoomStatus()
        saveCurrentPdfOptions()
    }

    fun resetZoom() {
        textSelectionManager.clearPageGeometryCache()
        pdfViewer.resetZoomWithAnimation()
        setCurrentZoomStatus()
        saveCurrentPdfOptions()
    }

    fun setCurrentZoomStatus() {
        val resetZoomButton: TextView = findViewById(R.id.buttonResetZoomToolbar)
        resetZoomButton.text =
            getString(R.string.zoom_status_perc).replace(
                "%d",
                ((pdfViewer.zoom * 100).toInt().toString())
            )
    }

    // ── Select & Copy Text ────────────────────────────────────────────────────

    private fun setupTextSelection() {
        fun updateSelectionGranularityButtonUi(wordButton: ImageView, characterButton: ImageView) {
            val charMode = textSelectionManager.isCharacterSelectionEnabled()
            wordButton.alpha = if (charMode) 0.45f else 1.0f
            characterButton.alpha = if (charMode) 1.0f else 0.45f
            wordButton.contentDescription = getString(R.string.tooltip_selection_mode_word)
            characterButton.contentDescription = getString(R.string.tooltip_selection_mode_character)
        }

        // Build the GestureDetector that fires onLongPress on the PDFView
        selectionGestureDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean {
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    if (!isSelectionGestureEligible(e.rawX, e.rawY)) return

                    val pressX = if (selectionDownViewX.isNaN()) e.x else selectionDownViewX
                    val pressY = if (selectionDownViewY.isNaN()) e.y else selectionDownViewY
                    val hit = textSelectionManager.viewToPage(pressX, pressY, pdfViewer.currentPage)
                    if (hit != null) {
                        val (page, normX, normY) = hit
                        val selectedImmediately = textSelectionManager.selectWordAt(normX, normY, page)
                        if (!selectedImmediately && !textSelectionManager.hasPendingSelection()) {
                            return
                        }
                        selectionDragState = SelectionDragState.NONE
                        selectionDragPage = page
                        // Haptic feedback
                        pdfViewer.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        // Cancel PDFView's ongoing scroll gesture
                        val cancel = MotionEvent.obtain(
                            e.downTime, e.eventTime, MotionEvent.ACTION_CANCEL, pressX, pressY, 0
                        )
                        pdfViewer.onTouchEvent(cancel)
                        cancel.recycle()
                        if (selectedImmediately) {
                            showSelectionPanel()
                        }
                        pdfViewer.invalidate()
                    }
                }
            })

        // OCR page-indexed callback: refresh selection when words become available
        ocrEngine.onPageIndexed = { _ ->
            if (textSelectionManager.active) {
                textSelectionManager.refreshSelection()
                pdfViewer.post {
                    if (textSelectionManager.selectedWords.isNotEmpty()) {
                        showSelectionPanel()
                    }
                    pdfViewer.invalidate()
                }
            }
        }

        // Wire panel buttons
        val copyBtn  = findViewById<ImageView>(R.id.buttonCopySelection)
        val closeBtn = findViewById<android.widget.ImageView>(R.id.buttonCloseSelectionMode)
        val wordModeBtn = findViewById<ImageView>(R.id.buttonSelectionWordMode)
        val characterModeBtn = findViewById<ImageView>(R.id.buttonSelectionCharacterMode)

        updateSelectionGranularityButtonUi(wordModeBtn, characterModeBtn)
        wordModeBtn.setOnClickListener {
            textSelectionManager.setCharacterSelectionEnabled(false)
            updateSelectionGranularityButtonUi(wordModeBtn, characterModeBtn)
            pdfViewer.invalidate()
            resetHideTopBarCounter()
        }
        characterModeBtn.setOnClickListener {
            textSelectionManager.setCharacterSelectionEnabled(true)
            updateSelectionGranularityButtonUi(wordModeBtn, characterModeBtn)
            pdfViewer.invalidate()
            resetHideTopBarCounter()
        }

        copyBtn.setOnClickListener {
            if (textSelectionManager.selectedWords.isNotEmpty()) {
                textSelectionManager.copySelectedText()
            } else {
                Toast.makeText(this, getString(R.string.select_text_no_text), Toast.LENGTH_SHORT).show()
            }
            hideSelectionPanel()
        }
        closeBtn.setOnClickListener {
            hideSelectionPanel()
        }
    }

    private fun isSelectionGestureEligible(rawX: Float, rawY: Float): Boolean {
        if (!findViewById<View>(R.id.messageMenuPanel).isGone) return false
        if (!findViewById<View>(R.id.messageGoTo).isGone) return false
        if (!findViewById<View>(R.id.messageSearch).isGone) return false
        if (!findViewById<View>(R.id.messagePassword).isGone) return false

        if (isPointInsideVisibleView(findViewById(R.id.toolbarContainer), rawX, rawY)) return false
        if (isPointInsideVisibleView(findViewById(R.id.textSelectionBar), rawX, rawY)) return false

        return true
    }

    private fun isPointInsideVisibleView(view: View?, rawX: Float, rawY: Float): Boolean {
        if (view == null || view.isGone) return false
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return rawX >= location[0] && rawX <= location[0] + view.width &&
            rawY >= location[1] && rawY <= location[1] + view.height
    }

    private fun showSelectionPanel() {
        val bar = findViewById<android.widget.LinearLayout>(R.id.textSelectionBar)
        bar.visibility = View.VISIBLE
        closeOverlayPanelsExcept(OverlayPanel.SELECTION)
    }

    private fun closeOverlayPanelsExcept(panelToKeep: OverlayPanel) {
        if (panelToKeep != OverlayPanel.GOTO) {
            hideGoToDialog()
        }
        if (panelToKeep != OverlayPanel.SEARCH) {
            hideSearchPanel(clearState = false)
        }
        if (panelToKeep != OverlayPanel.MENU) {
            hideMenuPanel()
        }
        if (panelToKeep != OverlayPanel.SELECTION) {
            hideSelectionPanel()
        }
    }

    fun hideSelectionPanel() {
        val bar = findViewById<android.widget.LinearLayout>(R.id.textSelectionBar)
        bar.visibility = View.GONE
        selectionDragState = SelectionDragState.NONE
        textSelectionManager.deactivate()
        pdfViewer.invalidate()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val loc = IntArray(2)
        pdfViewer.getLocationOnScreen(loc)
        val onPdf = event.rawX >= loc[0] && event.rawX <= loc[0] + pdfViewer.width &&
                    event.rawY >= loc[1] && event.rawY <= loc[1] + pdfViewer.height

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            selectionDownViewX = event.rawX - loc[0]
            selectionDownViewY = event.rawY - loc[1]
        }

        if (selectionDragState == SelectionDragState.NONE &&
            textSelectionManager.active &&
            textSelectionManager.selectedWords.isNotEmpty() &&
            event.actionMasked == MotionEvent.ACTION_DOWN
        ) {
            when (textSelectionManager.findHandleHit(event.rawX, event.rawY)) {
                1 -> {
                    selectionDragState = SelectionDragState.START_HANDLE
                    return true
                }
                2 -> {
                    selectionDragState = SelectionDragState.END_HANDLE
                    return true
                }
            }
        }

        if (onPdf && selectionDragState == SelectionDragState.NONE && isSelectionGestureEligible(event.rawX, event.rawY)) {
            selectionGestureDetector.onTouchEvent(event)
        }

        when (selectionDragState) {
            SelectionDragState.RUBBER_BAND -> {
                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        val pdfX = event.rawX - loc[0]
                        val pdfY = event.rawY - loc[1]
                        val hit  = textSelectionManager.viewToPage(pdfX, pdfY, selectionDragPage)
                        if (hit != null) {
                            textSelectionManager.onMove(hit.second, hit.third, hit.first)
                            pdfViewer.invalidate()
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        textSelectionManager.onUp()
                        selectionDragState = SelectionDragState.NONE
                        pdfViewer.invalidate()
                        return true
                    }
                }
            }
            SelectionDragState.START_HANDLE -> {
                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        val pdfX = event.rawX - loc[0]
                        val pdfY = event.rawY - loc[1]
                        val hit = textSelectionManager.viewToPage(pdfX, pdfY, pdfViewer.currentPage)
                            ?: run {
                                val fallbackPage = textSelectionManager.getSelectionViewerPage()
                                textSelectionManager.viewToPageClamped(pdfX, pdfY, fallbackPage)
                            }
                        if (hit != null) {
                            textSelectionManager.moveStartHandle(hit.second, hit.third, hit.first)
                            pdfViewer.invalidate()
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        selectionDragState = SelectionDragState.NONE
                        selectionDownViewX = Float.NaN
                        selectionDownViewY = Float.NaN
                        return true
                    }
                }
            }
            SelectionDragState.END_HANDLE -> {
                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        val pdfX = event.rawX - loc[0]
                        val pdfY = event.rawY - loc[1]
                        val hit = textSelectionManager.viewToPage(pdfX, pdfY, pdfViewer.currentPage)
                            ?: run {
                                val fallbackPage = textSelectionManager.getSelectionViewerPage()
                                textSelectionManager.viewToPageClamped(pdfX, pdfY, fallbackPage)
                            }
                        if (hit != null) {
                            textSelectionManager.moveEndHandle(hit.second, hit.third, hit.first)
                            pdfViewer.invalidate()
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        selectionDragState = SelectionDragState.NONE
                        selectionDownViewX = Float.NaN
                        selectionDownViewY = Float.NaN
                        return true
                    }
                }
            }
            SelectionDragState.NONE -> Unit
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            selectionDownViewX = Float.NaN
            selectionDownViewY = Float.NaN
        }
        return super.dispatchTouchEvent(event)
    }
}
