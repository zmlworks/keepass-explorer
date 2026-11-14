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

    /**
     * The picker's order, related icons together (0.4.34): basics, communication, people and places, money and
     * shopping, leisure, transport, the rest. Ids never change (they are stored in the files); 0.4.34 only gave
     * "web", "mail", "user" and "phone" new pictures (green globe, paper plane, two people, a handset with a
     * screen) and added the rest. [std] is the standard KeePass icon written alongside for other apps.
     */
    val ALL = listOf(
        Def("key", "鍵", "Key", PredefinedIcon.Key),
        Def("lock", "ロック", "Lock", PredefinedIcon.PaperLocked),
        Def("info", "情報", "Info", PredefinedIcon.Info),
        Def("mail", "メール", "Mail", PredefinedIcon.Email),
        Def("web", "ウェブ", "Web", PredefinedIcon.World),
        Def("wifi", "Wi-Fi", "Wi-Fi", PredefinedIcon.WorldSocket),
        Def("phone", "スマートフォン", "Smartphone", PredefinedIcon.BlackBerry),
        Def("telephone", "電話", "Telephone", PredefinedIcon.UserCommunication),
        Def("user", "アカウント", "Account", PredefinedIcon.Identity),
        Def("work", "仕事", "Work", PredefinedIcon.ClipboardReady),
        Def("school", "学校", "School", PredefinedIcon.Book),
        Def("home", "家", "Home", PredefinedIcon.Home),
        Def("card", "カード", "Card", PredefinedIcon.Money),
        Def("bank", "銀行", "Bank", PredefinedIcon.HomeBanking),
        Def("cart", "買い物", "Shopping", PredefinedIcon.Package),
        Def("tag", "値札", "Price tag", PredefinedIcon.Money),
        Def("game", "ゲーム", "Game", PredefinedIcon.Star),
        Def("puzzle", "パズル", "Puzzle", PredefinedIcon.Parts),
        Def("video", "動画配信", "Video", PredefinedIcon.Screen),
        Def("music", "音楽", "Music", PredefinedIcon.CDRom),
        Def("ticket", "チケット", "Ticket", PredefinedIcon.PaperFlag),
        Def("car", "車", "Car", PredefinedIcon.Run),
        Def("train", "交通", "Transit", PredefinedIcon.Run),
        Def("food", "食べ物", "Food", PredefinedIcon.Apple),
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
        // 0.4.34: standard icons of other apps that now have a picture here
        PredefinedIcon.Info -> def("info")
        PredefinedIcon.BlackBerry -> def("phone")
        PredefinedIcon.UserCommunication -> def("telephone")
        PredefinedIcon.Parts -> def("puzzle")
        PredefinedIcon.Screen -> def("video")
        PredefinedIcon.CDRom -> def("music")
        else -> null
    }
}
