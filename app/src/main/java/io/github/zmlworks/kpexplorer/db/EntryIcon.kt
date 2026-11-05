package io.github.zmlworks.kpexplorer.db

import app.keemobile.kotpass.constants.PredefinedIcon
import app.keemobile.kotpass.models.Entry
import io.github.zmlworks.kpexplorer.ui.Glyph

/**
 * Per-entry icon chosen on the key screen. Stored twice so other KeePass apps show something sensible:
 * the standard KDBX IconID (closest PredefinedIcon) and CustomData "KPExplorer.Icon" = our id.
 */
object EntryIcon {
    const val CD = "KPExplorer.Icon"

    class Def(val id: String, val label: String, val glyph: Glyph.Kind, val std: PredefinedIcon)

    val ALL = listOf(
        Def("key", "鍵", Glyph.Kind.KEY, PredefinedIcon.Key),
        Def("mail", "メール", Glyph.Kind.MAIL, PredefinedIcon.Email),
        Def("web", "ウェブ", Glyph.Kind.GLOBE, PredefinedIcon.World),
        Def("wifi", "Wi-Fi", Glyph.Kind.WIFI, PredefinedIcon.WorldSocket),
        Def("food", "食べ物", Glyph.Kind.FOOD, PredefinedIcon.Apple),
        Def("user", "アカウント", Glyph.Kind.ACCOUNT, PredefinedIcon.Identity),
        Def("card", "カード", Glyph.Kind.CARD, PredefinedIcon.Money),
        Def("bank", "銀行", Glyph.Kind.BANK, PredefinedIcon.HomeBanking),
        Def("cart", "買い物", Glyph.Kind.CART, PredefinedIcon.Package),
        Def("game", "ゲーム", Glyph.Kind.GAME, PredefinedIcon.Star),
        Def("phone", "電話", Glyph.Kind.PHONE, PredefinedIcon.UserCommunication),
        Def("lock", "ロック", Glyph.Kind.LOCK, PredefinedIcon.PaperLocked),
        Def("home", "家", Glyph.Kind.HOME, PredefinedIcon.Home),
        Def("star", "お気に入り", Glyph.Kind.STAR, PredefinedIcon.Star),
        Def("note", "メモ", Glyph.Kind.NOTE, PredefinedIcon.Note),
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
