package boo.komori.wallpaper

import android.content.Context
import android.util.TypedValue
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.R as ATR
import com.google.android.material.R as MR
import com.google.android.material.textfield.TextInputLayout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File

/**
 * PHASE A measurement rig — dumps the REAL rendered UI (sizes, paddings,
 * colors, widget state) and renders PNG screenshots. No app behavior is
 * asserted; this exists to produce ground truth for the v1.9 craft pass.
 *
 * TokenStore is shadowed because EncryptedSharedPreferences/AndroidKeyStore
 * does not initialize under Robolectric.
 *
 * NOTE: statusTick re-posts forever every 1000ms. Only ever call
 * shadowOf(mainLooper).idle() (due tasks only) — NEVER runToEndOfTasks(),
 * which would loop forever on the re-posting runnable.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    shadows = [UiMeasureTest.ShadowTokenStore::class],
    qualifiers = "w411dp-h891dp-420dpi"
)
class UiMeasureTest {

    @Implements(TokenStore::class)
    class ShadowTokenStore {
        @Implementation
        fun getToken(context: Context): String? = null

        @Implementation
        fun setToken(context: Context, token: String?) {}
    }

    // Gradle runs unit tests with the module dir as cwd, so "build/ui-shots"
    // lands in app/build/ui-shots on the host (v1.8's "app/build/..." path
    // landed in app/app/build/...).
    private val outDir = File("build/ui-shots").apply { mkdirs() }

    // ---- helpers -------------------------------------------------------

    private fun launch(): SettingsActivity {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        return controller.get()
    }

    private fun render(view: View, name: String, sb: StringBuilder) {
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) {
            sb.appendLine("!! could not render $name: view not laid out (${w}x${h})")
            return
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val f = File(outDir, name)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        sb.appendLine("PNG: ${f.absolutePath} (${w}x${h}px)")
    }

    private fun dp(activity: SettingsActivity, px: Int) =
        String.format("%.1fdp", px / activity.resources.displayMetrics.density)

    private fun sp(activity: SettingsActivity, px: Float) =
        String.format("%.1fsp", px / activity.resources.displayMetrics.scaledDensity)

    private fun resolveColor(activity: SettingsActivity, attr: Int): String {
        val tv = TypedValue()
        return if (activity.theme.resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) {
                val ctx = activity.applicationContext
                "#%08x".format(
                    if (android.os.Build.VERSION.SDK_INT >= 23)
                        ctx.getColor(tv.resourceId) else @Suppress("DEPRECATION") ctx.resources.getColor(tv.resourceId)
                )
            } else "#%08x".format(tv.data)
        } else "(unresolved)"
    }

    private fun dumpTree(v: View, depth: Int, activity: SettingsActivity, sb: StringBuilder) {
        val pad = "  ".repeat(depth)
        val cls = v.javaClass.simpleName
        sb.appendLine(
            "$pad$cls id=0x${if (v.id != View.NO_ID) v.id.toString(16) else "none"} " +
                "vis=${v.visibility} shown=${v.isShown} " +
                "size=${v.width}x${v.height}px(${dp(activity, v.width)}x${dp(activity, v.height)}) " +
                "pad=${dp(activity, v.paddingLeft)}/${dp(activity, v.paddingTop)}/${dp(activity, v.paddingRight)}/${dp(activity, v.paddingBottom)}"
        )
        when (v) {
            is TextView -> sb.appendLine(
                "$pad  TEXT '${v.text}' size=${sp(activity, v.textSize)} " +
                    "bold=${v.paint.typeface?.isBold} style=${v.paint.typeface?.style} " +
                    "color=" + "#%08x".format(v.currentTextColor) + " lineCount=${v.lineCount}"
            )
        }
        if (cls.contains("Switch", ignoreCase = true)) {
            val track = try { (v as? androidx.appcompat.widget.SwitchCompat)?.trackDrawable } catch (_: Throwable) { null }
            val thumb = try { (v as? androidx.appcompat.widget.SwitchCompat)?.thumbDrawable } catch (_: Throwable) { null }
            sb.appendLine("$pad  SWITCH track=${track?.javaClass?.simpleName} thumb=${thumb?.javaClass?.simpleName}")
        }
        if (v is TextInputLayout) {
            sb.appendLine("$pad  TEXTINPUT boxBackgroundMode=${v.boxBackgroundMode} boxStrokeWidthPx=${v.boxStrokeWidth} hint=${v.hint}")
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) dumpTree(v.getChildAt(i), depth + 1, activity, sb)
        }
    }

    private fun reportHeader(activity: SettingsActivity, sb: StringBuilder) {
        val dm = activity.resources.displayMetrics
        sb.appendLine("density=${dm.density} scaledDensity=${dm.scaledDensity}")
        sb.appendLine("theme colorPrimary=${resolveColor(activity, ATR.attr.colorPrimary)}")
        sb.appendLine("theme onSurface=${resolveColor(activity, MR.attr.colorOnSurface)}")
        sb.appendLine("theme onSurfaceVariant=${resolveColor(activity, MR.attr.colorOnSurfaceVariant)}")
    }

    // ---- tests ---------------------------------------------------------

    @Test
    fun dumpDay() {
        val sb = StringBuilder()
        val activity = launch()
        reportHeader(activity, sb)
        sb.appendLine("---- SETTINGS LIST (day) ----")
        dumpTree(activity.window.decorView, 0, activity, sb)
        render(activity.window.decorView, "settings-day.png", sb)
        File(outDir, "findings-day.txt").writeText(sb.toString())
        println(sb)
    }

    @Test
    @Config(
        sdk = [35],
        shadows = [UiMeasureTest.ShadowTokenStore::class],
        qualifiers = "w411dp-h891dp-night-420dpi"
    )
    fun dumpNight() {
        val sb = StringBuilder()
        val activity = launch()
        reportHeader(activity, sb)
        sb.appendLine("---- SETTINGS LIST (night) ----")
        dumpTree(activity.window.decorView, 0, activity, sb)
        render(activity.window.decorView, "settings-night.png", sb)
        File(outDir, "findings-night.txt").writeText(sb.toString())
        println(sb)
    }

    @Test
    fun dumpDialogs() {
        val sb = StringBuilder()
        val activity = launch()
        val frag =
            activity.supportFragmentManager.findFragmentById(android.R.id.content)
                as SettingsActivity.SettingsFragment

        for ((key, png) in listOf(
            WallpaperWorker.PREF_SERVER_URL to "dialog-url.png",
            WallpaperWorker.PREF_API_TOKEN to "dialog-token.png"
        )) {
            SettingsActivity.MaterialEditTextDialogFragment.show(frag, key)
            shadowOf(Looper.getMainLooper()).idle()
            val df = frag.childFragmentManager.fragments.lastOrNull { it is SettingsActivity.MaterialEditTextDialogFragment } as? androidx.fragment.app.DialogFragment
            val dialog = df?.dialog
            if (dialog == null) {
                sb.appendLine("!! dialog for $key did not show")
            } else {
                sb.appendLine("---- DIALOG $key ----")
                dumpTree(dialog.window!!.decorView, 0, activity, sb)
                render(dialog.window!!.decorView, png, sb)
                dialog.dismiss()
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
        File(outDir, "findings-dialogs.txt").writeText(sb.toString())
        println(sb)
    }
}
