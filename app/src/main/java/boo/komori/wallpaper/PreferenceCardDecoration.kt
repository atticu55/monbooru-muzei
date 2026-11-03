package boo.komori.wallpaper

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RoundRectShape
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Where a row sits inside its category group — decides which of the card's
 *  rounded corners its press highlight must follow. */
enum class RowPosition { FIRST, MIDDLE, LAST, SINGLE }

/** Bounded ripple whose mask is rounded. Carries its [RowPosition] so the
 *  decoration can skip re-assigning an already-correct background. */
class MaskedRipple(
    color: ColorStateList,
    val mask: ShapeDrawable,
    val position: RowPosition
) : RippleDrawable(color, null, mask)

/**
 * v1.9 — draws one rounded Material 3 card behind each preference category
 * group (the Pixel Settings look: label above, card around the rows, gaps
 * between groups).
 *
 * androidx.preference 1.2.1 — the latest release, checked against Google
 * Maven — has no Material3 styles or group containers, and rows are
 * inflated as individual RecyclerView items, so a per-group card can only
 * exist as an ItemDecoration drawn behind the rows. Row layouts stay stock
 * (which also keeps SwitchPreferenceCompat on its working code path); only
 * the category header layout is swapped via themes.xml.
 *
 * Header detection uses no adapter internals at all: the library inflates
 * every category row as a bare TextView root (and our custom header layout
 * is one too), while every real preference row is a ViewGroup — so
 * "root is a TextView" IS the header marker. This replaces the earlier
 * getItemViewType approach, whose return semantics in 1.2.1 were never
 * verified against source.
 *
 * v1.9.4 — every preference row layout (the library's and our switch copy)
 * ships ?android:selectableItemBackground: a bounded ripple with a
 * RECTANGULAR mask. The card behind the row is rounded, so a long-press
 * highlight painted square corners past the card outline (Ana's report,
 * 2026-11-03 19:51). During each draw pass this decoration also swaps each
 * attached row's background for a ripple masked to that row's slice of the
 * card — first row rounds the top corners, last row the bottom, middle
 * rows stay square (their edges are internal seams) — reusing the card's
 * own radius, so highlight and card match by construction. The ripple
 * color is the same ?android:colorControlHighlight the stock background
 * used, so only the shape changes.
 */
