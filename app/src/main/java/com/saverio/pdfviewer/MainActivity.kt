package com.saverio.pdfviewer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.navigation.findNavController
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_OPENED_EXTERNALLY = "opened_externally"
        const val EXTRA_FORCE_HOME_TAB = "force_home_tab"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Go edge-to-edge explicitly on every API level, then reserve the
        // system-bar safe area ourselves. On Android 15+ (API 35) edge-to-edge
        // is enforced anyway; handling insets manually keeps the layout correct
        // regardless of version (including API 36, where the opt-out is gone).
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        // The status-bar backdrop is the app's dark red: keep the bar icons light.
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightStatusBars = false
        if (forwardToPdfViewerIfNeeded(intent)) return

        setContentView(R.layout.activity_main)
        val navView: BottomNavigationView = findViewById(R.id.nav_view)

        // Opaque backdrop behind the status bar: on API 35+ the bar is forced
        // transparent (statusBarColor is ignored), so scrolling content would
        // otherwise show through under the clock and system icons.
        val statusBarScrim = findViewById<android.view.View>(R.id.statusBarScrimMain)
        ViewCompat.setOnApplyWindowInsetsListener(statusBarScrim) { v, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            // Keep hidden while the inset is 0: at 0dp height a ConstraintLayout
            // child means MATCH_CONSTRAINT and would stretch over the screen.
            if (topInset > 0 && v.layoutParams.height != topInset) {
                v.layoutParams = v.layoutParams.apply { height = topInset }
            }
            v.visibility = if (topInset > 0) android.view.View.VISIBLE else android.view.View.GONE
            insets
        }

        // Keep the bottom navigation above the system navigation bar (and the
        // gesture pill on the sides in landscape). Insets are not consumed so
        // the fragments still receive the top inset for the status bar.
        ViewCompat.setOnApplyWindowInsetsListener(navView) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }

        val navController = findNavController(R.id.nav_host_fragment)
        navView.selectedItemId = R.id.navigation_home
        // Keep the bottom navigation selection in sync with the current
        // destination, so that navigating back (e.g. from Settings to Home via
        // the system back button) also updates the highlighted navbar item.
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val targetItemId = when (destination.id) {
                R.id.navigation_settings -> R.id.navigation_settings
                R.id.navigation_home -> R.id.navigation_home
                else -> null
            }
            if (targetItemId != null && navView.selectedItemId != targetItemId) {
                navView.menu.findItem(targetItemId).isChecked = true
            }
        }
        navView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_open -> {
                    openPDFFile(openedExternally = false)
                    false
                }

                R.id.navigation_home -> {
                    if (navController.currentDestination?.id != R.id.navigation_home) {
                        navController.navigate(R.id.navigation_home)
                    }
                    true
                }

                R.id.navigation_settings -> {
                    if (navController.currentDestination?.id != R.id.navigation_settings) {
                        navController.navigate(R.id.navigation_settings)
                    }
                    true
                }

                else -> false
            }
        }
        navView.setOnItemReselectedListener { item ->
            if (item.itemId == R.id.navigation_open) {
                openPDFFile(openedExternally = false)
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            if (intent.getBooleanExtra(EXTRA_FORCE_HOME_TAB, false)) {
                findViewById<BottomNavigationView>(R.id.nav_view).selectedItemId =
                    R.id.navigation_home
                intent.removeExtra(EXTRA_FORCE_HOME_TAB)
            }
            forwardToPdfViewerIfNeeded(intent)
        }
    }

    private fun forwardToPdfViewerIfNeeded(incomingIntent: Intent): Boolean {
        val incomingUri = incomingIntent.data ?: return false
        openPDFFile(incomingUri, true)
        finish()
        return true
    }

    fun openPDFFile(uri: Uri? = null, openedExternally: Boolean = false) {
        val intent = Intent(this@MainActivity, PDFViewer::class.java)
        val uriToOpen = Bundle()
        uriToOpen.putString(EXTRA_URI, uri?.toString() ?: "")
        uriToOpen.putBoolean(EXTRA_OPENED_EXTERNALLY, openedExternally)
        intent.putExtras(uriToOpen) //Put your id to your next Intent
        if (uri != null) {
            intent.data = uri
        }
        startActivity(intent)
    }
}