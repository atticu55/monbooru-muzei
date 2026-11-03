package boo.komori.wallpaper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

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

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        super.onDraw(c, parent, state)
        // Draw for attached children only; a partially scrolled group gets
        // its card clipped at the recycler edge, like the real Settings app.
        val children = attachedChildren(parent)
        if (children.isEmpty()) return
        val left = (parent.paddingLeft + cardMargin).toFloat()
        val right = (parent.width - parent.paddingRight - cardMargin).toFloat()
        for (g in adapterGroups(children)) {
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
