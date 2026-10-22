package boo.komori.wallpaper

import com.google.android.apps.muzei.api.provider.MuzeiArtProvider

class WallpaperProvider : MuzeiArtProvider() {
    override fun onLoadRequested(initial: Boolean) {
        val context = context ?: return
        WallpaperWorker.enqueueLoad(context, ignoreRequirements = initial)
    }
}
