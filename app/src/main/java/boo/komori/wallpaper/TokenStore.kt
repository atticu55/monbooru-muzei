package boo.komori.wallpaper

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object TokenStore {

    private const val PREFS_NAME = "secure_prefs"
    private const val KEY_TOKEN = "apiToken"

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getToken(context: Context): String? =
        prefs(context).getString(KEY_TOKEN, null)?.trim()?.takeIf { it.isNotBlank() }

    fun setToken(context: Context, token: String?) {
        prefs(context).edit().apply {
            if (token.isNullOrBlank()) remove(KEY_TOKEN) else putString(KEY_TOKEN, token.trim())
        }.apply()
    }
}
