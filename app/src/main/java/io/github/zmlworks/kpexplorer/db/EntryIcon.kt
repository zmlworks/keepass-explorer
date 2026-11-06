package io.github.zmlworks.kpexplorer.db

import app.keemobile.kotpass.constants.PredefinedIcon
import app.keemobile.kotpass.models.Entry

/**
 * Per-entry icon chosen on the key screen. Stored twice so other KeePass apps show something sensible:
 * the standard KDBX IconID (closest PredefinedIcon) and CustomData "KPExplorer.Icon" = our id.
 * Pictures: Material Symbols (Outlined, navy) for most; "note" is an original Win9x notepad drawing — see MaterialIcons.
 */
object EntryIcon {
    const val CD = "KPExplorer.Icon"

    class Def(val id: String, val label: String, val std: PredefinedIcon)

    val ALL = listOf(
        Def("key", "鍵", PredefinedIcon.Key),
        Def("mail", "メール", PredefinedIcon.Email),
        Def("web", "ウェブ", PredefinedIcon.World),
        Def("wifi", "Wi-Fi", PredefinedIcon.WorldSocket),
        Def("food", "食べ物", PredefinedIcon.Apple),
        Def("user", "アカウント", PredefinedIcon.Identity),
        Def("card", "カード", PredefinedIcon.Money),
        Def("bank", "銀行", PredefinedIcon.HomeBanking),
        Def("cart", "買い物", PredefinedIcon.Package),
        Def("game", "ゲーム", PredefinedIcon.Star),
        Def("phone", "電話", PredefinedIcon.UserCommunication),
        Def("lock", "ロック", PredefinedIcon.PaperLocked),
        Def("home", "家", PredefinedIcon.Home),
        Def("star", "お気に入り", PredefinedIcon.Star),
        Def("note", "メモ", PredefinedIcon.Note),
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
