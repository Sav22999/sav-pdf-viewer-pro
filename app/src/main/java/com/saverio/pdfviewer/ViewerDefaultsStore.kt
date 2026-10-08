package com.saverio.pdfviewer

import android.content.Context

object ViewerDefaultsStore {
    private const val PREFS_NAME = "viewer_defaults"

    private const val KEY_SCROLL_MODE = "scroll_mode"
    private const val KEY_SINGLE_PAGE = "single_page"
    private const val KEY_ROTATION_LOCKED = "rotation_locked"
    private const val KEY_ZOOM_MODE = "zoom_mode"
    private const val KEY_ZOOM_PERCENT = "zoom_percent"
    private const val KEY_TOOLBAR_PLACEMENT = "toolbar_placement"
    private const val KEY_FULLSCREEN = "fullscreen"
    private const val KEY_NIGHT_MODE = "night_mode"
    private const val KEY_CONTRAST_OVERLAY = "contrast_overlay"
    private const val KEY_DARK_FILTER_AUTO = "dark_filter_auto"
    private const val KEY_DARK_FILTER_START_MINUTE = "dark_filter_start_minute"
    private const val KEY_DARK_FILTER_END_MINUTE = "dark_filter_end_minute"
    private const val KEY_NIGHT_LIGHT_AUTO = "night_light_auto"
    private const val KEY_NIGHT_LIGHT_START_MINUTE = "night_light_start_minute"
    private const val KEY_NIGHT_LIGHT_END_MINUTE = "night_light_end_minute"

    private const val KEY_ACCENT_COLOR = "accent_color"
    private const val KEY_HIGH_CONTRAST = "high_contrast"

    const val ZOOM_MODE_ADAPT = "ADAPT"
    const val ZOOM_MODE_PERCENT = "PERCENT"
    const val TOOLBAR_PLACEMENT_TOP = "TOP"
    const val TOOLBAR_PLACEMENT_BOTTOM = "BOTTOM"

    const val ACCENT_RED = "RED"
    const val ACCENT_GREEN = "GREEN"
    const val ACCENT_ORANGE = "ORANGE"
    const val ACCENT_BLUE = "BLUE"
    const val ACCENT_PURPLE = "PURPLE"
    const val ACCENT_BLACK = "BLACK"
    const val DEFAULT_ACCENT_COLOR = ACCENT_RED
    const val DEFAULT_DARK_FILTER_START_MINUTE = 21 * 60
    const val DEFAULT_DARK_FILTER_END_MINUTE = 7 * 60
    const val DEFAULT_NIGHT_LIGHT_START_MINUTE = 21 * 60
    const val DEFAULT_NIGHT_LIGHT_END_MINUTE = 7 * 60

    data class Defaults(
        val scrollMode: String = "VERTICAL_TOP_TO_BOTTOM",
        val singlePage: Boolean = false,
        val rotationLocked: Boolean = false,
        val zoomMode: String = ZOOM_MODE_ADAPT,
        val zoomPercent: Int = 100,
        val toolbarPlacement: String = TOOLBAR_PLACEMENT_TOP,
        val fullscreen: Boolean = false,
        val nightMode: Boolean = false,
        val contrastOverlay: Boolean = false,
        val darkFilterAuto: Boolean = false,
        val darkFilterStartMinute: Int = DEFAULT_DARK_FILTER_START_MINUTE,
        val darkFilterEndMinute: Int = DEFAULT_DARK_FILTER_END_MINUTE,
        val nightLightAuto: Boolean = true,
        val nightLightStartMinute: Int = DEFAULT_NIGHT_LIGHT_START_MINUTE,
        val nightLightEndMinute: Int = DEFAULT_NIGHT_LIGHT_END_MINUTE,
        val highContrast: Boolean = false
    )

    fun defaultDefaults(): Defaults = Defaults()

    private fun sanitizeToolbarPlacement(value: String?): String {
        return when (value) {
            TOOLBAR_PLACEMENT_BOTTOM -> TOOLBAR_PLACEMENT_BOTTOM
            else -> TOOLBAR_PLACEMENT_TOP
        }
    }

    fun load(context: Context): Defaults {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Defaults(
            scrollMode = prefs.getString(KEY_SCROLL_MODE, "VERTICAL_TOP_TO_BOTTOM") ?: "VERTICAL_TOP_TO_BOTTOM",
            singlePage = prefs.getBoolean(KEY_SINGLE_PAGE, false),
            rotationLocked = prefs.getBoolean(KEY_ROTATION_LOCKED, false),
            zoomMode = prefs.getString(KEY_ZOOM_MODE, ZOOM_MODE_ADAPT) ?: ZOOM_MODE_ADAPT,
            zoomPercent = prefs.getInt(KEY_ZOOM_PERCENT, 100).coerceIn(10, 500),
            toolbarPlacement = sanitizeToolbarPlacement(
                prefs.getString(KEY_TOOLBAR_PLACEMENT, TOOLBAR_PLACEMENT_TOP)
            ),
            fullscreen = prefs.getBoolean(KEY_FULLSCREEN, false),
            nightMode = prefs.getBoolean(KEY_NIGHT_MODE, false),
            contrastOverlay = prefs.getBoolean(KEY_CONTRAST_OVERLAY, false),
            darkFilterAuto = prefs.getBoolean(KEY_DARK_FILTER_AUTO, false),
            darkFilterStartMinute = prefs.getInt(KEY_DARK_FILTER_START_MINUTE, DEFAULT_DARK_FILTER_START_MINUTE)
                .coerceIn(0, 24 * 60 - 1),
            darkFilterEndMinute = prefs.getInt(KEY_DARK_FILTER_END_MINUTE, DEFAULT_DARK_FILTER_END_MINUTE)
                .coerceIn(0, 24 * 60 - 1),
            nightLightAuto = prefs.getBoolean(KEY_NIGHT_LIGHT_AUTO, true),
            nightLightStartMinute = prefs.getInt(KEY_NIGHT_LIGHT_START_MINUTE, DEFAULT_NIGHT_LIGHT_START_MINUTE)
                .coerceIn(0, 24 * 60 - 1),
            nightLightEndMinute = prefs.getInt(KEY_NIGHT_LIGHT_END_MINUTE, DEFAULT_NIGHT_LIGHT_END_MINUTE)
                .coerceIn(0, 24 * 60 - 1),
            highContrast = prefs.getBoolean(KEY_HIGH_CONTRAST, false)
        )
    }

