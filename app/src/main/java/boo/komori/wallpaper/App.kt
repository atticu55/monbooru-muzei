package boo.komori.wallpaper

import android.app.Application
import com.google.android.material.color.DynamicColors

/**
 * Applies Material You dynamic color on Android 12+ (API 31+).
 * On Android 11 and below the theme's default M3 palette is used.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
