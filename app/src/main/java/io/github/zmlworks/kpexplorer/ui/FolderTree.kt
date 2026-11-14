package io.github.zmlworks.kpexplorer.ui

import io.github.zmlworks.kpexplorer.L
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
    private val onSelect: (UUID) -> Unit,
    /** Main-screen mode (Registry Editor-like): "(folders,keys,memos)" counts, current folder bold on a band. */
    private val showCounts: Boolean = false,
    var current: UUID? = null,
    /** When set (main path tree): a single tap on the label navigates into that folder. +/− still expands. */
    private val onJump: ((UUID) -> Unit)? = null,
    private val rowDp: Int = 38,
    /**
     * 0.4.10: long press on a row (screen x, y). Return true when a menu was shown; the release is then
     * ignored (no navigation). Returning false keeps the normal tap behaviour on release.
     */
    private val onLongPress: ((UUID, Int, Int) -> Boolean)? = null,
    /** 0.4.23: folders not shown (the hidden recycle bin). */
    private val hidden: Set<UUID> = emptySet()
) : LinearLayout(ctx) {

    private class Node(val g: Group, val depth: Int, val lastFlags: List<Boolean>, val last: Boolean)

    private val expanded = HashMap<UUID, Boolean>()

    /** One view per visible node, tagged with the group uuid (drop targets). */
    val rows = ArrayList<View>()

    init {
        current?.let { cur -> pathTo(root, cur)?.dropLast(1)?.forEach { expanded[it.uuid] = true } }
        orientation = VERTICAL
        setBackgroundColor(W.WINDOW)
        setPadding(W.dp(4), W.dp(4), W.dp(4), W.dp(4))
        rebuild()
    }

    private fun pathTo(g: Group, id: UUID): List<Group>? {
        if (g.uuid == id) return listOf(g)
        for (c in g.groups) pathTo(c, id)?.let { return listOf(g) + it }
        return null
    }

    fun isExpanded(g: Group) = g.uuid == root.uuid || (expanded[g.uuid] ?: g.expanded)

    private fun flatten(): List<Node> {
        val out = ArrayList<Node>()
        fun walk(g: Group, depth: Int, flags: List<Boolean>, last: Boolean) {
            out.add(Node(g, depth, flags, last))
            if (!isExpanded(g)) return
            val kids = g.groups.filter { it.uuid !in hidden }
            kids.forEachIndexed { i, c -> walk(c, depth + 1, flags + last, i == kids.size - 1) }
        }
        walk(root, 0, emptyList(), true)
        return out
    }

    fun rebuild() {
        removeAllViews(); rows.clear()
        for (n in flatten()) {
            val v = Row(context, n).apply { tag = n.g.uuid }
            rows.add(v)
            addView(v, LayoutParams(W.MATCH, W.dp(rowDp)))
        }
    }

    @SuppressLint("ViewConstructor")
    /** Icon bitmap used for the row of [id] (tests). */
    fun iconFor(id: UUID): android.graphics.Bitmap? = (rows.firstOrNull { it.tag == id } as? Row)?.icon

    /** Tap the node of [id] (tests). */
    fun tap(id: UUID) { rows.firstOrNull { it.tag == id }?.let { (it as Row).tapLabel() } }
    /** Long press the label of [id] (tests): same as holding the finger there past the long-press timeout. */
    fun longPress(id: UUID): Boolean = (rows.firstOrNull { it.tag == id } as? Row)?.fireLong() ?: false

    /** Tap the +/− box of [id] (tests). */
    fun toggle(id: UUID) {
        val v = rows.firstOrNull { it.tag == id } as? Row ?: return
        val e = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, (v.n.depth * W.dp(22)).toFloat(), v.height / 2f, 0); v.onTouchEvent(e); e.recycle()
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
        val hasKids = n.g.groups.any { it.uuid !in hidden }

        /** Test hook: same as a tap on the label. */
        fun tapLabel() { val e = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, width - 1f, height / 2f, 0); onTouchEvent(e); e.recycle() }

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
            // 0.4.14: the root is always "/" (as in the title bar and every other path); imported files whose
            // root group is called "Root" / "NewDatabase" … used to show that name here only.
            val label = folderLabel(n.depth, n.g.name)
            val isCur = showCounts && n.g.uuid == current
            p.isFakeBoldText = isCur
            val tx = ix + icon.width * sc + W.dp(5)
            val tw = p.measureText(label)
            val dis = n.g.uuid in disabled
            if (sel) {
                val q = Paint().apply { color = W.NAVY }
                c.drawRect(tx - W.dp(2).toFloat(), cy - W.dp(11).toFloat(), tx + tw + W.dp(3), cy + W.dp(11).toFloat(), q)
            }
            if (isCur && !sel) {
                val q = Paint().apply { color = W.LIGHT }
                c.drawRect(tx - W.dp(2).toFloat(), cy - W.dp(11).toFloat(), tx + tw + W.dp(3), cy + W.dp(11).toFloat(), q)
                q.color = W.NAVY; q.style = Paint.Style.STROKE; q.strokeWidth = W.px.toFloat(); q.pathEffect = android.graphics.DashPathEffect(floatArrayOf(W.px.toFloat(), W.px.toFloat()), 0f)
                c.drawRect(tx - W.dp(2).toFloat(), cy - W.dp(11).toFloat(), tx + tw + W.dp(3), cy + W.dp(11).toFloat(), q)
            }
            p.color = when { sel -> W.HILITE; dis -> W.SHADOW; else -> W.TEXT }
            c.drawText(label, tx.toFloat(), cy + W.dp(5).toFloat(), p)
            if (showCounts) {
                val cp = Paint(p).apply { color = W.SHADOW; isFakeBoldText = false; textSize = W.dp(13).toFloat() }
                c.drawText(" " + io.github.zmlworks.kpexplorer.db.Order.counts(n.g, hidden), tx + tw + W.dp(4), cy + W.dp(5).toFloat(), cp)
            }
        }

        private var downX = 0f; private var downY = 0f; private var longDone = false
        private val longRun = Runnable { fireLong() }

        fun fireLong(): Boolean {
            val cb = onLongPress ?: return false
            val loc = IntArray(2); getLocationOnScreen(loc)
            val shown = cb(n.g.uuid, loc[0] + downX.toInt(), loc[1] + downY.toInt())
            if (shown) {
                longDone = true
                try { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) } catch (_: Throwable) {}
            }
            return shown
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; longDone = false
                    if (onLongPress != null) postDelayed(longRun, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                    if (kotlin.math.abs(e.x - downX) > slop || kotlin.math.abs(e.y - downY) > slop) removeCallbacks(longRun)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> { removeCallbacks(longRun); return true }
                MotionEvent.ACTION_UP -> { removeCallbacks(longRun); if (longDone) { longDone = false; return true } }
                else -> return true
            }
            val cx = n.depth * indent
            if (hasKids && n.depth > 0 && e.x < cx + indent / 2 + W.dp(2)) {
                val now = !isExpanded(n.g)
                expanded[n.g.uuid] = now
                onToggle(n.g.uuid, now)
                rebuild()
            } else if (n.g.uuid !in disabled) {
                // Main directory tree: one tap navigates. Move dialog (onJump=null): select only.
                if (onJump != null) {
                    selected = n.g.uuid
                    onSelect(n.g.uuid)
                    onJump.invoke(n.g.uuid)
                    return true
                }
                selected = n.g.uuid
                onSelect(n.g.uuid)
                for (i in 0 until this@FolderTree.childCount) this@FolderTree.getChildAt(i).invalidate()
            }
            return true
        }
    }
}

/** 0.4.14: tree label — the root is always "/", whatever its group name in the file; unnamed folders "(名前なし)". */
fun folderLabel(depth: Int, name: String): String = if (depth == 0) "/" else name.ifEmpty { L.t("(名前なし)", "(no name)") }