class PreferenceCardDecoration(context: Context) : RecyclerView.ItemDecoration() {

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density + 0.5f).toInt()

    private val cardMargin = dp(16)            // screen edge -> card edge
    private val cornerRadius = 28f * density   // M3 extra-large corner
    private val labelBottomGap = dp(8)         // category label -> its card
    private val groupTopGap = dp(12)           // card -> next category label

    // v1.9.2: explicit day/night colors (values/ + values-night/) instead of
    // theme attributes — the container ladder runs OPPOSITE directions in
    // light and dark, so the attribute pair made the day page lighter than
    // its cards. The resource picks the right variant per configuration.
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.settings_card_bg)
    }

    // v1.9.2: thin gap lines between rows INSIDE a card — a strip of the
    // page color across the card at each row seam reads as a transparent
    // cut through the card (Pixel Settings look).
    private val gapPaint = Paint().apply {
        color = context.getColor(R.color.settings_page_bg)
    }

    // v1.9.4: the same highlight color the stock selectableItemBackground
    // used, so swapping backgrounds changes only the mask shape. Resolved
    // once from the activity theme (Material3 DayNight).
    private val rippleColor: ColorStateList = run {
        val tv = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.colorControlHighlight, tv, true)) {
            if (tv.resourceId != 0) {
                try {
                    context.resources.getColorStateList(tv.resourceId, context.theme)
                } catch (e: Exception) {
                    ColorStateList.valueOf(context.getColor(tv.resourceId))
                }
            } else {
                ColorStateList.valueOf(tv.data)
            }
        } else {
            // Theme did not define it: neutral translucent grey, never opaque.
            ColorStateList.valueOf(0x33808080.toInt())
        }
    }

    /** Adapter positions [start..end] of the rows of one category group. */
    private data class Group(val start: Int, val end: Int)

    /** Attached children by adapter position. */
    private fun attachedChildren(parent: RecyclerView): Map<Int, View> {
        val out = HashMap<Int, View>()
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val pos = parent.getChildAdapterPosition(child)
            if (pos != RecyclerView.NO_POSITION) out[pos] = child
        }
        return out
    }

    private fun adapterGroups(children: Map<Int, View>): List<Group> {
        if (children.isEmpty()) return emptyList()
        val out = ArrayList<Group>()
        var open = -1
        val lastPos = children.keys.max()
        for (i in 0..lastPos) {
            val child = children[i] ?: continue
            if (child is TextView) {
                // Header row: bare TextView root. Closes the previous group.
                if (open >= 0) {
                    out.add(Group(open, i - 1))
                    open = -1
                }
            } else if (open < 0) {
                open = i
            }
        }
        if (open >= 0) out.add(Group(open, lastPos))
        return out
    }

    // v1.9.4: rounded-rect mask for a row's slice of the card.
    // RoundRectShape radii order: tlx, tly, trx, try, brx, bry, blx, bly.
    private fun rippleFor(position: RowPosition): MaskedRipple {
        val r = cornerRadius
        val radii: FloatArray? = when (position) {
            RowPosition.FIRST -> floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            RowPosition.LAST -> floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
            RowPosition.SINGLE -> floatArrayOf(r, r, r, r, r, r, r, r)
            RowPosition.MIDDLE -> null
        }
        val mask = ShapeDrawable(RoundRectShape(radii, null, null)).apply {
            paint.color = Color.BLACK // mask uses alpha only
        }
        return MaskedRipple(rippleColor, mask, position)
    }

    /** Swap each row's rectangular ripple for one masked to its card slice.
     *  Runs on every draw pass; the per-row position check makes the steady
     *  state allocation-free, and rows that recycle into another group
     *  position get re-masked on the next frame. */
    private fun applyRippleMasks(children: Map<Int, View>, groups: List<Group>) {
        for (g in groups) {
            val size = g.end - g.start + 1
            for (p in g.start..g.end) {
                children[p]?.let { row ->
                    if (row is TextView) return@let // headers: no highlight
                    val want = when {
                        size == 1 -> RowPosition.SINGLE
                        p == g.start -> RowPosition.FIRST
                        p == g.end -> RowPosition.LAST
                        else -> RowPosition.MIDDLE
                    }
                    if ((row.background as? MaskedRipple)?.position != want) {
                        row.background = rippleFor(want)
                    }
                }
            }
        }
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        super.onDraw(c, parent, state)
        // Draw for attached children only; a partially scrolled group gets
        // its card clipped at the recycler edge, like the real Settings app.
        val children = attachedChildren(parent)
        if (children.isEmpty()) return
        val left = (parent.paddingLeft + cardMargin).toFloat()
        val right = (parent.width - parent.paddingRight - cardMargin).toFloat()
        val groups = adapterGroups(children)
        applyRippleMasks(children, groups)
        for (g in groups) {
            var top = Int.MAX_VALUE
            var bottom = Int.MIN_VALUE
            for (p in g.start..g.end) {
                children[p]?.let {
                    if (it.top < top) top = it.top
                    if (it.bottom > bottom) bottom = it.bottom
                }
            }
            if (top == Int.MAX_VALUE) continue
            c.drawRoundRect(
                RectF(left, top.toFloat(), right, bottom.toFloat()),
                cornerRadius, cornerRadius, bgPaint
            )
            // v1.9.2: 2dp strip of page color at each seam between
            // consecutive rows of this group — only when both neighbors are
            // attached (a seam at the recycler edge scrolls out cleanly).
            for (p in g.start until g.end) {
                val above = children[p]
                val below = children[p + 1]
                if (above != null && below != null) {
                    val seam = (above.bottom + below.top) / 2f
                    c.drawRect(
                        left, seam - dp(1), right, seam + dp(1), gapPaint
                    )
                }
            }
        }
    }

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        outRect.left = cardMargin
        outRect.right = cardMargin
        if (view is TextView) {
            // Header: gap above (small at list top, normal between groups)
            // and breathing room between label and its card below.
            outRect.top = if (parent.getChildAdapterPosition(view) == 0) dp(4) else groupTopGap
            outRect.bottom = labelBottomGap
        }
        // Rows inside a group stack with no extra offset; the gap to the
        // next group comes from that group's own header top offset.
    }
}
