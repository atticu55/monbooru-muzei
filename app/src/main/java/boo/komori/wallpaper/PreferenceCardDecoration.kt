package boo.komori.wallpaper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
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
 * Header detection uses only public API: androidx.preference returns the
 * inflated layout resource ID as the RecyclerView view type, and every
 * category row inflates our custom layout, so that ID marks headers.
 */
class PreferenceCardDecoration(context: Context) : RecyclerView.ItemDecoration() {

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density + 0.5f).toInt()

    private val cardMargin = dp(16)            // screen edge -> card edge
    private val cornerRadius = 28f * density   // M3 extra-large corner
    private val labelBottomGap = dp(8)         // category label -> its card
    private val groupTopGap = dp(12)           // card -> next category label

    private val headerViewType = R.layout.preference_category_app

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val tv = TypedValue()
        if (context.theme.resolveAttribute(
                com.google.android.material.R.attr.colorSurfaceContainerHigh, tv, true)
        ) {
            color = if (tv.resourceId != 0) context.getColor(tv.resourceId) else tv.data
        }
    }

    /** Adapter positions [start..end] of the rows of one category group. */
    private data class Group(val start: Int, val end: Int)

    private fun isHeader(adapter: RecyclerView.Adapter<*>, pos: Int): Boolean =
        pos >= 0 && pos < adapter.itemCount &&
            adapter.getItemViewType(pos) == headerViewType

    private fun adapterGroups(parent: RecyclerView): List<Group> {
        val adapter = parent.adapter ?: return emptyList()
        val out = ArrayList<Group>()
        var open = -1
        for (i in 0 until adapter.itemCount) {
            if (isHeader(adapter, i)) {
                if (open >= 0) {
                    out.add(Group(open, i - 1))
                    open = -1
                }
            } else if (open < 0) {
                open = i
            }
        }
        if (open >= 0) out.add(Group(open, adapter.itemCount - 1))
        return out
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        super.onDraw(c, parent, state)
        // Draw for attached children only; a partially scrolled group gets
        // its card clipped at the recycler edge, like the real Settings app.
        val childByPos = HashMap<Int, View>()
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val pos = parent.getChildAdapterPosition(child)
            if (pos != RecyclerView.NO_POSITION) childByPos[pos] = child
        }
        if (childByPos.isEmpty()) return
        val left = (parent.paddingLeft + cardMargin).toFloat()
        val right = (parent.width - parent.paddingRight - cardMargin).toFloat()
        for (g in adapterGroups(parent)) {
            var top = Int.MAX_VALUE
            var bottom = Int.MIN_VALUE
            for (p in g.start..g.end) {
                childByPos[p]?.let {
                    if (it.top < top) top = it.top
                    if (it.bottom > bottom) bottom = it.bottom
                }
            }
            if (top == Int.MAX_VALUE) continue
            c.drawRoundRect(
                RectF(left, top.toFloat(), right, bottom.toFloat()),
                cornerRadius, cornerRadius, bgPaint
            )
        }
    }

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val adapter = parent.adapter ?: return
        val pos = parent.getChildAdapterPosition(view)
        if (pos == RecyclerView.NO_POSITION) return
        outRect.left = cardMargin
        outRect.right = cardMargin
        if (isHeader(adapter, pos)) {
            outRect.top = if (pos == 0) dp(4) else groupTopGap
            outRect.bottom = labelBottomGap
        } else {
            // Gap between a label and the first row comes from the label's
            // own bottom offset; rows inside a group stack with no gap.
            val lastOfGroup = pos == adapter.itemCount - 1 ||
                isHeader(adapter, pos + 1)
            outRect.bottom = if (lastOfGroup) dp(8) else 0
        }
    }
}