    fun save(context: Context, defaults: Defaults) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SCROLL_MODE, defaults.scrollMode)
            .putBoolean(KEY_SINGLE_PAGE, defaults.singlePage)
            .putBoolean(KEY_ROTATION_LOCKED, defaults.rotationLocked)
            .putString(KEY_ZOOM_MODE, defaults.zoomMode)
            .putInt(KEY_ZOOM_PERCENT, defaults.zoomPercent.coerceIn(10, 500))
            .putString(KEY_TOOLBAR_PLACEMENT, sanitizeToolbarPlacement(defaults.toolbarPlacement))
            .putBoolean(KEY_FULLSCREEN, defaults.fullscreen)
            .putBoolean(KEY_NIGHT_MODE, defaults.nightMode)
            .putBoolean(KEY_CONTRAST_OVERLAY, defaults.contrastOverlay)
            .putBoolean(KEY_DARK_FILTER_AUTO, defaults.darkFilterAuto)
            .putInt(KEY_DARK_FILTER_START_MINUTE, defaults.darkFilterStartMinute.coerceIn(0, 24 * 60 - 1))
            .putInt(KEY_DARK_FILTER_END_MINUTE, defaults.darkFilterEndMinute.coerceIn(0, 24 * 60 - 1))
            .putBoolean(KEY_NIGHT_LIGHT_AUTO, defaults.nightLightAuto)
            .putInt(KEY_NIGHT_LIGHT_START_MINUTE, defaults.nightLightStartMinute.coerceIn(0, 24 * 60 - 1))
            .putInt(KEY_NIGHT_LIGHT_END_MINUTE, defaults.nightLightEndMinute.coerceIn(0, 24 * 60 - 1))
            .putBoolean(KEY_HIGH_CONTRAST, defaults.highContrast)
            .apply()
    }

    fun reset(context: Context) {
        save(context, defaultDefaults())
    }

    /** Save dark-filter state globally, disabling auto schedule. */
    fun saveNightMode(context: Context, enabled: Boolean) {
        val current = load(context)
        save(context, current.copy(nightMode = enabled, darkFilterAuto = false))
    }

    /** Save night-light state globally, disabling auto schedule. */
    fun saveContrastOverlay(context: Context, enabled: Boolean) {
        val current = load(context)
        save(context, current.copy(contrastOverlay = enabled, nightLightAuto = false))
    }

    private fun sanitizeAccentColor(value: String?): String {
        return when (value) {
            ACCENT_GREEN -> ACCENT_GREEN
            ACCENT_ORANGE -> ACCENT_ORANGE
            ACCENT_BLUE -> ACCENT_BLUE
            ACCENT_PURPLE -> ACCENT_PURPLE
            ACCENT_BLACK -> ACCENT_BLACK
            else -> ACCENT_RED
        }
    }

    /** Load the user-selected accent color key (defaults to red). */
    fun loadAccentColor(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sanitizeAccentColor(prefs.getString(KEY_ACCENT_COLOR, DEFAULT_ACCENT_COLOR))
    }

    /** Persist the user-selected accent color key. */
    fun saveAccentColor(context: Context, value: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCENT_COLOR, sanitizeAccentColor(value))
            .apply()
    }

    /** Load the high-contrast mode flag (defaults to disabled). */
    fun loadHighContrast(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_HIGH_CONTRAST, false)
    }

    /** Persist the high-contrast mode flag. */
    fun saveHighContrast(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HIGH_CONTRAST, enabled)
            .apply()
    }

    /** Resolve the theme style resource for the given accent color key. */
    fun accentThemeStyle(value: String): Int {
        return when (sanitizeAccentColor(value)) {
            ACCENT_GREEN -> R.style.Theme_SavPDFViewer_Green
            ACCENT_ORANGE -> R.style.Theme_SavPDFViewer_Orange
            ACCENT_BLUE -> R.style.Theme_SavPDFViewer_Blue
            ACCENT_PURPLE -> R.style.Theme_SavPDFViewer_Purple
            ACCENT_BLACK -> R.style.Theme_SavPDFViewer_Black
            else -> R.style.Theme_SavPDFViewer
        }
    }

    /** Apply the stored accent theme to an activity. Call before setContentView. */
    fun applyAccentTheme(activity: android.app.Activity) {
        activity.setTheme(accentThemeStyle(loadAccentColor(activity)))
        if (loadHighContrast(activity)) {
            activity.theme.applyStyle(R.style.ThemeOverlay_SavPDFViewer_HighContrast, true)
        }
    }
}

