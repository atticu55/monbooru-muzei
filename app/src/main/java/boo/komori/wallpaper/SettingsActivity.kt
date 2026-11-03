package boo.komori.wallpaper

import android.app.Dialog
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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

        // Base list padding captured before the first insets pass, so
        // re-dispatches (IME showing/hiding) can't stack padding on.
        private var basePadding: Rect? = null

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

            // Edge-to-edge insets are attached in onViewCreated (v1.8 did
            // it here, where `view` is always null, so it never attached and
            // the list drew under the status bar).

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

        // v1.8: the preference library's default EditTextPreference dialog is
        // the old AppCompat AlertDialog. Intercept it and show a Material 3
        // dialog with an outlined text field instead. Everything else
        // (e.g. ListPreference) falls through to the default behavior.
        override fun onDisplayPreferenceDialog(preference: Preference) {
            if (preference is EditTextPreference) {
                MaterialEditTextDialogFragment.show(this, preference.key)
            } else {
                super.onDisplayPreferenceDialog(preference)
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

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            // v1.9: one rounded M3 card behind each category group (Pixel
            // Settings look) — see PreferenceCardDecoration.
            listView.addItemDecoration(PreferenceCardDecoration(requireContext()))
            // v1.9.1: the library's DividerDecoration draws a full-width
            // line between every row by default. The card fill covers the
            // ones inside a group, leaving only strays between the cards.
            // M3 list guidance: dividers are for lists NOT in a container;
            // rows grouped in a card separate by gaps. Clear the drawable.
            setDivider(null)
            // Edge-to-edge (enforced on Android 15 / targetSdk 35): pad the
            // preference list for the status and navigation bars so it never
            // renders underneath them.
            ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                // v1.9.1: the system bar areas show the window background,
                // which is light in day mode — so the bar icons must track
                // the theme. Without this the default white status icons sat
                // invisible against the light background (Ana's day-mode
                // report). Done here, post-attach, because the insets
                // controller is only available once the view is attached
                // (same trap the v1.8 insets code fell into).
                val isDay = (resources.configuration.uiMode
                    and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES
                ViewCompat.getWindowInsetsController(v)?.apply {
                    isAppearanceLightStatusBars = isDay
                    isAppearanceLightNavigationBars = isDay
                }
                if (basePadding == null) {
                    basePadding = Rect(v.paddingLeft, v.paddingTop, v.paddingRight, v.paddingBottom)
                }
                val b = basePadding!!
                (v as? ViewGroup)?.setPadding(
                    b.left + bars.left,
                    b.top + bars.top,
                    b.right + bars.right,
                    b.bottom + bars.bottom
                )
                insets
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

    /**
     * Material 3 replacement for the preference library's default
     * EditTextPreference dialog. Shown from
     * [SettingsFragment.onDisplayPreferenceDialog]; persists through the
     * [EditTextPreference] object (never SharedPreferences directly) so the
     * existing summary providers and the token-masking listener keep working.
     *
     * A plain [DialogFragment] so it survives rotation like the default
     * PreferenceDialogFragmentCompat does.
     */
    class MaterialEditTextDialogFragment : DialogFragment() {

        private val preferenceKey: String
            get() = requireArguments().getString(ARG_PREFERENCE_KEY)
                ?: error("MaterialEditTextDialogFragment requires the preference key argument")

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            // Resolve the live preference instance at dialog-creation time,
            // not a stale reference captured by the caller.
            val preference = (parentFragment as? SettingsFragment)
                ?.findPreference<EditTextPreference>(preferenceKey)
                ?: error("Preference $preferenceKey not found")

            val builder = MaterialAlertDialogBuilder(requireContext())
                .setTitle(preference.dialogTitle ?: preference.title)
                .setNegativeButton("Cancel", null)

            // Build the text field programmatically, inside a padded
            // container: MaterialAlertDialogBuilder adds no padding around a
            // custom view (measured in v1.8: the field ran edge to edge).
            // 24dp sides is the M3 dialog content spacing.
            val dp = resources.displayMetrics.density
            val container = FrameLayout(requireContext()).apply {
                setPadding(
                    (24 * dp).toInt(), (8 * dp).toInt(),
                    (24 * dp).toInt(), (16 * dp).toInt()
                )
            }
            val inputLayout = TextInputLayout(requireContext()).apply {
                // Deterministic outlined box: the theme's default
                // textInputStyle is not involved at all (v1.8 measurement
                // showed runtime resolution diverging from expectations).
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                hint = preference.dialogTitle ?: preference.title
            }
            val input = TextInputEditText(requireContext())
            // Token stays single-line and masked, like the v1.8 dialog
            // did; the URL is single-line URI.
            input.inputType = if (preferenceKey == WallpaperWorker.PREF_API_TOKEN) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            inputLayout.addView(input)
            container.addView(inputLayout)
            builder.setView(container)

            // Prefill: the token always opens empty (the stored token never
            // appears on screen); the other two show their current value.
            if (preferenceKey != WallpaperWorker.PREF_API_TOKEN) {
                input.setText(preference.text ?: "")
            }

            builder.setPositiveButton("Save") { _, _ ->
                // Persist through the preference object: this triggers the
                // summary providers and the token-masking listener exactly
                // like the old dialog did. Zero validation, as before.
                preference.text = input.text?.toString() ?: ""
            }

            return builder.create()
        }

        companion object {
            private const val ARG_PREFERENCE_KEY = "preference_key"
            private const val TAG = "MaterialEditTextDialog"

            fun show(fragment: SettingsFragment, key: String) {
                val args = Bundle().apply { putString(ARG_PREFERENCE_KEY, key) }
                val dialog = MaterialEditTextDialogFragment().apply { arguments = args }
                // Child manager: the dialog lives and dies with the settings
                // fragment, and survives rotation via the standard
                // DialogFragment state restoration.
                if (fragment.childFragmentManager.findFragmentByTag(TAG) != null) return
                dialog.show(fragment.childFragmentManager, TAG)
            }
        }
    }
}
