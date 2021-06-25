package com.saverio.pdfviewer

import RealPathUtil
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.widget.addTextChangedListener
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.listener.OnErrorListener
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*


class PDFViewer : AppCompatActivity() {
    lateinit var pdfViewer: PDFView
    val PDF_SELECTION_CODE = 100

    var fileOpened: String? = ""
    var uriOpened: Uri? = null

    val timesAfterOpenReviewMessage = 500

    var isFullscreenEnabled = false
    var showingTopBar = true

    var isSupportedShareFeature = false
    var isSupportedGoTop = true

    var passwordRequired = false
    var passwordToUse = ""

    var totalPages = 0

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
            }
        } catch (e: Exception) {
            uriToUse = ""
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


        checkReviewApp()

        val backButton: ImageView = findViewById(R.id.buttonGoBackToolbar)
        backButton.setOnClickListener {
            updateLastFileOpened("")
            finish()
        }
        backButton.setOnLongClickListener {
            showTooltip(R.string.tooltip_close_app)
            true
        }

        val shareButton: ImageView = findViewById(R.id.buttonShareToolbar)
        shareButton.setOnClickListener {
            setShareButton()
        }
        shareButton.setOnLongClickListener {
            showTooltip(R.string.tooltip_share_file)
            true
        }

        val fullScreenButton: ImageView = findViewById(R.id.buttonFullScreenToolbar)
        fullScreenButton.setOnClickListener {
            setFullscreenButton(fullScreenButton)
        }
        fullScreenButton.setOnLongClickListener {
            if (isFullscreenEnabled) showTooltip(R.string.tooltip_full_screen_off)
            else showTooltip(R.string.tooltip_full_screen_on)
            true
        }

        val goTopButton: ImageView = findViewById(R.id.buttonGoTopToolbar)
        goTopButton.setOnClickListener {
            pdfViewer.jumpTo(0, true)
        }
        goTopButton.setOnLongClickListener {
            showTooltip(R.string.tooltip_go_to_top)
            true
        }

        val currentPage: TextView = findViewById(R.id.totalPagesToolbar)
        currentPage.setOnClickListener {
            if (findViewById<ConstraintLayout>(R.id.messageGoTo).isGone) showGoToDialog()
            else hideGoToDialog()
        }
        currentPage.setOnLongClickListener {
            showTooltip(R.string.tooltip_go_to_feature)
            true
        }

        val openButton: ImageView = findViewById(R.id.buttonOpenToolbar)
        openButton.setOnClickListener {
            openFromStorage()
        }
        openButton.setOnLongClickListener {
            showTooltip(R.string.tooltip_open_new_file)
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
        }
        lightButton.setOnLongClickListener {
            if (comfortView.isGone) showTooltip(R.string.tooltip_night_light_on)
            else showTooltip(R.string.tooltip_night_light_off)
            true
        }
        lightButton.isGone = false

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
            Intent.createChooser(browserStorage, "Select the file"),
            PDF_SELECTION_CODE
        )
    }

    fun selectPdfFromURI(uri: Uri?) {
        try {
            var lastPosition = 0
            pdfViewer.fromUri(uri)
                .enableSwipe(true) //leave as "true" (it causes a bug with scrolling when zoom is "100%")
                .swipeHorizontal(false) //horizontal scrolling disable
                .enableDoubletap(true)
                //.defaultPage(getPdfPage(uri.toString()))
                .spacing(10)
                .enableAnnotationRendering(true) // render annotations (such as comments, colors or forms)
                .password(passwordToUse)
                .scrollHandle(null)
                .enableAntialiasing(true) // improve rendering a little bit on low-res screens
                .onPageChange { page, pageCount -> updatePdfPage(uri.toString(), page) }
                .onPageScroll { page, positionOffset ->
                    if (!showingTopBar && (page > 0 || positionOffset > 0F)) {
                        hideTopBar()
                    } else if (positionOffset == 0F) {
                        showTopBar()
                        findViewById<ImageView>(R.id.buttonGoTopToolbar).isGone = true
                    } else {
                        showingTopBar = false
                    }
                    hideGoToDialog()
                }
                .onLoad {
                    lastPosition = getPdfPage(uri.toString())
                    /*pdfViewer.positionOffset = 1F
                    totalPages = pdfViewer.currentPage + 1*/
                    /*
                    println("title: " + pdfViewer.documentMeta.title)
                    println("author: " + pdfViewer.documentMeta.author)
                    println("keywords: " + pdfViewer.documentMeta.keywords)
                    println("creationDate: " + pdfViewer.documentMeta.creationDate)
                    */
                }
                .onRender { nbPages, pageWidth, pageHeight ->
                    totalPages = nbPages
                    updatePdfPage(uri.toString(), lastPosition)
                    if (totalPages == 1) {
                        isSupportedGoTop = false
                        findViewById<ImageView>(R.id.buttonGoTopToolbar).isGone = true
                    }
                    pdfViewer.fitToWidth()
                    pdfViewer.jumpTo(lastPosition, false)
                    if (lastPosition.toString() == "0") {
                        showTopBar(showGoTop = false)
                    } else {
                        hideTopBar()
                    }
                }
                .onError(OnErrorListener {
                    if (it.message.toString()
                            .contains("Password required or incorrect password.")
                    ) {
                        var passwordWrong = false
                        if (passwordRequired) passwordWrong = true
                        passwordRequired = true
                        askThePassword(uri, passwordWrong)
                    }
                    //PdfPasswordException
                })
                .load()
        } catch (e: Exception) {
            println("Exception 1")
        }
    }

    fun askThePassword(uri: Uri?, passwordWrong: Boolean = false) {
        showMessagePassword(passwordWrong)

        val buttonOpen: TextView = findViewById(R.id.buttonOpenPassword)
        val buttonClose: TextView = findViewById(R.id.buttonClosePassword)

        val textboxPassword: EditText = findViewById(R.id.textboxPassword)

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
        }
        buttonOpen.setOnLongClickListener {
            showTooltip(R.string.tooltip_open_file_password)
            true
        }

        buttonClose.setOnClickListener {
            finishAffinity()
        }
        buttonClose.setOnLongClickListener {
            showTooltip(R.string.tooltip_close_app)
            true
        }
    }

    fun showMessagePassword(passwordWrong: Boolean = false) {
        hideGoToDialog()
        hideMessageGuide1()
        val toolbar: View = findViewById(R.id.toolbar)
        val toolbarInvisible: View = findViewById(R.id.toolbarInvisible)
        val buttonClose: ImageView = findViewById(R.id.buttonGoBackToolbar)
        val buttonShare: ImageView = findViewById(R.id.buttonShareToolbar)
        val buttonFullscreen: ImageView = findViewById(R.id.buttonFullScreenToolbar)
        val buttonGoTop: ImageView = findViewById(R.id.buttonGoTopToolbar)
        val currentPage: TextView = findViewById(R.id.totalPagesToolbar)
        val buttonOpen: ImageView = findViewById(R.id.buttonOpenToolbar)
        toolbar.isGone = true
        buttonClose.isGone = true
        buttonShare.isGone = true
        buttonFullscreen.isGone = true
        buttonGoTop.isGone = true
        currentPage.isGone = true
        toolbarInvisible.isGone = true
        buttonOpen.isGone = true


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
        Handler().postDelayed({
            if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                //LANDSCAPE
            } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
                //PORTRAIT
            }
            val startZoom = pdfViewer.zoom
            pdfViewer.fitToWidth()
            val endZoom = pdfViewer.zoom
            pdfViewer.zoomTo(startZoom)
            pdfViewer.zoomWithAnimation(endZoom)
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
                selectPdfFromURI(selectedPdf)

                val shareButton: ImageView = findViewById(R.id.buttonShareToolbar)
                val fullscreenButton: ImageView = findViewById(R.id.buttonFullScreenToolbar)
                val goTopButton: ImageView = findViewById(R.id.buttonGoTopToolbar)
                val openButton: ImageView = findViewById(R.id.buttonOpenToolbar)
                shareButton.isGone = true
                fullscreenButton.isGone = true
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
                }
                val pagesNumber: TextView = findViewById(R.id.totalPagesToolbar)
                pagesNumber.isGone = true

                //checkRecentFiles(selectedPdf)

                updateLastFileOpened(selectedPdf.toString())
                //setTitle(getTheFileName(selectedPdf.toString(), -1))
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
                titleTemp.length - 16,
                titleTemp.length - 1
            )
        }
        titleElement.text = titleTemp
    }

    override fun onBackPressed() {
        updateLastFileOpened("")
        super.onBackPressed()
    }

    private fun updatePdfPage(pathName: String, currentPage: Int) {
        val pathNameTemp = getTheFileName(pathName, 0).toMD5()
        getSharedPreferences(pathNameTemp, Context.MODE_PRIVATE).edit()
            .putInt(pathNameTemp, currentPage).apply()

        val currentPageText: TextView = findViewById(R.id.totalPagesToolbar)
        currentPageText.text = (currentPage + 1).toString() + "/" + totalPages.toString()
        currentPageText.isGone = false
    }

    private fun getPdfPage(pathName: String): Int {
        val pathNameTemp = getTheFileName(pathName, 0).toMD5()
        return getSharedPreferences(pathNameTemp, Context.MODE_PRIVATE).getInt(pathNameTemp, 0)
    }

    private fun checkRecentFiles(uri: Uri?) {
        //format: date1:::uri1:::favourite1/:::/date2:::uri2:::favourite2/:::/.. ecc.
        val uriToUse = getTheFileName(uri.toString(), 2)
        //println(uriToUse)
        val RECENT_FILES = "recents"
        val recentFiles: String? =
            getSharedPreferences(RECENT_FILES, Context.MODE_PRIVATE).getString(RECENT_FILES, "")
        var recentFilesToSave = ""

        var recentFilesParts = recentFiles?.split("/:::/")

        var found = false
        var found_i = -1
        var i = 0
        var added_i = 0
        while (i < recentFilesParts!!.size) {
            val recentFilesParts2 = recentFilesParts[i].split(":::")
            if (recentFilesParts2.size == 3 && recentFilesParts2[1] == uriToUse) {
                found = true
                found_i = i
            }

            if (recentFilesParts2.size == 3) {
                if (added_i > 0) recentFilesToSave += "/:::/"
                recentFilesToSave += "${recentFilesParts2[0]}:::${recentFilesParts2[1]}:::${recentFilesParts2[2]}"
                added_i++
            }
            i++
        }

        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm:ss")
        val currentDate = sdf.format(Date())


        if (recentFiles != null && added_i > 0 && found) {
            //already in recent files: remove and re-add the element
            //println("Already in list")
            if (added_i == 1 || found_i == 0) {
                //it's not necessary remove and re-add
            } else {
                //it's necessary
                recentFilesToSave =
                    "${currentDate}:::${uriToUse}:::false/:::/" + getRecentFilesWithoutIndex(
                        found_i,
                        recentFilesParts
                    )
            }
        } else if (recentFiles == null || added_i == 0) {
            //add to the recent files: no other elements --> first element in the list
            //println("List empty - Added")
            recentFilesToSave = "${currentDate}:::${uriToUse}:::false"
        } else {
            //add to the recent files: with other elements
            //println("Added")
            recentFilesToSave = "${currentDate}:::${uriToUse}:::false/:::/" + recentFilesToSave
        }
        getSharedPreferences(RECENT_FILES, Context.MODE_PRIVATE).edit()
            .putString(RECENT_FILES, recentFilesToSave).apply()
    }

    fun getRecentFilesWithoutIndex(index: Int, recentFiles: List<String>): String {
        var recentFilesToSave = ""
        var i = 0
        while (i < recentFiles.size) {
            if (i != index) {
                if (i != 0) {
                    recentFilesToSave += " /:::/ "
                }
                recentFilesToSave += recentFiles[i]
            }
            i++
        }
        return recentFilesToSave
    }

    fun getTheFileName(path: String, type: Int = 0): String {
        try {
            var pathTemp = path
            pathTemp =
                pathTemp.replace("%3A", ":").replace("%2F", "/").replace("content://", "")

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

    fun getLastFileOpened(): String? {
        return getSharedPreferences(
            "last_opened_file",
            Context.MODE_PRIVATE
        ).getString("last_opened_file", "")
    }

    fun updateLastFileOpened(uri: String?) {
        getSharedPreferences("last_opened_file", Context.MODE_PRIVATE).edit()
            .putString("last_opened_file", uri).apply()
    }

    fun String.toMD5(): String {
        val bytes = MessageDigest.getInstance("MD5").digest(this.toByteArray())
        return bytes.toHex()
    }

    fun ByteArray.toHex(): String {
        return joinToString("") { "%02x".format(it) }
    }

    fun setShareButton() {
        //intent.getStringExtra("iName")
        val shareIntent = Intent()
        shareIntent.action = Intent.ACTION_SEND
        shareIntent.putExtra(Intent.EXTRA_STREAM, uriOpened)
        shareIntent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        shareIntent.type = "application/pdf"
        startActivity(Intent.createChooser(shareIntent, "Share"))
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

    fun showTopBar(showGoTop: Boolean = true) {
        showingTopBar = true

        val toolbar: View = findViewById(R.id.toolbar)
        val toolbarInvisible: View = findViewById(R.id.toolbarInvisible)
        val buttonClose: ImageView = findViewById(R.id.buttonGoBackToolbar)
        val buttonShare: ImageView = findViewById(R.id.buttonShareToolbar)
        val buttonFullscreen: ImageView = findViewById(R.id.buttonFullScreenToolbar)
        val buttonGoTop: ImageView = findViewById(R.id.buttonGoTopToolbar)
        val currentPage: TextView = findViewById(R.id.totalPagesToolbar)
        val buttonOpen: ImageView = findViewById(R.id.buttonOpenToolbar)
        val buttonNightDay: ImageView = findViewById(R.id.buttonNightDayToolbar)

        toolbar.isGone = false
        buttonClose.isGone = false
        if (isSupportedShareFeature) buttonShare.isGone = false
        buttonFullscreen.isGone = false
        if (isSupportedGoTop && showGoTop) buttonGoTop.isGone = false
        currentPage.isGone = false
        currentPage.setTextColor(ContextCompat.getColor(applicationContext, R.color.white))
        buttonOpen.isGone = false
        buttonNightDay.isGone = false

        toolbarInvisible.isGone = true

        hideMessageGuide1()

        if (getBooleanData("firstTimeShowTopBar", true)) {
            val message: ConstraintLayout = findViewById(R.id.messageGuide1)
            val arrow: View = findViewById(R.id.arrowRight)
            val messageText: TextView = findViewById(R.id.messageTextGuide1)
            messageText.setText(getString(R.string.text_tap_here_to_show_go_to_dialog))
            message.isGone = false
            arrow.isGone = false

            val button: TextView = findViewById(R.id.buttonHideGuide1)
            button.setOnClickListener {
                message.isGone = true
                arrow.isGone = true
                saveBooleanData("firstTimeShowTopBar", false)
            }
        }
    }

    fun hideTopBar() {
        if (!showingTopBar) {
            val toolbar: View = findViewById(R.id.toolbar)
            val toolbarInvisible: View = findViewById(R.id.toolbarInvisible)
            val buttonClose: ImageView = findViewById(R.id.buttonGoBackToolbar)
            val buttonShare: ImageView = findViewById(R.id.buttonShareToolbar)
            val buttonFullscreen: ImageView = findViewById(R.id.buttonFullScreenToolbar)
            val buttonGoTop: ImageView = findViewById(R.id.buttonGoTopToolbar)
            val currentPage: TextView = findViewById(R.id.totalPagesToolbar)
            val buttonOpen: ImageView = findViewById(R.id.buttonOpenToolbar)
            val buttonNightDay: ImageView = findViewById(R.id.buttonNightDayToolbar)

            toolbar.isGone = true
            buttonClose.isGone = true
            buttonShare.isGone = true
            buttonFullscreen.isGone = true
            buttonGoTop.isGone = true
            currentPage.isGone = false
            currentPage.setTextColor(
                ContextCompat.getColor(
                    applicationContext,
                    R.color.dark_red
                )
            )
            buttonOpen.isGone = true
            buttonNightDay.isGone = true

            toolbarInvisible.isGone = false

            hideMessageGuide1()

            if (getBooleanData("firstTimeHideTopBar", true)) {
                val message: ConstraintLayout = findViewById(R.id.messageGuide1)
                val arrow: View = findViewById(R.id.arrowLeft)
                val messageText: TextView = findViewById(R.id.messageTextGuide1)
                messageText.setText(getString(R.string.text_tap_here_to_show_the_top_bar))
                message.isGone = false
                arrow.isGone = false
                toolbarInvisible.setBackgroundResource(R.color.transparent_red_2)
                currentPage.setTextColor(
                    ContextCompat.getColor(
                        applicationContext,
                        R.color.white
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
                            applicationContext,
                            R.color.dark_red
                        )
                    )
                }
            }
        }
    }

    fun hideMessageGuide1() {
        val message: ConstraintLayout = findViewById(R.id.messageGuide1)
        val arrow: View = findViewById(R.id.arrowLeft)
        val arrow2: View = findViewById(R.id.arrowRight)
        if (!message.isGone || !arrow.isGone) {
            message.isGone = true
            arrow.isGone = true
            arrow2.isGone = true
        }
    }

    fun checkReviewApp() {
        var timesOpened = getSharedPreferences(
            "app_opened_times",
            Context.MODE_PRIVATE
        ).getInt("app_opened_times", 0)

        val alreadyReviewed = getSharedPreferences(
            "already_reviewed_app",
            Context.MODE_PRIVATE
        ).getBoolean("already_reviewed_app", false)

        val buttonReviewNow: TextView = findViewById(R.id.buttonReviewNow)
        val messageContainer: ConstraintLayout = findViewById(R.id.messageContainer)
        val buttonHideMessage: ImageView = findViewById(R.id.buttonHideMessageDialog)

        buttonReviewNow.setOnClickListener {
            if (openOnGooglePlay()) {
                messageContainer.isGone = true
                getSharedPreferences("already_reviewed_app", Context.MODE_PRIVATE).edit()
                    .putBoolean("already_reviewed_app", true).apply()
            }
        }

        buttonHideMessage.setOnClickListener {
            messageContainer.isGone = true
        }

        if (!alreadyReviewed) {
            if ((timesOpened % timesAfterOpenReviewMessage) == 0 && timesOpened >= timesAfterOpenReviewMessage) {
                messageContainer.isGone = false
            } else {
                messageContainer.isGone = true
            }
        } else {
            messageContainer.isGone = true
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
                    Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=com.saverio.pdfviewer")
                )
            )
        } catch (e: Exception) {
            println("Exception 3: " + e.toString())
            valueToReturn = false
        }

        return valueToReturn
    }

    fun showGoToDialog() {
        if (pdfViewer.currentPage == 0) showTopBar(showGoTop = false)
        else showTopBar()

        hideMessageGuide1()

        val buttonHide: ImageView = findViewById(R.id.buttonHideMessageGoTo)
        val textAllPages: TextView = findViewById(R.id.textAllPagesGoTo)
        val textbox: EditText = findViewById(R.id.textboxGoTo)
        val buttonGoTo: TextView = findViewById(R.id.buttonGoTo)

        textAllPages.text = "/ $totalPages"
        textbox.setText((pdfViewer.currentPage + 1).toString())

        val message: ConstraintLayout = findViewById(R.id.messageGoTo)
        val arrow: View = findViewById(R.id.arrow2)
        message.isGone = false
        arrow.isGone = false

        textbox.requestFocus()
        textbox.hasFocus()
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
        }

        buttonHide.setOnClickListener {
            hideGoToDialog()
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

    fun goToFeature(textbox: EditText) {
        var valueToGo = pdfViewer.currentPage + 1

        val valueTemp = textbox.text.toString().replace(" ", "")
        if (valueTemp != "" && valueTemp != "-"
        ) {
            if (valueTemp.toInt() < 0) {
                valueToGo = 0
            } else if (valueTemp.toInt() > totalPages) {
                valueToGo = totalPages
            } else {
                valueToGo = valueTemp.toInt() - 1
            }
        }
        try {
            pdfViewer.jumpTo(valueToGo, true)
            textbox.clearFocus()
        } catch (e: Exception) {

        }
    }

    fun hideKeyboard(view: View) {
        val manager = getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
        if (manager.isActive) manager.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    fun hideGoToDialog() {
        val message: ConstraintLayout = findViewById(R.id.messageGoTo)
        val arrow: View = findViewById(R.id.arrow2)
        message.isGone = true
        arrow.isGone = true
    }

    @SuppressLint("ClickableViewAccessibility")
    fun setupGestures() {
        //conflict with PDFView class
        val toolbarInvisible: View = findViewById(R.id.toolbarInvisible)
        toolbarInvisible.setOnTouchListener(object :
            OnSwipeTouchListener(this@PDFViewer) {

            override fun onSingleTapUp() {
                showTopBar()
            }
        })
    }

    fun getBooleanData(variable: String, default: Boolean = false): Boolean {
        return getSharedPreferences(variable, Context.MODE_PRIVATE).getBoolean(
            variable,
            default
        )
    }

    fun saveBooleanData(variable: String, value: Boolean) {
        getSharedPreferences(variable, Context.MODE_PRIVATE).edit().putBoolean(variable, value)
            .apply()
    }
}
