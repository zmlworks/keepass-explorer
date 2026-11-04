package io.github.zmlworks.kpexplorer

import android.app.Activity
import android.content.Intent
import android.graphics.Insets
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import io.github.zmlworks.kpexplorer.db.Store
import io.github.zmlworks.kpexplorer.sec.PinVault
import io.github.zmlworks.kpexplorer.sec.Vault
import io.github.zmlworks.kpexplorer.ui.Dlg
import io.github.zmlworks.kpexplorer.ui.Menus
import io.github.zmlworks.kpexplorer.ui.W

/** Single activity; each "window" (setup, logon, explorer, editor, backups) is a [Screen] swapped in [host]. */
class MainActivity : Activity() {
    interface Screen {
        val view: View
        /** Desktop screens (setup / logon) sit on the teal desktop. */
        val desktop: Boolean get() = false
        fun onBack(): Boolean = false
        fun onShown() {}
        fun onPause() {}
    }

    lateinit var store: Store
    lateinit var vault: Vault
    lateinit var pins: PinVault
    lateinit var host: FrameLayout
    var screen: Screen? = null
        private set
    var explorer: ExplorerScreen? = null
    private var stoppedAt = 0L
    private val results = HashMap<Int, (Int, Intent?) -> Unit>()
    private var nextReq = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        W.init(this)
        App.wipeOldData(this)
        store = App.store(this)
        vault = Vault(this)
        pins = PinVault(this)
        applySecure()
        host = FrameLayout(this)
        host.setOnApplyWindowInsetsListener { v, ins ->
            val bars: Insets = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime: Insets = ins.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsets.CONSUMED
        }
        setContentView(host)
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        route()
    }

    fun applySecure() {
        if (App.prefs(this).getBoolean("secure", true)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** Pick the right screen for the current state. */
    fun route() {
        when {
            !store.exists() -> show(SetupScreen(this))
            !Session.isOpen -> show(UnlockScreen(this))
            else -> showExplorer()
        }
    }

    fun showExplorer() {
        val e = explorer ?: ExplorerScreen(this).also { explorer = it }
        show(e)
        e.refresh()
    }

    fun show(s: Screen) {
        Menus.dismiss()
        screen?.onPause()
        screen = s
        host.removeAllViews()
        // the system status bar area (host padding) shows the classic navy title colour
        host.setBackgroundColor(if (s.desktop) W.DESKTOP else W.NAVY)
        host.addView(s.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(
                0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        }
        s.onShown()
    }

    fun lock() {
        screen?.onPause()
        Session.lock()
        explorer = null
        Dlg.last?.dismiss()
        route()
    }

    fun startForResult(i: Intent, cb: (Int, Intent?) -> Unit) {
        val rc = nextReq++
        results[rc] = cb
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, rc)
        } catch (t: Throwable) {
            results.remove(rc)
            Dlg.message(this, "エラー", "ファイル選択画面を開けませんでした。\n$t", 'x')
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        results.remove(requestCode)?.invoke(resultCode, data)
    }

    override fun onPause() {
        super.onPause()
        screen?.onPause()
        Session.flush()
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
        if (Session.isOpen && autoLockMinutes() == 0) lock()
    }

    override fun onStart() {
        super.onStart()
        val mins = autoLockMinutes()
        if (stoppedAt > 0 && Session.isOpen && mins >= 0 &&
            SystemClock.elapsedRealtime() - stoppedAt > mins * 60_000L) lock()
        else if (stoppedAt > 0 && !Session.isOpen && screen !is UnlockScreen && screen !is SetupScreen) route()
        stoppedAt = 0
    }

    /** 0 = immediately when leaving the app, -1 = never. */
    fun autoLockMinutes(): Int = App.prefs(this).getInt("autolock", 5)

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (Menus.current?.isShowing == true) { Menus.dismiss(); return }
        if (screen?.onBack() == true) return
        if (Session.isOpen) Session.lock()
        explorer = null
        finish()
    }

    override fun onDestroy() {
        if (isFinishing && Session.isOpen) Session.lock()
        Session.listener = null
        super.onDestroy()
    }
}
