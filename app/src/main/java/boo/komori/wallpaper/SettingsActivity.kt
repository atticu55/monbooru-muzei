package boo.komori.wallpaper

import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

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

        private val http = OkHttpClient()

        private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            when (key) {
                WallpaperWorker.PREF_BASE_URL -> {
                    refreshFolders()
                    WallpaperWorker.enqueueLoad(requireContext(), ignoreRequirements = true)
                }
                WallpaperWorker.PREF_FOLDER ->
                    WallpaperWorker.enqueueLoad(requireContext(), ignoreRequirements = true)
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<EditTextPreference>(WallpaperWorker.PREF_BASE_URL)?.apply {
                setOnBindEditTextListener { it.inputType = InputType.TYPE_TEXT_VARIATION_URI }
            }

            refreshFolders()
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

        /** Fill the folder picker from the cached manifest, then refresh it in the background. */
        private fun refreshFolders() {
            val ctx = context ?: return
            val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
            val folderPref = findPreference<ListPreference>(WallpaperWorker.PREF_FOLDER) ?: return

            val cached = prefs.getStringSet(WallpaperWorker.PREF_KNOWN_FOLDERS, emptySet())
                ?.toTypedArray()?.sortedArray() ?: emptyArray()
            if (folderPref.entries.isNullOrEmpty() && cached.isNotEmpty()) {
                folderPref.entries = cached
                folderPref.entryValues = cached
            }

            Thread {
                try {
                    val base = prefs.getString(WallpaperWorker.PREF_BASE_URL, null)
                        ?.trim()?.trimEnd('/')
                        ?.takeIf { it.startsWith("http") } ?: return@Thread
                    val body = http.newCall(
                        Request.Builder().url("$base/manifest.json").build()
                    ).execute().use { r ->
                        if (!r.isSuccessful) return@Thread
                        r.body?.string() ?: return@Thread
                    }
                    val names = mutableSetOf<String>()
                    val manifest = JSONArray(body)
                    for (i in 0 until manifest.length()) {
                        manifest.getJSONObject(i).optString("name")
                            .takeIf { it.isNotBlank() }?.let { names.add(it) }
                    }
                    if (names.isNotEmpty()) {
                        prefs.edit().putStringSet(WallpaperWorker.PREF_KNOWN_FOLDERS, names).apply()
                        val sorted = names.toTypedArray().sortedArray()
                        activity?.runOnUiThread {
                            folderPref.entries = sorted
                            folderPref.entryValues = sorted
                        }
                    }
                } catch (_: Exception) {
                    // offline or bad URL — cached list stays
                }
            }.start()
        }
    }
}
