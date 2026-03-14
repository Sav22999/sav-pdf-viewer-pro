package com.saverio.pdfviewer

import android.content.Context

object ViewerDefaultsStore {
    private const val PREFS_NAME = "viewer_defaults"

    private const val KEY_SCROLL_MODE = "scroll_mode"
    private const val KEY_SINGLE_PAGE = "single_page"
    private const val KEY_ROTATION_LOCKED = "rotation_locked"
    private const val KEY_ZOOM_MODE = "zoom_mode"
    private const val KEY_ZOOM_PERCENT = "zoom_percent"
    private const val KEY_FULLSCREEN = "fullscreen"
    private const val KEY_NIGHT_MODE = "night_mode"
    private const val KEY_CONTRAST_OVERLAY = "contrast_overlay"
    private const val KEY_DARK_FILTER_AUTO = "dark_filter_auto"
    private const val KEY_DARK_FILTER_START_MINUTE = "dark_filter_start_minute"
    private const val KEY_DARK_FILTER_END_MINUTE = "dark_filter_end_minute"
    private const val KEY_NIGHT_LIGHT_AUTO = "night_light_auto"
    private const val KEY_NIGHT_LIGHT_START_MINUTE = "night_light_start_minute"
    private const val KEY_NIGHT_LIGHT_END_MINUTE = "night_light_end_minute"

    const val ZOOM_MODE_ADAPT = "ADAPT"
    const val ZOOM_MODE_PERCENT = "PERCENT"
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
        val fullscreen: Boolean = false,
        val nightMode: Boolean = false,
        val contrastOverlay: Boolean = false,
        val darkFilterAuto: Boolean = false,
        val darkFilterStartMinute: Int = DEFAULT_DARK_FILTER_START_MINUTE,
        val darkFilterEndMinute: Int = DEFAULT_DARK_FILTER_END_MINUTE,
        val nightLightAuto: Boolean = true,
        val nightLightStartMinute: Int = DEFAULT_NIGHT_LIGHT_START_MINUTE,
        val nightLightEndMinute: Int = DEFAULT_NIGHT_LIGHT_END_MINUTE
    )

    fun defaultDefaults(): Defaults = Defaults()

    fun load(context: Context): Defaults {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Defaults(
            scrollMode = prefs.getString(KEY_SCROLL_MODE, "VERTICAL_TOP_TO_BOTTOM") ?: "VERTICAL_TOP_TO_BOTTOM",
            singlePage = prefs.getBoolean(KEY_SINGLE_PAGE, false),
            rotationLocked = prefs.getBoolean(KEY_ROTATION_LOCKED, false),
            zoomMode = prefs.getString(KEY_ZOOM_MODE, ZOOM_MODE_ADAPT) ?: ZOOM_MODE_ADAPT,
            zoomPercent = prefs.getInt(KEY_ZOOM_PERCENT, 100).coerceIn(10, 500),
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
                .coerceIn(0, 24 * 60 - 1)
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
            .putBoolean(KEY_FULLSCREEN, defaults.fullscreen)
            .putBoolean(KEY_NIGHT_MODE, defaults.nightMode)
            .putBoolean(KEY_CONTRAST_OVERLAY, defaults.contrastOverlay)
            .putBoolean(KEY_DARK_FILTER_AUTO, defaults.darkFilterAuto)
            .putInt(KEY_DARK_FILTER_START_MINUTE, defaults.darkFilterStartMinute.coerceIn(0, 24 * 60 - 1))
            .putInt(KEY_DARK_FILTER_END_MINUTE, defaults.darkFilterEndMinute.coerceIn(0, 24 * 60 - 1))
            .putBoolean(KEY_NIGHT_LIGHT_AUTO, defaults.nightLightAuto)
            .putInt(KEY_NIGHT_LIGHT_START_MINUTE, defaults.nightLightStartMinute.coerceIn(0, 24 * 60 - 1))
            .putInt(KEY_NIGHT_LIGHT_END_MINUTE, defaults.nightLightEndMinute.coerceIn(0, 24 * 60 - 1))
            .apply()
    }

    fun reset(context: Context) {
        save(context, defaultDefaults())
    }
}

