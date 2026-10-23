package boo.komori.wallpaper

import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager

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

        private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                WallpaperWorker.PREF_SERVER_URL,
                WallpaperWorker.PREF_API_TOKEN,
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
