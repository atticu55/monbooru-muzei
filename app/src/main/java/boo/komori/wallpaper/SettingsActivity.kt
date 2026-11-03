package boo.komori.wallpaper

import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MASKED_TOKEN = "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"

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
                    // Route a newly typed token to the encrypted store, then
                    // replace it with a mask in the plain prefs file. Blank or
                    // mask values are ignored so cancelling the dialog can't
                    // wipe the stored token.
                    val raw = prefs.getString(key, null)
                    if (!raw.isNullOrBlank() && raw != MASKED_TOKEN) {
                        TokenStore.setToken(requireContext(), raw)
                        prefs.edit().putString(key, MASKED_TOKEN).apply()
                        WallpaperWorker.enqueueLoad(requireContext(), ignoreRequirements = true)
                    }
                }
                WallpaperWorker.PREF_SERVER_URL,
                WallpaperWorker.PREF_COLLECTION ->
                    WallpaperWorker.enqueueLoad(requireContext(), ignoreRequirements = true)
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            // Edge-to-edge (enforced on Android 15 / targetSdk 35): pad the
            // preference list for the status and navigation bars so it never
            // renders underneath them.
            view?.let { root ->
                ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
                    val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                    (v as? ViewGroup)?.apply {
                        setPadding(
                            paddingLeft + bars.left,
                            paddingTop + bars.top,
                            paddingRight + bars.right,
                            paddingBottom + bars.bottom
                        )
                    }
                    insets
                }
            }

            findPreference<EditTextPreference>(WallpaperWorker.PREF_SERVER_URL)?.apply {
                setOnBindEditTextListener { it.inputType = InputType.TYPE_TEXT_VARIATION_URI }
            }

            findPreference<EditTextPreference>(WallpaperWorker.PREF_API_TOKEN)?.apply {
                setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    // Always open empty: the stored token never appears on
                    // screen, and a cancelled dialog leaves it untouched.
                    it.setText("")
                }
                // Show the mask once a token is stored.
                if (!TokenStore.getToken(requireContext()).isNullOrBlank() &&
                    text != MASKED_TOKEN
                ) {
                    text = MASKED_TOKEN
                }
            }

            findPreference<EditTextPreference>(WallpaperWorker.PREF_COLLECTION)?.apply {
                setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_TEXT }
            }

            findPreference<Preference>("syncStatus")?.apply {
                summary = SyncStatus.read(requireContext())
            }

            findPreference<Preference>("syncNow")?.setOnPreferenceClickListener {
                WallpaperWorker.enqueueLoad(requireContext(), ignoreRequirements = true)
                Toast.makeText(requireContext(), "Sync started", Toast.LENGTH_SHORT).show()
                true
            }

            findPreference<Preference>("collectionPicker")?.apply {
                val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                summary = prefs.getString(WallpaperWorker.PREF_COLLECTION, null)
                    ?.takeIf { it.isNotBlank() }?.let { "Current: $it" }
                    ?: "Pick from the server's list"
                setOnPreferenceClickListener {
                    val server = prefs.getString(WallpaperWorker.PREF_SERVER_URL, null)
                    if (server.isNullOrBlank() || TokenStore.getToken(requireContext()) == null) {
                        Toast.makeText(requireContext(), "Set the server URL and API token first", Toast.LENGTH_SHORT).show()
                    } else {
                        showCollectionPicker(server)
                    }
                    true
                }
            }
        }

        private fun showCollectionPicker(server: String) {
            val ctx = requireContext()
            val dialog = MaterialAlertDialogBuilder(ctx)
                .setTitle("Collections")
                .setMessage("Loading\u2026")
                .setNegativeButton("Cancel", null)
                .create()
            dialog.show()

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val names = MonbooruApi.listCollections(server, TokenStore.getToken(ctx)!!)
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        if (names.isEmpty()) {
                            // A dialog's content is fixed after show(); dismiss
                            // the "Loading" one and show a fresh dialog.
                            dialog.dismiss()
                            MaterialAlertDialogBuilder(ctx)
                                .setTitle("Collections")
                                .setMessage("No collections found on the server")
                                .setNegativeButton("Cancel", null)
                                .show()
                        } else {
                            // A dialog's content view is fixed after show();
                            // dismiss the "Loading" one and show the list in a
                            // fresh dialog. Keep the fresh dialog's reference —
                            // the click handler must dismiss THIS one (the old
                            // "dialog" var is already gone, and dismissing it
                            // silently left the popup stuck on screen).
                            dialog.dismiss()
                            val current = PreferenceManager.getDefaultSharedPreferences(ctx)
                                .getString(WallpaperWorker.PREF_COLLECTION, null)
                            val checkedIndex = names.indexOf(current)
                            val listDialog = MaterialAlertDialogBuilder(ctx)
                                .setTitle("Collections")
                                .setSingleChoiceItems(names.toTypedArray(), checkedIndex) { d, which ->
                                    val picked = names[which]
                                    PreferenceManager.getDefaultSharedPreferences(ctx)
                                        .edit().putString(WallpaperWorker.PREF_COLLECTION, picked).apply()
                                    findPreference<Preference>("collectionPicker")?.summary = "Current: $picked"
                                    d.dismiss()
                                }
                                .setNegativeButton("Cancel", null)
                                .create()
                            listDialog.show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        dialog.dismiss()
                        MaterialAlertDialogBuilder(ctx)
                            .setTitle("Collections")
                            .setMessage("Failed: ${e.message ?: "network error"}")
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
        }

        // Polls the sync status once a second while settings is on screen,
        // so "Syncing…" flips to the result without leaving the screen.
        private val statusTick = object : Runnable {
            override fun run() {
                if (!isAdded) return
                findPreference<Preference>("syncStatus")?.let { p ->
                    val text = SyncStatus.read(requireContext())
                    if (p.summary != text) p.summary = text
                }
                view?.postDelayed(this, 1000)
            }
        }

        override fun onResume() {
            super.onResume()
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .registerOnSharedPreferenceChangeListener(prefListener)
            statusTick.run()
        }

        override fun onPause() {
            view?.removeCallbacks(statusTick)
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .unregisterOnSharedPreferenceChangeListener(prefListener)
            super.onPause()
        }
    }
}
