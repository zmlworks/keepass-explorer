package io.github.zmlworks.kpexplorer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ListView
import app.keemobile.kotpass.database.modifiers.modifyContent
import io.github.zmlworks.kpexplorer.db.Kdbx
import io.github.zmlworks.kpexplorer.db.Order
import io.github.zmlworks.kpexplorer.db.Store
import io.github.zmlworks.kpexplorer.db.Tree
import io.github.zmlworks.kpexplorer.sec.Vault
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.FieldEditSession
import io.github.zmlworks.kpexplorer.ui.Menus
import io.github.zmlworks.kpexplorer.ui.W
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** 0.4.19: key History copies hold only the changed fields; the selected item stays visible above the preview pane. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w411dp-h891dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Ux0419Test {
    private val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens").apply { File(this, "v0419").mkdirs() }
    private val key = Kdbx.compositeKey("sample")
    private lateinit var a: MainActivity
    private val ids = HashMap<String, UUID>()
    private val W_ = 411 * 3; private val H_ = 891 * 3

    private fun app() = RuntimeEnvironment.getApplication()
    private fun idle() { repeat(30) { ShadowLooper.idleMainLooper(); Thread.sleep(3) } }

    @Before fun setUp() {
        Session.lock(); Session.store = null; Session.debounceMs = 50
        Vault(app()).clear(); App.prefs(app()).edit().clear().commit()
        Vault.forceAvailableForTest = true
        App.store(app()).let { s -> s.delete(); s.backups().forEach { it.delete() } }
        File(app().filesDir, "data-version").writeText(App.DATA_VERSION.toString())
        var db = Kdbx.create("t", key, rootName = "", light = true)
        val root = Tree.root(db).uuid
        val g = Tree.newGroup("仕事"); db = Tree.addGroup(db, root, g); ids["G"] = g.uuid
        db = Tree.addGroup(db, root, Tree.newGroup("銀行"))
        for ((t, u) in listOf("Gmail" to "taro.yamada@gmail.com", "Amazon" to "taro_y", "家のWi-Fi" to "MyHome-5G",
                "ネット銀行 (メイン口座)" to "1234-5678-9012", "会社のVPN" to "t.yamada", "Steam" to "taro_games",
                "楽天" to "taro.r", "X (Twitter)" to "@taro")) {
            val e = Tree.newEntry(Tree.Fields(t, u, "p", "", "", emptyList())); db = Tree.addEntry(db, root, e); ids[t] = e.uuid
        }
        db = Tree.addEntry(db, root, Order.newMemo("買い物メモ"))
        // enough items that the last ones sit at the bottom of the list (behind where the preview pane opens)
        for (i in 1..30) { val k = Tree.newEntry(Tree.Fields("サービス%02d".format(i), "user$i", "p$i", "", "", emptyList())); db = Tree.addEntry(db, root, k); ids["S$i"] = k.uuid }
        val old = Tree.newEntry(Tree.Fields("古いアカウント", "old", "x", "", "", emptyList())); db = Tree.addEntry(db, g.uuid, old); ids["old"] = old.uuid
        db = Tree.moveToBin(db, old.uuid, "ごみ箱")
        App.store(app()).write(Kdbx.encodeVerified(db, keepSalt = true))
        Session.open(db); Session.store = App.store(app())
        a = Robolectric.buildActivity(MainActivity::class.java).setup().get(); idle()
        if (!Session.isOpen) Session.open(db)
        Session.store = a.store
        if (a.explorer == null) a.showExplorer(); idle()
    }
    @After fun tearDown() { runCatching { FieldEditSession.commitActive() }; Session.lock(); Vault.forceAvailableForTest = null; Menus.dismiss(); Dlg.last = null }

    private fun layoutHost() {
        a.host.measure(View.MeasureSpec.makeMeasureSpec(W_, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H_, View.MeasureSpec.EXACTLY))
        a.host.layout(0, 0, W_, H_); idle()
    }
    private fun shot(name: String, menuAt: Pair<Int, Int>? = null, dialog: Boolean = false) {
        layoutHost()
        val b = Bitmap.createBitmap(W_, H_, Bitmap.Config.ARGB_8888); val c = Canvas(b); a.host.draw(c)
        if (menuAt != null) Menus.lastView?.let { v ->
            v.measure(View.MeasureSpec.makeMeasureSpec(Menus.current?.width ?: W.dp(230), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H_, View.MeasureSpec.AT_MOST))
            v.layout(0, 0, v.measuredWidth, v.measuredHeight)
            c.save(); c.translate(menuAt.first.toFloat(), menuAt.second.toFloat()); v.draw(c); c.restore()
        }
        if (dialog) Dlg.last?.frame?.let { v ->
            v.measure(View.MeasureSpec.makeMeasureSpec(W_ - 72, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H_ - 300, View.MeasureSpec.AT_MOST))
            v.layout(0, 0, v.measuredWidth, v.measuredHeight)
            c.save(); c.translate(36f, ((H_ - v.measuredHeight) / 2).toFloat()); v.draw(c); c.restore()
        }
        FileOutputStream(File(out, name)).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun texts(v: View, acc: MutableList<String> = ArrayList()): List<String> {
        if (v is android.widget.TextView) acc.add(v.text.toString())
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), acc)
        return acc
    }
    private fun <T : View> find(v: View, cls: Class<T>): T? {
        if (cls.isInstance(v)) return cls.cast(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), cls)?.let { return it }
        return null
    }
    private fun details(): ExplorerScreen {
        val ex = a.explorer!!
        if (ex.iconMode) ex.toggleView()
        idle(); layoutHost(); layoutHost()
        val list = find(ex.view, ListView::class.java)!!
        assertTrue("rows laid out: ${list.childCount} h=${list.height}", list.childCount >= 6)
        return ex
    }
    private fun ev(act: Int, x: Float, y: Float, t: Long) = MotionEvent.obtain(0, t, act, x, y, 0)

    private fun grid(): android.widget.GridView = find(a.explorer!!.view, android.widget.GridView::class.java)!!
    private fun icons(): ExplorerScreen { val ex = a.explorer!!; if (!ex.iconMode) ex.toggleView(); idle(); layoutHost(); layoutHost(); return ex }
    private fun cellCenter(v: android.widget.AbsListView, id: UUID, fx: Float = 0.5f, fy: Float = 0.5f): Pair<Float, Float> {
        val ex = a.explorer!!
        val pos = ex.rows.indexOfFirst { it.uuid == id }
        val c = v.getChildAt(pos - v.firstVisiblePosition)!!
        val l = IntArray(2); c.getLocationInWindow(l)
        return (l[0] + c.width * fx) to (l[1] + c.height * fy)
    }
    private var t = 0L
    /** Events go through the window root (like a real finger): x/y local per view, raw = window coordinates. */
    private fun touch(act: Int, x: Float, y: Float) { t += 16; val e = MotionEvent.obtain(0, t, act, x, y, 0); a.host.rootView.dispatchTouchEvent(e); e.recycle() }
    private fun hold() { ShadowLooper.idleMainLooper(android.view.ViewConfiguration.getLongPressTimeout() + 60L, java.util.concurrent.TimeUnit.MILLISECONDS) }
    private fun menuShowing() = Menus.current?.isShowing == true
    private fun order() = a.explorer!!.rows.map { it.uuid }

    private fun s(id: Int) = app().getString(id)
    private fun labels(items: List<Menus.Item>) = items.map { if (it.sep) "|" else it.label }
    private val FILE = listOf("名前の変更", "|", "切り取り", "フォルダーを指定して移動", "一つ上の階層へ移動", "|", "削除")
    private fun row(id: UUID) = a.explorer!!.rows.first { it.uuid == id }
    private fun memoId() = a.explorer!!.rows.first { it.name == "買い物メモ" }.uuid
    private fun cellRect(v: android.widget.AbsListView, id: UUID): android.graphics.Rect {
        val pos = a.explorer!!.rows.indexOfFirst { it.uuid == id }
        val c = v.getChildAt(pos - v.firstVisiblePosition)!!
        val l = IntArray(2); c.getLocationOnScreen(l)
        return android.graphics.Rect(l[0], l[1], l[0] + c.width, l[1] + c.height)
    }
    private fun longPressAt(v: android.widget.AbsListView, id: UUID): android.graphics.Rect {
        val (x, y) = cellCenter(v, id)
        Menus.lastPlace = null
        touch(MotionEvent.ACTION_DOWN, x, y); hold()
        touch(MotionEvent.ACTION_UP, x, y); idle()
        assertTrue(menuShowing())
        return Menus.lastPlace!!
    }

    private fun entry(id: UUID) = Tree.findEntry(Session.db!!, id)!!.second
    private fun hist(id: UUID) = entry(id).history
    private fun mtime(id: UUID) = entry(id).times?.lastModificationTime
    private fun openKey(id: UUID): KeyScreen { val ks = KeyScreen(a, id, Tree.root(Session.db!!).uuid); a.show(ks); idle(); layoutHost(); return ks }
    private fun later() = Thread.sleep(5)
    private fun latestDialog() = org.robolectric.shadows.ShadowDialog.getLatestDialog() as io.github.zmlworks.kpexplorer.ui.ClassicDialog
    private fun clickButton(d: io.github.zmlworks.kpexplorer.ui.ClassicDialog, label: String) { d.buttonViews.first { it.text.toString() == label }.performClick(); idle() }
    private fun <T : View> findTag(v: View, tag: String): View? {
        if (v.tag == tag) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findTag<View>(v.getChildAt(i), tag)?.let { return it }
        return null
    }
    private fun withHistory(id: UUID, n: Int) {
        repeat(n) { i -> Session.update { Order.saveEntry(it, id, mapOf("Title" to Tree.title(entry(id)), "UserName" to "u$i", "Password" to "p$i"), emptyList(), null) } }
        idle()
    }


    private fun f(e: app.keemobile.kotpass.models.Entry, k: String) = e.fields[k]

    // ---------------------------------------------------------------- 1 changed-only history copies
    @Test fun keyHistory_keepsOnlyChangedFields() {
        val id = ids["Gmail"]!!
        // start: user, pass, URL, notes, a protected custom PIN, a plain custom field, tags, OTP
        Session.update { Order.saveEntry(it, id, mapOf("Title" to "Gmail", "UserName" to "taro.yamada@gmail.com", "Password" to "old-pass",
            "URL" to "https://mail.google.com", "Notes" to "回復用メール: a@example.com\n電話: 090", "PIN" to "1234", "部署" to "営業",
            Order.OTP to "otpauth://totp/x?secret=JBSWY3DPEHPK3PXP"), listOf("mail"), null) }   // gitleaks:allow (RFC test secret)
        Session.update { d -> Tree.modifyGroup(d, Tree.root(d).uuid) { g -> g.copy(entries = g.entries.map { e ->
            if (e.uuid != id) e else e.copy(history = listOf(), fields = app.keemobile.kotpass.models.EntryFields(e.fields + ("PIN" to app.keemobile.kotpass.models.EntryValue.Encrypted(
                app.keemobile.kotpass.cryptography.EncryptedValue.fromString("1234"))))) }) } }
        idle()
        fun cur() = entry(id).fields.entries.associate { it.key to it.value.content }
        // 1) password only
        Session.update { Order.saveEntry(it, id, cur() + ("Password" to "new-pass"), listOf("mail"), null) }
        var h = hist(id).last()
        assertEquals(setOf("Title", "Password"), h.fields.keys)
        assertEquals("Gmail", Tree.title(h)); assertEquals("old-pass", Tree.password(h))
        assertTrue(f(h, "Password") is app.keemobile.kotpass.models.EntryValue.Encrypted)
        assertTrue("no tags when unchanged", h.tags.isEmpty())
        assertTrue(h.customData.isEmpty()); assertTrue(h.history.isEmpty())
        assertTrue(Order.isChangedOnly(h))
        // 2) notes → a 1-character-context change; tags → the old tags; protected custom keeps its flag
        Session.update { Order.saveEntry(it, id, cur() + ("Notes" to "回復用メール: b@example.com\n電話: 090") + ("PIN" to "9999"), listOf("mail", "仕事"), null) }
        h = hist(id).last()
        assertEquals(setOf("Title", "Notes", "PIN"), h.fields.keys)
        assertEquals("- :a@\n+ :b@".replace(":", " "), Tree.notes(h).let { it })  // "- 「 a@」" style: one char each side
        assertEquals("1234", f(h, "PIN")!!.content); assertTrue(f(h, "PIN") is app.keemobile.kotpass.models.EntryValue.Encrypted)
        assertEquals(listOf("mail"), h.tags)
        // 3) a new custom field (was absent → kept empty), a removed one (old value), OTP removed (old URI)
        Session.update { Order.saveEntry(it, id, cur().filterKeys { it != "部署" && it != Order.OTP } + ("会員番号" to "A-1"), listOf("mail", "仕事"), null,
            removed = setOf(Order.OTP)) }
        h = hist(id).last()
        assertEquals(setOf("Title", "部署", "会員番号", Order.OTP), h.fields.keys)
        assertEquals("営業", f(h, "部署")!!.content); assertEquals("", f(h, "会員番号")!!.content)
        assertTrue(f(h, Order.OTP)!!.content.startsWith("otpauth://")); assertTrue(f(h, Order.OTP) is app.keemobile.kotpass.models.EntryValue.Encrypted)
        // 4) rename only → just the old title
        Session.update { Order.renameEntry(it, id, "Gmail (個人)") }
        h = hist(id).last()
        assertEquals(setOf("Title"), h.fields.keys); assertEquals("Gmail", Tree.title(h))
        // a valid KDBX file: written, verified and read back as it is
        Session.flush(); idle()
        val disk = Tree.findEntry(Kdbx.open(a.store.read(), key), id)!!.second
        assertEquals(hist(id).map { it.fields.keys }, disk.history.map { it.fields.keys })
        assertEquals("old-pass", Tree.password(disk.history[0]))
        assertEquals(4, disk.history.size)
        // the current entry is complete
        assertEquals("new-pass", Tree.password(disk)); assertEquals("A-1", disk.fields["会員番号"]!!.content)
    }

    @Test fun keyHistory_oldFullCopiesKept_layoutMakesNone() {
        val id = ids["Amazon"]!!
        // a full copy from 0.4.18 or KeePass stays as it is
        val full = entry(id).copy(history = listOf())
        Session.update { d -> Tree.modifyGroup(d, Tree.root(d).uuid) { g -> g.copy(entries = g.entries.map { if (it.uuid == id) it.copy(history = listOf(full)) else it }) } }; idle()
        var ks = openKey(id); ks.applyArrangement(ks.layout.reversed()); assertTrue(ks.save(silent = true)); idle()
        assertEquals(1, hist(id).size)
        ks = openKey(id); ks.boxes["UserName"]!!.field.setText("taro_y2"); assertTrue(ks.save(silent = true)); idle()
        assertEquals(2, hist(id).size)
        assertFalse(Order.isChangedOnly(hist(id)[0])); assertEquals(full.fields.keys, hist(id)[0].fields.keys)
        assertTrue(Order.isChangedOnly(hist(id)[1])); assertEquals(setOf("Title", "UserName"), hist(id)[1].fields.keys)
    }

    @Test fun keyHistoryViewer_showsChangedFields() {
        val id = ids["Gmail"]!!
        fun cur() = entry(id).fields.entries.associate { it.key to it.value.content }
        Session.update { Order.saveEntry(it, id, cur() + ("Notes" to "秘密の質問: 母の旧姓\n回答: 山田"), listOf(), null) }   // the first full… (empty → content)
        later(); Session.update { Order.saveEntry(it, id, cur() + ("Password" to "p-2") + ("Notes" to "秘密の質問: 母の旧姓\n回答: 田中"), listOf("mail"), null) }
        later(); Session.update { Order.renameEntry(it, id, "Gmail 個人") }
        idle()
        val ks = openKey(id)
        ks.historyLink!!.performClick(); idle()
        val v = io.github.zmlworks.kpexplorer.ui.HistoryViewer.current!!
        // #1 = rename
        assertTrue(texts(findTag<View>(v.frame, "history-changed-1")!!).any { it == "Gmail → Gmail 個人" })
        // #2 = pass + notes + tags
        val c2 = findTag<View>(v.frame, "history-changed-2")!!
        assertEquals("******", (findTag<View>(c2, "changed-Password") as android.widget.TextView).text.toString())
        val notes = findTag<View>(c2, "changed-Notes") as android.widget.TextView
        assertEquals("- 山田\n+ 田中".let { "- :山田\n+ :田中".replace(":", " ") }, notes.text.toString())
        val sp = notes.text as android.text.Spanned
        assertEquals(listOf(io.github.zmlworks.kpexplorer.ui.MemoDiffViewer.MINUS, io.github.zmlworks.kpexplorer.ui.MemoDiffViewer.PLUS),
            sp.getSpans(0, sp.length, android.text.style.ForegroundColorSpan::class.java).map { it.foregroundColor })
        assertTrue(texts(c2).toString(), texts(c2).any { it == "変わった欄: Pass、Memo" })
        assertNull(findTag<View>(c2, "changed-UserName"))
        // the password is shown only on 表示
        assertTrue(texts(c2).none { it == "p" })
        Dlg.last = v; shot("v0419/01-key-history-changed-fields.png", dialog = true)
        v.dismiss()
    }

    // ---------------------------------------------------------------- 2 the selected item stays above the preview
    private fun visibleInList(v: android.widget.AbsListView, id: UUID): Boolean {
        val pos = a.explorer!!.rows.indexOfFirst { it.uuid == id }
        val lp = if (v is ListView) pos + v.headerViewsCount else pos
        val c = v.getChildAt(lp - v.firstVisiblePosition) ?: return false
        return c.top >= v.paddingTop && c.bottom <= v.height - v.paddingBottom
    }
    private fun tapAndCheck(v: android.widget.AbsListView, id: UUID) {
        val ex = a.explorer!!
        assertNull(ex.selection); layoutHost()
        assertTrue("visible before the tap", visibleInList(v, id))
        val (x, y) = cellCenter(v, id)
        touch(MotionEvent.ACTION_DOWN, x, y); touch(MotionEvent.ACTION_UP, x, y); idle()
        repeat(3) { layoutHost(); idle() }
        assertEquals(id, ex.selection)
        assertTrue("the preview pane is open", texts(ex.view).any { it == Tree.title(entry(id)) })
        assertTrue("the selected item is fully above the preview pane", visibleInList(v, id))
    }

    @Test fun preview_keepsSelectedItemVisible_icons() {
        val ex = icons()
        val g = grid()
        // an item on the last fully visible row (near the bottom of the screen, where the preview pane opens)
        val last = ex.rows.indices.reversed().map { ex.rows[it].uuid }.first { visibleInList(g, it) }
        tapAndCheck(g, last)
        shot("v0419/02-icons-bottom-item-above-preview.png")
        // the very last item can also be brought above it
        ex.select(ex.rows.last().uuid); repeat(3) { layoutHost(); idle() }
        assertTrue(visibleInList(g, ex.rows.last().uuid))
    }

    @Test fun preview_keepsSelectedItemVisible_details() {
        val ex = details()
        var list = find(ex.view, ListView::class.java)!!
        // the last row visible before the preview opens (near the bottom of the screen)
        val last = ex.rows.indices.reversed().map { ex.rows[it].uuid }.first { visibleInList(list, it) }
        tapAndCheck(list, last)
        shot("v0419/03-details-bottom-item-above-preview.png")
        ex.select(ex.rows.last().uuid); repeat(3) { layoutHost(); idle() }
        list = find(ex.view, ListView::class.java)!!
        assertTrue(visibleInList(list, ex.rows.last().uuid))
    }
}
