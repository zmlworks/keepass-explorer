# KeePass Explorer (v0.1.0 試作版)

昔の Windows (95/98/2000) のエクスプローラー風の見た目で使う、KeePass (KDBX) 形式のパスワード／メモ帳アプリ (Android)。
A KeePass (KDBX) password & memo manager for Android with a classic Windows-Explorer look. Japanese UI, light only.

- **フォルダー = KeePass のグループ、テキスト文書 = エントリ。** サブフォルダーのあるフォルダーは「大きいアイコン」表示、
  エントリだけのフォルダーは 1 行 1 件の「一覧」表示 (表示メニューでフォルダーごとに固定可。設定は Group CustomData `KPExplorer.View`)。
- **メモだけのエントリも普通に使えます** (タイトル + メモだけ)。メモ型のエントリはメモ欄が先頭に大きく出ます。
- **タグ**: スペース区切りで入力 (KDBX の標準 Tags に `;` 区切りで保存)。データベース内で使われているタグを候補ボタンで表示。
- **操作**: フォルダー／エントリの作成・名前の変更・削除 (ごみ箱 → 完全削除で DeletedObjects に記録)、
  上へ／下へ／先頭／末尾への並べ替え (XML の文書順)、切り取り → 貼り付け、またはツリーから「フォルダーへ移動」
  (LocationChanged と PreviousParentGroup を更新)。ツリーの開閉は Group の IsExpanded に保存。編集前の内容は履歴 (History) に残ります。
- **マスターキー**: マスターパスワードのみ。データベースはアプリ専用領域に 1 つ。合成鍵 (SHA-256 の 32 バイト、パスワードそのものではない)
  を Android キーストアの AES-256-GCM 鍵 (強い生体認証が毎回必要・指紋の再登録で無効化) で暗号化して保存し、普段は指紋で解除。
  パスワードでも解除できます。ロック時はメモリ上のデータベースへの参照を捨てます。
- **自動保存**: 変更のたびに保存 (保存前に読み直して検証し、一時ファイル → rename で置き換え)。
- **バックアップ**: フロッピーのボタン 1 回で `backups/2026-10-21.kdbx` (同じ日は `_2`, `_3`…)。一覧画面からエクスポート (保存先を選ぶ)、
  共有、復元 (現在の内容は自動でバックアップ)、削除。
- コピー: 長押しメニューでユーザー名／パスワードをコピー (パスワードは「機密」扱い、45 秒後に消去)。
- 権限はなし (INTERNET なし)。USE_BIOMETRIC のみ。Android 11 以上。

KeePass / KeePassXC / Keepass2Android と同じ KDBX 3.1 / 4 を読み書きします (保存したファイルは `keepassxc-cli` で確認済み)。
キーファイルが必要なデータベースは未対応。Twofish のファイルはインポート時にアプリ内のコピーを AES-256 に変換します。

## ビルド

JDK 17、Android SDK (platform 35, build-tools 35.0.0)。

```
./gradlew testDebugUnitTest lintRelease assembleRelease
```

リリース署名はリポジトリの外のプロパティファイル (`ZML_KEYSTORE_PROPS`、既定 `/workspace/android/keys/keystore.properties`) から。
無ければ未署名の APK になります。

テスト: KDBX の往復・移動・並べ替え・削除・タグ (`TreeTest`)、バックアップ名 (`StoreTest`)、画面の描画と操作 (`UiScreenshotsTest`, Robolectric;
画像は `KPX_SCREENS_DIR` に出力)。手元の .kdbx で試す場合は `KCK_KDBX_FIXTURES` に properties ファイルを指定 (`FixturesTest`; リポジトリには入れない)。
`CompatSampleTest` は `app/build/compat/kpx-sample.kdbx` (パスワード `kpx-sample`) を書き出すので、他の KeePass 実装で開いて確認できます。

## ライセンス

MIT © 2026 zmlworks。同梱ライブラリ (kotpass ほか) は [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) と [LICENSES/](LICENSES/) を参照。
Windows は Microsoft の商標、KeePass は Dominik Reichl によるソフトウェアです。このアプリはどちらとも無関係です。
