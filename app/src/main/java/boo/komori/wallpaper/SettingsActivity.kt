package boo.komori.wallpaper

import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager

private const val MASKED_TOKEN = "••••••••"

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.app_name)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            when (key) {
                WallpaperWorker.PREF_API_TOKEN -> {
                    // Route token to encrypted store, replace with mask in plain prefs
                    val raw = prefs.getString(key, null)
                    TokenStore.setToken(requireContext(), raw)
                    prefs.edit().putString(key, MASKED_TOKEN).apply()
                    WallpaperWorker.enqueueLoad(requireContext(), ignoreRequirements = true)
                }
                WallpaperWorker.PREF_SERVER_URL,
                WallpaperWorker.PREF_COLLECTION ->
                    WallpaperWorker.enqueueLoad(requireContext(), ignoreRequirements = true)
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<EditTextPreference>(WallpaperWorker.PREF_SERVER_URL)?.apply {
                setOnBindEditTextListener { it.inputType = InputType.TYPE_TEXT_VARIATION_URI }
            }

            findPreference<EditTextPreference>(WallpaperWorker.PREF_API_TOKEN)?.apply {
                setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                // Show mask if token already stored securely
                if (!TokenStore.getToken(requireContext()).isNullOrBlank()) {
                    PreferenceManager.getDefaultSharedPreferences(requireContext())
                        .edit().putString(WallpaperWorker.PREF_API_TOKEN, MASKED_TOKEN).apply()
                }
                // Clear mask when user opens dialog so they can type a new token
                setOnPreferenceClickListener {
                    PreferenceManager.getDefaultSharedPreferences(requireContext())
                        .edit().putString(WallpaperWorker.PREF_API_TOKEN, "").apply()
                    false
                }
            }

            findPreference<EditTextPreference>(WallpaperWorker.PREF_COLLECTION)?.apply {
                setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT }
            }
        }

        override fun onResume() {
            super.onResume()
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .registerOnSharedPreferenceChangeListener(prefListener)
        }

        override fun onPause() {
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .unregisterOnSharedPreferenceChangeListener(prefListener)
            super.onPause()
        }
    }
}
