package boo.komori.wallpaper

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
 * v1.9.4 rig — verifies the long-press highlight is clipped to the card's
 * rounded corners (her 2026-11-03 19:51 report: square ripple overflowing
 * the card corners).
 *
 * 1. Structure: after one full draw pass, every row's background is a
 *    MaskedRipple whose position matches its slot in the group
 *    (FIRST/LAST/MIDDLE/SINGLE) — the same TextView-header rule the
 *    decoration itself uses. RippleDrawable paints a bounded ripple only
 *    where the mask's alpha is set, so the mask IS the highlight shape.
 * 2. Geometry: the "Sync now" row (LAST of the first group: square top
 *    edge, rounded bottom corners — exactly the corner her long-press
 *    exposed) has its mask drawn to a bitmap: center and square top edge
 *    painted, rounded bottom corner NOT painted. Deterministic — no
 *    reliance on Robolectric animating the ripple.
 *
 * TokenStore is shadowed because EncryptedSharedPreferences/AndroidKeyStore
 * does not initialize under Robolectric. statusTick re-posts forever every
 * 1000ms, so only ever idle() (due tasks), never runToEndOfTasks().
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    shadows = [RippleMaskTest.ShadowTokenStore::class],
    qualifiers = "w411dp-h891dp-420dpi"
)
class RippleMaskTest {

    @Implements(TokenStore::class)
    class ShadowTokenStore {
        @Implementation
        fun getToken(context: Context): String? = null

        @Implementation
        fun setToken(context: Context, token: String?) {}
    }

    // Gradle runs unit tests with the module dir as cwd, so this lands in
    // app/build/ui-shots on the host.
    private val outDir = File("build/ui-shots").apply { mkdirs() }

    // ---- helpers -------------------------------------------------------

    private fun launchAndDraw(): Pair<Activity, RecyclerView> {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val activity = controller.get()
        val decor = activity.window.decorView
        // One full draw pass: PreferenceCardDecoration.onDraw runs during
        // it and swaps each row's background for its masked ripple.
        val bmp = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bmp))
        val list = findList(decor)
        assertTrue("RecyclerView not found in decor", list != null)
        return activity to list!!
    }

    private fun findList(v: View): RecyclerView? {
        if (v is RecyclerView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findList(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun titleOf(row: View): String =
        row.findViewById<TextView>(android.R.id.title)?.text?.toString() ?: "(no title)"

    // Non-header attached children, in child order (headers are the
    // bare-TextView roots, same rule the decoration uses).
    private fun visibleRows(list: RecyclerView): List<View> =
        (0 until list.childCount).map { list.getChildAt(it) }.filterNot { it is TextView }

    // Expected per-row position inside its group, computed independently
    // from the decoration: a bare-TextView root is a header and CLOSES the
    // previous group; first/last of a group get rounded top/bottom corners,
    // a lone row gets all four. Group boundaries MUST come from the
    // headers — filtering them out first would merge groups into one run.
    private fun expectedKinds(list: RecyclerView): List<Pair<View, RowPosition>> {
        val out = ArrayList<Pair<View, RowPosition>>()
        var run = ArrayList<View>()
        fun close() {
            if (run.isEmpty()) return
            run.forEachIndexed { i, row ->
                val kind = when {
                    run.size == 1 -> RowPosition.SINGLE
                    i == 0 -> RowPosition.FIRST
                    i == run.size - 1 -> RowPosition.LAST
                    else -> RowPosition.MIDDLE
                }
                out.add(row to kind)
            }
            run = ArrayList()
        }
        for (i in 0 until list.childCount) {
            val c = list.getChildAt(i)
            if (c is TextView) close() else run.add(c)
        }
        close()
        return out
    }

    // ---- tests ---------------------------------------------------------

    @Test
    fun everyRowBackgroundMatchesItsGroupPosition() {
        val (_, list) = launchAndDraw()
        val expected = expectedKinds(list)
        assertTrue("no rows found in the list", expected.isNotEmpty())
        for ((row, kind) in expected) {
            val bg = row.background
            assertTrue(
                "row '${titleOf(row)}' background is ${bg?.javaClass?.simpleName}, expected MaskedRipple",
                bg is MaskedRipple
            )
            assertEquals("row '${titleOf(row)}' mask position", kind, (bg as MaskedRipple).position)
        }
    }

    @Test
    fun maskGeometryMatchesTheCardSlice() {
        val (_, list) = launchAndDraw()
        val row = visibleRows(list).first { titleOf(it) == "Sync now" }
        val bg = row.background as MaskedRipple
        assertEquals("Sync now should be the LAST row of its group", RowPosition.LAST, bg.position)
        val bmp = Bitmap.createBitmap(row.width, row.height, Bitmap.Config.ARGB_8888)
        bg.mask.setBounds(0, 0, row.width, row.height)
        bg.mask.draw(Canvas(bmp))
        File(outDir, "ripple-mask-geometry.png").outputStream().use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        fun painted(x: Int, y: Int) = (bmp.getPixel(x, y) ushr 24) > 0
        assertTrue("mask paints the row center", painted(row.width / 2, row.height / 2))
        assertTrue("mask paints the square top edge", painted(6, 6))
        assertTrue("mask paints the bottom center edge", painted(row.width / 2, row.height - 7))
        assertFalse(
            "mask must NOT paint the rounded bottom corner (the exact spot that glowed past the card)",
            painted(6, row.height - 7)
        )
    }
}
