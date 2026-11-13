package io.github.zmlworks.kpexplorer.db

import app.keemobile.kotpass.constants.PredefinedIcon
import app.keemobile.kotpass.models.Entry

/**
 * Per-entry icon chosen on the key screen. Stored twice so other KeePass apps show something sensible:
 * the standard KDBX IconID (closest PredefinedIcon) and CustomData "KPExplorer.Icon" = our id.
 * Pictures: Fugue Icons (Yusuke Kamiyamane, CC BY 3.0) — see FugueIcons. Independent memo items use Ico.notepad*.
 */
object EntryIcon {
    const val CD = "KPExplorer.Icon"

    /** 0.4.20: [label] follows the display language ([L]). */
    class Def(val id: String, private val ja: String, private val en: String, val std: PredefinedIcon) {
        val label: String get() = io.github.zmlworks.kpexplorer.L.t(ja, en)
    }

    val ALL = listOf(
        Def("key", "鍵", "Key", PredefinedIcon.Key),
        Def("mail", "メール", "Mail", PredefinedIcon.Email),
        Def("web", "ウェブ", "Web", PredefinedIcon.World),
        Def("wifi", "Wi-Fi", "Wi-Fi", PredefinedIcon.WorldSocket),
        Def("food", "食べ物", "Food", PredefinedIcon.Apple),
        Def("user", "アカウント", "Account", PredefinedIcon.Identity),
        Def("card", "カード", "Card", PredefinedIcon.Money),
        Def("bank", "銀行", "Bank", PredefinedIcon.HomeBanking),
        Def("cart", "買い物", "Shopping", PredefinedIcon.Package),
        Def("game", "ゲーム", "Game", PredefinedIcon.Star),
        Def("phone", "電話", "Phone", PredefinedIcon.UserCommunication),
        Def("lock", "ロック", "Lock", PredefinedIcon.PaperLocked),
        Def("home", "家", "Home", PredefinedIcon.Home),
        Def("star", "お気に入り", "Favourite", PredefinedIcon.Star),
        Def("note", "メモ", "Note", PredefinedIcon.Note),
    )

    fun def(id: String?): Def? = ALL.firstOrNull { it.id == id }

    /** Our id stored on the entry, else a match for its standard icon, else null (default key). */
    fun of(e: Entry): Def? = def(e.customData[CD]?.value) ?: when (e.icon) {
        PredefinedIcon.Email, PredefinedIcon.EmailBox -> def("mail")
        PredefinedIcon.World, PredefinedIcon.WorldStar, PredefinedIcon.WorldComputer -> def("web")
        PredefinedIcon.Money -> def("card")
        PredefinedIcon.HomeBanking -> def("bank")
        PredefinedIcon.Identity, PredefinedIcon.UserKey -> def("user")
        PredefinedIcon.Home -> def("home")
        else -> null
    }
}
