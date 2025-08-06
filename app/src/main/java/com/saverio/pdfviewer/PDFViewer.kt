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
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.link.DefaultLinkHandler
import com.github.barteksc.pdfviewer.listener.OnErrorListener
import com.google.android.material.bottomsheet.BottomSheetDialog
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


class PDFViewer : AppCompatActivity() {
    lateinit var pdfViewer: PDFView
    val PDF_SELECTION_CODE = 100

    var fileOpened: String? = ""
    var fileId: String = ""
    var uriOpened: Uri? = null

    val timesAfterOpenReviewMessage = 500
    val timesAfterShowFollowApp = 5
    val timesAfterLiberaPay = 5000

    var isFullscreenEnabled = false
    var showingTopBar = true
    var menuOpened = false

    var isSupportedShareFeature = false
    var isSupportedGoTop = true
    var isSupportedScrollbarButton = true

    var passwordRequired = false
    var passwordToUse = ""

    var totalPages = 0
    var savedCurrentPageOld = 0
    var savedCurrentPage = 0

    var horizontal = false
    var single_page = false
    var night_mode = false
    var rotation_locked = false

    var zoom_value = 0.2F

    var hideTopBarCounter = 0
    var dialog: BottomSheetDialog? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_viewer)

        pdfViewer = findViewById(R.id.pdfView)
        var uriToUse: String? = ""

        val parameters = intent.extras
        if (parameters != null) uriToUse = parameters.getString("uri", "")

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
        backButton.setOnClickListener {
            resetHideTopBarCounter()
            finish()
        }
        backButton.setOnLongClickListener {
            showTooltip(R.string.tooltip_close_app)
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
            setRotationLock()
            resetHideTopBarCounter()
            hideMenuPanel()
        }
        rotationButton.setOnLongClickListener {
            if (rotation_locked) {
                showTooltip(R.string.tooltip_unlock_rotation)
            } else {
                showTooltip(R.string.tooltip_lock_rotation)
            }
            true
        }

        val fullScreenButton: ImageView = findViewById(R.id.buttonFullScreenToolbar)
        fullScreenButton.setOnClickListener {
            setFullscreenButton(fullScreenButton)
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
            pdfViewer.jumpTo(0, true)
            resetHideTopBarCounter()
        }
        goTopButton.setOnLongClickListener {
            showTooltip(R.string.tooltip_go_to_top)
            true
        }

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

        val lightButton: ImageView = findViewById(R.id.buttonNightDayToolbar)
        val comfortView: View = findViewById(R.id.nightThemeBackground)
        lightButton.setOnClickListener {
            if (!comfortView.isGone) {
                comfortView.isGone = true
                lightButton.setImageResource(R.drawable.ic_light_on)
            } else {
                comfortView.isGone = false
                lightButton.setImageResource(R.drawable.ic_light_off)
            }
            resetHideTopBarCounter()
            hideMenuPanel()
        }
        lightButton.setOnLongClickListener {
            if (comfortView.isGone) showTooltip(R.string.tooltip_night_light_on)
            else showTooltip(R.string.tooltip_night_light_off)
            true
        }
        lightButton.isGone = false


        val buttonSinglePage: ImageView = findViewById(R.id.buttonSinglePage)
        buttonSinglePage.setOnClickListener {
            if (!single_page) {
                single_page = true
                pdfViewer.setPageSnap(true)
                pdfViewer.setPageFling(true)
                buttonSinglePage.setImageResource(R.drawable.ic_single_page_disabled)
            } else {
                single_page = false
                pdfViewer.setPageSnap(false)
                pdfViewer.setPageFling(false)
                buttonSinglePage.setImageResource(R.drawable.ic_single_page)
            }
            resetHideTopBarCounter()
            hideMenuPanel()
            selectPdfFromURI(uriOpened)
        }
        buttonSinglePage.setOnLongClickListener {
            if (!single_page) showTooltip(R.string.tooltip_single_page_scroll)
            else showTooltip(R.string.tooltip_single_page_scroll_disabled)
            true
        }
        buttonSinglePage.isGone = false

        val buttonDarkFilter: ImageView = findViewById(R.id.buttonDarkFilter)
        buttonDarkFilter.setOnClickListener {
            if (!night_mode) {
                night_mode = true
                pdfViewer.setNightMode(true)
                pdfViewer.jumpTo(pdfViewer.currentPage, true)
                buttonDarkFilter.setImageResource(R.drawable.ic_dark_filter_disabled)
                pdfViewer.setBackgroundResource(R.color.spacingPageDark)
            } else {
                night_mode = false
                pdfViewer.setNightMode(false)
                pdfViewer.jumpTo(pdfViewer.currentPage, true)
                buttonDarkFilter.setImageResource(R.drawable.ic_dark_filter)
                pdfViewer.setBackgroundResource(R.color.spacingPage)
            }
            resetHideTopBarCounter()
            hideMenuPanel()
        }
        buttonDarkFilter.setOnLongClickListener {
            if (!night_mode) showTooltip(R.string.tooltip_force_dark_filter)
            else showTooltip(R.string.tooltip_force_dark_filter_disable)
            true
        }
        buttonDarkFilter.isGone = false

        val buttonHorizontallyScroll: ImageView = findViewById(R.id.buttonHorizontallyScroll)
        buttonHorizontallyScroll.setOnClickListener {
            if (!horizontal) {
                horizontal = true
                buttonHorizontallyScroll.setImageResource(R.drawable.ic_scroll_vertically)
            } else {
                horizontal = false
                buttonHorizontallyScroll.setImageResource(R.drawable.ic_scroll_horizontally)
            }
            resetHideTopBarCounter()
            hideMenuPanel()
            selectPdfFromURI(uriOpened)
        }
        buttonHorizontallyScroll.setOnLongClickListener {
            if (!horizontal) showTooltip(R.string.tooltip_scroll_horizontally)
            else showTooltip(R.string.tooltip_scroll_vertically)
            true
        }
        buttonHorizontallyScroll.isGone = false

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

        setupGestures()
    }

    private fun showTooltip(string: Int) {
        Toast.makeText(this, string, Toast.LENGTH_LONG).show()
    }

    private fun openFromStorage(uri: Uri? = null) {
        if (uri == null) selectPdfFromStorage()
        else selectPdfFromURI(uri)
    }

    private fun selectPdfFromStorage() {
        val browserStorage = Intent(Intent.ACTION_GET_CONTENT)
        browserStorage.type = "application/pdf"
        browserStorage.addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(
            Intent.createChooser(browserStorage, getString(R.string.select_file_intent)),
            PDF_SELECTION_CODE
        )
    }

    fun incrementHideTopBarCounter() {
        Handler().postDelayed({
            hideTopBarCounter++
            incrementHideTopBarCounter()
            if (hideTopBarCounter >= 5) {
                if (!menuOpened) {
                    hideTopBar(fullHiding = true)
                }
            }
        }, 1000)
    }

    fun resetHideTopBarCounter() {
        hideTopBarCounter = 0
    }

    fun selectPdfFromURI(uri: Uri?) {
        try {
            //Toast.makeText(this, fileOpened, Toast.LENGTH_LONG).show()
            //Toast.makeText(this, uri.toString(), Toast.LENGTH_LONG).show()
            incrementHideTopBarCounter()
            var lastPosition = 0
            fileId = fileOpened!!.toString()
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
                        updatePdfPage(fileId, page)
                        //setPositionScrollbarByPage(page.toFloat())
                    }
                }
                .onPageScroll { page, positionOffset ->
                    hideTopBarCounter = 0
                    if (!showingTopBar && (page > 0 || positionOffset > 0F)) {
                        hideTopBar()
                    } else if (positionOffset == 0F) {
                        showTopBar()
                        findViewById<ImageView>(R.id.buttonGoTopToolbar).isGone = true
                    } else {
                        showingTopBar = false
                    }
                    hideGoToDialog()
                    hideMenuPanel()

                    val buttonSideScroll: TextView = findViewById(R.id.buttonSideScroll)
                    val buttonBottomScroll: TextView = findViewById(R.id.buttonBottomScroll)
                    if (totalPages > 1) {
                        if (horizontal) {
                            buttonSideScroll.isGone = true
                            buttonBottomScroll.isGone = false
                        } else {
                            buttonSideScroll.isGone = false
                            buttonBottomScroll.isGone = true
                        }
                    } else {
                        buttonSideScroll.isGone = true
                        buttonBottomScroll.isGone = true
                    }
                }
                .onDraw { canvas, pageWidth, pageHeight, displayedPage ->
                    /*if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        canvas.drawColor(Color.argb(0.4f, 0f, 0f, 0f))
                    }*/
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

                    val buttonSideScroll: TextView = findViewById(R.id.buttonSideScroll)
                    val buttonBottomScroll: TextView = findViewById(R.id.buttonBottomScroll)

                    val nowLandscape = resources.configuration.orientation

                    val residualView: View = findViewById(R.id.residualView)
                    val fullView: View = findViewById(R.id.fullView)
                    var currentStatus = "landscape"
                    val toAddOrRemove = fullView.measuredHeight - residualView.measuredHeight
                    if (nowLandscape == Configuration.ORIENTATION_LANDSCAPE) {
                        currentStatus = "landscape"

                        if (!residualViewConfigurationConfigurated) {
                            residualViewConfiguration["landscape"] =
                                hashMapOf(
                                    "width" to residualView.measuredWidth, //run in landscape, currently in landscape (bottomScroll)
                                    "height" to residualView.measuredHeight //run in landscape, currently in landscape (sideScroll)
                                )
                            residualViewConfiguration["portrait"] =
                                hashMapOf(
                                    "width" to residualView.measuredHeight + toAddOrRemove * (3 / 2),
                                    "height" to residualView.measuredWidth - toAddOrRemove * 2 //run in landscape, currently in portrait (sideScroll)
                                )
                            residualViewConfigurationConfigurated = true;
                        }
                    } else {
                        currentStatus = "portrait"

                        if (!residualViewConfigurationConfigurated) {
                            residualViewConfiguration["landscape"] =
                                hashMapOf(
                                    "width" to residualView.measuredHeight + toAddOrRemove, //run in portrait, currently in landscape (bottomScroll)
                                    "height" to residualView.measuredWidth - toAddOrRemove * 2 //run in portrait, currently in landscape (sideScroll)
                                )
                            residualViewConfiguration["portrait"] =
                                hashMapOf(
                                    "width" to residualView.measuredWidth - toAddOrRemove / 2, //run in portrait, currently in portrait (bottomScroll)
                                    "height" to residualView.measuredHeight //run in portrait, currently in portrait (sideScroll)
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

                    residualView.isGone = true
                    fullView.isGone = true

                    updatePdfPage(fileId, lastPosition)
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
                    pdfViewer.fitToWidth(0)
                    pdfViewer.jumpTo(lastPosition, false)
                    if (lastPosition.toString() == "0") {
                        showTopBar(showGoTop = false)
                    } else {
                        hideTopBar()
                    }

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
                    }
                    //PdfPasswordException
                }).load()
        } catch (e: Exception) {
            println("Exception 1")
        }
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

        hideMenuPanel()


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
            pdfViewer.fitToWidth(savedCurrentPage)
            val buttonSideScroll: TextView = findViewById(R.id.buttonSideScroll)
            val buttonBottomScroll: TextView = findViewById(R.id.buttonBottomScroll)
            val residualView: View = findViewById(R.id.residualView)
            val fullView: View = findViewById(R.id.fullView)
            if (minPositionScrollbar == 0F) minPositionScrollbar = buttonSideScroll.y
            maxPositionScrollbar =
                residualViewConfiguration[currentStatus]!!["height"]!!.toInt() - minPositionScrollbar
            startY = minPositionScrollbar

            if (minPositionScrollbarHorizontal == 0F) minPositionScrollbarHorizontal =
                buttonBottomScroll.x
            maxPositionScrollbarHorizontal =
                residualViewConfiguration[currentStatus]!!["width"]!!.toInt() - minPositionScrollbarHorizontal
            startX = minPositionScrollbarHorizontal

            residualView.isGone = true
            fullView.isGone = true

            setScrollBarSide()
            setScrollBarBottom()

            //restore the visited page
            goToPage(savedPageToUse, animation = true)
            pdfViewer.isEnabled = true
        }, 100)

        pdfViewer.isEnabled = false

        if (pdfViewer.currentPage == 0) showTopBar(showGoTop = false)
        else hideTopBar()

        super.onConfigurationChanged(newConfig)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PDF_SELECTION_CODE && resultCode == Activity.RESULT_OK && data != null) {
            try {
                val selectedPdf = data.data


                val shareButton: ImageView = findViewById(R.id.buttonShareToolbar)
                val fullscreenButton: ImageView = findViewById(R.id.buttonFullScreenToolbar)
                val goTopButton: ImageView = findViewById(R.id.buttonGoTopToolbar)
                val openButton: ImageView = findViewById(R.id.buttonOpenToolbar)
                val menuButton: ImageView = findViewById(R.id.buttonMenuToolbar)
                val bookmarkButton: ImageView = findViewById(R.id.buttonBookmarkToolbar)
                val zoomInButton: ImageView = findViewById(R.id.buttonZoomInToolbar)
                val resetZoomButton: TextView = findViewById(R.id.buttonResetZoomToolbar)
                val zoomOutButton: ImageView = findViewById(R.id.buttonZoomOutToolbar)
                val rotationButton: ImageView = findViewById(R.id.buttonRotationToolbar)
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

                if (rotation_locked) {
                    rotationButton.setImageResource(R.drawable.ic_rotation_unlocked)
                } else {
                    rotationButton.setImageResource(R.drawable.ic_rotation_locked)
                }

                //setTitle(getTheFileName(selectedPdf.toString(), -1))

                selectPdfFromURI(selectedPdf)
            } catch (e: Exception) {
                println("Exception 4: Loading failed")
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
        if (databaseHandler.checkFile(id = pathNameTemp)) {
            //already exists -> update
            val file = databaseHandler.getFiles(id = pathNameTemp)[0]
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
            updateButtonBookmark(pathName, pdfViewer.currentPage)
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
        val rotationButton: ImageView = findViewById(R.id.buttonRotationToolbar)
        if (rotation_locked) {
            //unlock rotation
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            rotationButton.setImageResource(R.drawable.ic_rotation_locked)
            rotation_locked = false
        } else {
            //lock rotation
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            rotationButton.setImageResource(R.drawable.ic_rotation_unlocked)
            rotation_locked = true
        }
    }

    fun setFullscreenButton(button: ImageView) {
        showingTopBar = true
        if (!isFullscreenEnabled) {
            //show fullscreen
            getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
            )
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
            button.setImageResource(R.drawable.ic_exit_fullscreen)
            isFullscreenEnabled = true
        } else {
            //hide fullscreen
            getWindow().clearFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            button.setImageResource(R.drawable.ic_fullscreen)
            isFullscreenEnabled = false
        }
    }

    fun showTopBar(showGoTop: Boolean = true, x: Float = 0F, y: Float = 0F) {
        val toolbar: View = findViewById(R.id.toolbar)
        val toolbarInvisible: View = findViewById(R.id.toolbarInvisible)
        val buttonClose: ImageView = findViewById(R.id.buttonGoBackToolbar)
        val buttonShare: ImageView = findViewById(R.id.buttonShareToolbar)
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
            hideTopBarCounter = 0

            toolbar.isGone = false
            buttonClose.isGone = false
            if (isSupportedShareFeature) buttonShare.isGone = false
            buttonFullscreen.isGone = false
            if (isSupportedGoTop && showGoTop && pdfViewer.currentPage > 0) buttonGoTop.isGone =
                false
            buttonOpen.isGone = false
            buttonMenu.isGone = false
            buttonNightDay.isGone = false
            buttonBookmark.isGone = false

            currentPage.isGone = false
            currentPage.setTextColor(ContextCompat.getColor(applicationContext, R.color.white))
            toolbarInvisible.isGone = true
            if (isSupportedScrollbarButton) {
                if (horizontal) {
                    buttonSideScroll.isGone = true
                    buttonBottomScroll.isGone = false
                } else {
                    buttonSideScroll.isGone = false
                    buttonBottomScroll.isGone = true
                }
            }

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
            val message: ConstraintLayout = findViewById(R.id.messageGuide1)
            val messageGoTo: ConstraintLayout = findViewById(R.id.messageGoTo)
            val menuPanel: ConstraintLayout = findViewById(R.id.messageMenuPanel)
            val toolbar: View = findViewById(R.id.toolbar)
            val toolbarInvisible: View = findViewById(R.id.toolbarInvisible)
            val buttonClose: ImageView = findViewById(R.id.buttonGoBackToolbar)
            val buttonShare: ImageView = findViewById(R.id.buttonShareToolbar)
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
            if (pdfViewer.currentPage == 0) showTopBar(showGoTop = false)
            else showTopBar()

            hideMessageGuide1()
            hideMenuPanel()

            val buttonHide: ImageView = findViewById(R.id.buttonHideMessageGoTo)
            val textAllPages: TextView = findViewById(R.id.textAllPagesGoTo)
            val textbox: EditText = findViewById(R.id.textboxGoTo)
            val buttonGoTo: TextView = findViewById(R.id.buttonGoTo)

            textAllPages.text = "/ $totalPages"
            textbox.setText((pdfViewer.currentPage + 1).toString())

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
        var valueToGo = pdfViewer.currentPage + 1

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
        pdfViewer.jumpTo(valueToGo, true)
        if (dialog != null) dialog!!.dismiss()
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
        hideGoToDialog()

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
    }

    fun hideMenuPanel() {
        val message: ConstraintLayout = findViewById(R.id.messageMenuPanel)
        val arrow: View = findViewById(R.id.arrowMenuPanel)
        message.isGone = true
        arrow.isGone = true

        menuOpened = false
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
            var startY_moving: Float? = null
            var scrolled: Float = 0F

            button.setOnTouchListener(View.OnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        //
                    }

                    MotionEvent.ACTION_MOVE -> {
                        resetHideTopBarCounter()

                        button.layoutParams.width = 60;
                        button.isGone = true
                        button.isGone = false
                        // get the new co-ordinate of X-axis
                        if (startY_moving == null) startY_moving = event.rawY - startY
                        val newY = event.rawY - startY
                        scrolled = newY - minPositionScrollbar
                        if (scrolled < 0F) scrolled = 0F
                        else if (scrolled > (maxPositionScrollbar)) scrolled = maxPositionScrollbar

                        //println(scrolled)
                        if (newY >= minPositionScrollbar && newY <= (maxPositionScrollbar + minPositionScrollbar)) {
                            view.animate().y(newY).setDuration(0).start()
                            container.animate().y(newY).setDuration(0).start()
                        } else if (newY < minPositionScrollbar) {
                            view.animate().y(minPositionScrollbar).setDuration(0).start()
                            container.animate().y(minPositionScrollbar).setDuration(0).start()
                        } else {
                            //newY > maxPosition
                            view.animate().y(maxPositionScrollbar + minPositionScrollbar)
                                .setDuration(0)
                                .start()
                            container.animate().y(maxPositionScrollbar + minPositionScrollbar)
                                .setDuration(0)
                                .start()
                        }
                        val pageN = ((totalPages - 1) * scrolled) / maxPositionScrollbar
                        textPage.text = (pageN.toInt() + 1).toString()
                        container.isGone = false
                        //goToPage(pageN.toInt(), false)
                    }

                    MotionEvent.ACTION_UP -> {
                        button.layoutParams.width = 30;
                        button.isGone = true
                        button.isGone = false
                        startY_moving = null

                        val pageN = ((totalPages - 1) * scrolled) / maxPositionScrollbar

                        goToPage(pageN.toInt(), animation)
                        container.isGone = true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        //TODO: improve this code -- It's equals to the ACTION_UP
                        button.layoutParams.width = 30;
                        button.isGone = true
                        button.isGone = false
                        startY_moving = null

                        val pageN = ((totalPages - 1) * scrolled) / maxPositionScrollbar

                        goToPage(pageN.toInt(), animation)
                        container.isGone = true
                    }
                }

                // required to by-pass lint warning
                view.performClick()
                return@OnTouchListener true
            })

            if (!horizontal && totalPages > 1) button.isGone = false
            else button.isGone = true
        }
    }

    fun setPositionScrollbarByPage(page: Float, animationDuration: Long = 0) {
        if (isSupportedScrollbarButton) {
            val button: TextView = findViewById(R.id.buttonSideScroll)
            val textPage: TextView = findViewById(R.id.textSideScroll)
            val container: ConstraintLayout = findViewById(R.id.containerSideScroll)
            button.layoutParams.width = 30;
            button.isGone = true
            button.isGone = false
            if (!page.isNaN() && minPositionScrollbar != 0F) {
                var pageToUse = 0F
                if (page >= 0 && page <= totalPages) pageToUse = page
                var initialPosition =
                    (((pageToUse - 1) * maxPositionScrollbar) / (totalPages - 1)) + minPositionScrollbar
                if (initialPosition.isNaN()) initialPosition = 0F
                button.animate().y(initialPosition).setDuration(animationDuration).start()
                container.animate().y(initialPosition).setDuration(animationDuration).start()
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
            var startX_moving: Float? = null
            var scrolled: Float = 0F

            button.setOnTouchListener(View.OnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        //
                    }

                    MotionEvent.ACTION_MOVE -> {
                        resetHideTopBarCounter()

                        button.layoutParams.height = 60;
                        button.isGone = true
                        button.isGone = false
                        // get the new co-ordinate of X-axis
                        if (startX_moving == null) startX_moving =
                            event.rawX - startX - button.width
                        val newX = event.rawX - startX - button.width
                        scrolled = newX - minPositionScrollbarHorizontal
                        if (scrolled < 0F) scrolled = 0F
                        else if (scrolled > (maxPositionScrollbarHorizontal - startX)) scrolled =
                            maxPositionScrollbarHorizontal - startX

                        //println(scrolled)
                        if (newX >= minPositionScrollbarHorizontal && newX <= ((maxPositionScrollbarHorizontal - startX) + minPositionScrollbarHorizontal)) {
                            view.animate().x(newX).setDuration(0).start()
                            container.animate().x(newX).setDuration(0).start()
                        } else if (newX < minPositionScrollbarHorizontal) {
                            view.animate().x(minPositionScrollbarHorizontal).setDuration(0).start()
                            container.animate().x(minPositionScrollbarHorizontal).setDuration(0)
                                .start()
                        } else {
                            //newX > maxPosition
                            view.animate()
                                .x((maxPositionScrollbarHorizontal - startX) + minPositionScrollbarHorizontal)
                                .setDuration(0)
                                .start()
                            container.animate()
                                .x((maxPositionScrollbarHorizontal - startX) + minPositionScrollbarHorizontal)
                                .setDuration(0)
                                .start()
                        }
                        var pageN = ((totalPages + 1) * scrolled) / maxPositionScrollbarHorizontal
                        if (pageN > totalPages) pageN = totalPages.toFloat() - 1
                        textPage.text = (pageN.toInt() + 1).toString()
                        container.isGone = false
                        //goToPage(pageN.toInt(), false)
                    }

                    MotionEvent.ACTION_UP -> {
                        button.layoutParams.height = 30;
                        button.isGone = true
                        button.isGone = false
                        startX_moving = null

                        val pageN = ((totalPages + 1) * scrolled) / maxPositionScrollbarHorizontal

                        goToPage(pageN.toInt(), animation)
                        container.isGone = true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        //TODO: improve this code -- It's equals to the ACTION_UP
                        button.layoutParams.height = 30;
                        button.isGone = true
                        button.isGone = false
                        startX_moving = null

                        val pageN = ((totalPages + 1) * scrolled) / maxPositionScrollbarHorizontal

                        goToPage(pageN.toInt(), animation)
                        container.isGone = true
                    }
                }

                // required to by-pass lint warning
                view.performClick()
                return@OnTouchListener true
            })

            if (horizontal && totalPages > 1) button.isGone = false
            else button.isGone = true
        }
    }

    fun setPositionBottomScrollbarByPage(page: Float, animationDuration: Long = 0) {
        if (isSupportedScrollbarButton) {
            val button: TextView = findViewById(R.id.buttonBottomScroll)
            val textPage: TextView = findViewById(R.id.textBottomScroll)
            val container: ConstraintLayout = findViewById(R.id.containerBottomScroll)
            button.layoutParams.height = 30;
            button.isGone = true
            button.isGone = false
            if (!page.isNaN() && minPositionScrollbarHorizontal != 0F) {
                var pageToUse = 0F
                if (page >= 0 && page <= totalPages) pageToUse = page
                var initialPosition =
                    (((pageToUse - 1) * maxPositionScrollbarHorizontal) / (totalPages + 1)) + minPositionScrollbarHorizontal
                if (initialPosition.isNaN()) initialPosition = 0F
                button.animate().x(initialPosition).setDuration(animationDuration).start()
                container.animate().x(initialPosition).setDuration(animationDuration).start()
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

    fun zoomIn() {
        if (pdfViewer.zoom <= (10.0F - zoom_value)) pdfViewer.zoomWithAnimation(pdfViewer.zoom + zoom_value)
        setCurrentZoomStatus()
    }

    fun zoomOut() {
        if (pdfViewer.zoom >= (0.0F + zoom_value)) pdfViewer.zoomWithAnimation(pdfViewer.zoom - zoom_value)
        setCurrentZoomStatus()
    }

    fun resetZoom() {
        pdfViewer.resetZoomWithAnimation()
        setCurrentZoomStatus()
    }

    fun setCurrentZoomStatus() {
        val resetZoomButton: TextView = findViewById(R.id.buttonResetZoomToolbar)
        resetZoomButton.text =
            getString(R.string.zoom_status_perc).replace(
                "%d",
                ((pdfViewer.zoom * 100).toInt().toString())
            )
    }
}
