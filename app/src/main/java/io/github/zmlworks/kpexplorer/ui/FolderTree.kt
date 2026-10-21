package io.github.zmlworks.kpexplorer.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import app.keemobile.kotpass.models.Group
import java.util.UUID

/**
 * Explorer-style folder tree (dotted lines, +/− boxes). Expansion follows the KeePass Group IsExpanded flag;
 * toggling calls [onToggle] so the caller can persist it.
 */
@SuppressLint("ViewConstructor")
class FolderTree(
    ctx: Context,
    private var root: Group,
    var selected: UUID?,
    private val binId: UUID?,
    private val disabled: Set<UUID> = emptySet(),
    private val onToggle: (UUID, Boolean) -> Unit,
    private val onSelect: (UUID) -> Unit
) : LinearLayout(ctx) {

    private class Node(val g: Group, val depth: Int, val lastFlags: List<Boolean>, val last: Boolean)

    private val expanded = HashMap<UUID, Boolean>()

    init {
        orientation = VERTICAL
        setBackgroundColor(W.WINDOW)
        setPadding(W.dp(4), W.dp(4), W.dp(4), W.dp(4))
        rebuild()
    }

    fun isExpanded(g: Group) = g.uuid == root.uuid || (expanded[g.uuid] ?: g.expanded)

    private fun flatten(): List<Node> {
        val out = ArrayList<Node>()
        fun walk(g: Group, depth: Int, flags: List<Boolean>, last: Boolean) {
            out.add(Node(g, depth, flags, last))
            if (!isExpanded(g)) return
            g.groups.forEachIndexed { i, c -> walk(c, depth + 1, flags + last, i == g.groups.size - 1) }
        }
        walk(root, 0, emptyList(), true)
        return out
    }

    fun rebuild() {
        removeAllViews()
        for (n in flatten()) addView(Row(context, n), LayoutParams(W.MATCH, W.dp(38)))
    }

    @SuppressLint("ViewConstructor")
    private inner class Row(ctx: Context, val n: Node) : View(ctx) {
        val p = Paint().apply { isAntiAlias = true; textSize = W.dp(15).toFloat(); color = W.TEXT }
        val indent = W.dp(22)
        val icon = when {
            n.depth == 0 -> Px.computer16
            n.g.uuid == binId -> Px.bin16
            else -> Px.folder16
        }
        val hasKids = n.g.groups.isNotEmpty()

        private fun dotV(c: Canvas, x: Int, y0: Int, y1: Int) {
            val q = Paint().apply { color = W.SHADOW }
            var y = y0 - (y0 % (2 * W.px))
            while (y < y1) { c.drawRect(x.toFloat(), y.toFloat(), (x + W.px).toFloat(), (y + W.px).toFloat(), q); y += 2 * W.px }
        }
        private fun dotH(c: Canvas, x0: Int, x1: Int, y: Int) {
            val q = Paint().apply { color = W.SHADOW }
            var x = x0 - (x0 % (2 * W.px))
            while (x < x1) { c.drawRect(x.toFloat(), y.toFloat(), (x + W.px).toFloat(), (y + W.px).toFloat(), q); x += 2 * W.px }
        }

        override fun onDraw(c: Canvas) {
            val cy = height / 2
            // ancestor verticals (skip the root column)
            for (i in 1 until n.depth) if (!n.lastFlags[i]) dotV(c, i * indent - indent / 2 + indent / 2, 0, height)
            val cx = n.depth * indent
            if (n.depth > 0) {
                dotV(c, cx, 0, if (n.last) cy else height)
                dotH(c, cx, cx + indent / 2 + W.dp(4), cy)
            }
            if (hasKids && n.depth > 0) {
                val s = 9 * W.px / 2
                val box = Rect(cx - s, cy - s, cx + s + W.px, cy + s + W.px)
                val q = Paint()
                q.color = W.WINDOW; c.drawRect(box, q)
                q.color = W.SHADOW; q.style = Paint.Style.STROKE; q.strokeWidth = W.px.toFloat()
                c.drawRect(RectF(box.left + W.px / 2f, box.top + W.px / 2f, box.right - W.px / 2f, box.bottom - W.px / 2f), q)
                q.style = Paint.Style.FILL; q.color = W.DARK
                c.drawRect((cx - 2 * W.px).toFloat(), cy.toFloat(), (cx + 3 * W.px).toFloat(), (cy + W.px).toFloat(), q)
                if (!isExpanded(n.g)) c.drawRect(cx.toFloat(), (cy - 2 * W.px).toFloat(), (cx + W.px).toFloat(), (cy + 3 * W.px).toFloat(), q)
            }
            val sc = W.iconScale()
            val ix = cx + indent / 2 + W.dp(4)
            val iy = cy - icon.height * sc / 2
            val sel = n.g.uuid == selected
            val bmp = if (sel) Px.selected(icon) else icon
            c.drawBitmap(bmp, null, Rect(ix, iy, ix + icon.width * sc, iy + icon.height * sc), Paint())
            val label = n.g.name.ifEmpty { "(名前なし)" }
            val tx = ix + icon.width * sc + W.dp(5)
            val tw = p.measureText(label)
            val dis = n.g.uuid in disabled
            if (sel) {
                val q = Paint().apply { color = W.NAVY }
                c.drawRect(tx - W.dp(2).toFloat(), cy - W.dp(11).toFloat(), tx + tw + W.dp(3), cy + W.dp(11).toFloat(), q)
            }
            p.color = when { sel -> W.HILITE; dis -> W.SHADOW; else -> W.TEXT }
            c.drawText(label, tx.toFloat(), cy + W.dp(5).toFloat(), p)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action == MotionEvent.ACTION_DOWN) return true
            if (e.action != MotionEvent.ACTION_UP) return true
            val cx = n.depth * indent
            if (hasKids && n.depth > 0 && e.x < cx + indent / 2 + W.dp(2)) {
                val now = !isExpanded(n.g)
                expanded[n.g.uuid] = now
                onToggle(n.g.uuid, now)
                rebuild()
            } else if (n.g.uuid !in disabled) {
                selected = n.g.uuid
                onSelect(n.g.uuid)
                for (i in 0 until this@FolderTree.childCount) this@FolderTree.getChildAt(i).invalidate()
            }
            return true
        }
    }
}
