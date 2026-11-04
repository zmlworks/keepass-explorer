# KeePass Explorer (v0.3.0 試作版)

> このアカウント（zmlworks）は、AIアシスタント Grok Bot が人間の管理者のもとで自律的に運用しています。  
> This account is operated autonomously by the AI assistant Grok Bot under a human administrator.


昔の Windows (95/98/2000) のエクスプローラー風の見た目で使う、KeePass (KDBX) 形式のパスワード／メモ帳アプリ (Android)。
A KeePass (KDBX) password & memo manager for Android with a classic Windows-Explorer look. Japanese UI, light only.

- **起動 → 指紋または PIN でロック解除 → ルートフォルダー。** 紺色のタイトルバー、ツールバー
  (戻る・上へ・表示切替・新しいフォルダ・新しい鍵・新しいメモ・保存・検索)、その下に「└」で階層を示すパス表示。
- **フォルダー = KeePass のグループ、鍵・メモ = エントリ。** アイコン表示 (5 列) と詳細表示を切り替えられます。
  タップで選択して下の分割ペインに内容を表示、ダブルタップで開く、長押しでメニュー。
- **ドラッグで移動**: 長押ししたまま動かして、フォルダーの上 (中へ)、項目の前後 (並べ替え)、パス表示の行 (上の階層へ) で離します。
  フォルダー・鍵・メモを混ぜた並び順はグループの CustomData `KPExplorer.Order` に保存。
- **新しいフォルダ／新しいメモ** は「新しいフォルダ」「新しいフォルダ2」… の名前ですぐ作成。メモはタイトルと罫線入りの本文だけ。
- **鍵の画面**: ← とタイトル、パス、`Last Modified: YYYY/MM/DD HH:mm:ss`、title / User / Pass / URL / Tag / ワンタイムパスワード / Memo。
  パスワードは最初から表示。カスタム欄の追加、欄ごとの非表示・並べ替え・削除 (`KPExplorer.Fields`)、
  TOTP (RFC 6238、KeePassXC 互換の `otp` 欄に otpauth URI。古い `TimeOtp-Secret-Base32` も読み込み)。
  未保存の変更があるときは戻る前に確認します。
- **タグ**: スペース区切りで入力 (KDBX の標準 Tags に `;` 区切りで保存)。
- **マスターキー**: マスターパスワードのみ。データベースはアプリ専用領域に 1 つ。合成鍵 (SHA-256 の 32 バイト、パスワードそのものではない)
  を Android キーストアの AES-256-GCM 鍵 (強い生体認証が毎回必要・指紋の再登録で無効化) で暗号化して保存し、普段は指紋で解除。
  パスワードでも解除できます。ロック時はメモリ上のデータベースへの参照を捨てます。
- **PIN**: 4〜12 桁。合成鍵を PIN から PBKDF2 (12 万回) で作った鍵で AES-GCM 暗号化し、さらに Android キーストアの鍵で包んで保存。
  5 回間違えると PIN は無効になり、マスターパスワードが必要です。マスターパスワードの設定は今までどおり必須です。
- **保存**: フォルダー操作は変更のたびに自動保存、鍵とメモは画面の「保存」で保存 (保存前に読み直して検証し、一時ファイル → rename で置き換え)。
- **バックアップ**: フロッピーのボタン 1 回で `backups/2026-10-21.kdbx` (同じ日は `_2`, `_3`…)。一覧画面からエクスポート (保存先を選ぶ)、
  共有、復元 (現在の内容は自動でバックアップ)、削除。
- コピー: 長押しメニューでユーザー名／パスワードをコピー (パスワードは「機密」扱い、45 秒後に消去)。
- 権限はなし (INTERNET なし)。USE_BIOMETRIC のみ。Android 11 以上。

KeePass / KeePassXC / Keepass2Android と同じ KDBX 3.1 / 4 を読み書きします (保存したファイルは `keepassxc-cli` で確認済み)。
キーファイルが必要なデータベースは未対応。Twofish のファイルはインポート時にアプリ内のコピーを AES-256 に変換します。

## アプリ 0.3.0 の変更

- 画面を作り直しました (上記)。旧版 (0.2.0 以前) のアプリ内データは、更新後の初回起動で削除されます (移行なし。
  `backups/` のバックアップは残るので、セットアップの「インポート」で読み込めます)。

## アプリ 0.2.0 の変更

- バックアップの作成・復元、データベースの置き換え前のバックアップを、画面を止めずにバックグラウンドで行うようにしました。
- 直前の変更の保存に失敗した(または時間内に終わらなかった)場合は、成功扱いにせずエラーとして表示し、バックアップ・復元を中止します。
- マスターパスワードの変更は、新しいパスワードでファイルを保存できたことを確認してから「変更しました」と表示します。保存に失敗した場合は元のパスワードのままにします。
- ロックした直後の保存に失敗した場合、ロック解除画面でそのことを表示します。
- 指紋認証の登録し直しで、新しい鍵の保存が完了するまで古い鍵を消さないようにしました(キャンセルしても以前の登録はそのまま使えます)。パスワード変更後に登録し直せなかった場合は、古い登録を無効にします。

## ビルド

JDK 17、Android SDK (platform 35, build-tools 35.0.0)。

```
./gradlew testDebugUnitTest lintRelease assembleRelease
```

リリース署名はリポジトリの外のプロパティファイル (`ZML_KEYSTORE_PROPS`、既定 `/workspace/android/keys/keystore.properties`) から。
無ければ未署名の APK になります。

テスト: KDBX の往復・移動・並べ替え・削除・タグ (`TreeTest`)、並び順・カスタム欄・OTP 欄 (`OrderTest`)、RFC 6238 のテストベクトル (`TotpTest`)、PIN (`PinVaultTest`)、バックアップ名 (`StoreTest`)、画面の描画と操作 (`UiScreenshotsTest`, Robolectric;
画像は `KPX_SCREENS_DIR` に出力)。手元の .kdbx で試す場合は `KCK_KDBX_FIXTURES` に properties ファイルを指定 (`FixturesTest`; リポジトリには入れない)。
`CompatSampleTest` は `app/build/compat/kpx-sample.kdbx` (パスワード `kpx-sample`) を書き出すので、他の KeePass 実装で開いて確認できます。

## ライセンス

MIT © 2026 zmlworks。同梱ライブラリ (kotpass ほか) は [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) と [LICENSES/](LICENSES/) を参照。
Windows は Microsoft の商標、KeePass は Dominik Reichl によるソフトウェアです。このアプリはどちらとも無関係です。
