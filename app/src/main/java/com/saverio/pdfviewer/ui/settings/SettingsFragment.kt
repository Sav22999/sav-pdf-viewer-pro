package com.saverio.pdfviewer.ui.settings

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.saverio.pdfviewer.R
import com.saverio.pdfviewer.ViewerDefaultsStore
import com.saverio.pdfviewer.db.DatabaseHandler
import java.util.Locale

class SettingsFragment : Fragment() {

    private enum class ThreeStateMode {
        ALWAYS_ON,
        AUTO,
        OFF
    }

    private lateinit var settingsViewModel: SettingsViewModel
    private val selectedAlpha = 1.0f
    private val unselectedAlpha = 0.45f

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        settingsViewModel = ViewModelProvider(this)[SettingsViewModel::class.java]
        val root = inflater.inflate(R.layout.fragment_settings, container, false)

        // Keep the settings content below the status bar (edge-to-edge).
        val settingsScroll: View = root.findViewById(R.id.settingsScroll)
        ViewCompat.setOnApplyWindowInsetsListener(settingsScroll) { v, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            v.updatePadding(top = topInset)
            insets
        }

        val packageInfo = runCatching {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
        }.getOrNull()
        val versionName = packageInfo?.versionName ?: "-"
        val versionCode = if (packageInfo == null) {
            0L
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        root.findViewById<TextView>(R.id.textAppVersion).text =
            getString(R.string.settings_version_label, versionName, versionCode)

        val buttonScrollVTopToBottom: ImageView =
            root.findViewById(R.id.buttonDefaultScrollVTopToBottom)
        val buttonScrollVBottomToTop: ImageView =
            root.findViewById(R.id.buttonDefaultScrollVBottomToTop)
        val buttonScrollHLeftToRight: ImageView =
            root.findViewById(R.id.buttonDefaultScrollHLeftToRight)
        val buttonScrollHRightToLeft: ImageView =
            root.findViewById(R.id.buttonDefaultScrollHRightToLeft)

        val buttonZoomDecrease: ImageView = root.findViewById(R.id.buttonDefaultZoomDecrease)
        val buttonZoomIncrease: ImageView = root.findViewById(R.id.buttonDefaultZoomIncrease)
        val textZoomValue: TextView = root.findViewById(R.id.buttonDefaultZoomValue)

        val buttonSinglePageOn: ImageView = root.findViewById(R.id.buttonDefaultSinglePageOn)
        val buttonSinglePageOff: ImageView = root.findViewById(R.id.buttonDefaultSinglePageOff)

        val buttonRotationOn: ImageView = root.findViewById(R.id.buttonDefaultRotationLockedOn)
        val buttonRotationOff: ImageView = root.findViewById(R.id.buttonDefaultRotationLockedOff)

        val buttonToolbarTop: ImageView = root.findViewById(R.id.buttonDefaultToolbarTop)
        val buttonToolbarBottom: ImageView = root.findViewById(R.id.buttonDefaultToolbarBottom)

        val buttonFullscreenOn: ImageView = root.findViewById(R.id.buttonDefaultFullscreenOn)
        val buttonFullscreenOff: ImageView = root.findViewById(R.id.buttonDefaultFullscreenOff)

        val buttonDarkFilterOn: ImageView = root.findViewById(R.id.buttonDefaultNightModeOn)
        val buttonDarkFilterAuto: TextView = root.findViewById(R.id.buttonDefaultNightModeAuto)
        val buttonDarkFilterOff: ImageView = root.findViewById(R.id.buttonDefaultNightModeOff)
        val darkFilterScheduleContainer: LinearLayout =
            root.findViewById(R.id.containerDarkFilterSchedule)
        val buttonDarkFilterStartTime: TextView =
            root.findViewById(R.id.buttonDefaultDarkFilterStartTime)
        val buttonDarkFilterEndTime: TextView =
            root.findViewById(R.id.buttonDefaultDarkFilterEndTime)

        val buttonNightLightOn: ImageView = root.findViewById(R.id.buttonDefaultContrastOn)
        val buttonNightLightAuto: TextView = root.findViewById(R.id.buttonDefaultContrastAuto)
        val buttonNightLightOff: ImageView = root.findViewById(R.id.buttonDefaultContrastOff)
        val nightLightScheduleContainer: LinearLayout =
            root.findViewById(R.id.containerNightLightSchedule)
        val buttonNightLightStartTime: TextView =
            root.findViewById(R.id.buttonDefaultNightLightStartTime)
        val buttonNightLightEndTime: TextView =
            root.findViewById(R.id.buttonDefaultNightLightEndTime)

        val resetButton: View = root.findViewById(R.id.buttonResetViewerDefaults)
        val clearRecentsButton: View = root.findViewById(R.id.buttonClearRecents)

        var selectedScrollMode = ViewerDefaultsStore.Defaults().scrollMode
        var selectedZoomMode = ViewerDefaultsStore.ZOOM_MODE_ADAPT
        var selectedZoomPercent = 100
        var selectedSinglePage = false
        var selectedRotationLocked = false
        var selectedToolbarPlacement = ViewerDefaultsStore.TOOLBAR_PLACEMENT_TOP
        var selectedFullscreen = false

        var darkFilterMode = ThreeStateMode.OFF
        var nightLightMode = ThreeStateMode.OFF

        var selectedDarkFilterStart = ViewerDefaultsStore.DEFAULT_DARK_FILTER_START_MINUTE
        var selectedDarkFilterEnd = ViewerDefaultsStore.DEFAULT_DARK_FILTER_END_MINUTE
        var selectedNightLightStart = ViewerDefaultsStore.DEFAULT_NIGHT_LIGHT_START_MINUTE
        var selectedNightLightEnd = ViewerDefaultsStore.DEFAULT_NIGHT_LIGHT_END_MINUTE

        var suppressSave = false

        fun setSelected(view: View, selected: Boolean) {
            view.alpha = if (selected) selectedAlpha else unselectedAlpha
        }

        fun formatMinuteOfDay(totalMinutes: Int): String {
            val normalized = totalMinutes.coerceIn(0, 24 * 60 - 1)
            val hour = normalized / 60
            val minute = normalized % 60
            return String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
        }

        fun persist() {
            if (suppressSave) return
            ViewerDefaultsStore.save(
                requireContext(),
                ViewerDefaultsStore.Defaults(
                    scrollMode = selectedScrollMode,
                    singlePage = selectedSinglePage,
                    rotationLocked = selectedRotationLocked,
                    zoomMode = selectedZoomMode,
                    zoomPercent = selectedZoomPercent,
                    toolbarPlacement = selectedToolbarPlacement,
                    fullscreen = selectedFullscreen,
                    nightMode = darkFilterMode == ThreeStateMode.ALWAYS_ON,
                    contrastOverlay = nightLightMode == ThreeStateMode.ALWAYS_ON,
                    darkFilterAuto = darkFilterMode == ThreeStateMode.AUTO,
                    darkFilterStartMinute = selectedDarkFilterStart,
                    darkFilterEndMinute = selectedDarkFilterEnd,
                    nightLightAuto = nightLightMode == ThreeStateMode.AUTO,
                    nightLightStartMinute = selectedNightLightStart,
                    nightLightEndMinute = selectedNightLightEnd
                )
            )
        }

        fun refreshDirectionUi() {
            setSelected(buttonScrollVTopToBottom, selectedScrollMode == "VERTICAL_TOP_TO_BOTTOM")
            setSelected(buttonScrollVBottomToTop, selectedScrollMode == "VERTICAL_BOTTOM_TO_TOP")
            setSelected(buttonScrollHLeftToRight, selectedScrollMode == "HORIZONTAL_LEFT_TO_RIGHT")
            setSelected(buttonScrollHRightToLeft, selectedScrollMode == "HORIZONTAL_RIGHT_TO_LEFT")
        }

        fun refreshZoomUi() {
            val isAuto = selectedZoomMode == ViewerDefaultsStore.ZOOM_MODE_ADAPT
            textZoomValue.text = if (isAuto) {
                getString(R.string.viewer_defaults_zoom_mode_auto)
            } else {
                getString(R.string.zoom_status_perc).replace("%d", selectedZoomPercent.toString())
            }
            buttonZoomDecrease.alpha = if (isAuto) 0.45f else 1.0f
            buttonZoomIncrease.alpha = if (isAuto) 0.45f else 1.0f
        }

        fun refreshToggleUi() {
            setSelected(buttonSinglePageOn.parent as View, selectedSinglePage)
            setSelected(buttonSinglePageOff.parent as View, !selectedSinglePage)

            setSelected(buttonRotationOn.parent as View, selectedRotationLocked)
            setSelected(buttonRotationOff.parent as View, !selectedRotationLocked)

            setSelected(
                buttonToolbarTop.parent as View,
                selectedToolbarPlacement == ViewerDefaultsStore.TOOLBAR_PLACEMENT_TOP
            )
            setSelected(
                buttonToolbarBottom.parent as View,
                selectedToolbarPlacement == ViewerDefaultsStore.TOOLBAR_PLACEMENT_BOTTOM
            )

            setSelected(buttonFullscreenOn.parent as View, selectedFullscreen)
            setSelected(buttonFullscreenOff.parent as View, !selectedFullscreen)
        }

        fun refreshDarkFilterUi() {
            setSelected(buttonDarkFilterOn.parent as View, darkFilterMode == ThreeStateMode.ALWAYS_ON)
            setSelected(buttonDarkFilterAuto, darkFilterMode == ThreeStateMode.AUTO)
            setSelected(buttonDarkFilterOff.parent as View, darkFilterMode == ThreeStateMode.OFF)
            darkFilterScheduleContainer.visibility =
                if (darkFilterMode == ThreeStateMode.AUTO) View.VISIBLE else View.GONE
            buttonDarkFilterStartTime.text = formatMinuteOfDay(selectedDarkFilterStart)
            buttonDarkFilterEndTime.text = formatMinuteOfDay(selectedDarkFilterEnd)
        }

        fun refreshNightLightUi() {
            setSelected(buttonNightLightOn.parent as View, nightLightMode == ThreeStateMode.ALWAYS_ON)
            setSelected(buttonNightLightAuto, nightLightMode == ThreeStateMode.AUTO)
            setSelected(buttonNightLightOff.parent as View, nightLightMode == ThreeStateMode.OFF)
            nightLightScheduleContainer.visibility =
                if (nightLightMode == ThreeStateMode.AUTO) View.VISIBLE else View.GONE
            buttonNightLightStartTime.text = formatMinuteOfDay(selectedNightLightStart)
            buttonNightLightEndTime.text = formatMinuteOfDay(selectedNightLightEnd)
        }

        fun applyDefaults(defaults: ViewerDefaultsStore.Defaults) {
            suppressSave = true

            selectedScrollMode = defaults.scrollMode
            selectedZoomMode = defaults.zoomMode
            selectedZoomPercent = defaults.zoomPercent
            selectedSinglePage = defaults.singlePage
            selectedRotationLocked = defaults.rotationLocked
            selectedToolbarPlacement = defaults.toolbarPlacement
            selectedFullscreen = defaults.fullscreen

            darkFilterMode = when {
                defaults.darkFilterAuto -> ThreeStateMode.AUTO
                defaults.nightMode -> ThreeStateMode.ALWAYS_ON
                else -> ThreeStateMode.OFF
            }
            nightLightMode = when {
                defaults.nightLightAuto -> ThreeStateMode.AUTO
                defaults.contrastOverlay -> ThreeStateMode.ALWAYS_ON
                else -> ThreeStateMode.OFF
            }

            selectedDarkFilterStart = defaults.darkFilterStartMinute
            selectedDarkFilterEnd = defaults.darkFilterEndMinute
            selectedNightLightStart = defaults.nightLightStartMinute
            selectedNightLightEnd = defaults.nightLightEndMinute

            refreshDirectionUi()
            refreshZoomUi()
            refreshToggleUi()
            refreshDarkFilterUi()
            refreshNightLightUi()

            suppressSave = false
        }

        fun pickTime(initialMinute: Int, onPicked: (Int) -> Unit) {
            val hour = initialMinute / 60
            val minute = initialMinute % 60
            TimePickerDialog(requireContext(), { _, pickedHour, pickedMinute ->
                onPicked((pickedHour * 60) + pickedMinute)
            }, hour, minute, true).show()
        }

        applyDefaults(ViewerDefaultsStore.load(requireContext()))

        buttonScrollVTopToBottom.setOnClickListener {
            selectedScrollMode = "VERTICAL_TOP_TO_BOTTOM"
            refreshDirectionUi()
            persist()
        }
        buttonScrollVBottomToTop.setOnClickListener {
            selectedScrollMode = "VERTICAL_BOTTOM_TO_TOP"
            refreshDirectionUi()
            persist()
        }
        buttonScrollHLeftToRight.setOnClickListener {
            selectedScrollMode = "HORIZONTAL_LEFT_TO_RIGHT"
            refreshDirectionUi()
            persist()
        }
        buttonScrollHRightToLeft.setOnClickListener {
            selectedScrollMode = "HORIZONTAL_RIGHT_TO_LEFT"
            refreshDirectionUi()
            persist()
        }

        buttonZoomDecrease.setOnClickListener {
            if (selectedZoomMode == ViewerDefaultsStore.ZOOM_MODE_ADAPT) {
                selectedZoomMode = ViewerDefaultsStore.ZOOM_MODE_PERCENT
                selectedZoomPercent = 100
            }
            selectedZoomPercent = (selectedZoomPercent - 10).coerceAtLeast(10)
            refreshZoomUi()
            persist()
        }
        buttonZoomIncrease.setOnClickListener {
            if (selectedZoomMode == ViewerDefaultsStore.ZOOM_MODE_ADAPT) {
                selectedZoomMode = ViewerDefaultsStore.ZOOM_MODE_PERCENT
                selectedZoomPercent = 100
            }
            selectedZoomPercent = (selectedZoomPercent + 10).coerceAtMost(500)
            refreshZoomUi()
            persist()
        }
        textZoomValue.setOnClickListener {
            selectedZoomMode = if (selectedZoomMode == ViewerDefaultsStore.ZOOM_MODE_ADAPT) {
                ViewerDefaultsStore.ZOOM_MODE_PERCENT
            } else {
                ViewerDefaultsStore.ZOOM_MODE_ADAPT
            }
            if (selectedZoomMode == ViewerDefaultsStore.ZOOM_MODE_PERCENT && selectedZoomPercent !in 10..500) {
                selectedZoomPercent = 100
            }
            refreshZoomUi()
            persist()
        }

        buttonSinglePageOn.setOnClickListener {
            selectedSinglePage = true
            refreshToggleUi()
            persist()
        }
        buttonSinglePageOff.setOnClickListener {
            selectedSinglePage = false
            refreshToggleUi()
            persist()
        }

        buttonRotationOn.setOnClickListener {
            selectedRotationLocked = true
            refreshToggleUi()
            persist()
        }
        buttonRotationOff.setOnClickListener {
            selectedRotationLocked = false
            refreshToggleUi()
            persist()
        }

        buttonToolbarTop.setOnClickListener {
            selectedToolbarPlacement = ViewerDefaultsStore.TOOLBAR_PLACEMENT_TOP
            refreshToggleUi()
            persist()
        }
        buttonToolbarBottom.setOnClickListener {
            selectedToolbarPlacement = ViewerDefaultsStore.TOOLBAR_PLACEMENT_BOTTOM
            refreshToggleUi()
            persist()
        }

        buttonFullscreenOn.setOnClickListener {
            selectedFullscreen = true
            refreshToggleUi()
            persist()
        }
        buttonFullscreenOff.setOnClickListener {
            selectedFullscreen = false
            refreshToggleUi()
            persist()
        }

        buttonDarkFilterOn.setOnClickListener {
            darkFilterMode = ThreeStateMode.ALWAYS_ON
            refreshDarkFilterUi()
            persist()
        }
        buttonDarkFilterAuto.setOnClickListener {
            darkFilterMode = ThreeStateMode.AUTO
            refreshDarkFilterUi()
            persist()
        }
        buttonDarkFilterOff.setOnClickListener {
            darkFilterMode = ThreeStateMode.OFF
            refreshDarkFilterUi()
            persist()
        }

        buttonNightLightOn.setOnClickListener {
            nightLightMode = ThreeStateMode.ALWAYS_ON
            refreshNightLightUi()
            persist()
        }
        buttonNightLightAuto.setOnClickListener {
            nightLightMode = ThreeStateMode.AUTO
            refreshNightLightUi()
            persist()
        }
        buttonNightLightOff.setOnClickListener {
            nightLightMode = ThreeStateMode.OFF
            refreshNightLightUi()
            persist()
        }

        buttonDarkFilterStartTime.setOnClickListener {
            if (darkFilterMode != ThreeStateMode.AUTO) return@setOnClickListener
            pickTime(selectedDarkFilterStart) {
                selectedDarkFilterStart = it
                refreshDarkFilterUi()
                persist()
            }
        }
        buttonDarkFilterEndTime.setOnClickListener {
            if (darkFilterMode != ThreeStateMode.AUTO) return@setOnClickListener
            pickTime(selectedDarkFilterEnd) {
                selectedDarkFilterEnd = it
                refreshDarkFilterUi()
                persist()
            }
        }
        buttonNightLightStartTime.setOnClickListener {
            if (nightLightMode != ThreeStateMode.AUTO) return@setOnClickListener
            pickTime(selectedNightLightStart) {
                selectedNightLightStart = it
                refreshNightLightUi()
                persist()
            }
        }
        buttonNightLightEndTime.setOnClickListener {
            if (nightLightMode != ThreeStateMode.AUTO) return@setOnClickListener
            pickTime(selectedNightLightEnd) {
                selectedNightLightEnd = it
                refreshNightLightUi()
                persist()
            }
        }

        resetButton.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.viewer_defaults_reset_confirm_title))
                .setMessage(getString(R.string.viewer_defaults_reset_confirm_message))
                .setPositiveButton(getString(R.string.button_ok)) { _, _ ->
                    ViewerDefaultsStore.reset(requireContext())
                    applyDefaults(ViewerDefaultsStore.defaultDefaults())
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.viewer_defaults_reset_done),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .setNegativeButton(getString(R.string.button_close), null)
                .show()
        }

        clearRecentsButton.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.viewer_defaults_clear_recents_confirm_title))
                .setMessage(getString(R.string.viewer_defaults_clear_recents_confirm_message))
                .setPositiveButton(getString(R.string.button_ok)) { _, _ ->
                    DatabaseHandler(requireContext()).deleteAllFiles()
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.viewer_defaults_clear_recents_done),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .setNegativeButton(getString(R.string.button_close), null)
                .show()
        }

        return root
    }
}