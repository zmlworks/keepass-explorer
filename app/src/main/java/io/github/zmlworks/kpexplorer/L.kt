package io.github.zmlworks.kpexplorer

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 0.4.20: display language and date order (Menu → 表示の言語 / 日付の表示).
 *
 * Text written in Kotlin goes through [t] (Japanese, English) at the call site; Android string resources follow
 * the same choice because [wrap] gives the activity a context with that locale. Saved in the app settings.
 * Backup file names (yyyy-MM-dd_HH-mm-ss.kdbx) never change, so they stay sortable.
 */
object L {
    enum class Lang(val key: String) { SYSTEM("system"), JA("ja"), EN("en") }
    enum class DateOrder(val key: String, val pattern: String) {
        YMD("ymd", "yyyy/MM/dd"), MDY("mdy", "MM/dd/yyyy"), DMY("dmy", "dd/MM/yyyy")
    }
    const val PREF_LANG = "lang"
    const val PREF_DATE = "dateOrder"

    var lang: Lang = Lang.SYSTEM
        private set
    var dateOrder: DateOrder = DateOrder.YMD
        private set

    /** English text (else Japanese). SYSTEM = English unless the phone's language is Japanese. */
    val en: Boolean get() = when (lang) {
        Lang.JA -> false
        Lang.EN -> true
        Lang.SYSTEM -> systemLanguage() != "ja"
    }

    private fun systemLanguage(): String =
        runCatching { Resources.getSystem().configuration.locales[0].language }.getOrNull() ?: Locale.getDefault().language

    fun t(ja: String, en: String): String = if (this.en) en else ja

    fun load(ctx: Context) {
        val p = App.prefs(ctx)
        lang = Lang.entries.firstOrNull { it.key == p.getString(PREF_LANG, null) } ?: Lang.SYSTEM
        dateOrder = DateOrder.entries.firstOrNull { it.key == p.getString(PREF_DATE, null) } ?: DateOrder.YMD
    }

    fun setLang(ctx: Context, l: Lang) { lang = l; App.prefs(ctx).edit().putString(PREF_LANG, l.key).apply() }
    fun setDateOrder(ctx: Context, d: DateOrder) { dateOrder = d; App.prefs(ctx).edit().putString(PREF_DATE, d.key).apply() }

    /** Context for the activity: resources in the chosen language (SYSTEM = unchanged). */
    fun wrap(base: Context): Context {
        load(base)
        val tag = when (lang) { Lang.SYSTEM -> return base; Lang.JA -> "ja"; Lang.EN -> "en" }
        val cfg = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return base.createConfigurationContext(cfg)
    }

    /** Date only, e.g. 2026/11/14, 11/14/2026 or 14/11/2026. */
    fun date(): DateTimeFormatter = DateTimeFormatter.ofPattern(dateOrder.pattern).withZone(ZoneId.systemDefault())
    /** Date and time ([seconds] = HH:mm:ss, else HH:mm). */
    fun dateTime(seconds: Boolean = true): DateTimeFormatter =
        DateTimeFormatter.ofPattern(dateOrder.pattern + if (seconds) " HH:mm:ss" else " HH:mm").withZone(ZoneId.systemDefault())
}
