package boo.komori.wallpaper

import android.content.Context
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Shared TokenStore shadow — EncryptedSharedPreferences/AndroidKeyStore
 * does not initialize under Robolectric. ONE definition for all test
 * classes: two different @Implements(TokenStore) shadows (one per test
 * file) clash in a single test run — ClassCastException at shadow lookup,
 * every test in the run dies (verified build C, 2026-11-03).
 */
@Implements(TokenStore::class)
class ShadowTokenStore {
    @Implementation
    fun getToken(context: Context): String? = null

    @Implementation
    fun setToken(context: Context, token: String?) {}
}
