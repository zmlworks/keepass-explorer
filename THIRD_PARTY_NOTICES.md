# Third-party notices

The app code is MIT licensed, © 2026 zmlworks ([LICENSE](LICENSE)). Icons and pixel art are original work of the
project (drawn in code: `app/src/main/java/.../ui/Px.kt`, `art/make_icon.py`) in the style of classic Windows.

## kotpass (KeePass KDBX) — MIT

Vendored under `kotpass/` from [kotpass](https://github.com/keemobile/kotpass) (MIT, © 2021 Denis T.), the same copy
as in zmlworks' kanji-color-key, with local additions: `Credentials.fromComposite` (open/save with a raw 32-byte
KeePass composite key) and a Twofish cipher (`TwofishCipher`, engine from Bouncy Castle, see below).
License: [`kotpass/LICENSE-kotpass.txt`](kotpass/LICENSE-kotpass.txt).

## Bouncy Castle (crypto engines inside kotpass) — MIT-style Bouncy Castle License

`kotpass/src/main/kotlin/app/keemobile/kotpass/cryptography/engines/*` (Argon2, Blake2b, ChaCha, Salsa20, Twofish) and
`cryptography/padding/PKCS7Padding.kt` are ports of Bouncy Castle code, © 2000-2021 The Legion of the Bouncy Castle
Inc.; the license text is kept in each file header.

## Apache-2.0 components

Full text: [`LICENSES/Apache-2.0.txt`](LICENSES/Apache-2.0.txt).

- **kotlin-xml-builder** ([redundent/kotlin-xml-builder](https://github.com/redundent/kotlin-xml-builder)), vendored with
  kotpass under `kotpass/src/main/kotlin/org/redundent/kotlin/xml/` (license file kept there).
- **Apache Commons Lang** — a small subset vendored under `kotpass/src/main/kotlin/org/apache/commons/lang3/`
  (LICENSE and NOTICE kept there) and `org.apache.commons:commons-lang3` 3.17.0 as a build dependency.
  NOTICE: “Apache Commons Lang, Copyright 2001-2024 The Apache Software Foundation. This product includes software
  developed at The Apache Software Foundation (https://www.apache.org/).”
- **okio** 3.15.0 ([square/okio](https://github.com/square/okio)), © Square, Inc. — I/O for kotpass.
- **Kotlin standard library** 2.1.21, © JetBrains s.r.o. and Kotlin Programming Language contributors.

JUnit and Robolectric are test-only and not in the APK. No AndroidX, no Google Play services. Fonts are the phone's
system fonts.

"Windows" is a trademark of Microsoft Corporation; this project is not affiliated with Microsoft. "KeePass" is the
password manager by Dominik Reichl; this is an independent app that reads and writes the KeePass (KDBX) format.


Modifications to vendored kotpass (KeePass Explorer 0.3.3): BaseKdfProvider made public; added `keepKdfSalt` option to encoding to keep KDF salt/parameters.


## Fugue Icons (entry icons) — CC BY 3.0

Some icons by Yusuke Kamiyamane. Licensed under a [Creative Commons Attribution 3.0 License](https://creativecommons.org/licenses/by/3.0/).
Source: [Fugue Icons 3.5.6](https://p.yusukekamiyamane.com/) (© 2013 Yusuke Kamiyamane), official archive `fugue-icons-3.5.6.zip`.
16px icons used: `key`, `mail`, `globe`, `wi-fi`, `plate-cutlery`, `user`, `credit-card`, `bank`, `shopping-basket`, `controller`, `mobile-phone`, `lock`, `home`, `star`, `document-text`.
Bundled as `app/src/main/res/drawable-nodpi/fugue_<id>.png` (renamed only; pixels/colours unchanged).
Modification: shown enlarged by integer nearest-neighbour scaling. See [`LICENSES/Fugue-Icons-CC-BY-3.0.txt`](LICENSES/Fugue-Icons-CC-BY-3.0.txt).

## JetBrains Mono (font for field values) — SIL Open Font License 1.1

Copyright 2020 The JetBrains Mono Project Authors (https://github.com/JetBrains/JetBrainsMono), version 2.211.
Bundled as `app/src/main/res/font/kpx_mono.ttf` (since app 0.4.25): the Regular (weight 400)
instance of the variable font, subset to Latin characters and common punctuation, with the OpenType layout features
(code ligatures) removed so that passwords show every character as typed. The font name is unchanged (the font has
no Reserved Font Name). License: [`LICENSES/OFL-1.1-JetBrainsMono.txt`](LICENSES/OFL-1.1-JetBrainsMono.txt).

## Didact Gothic (font of the dotted logo on the unlock screen) — SIL Open Font License 1.1

Copyright 2016 The Didact Gothic Project Authors (il.basso.buffo@gmail.com), version 2.101.
Bundled as `app/src/main/res/font/kpx_logo.ttf` (since app 0.4.32): subset to the letters of "KeePass Explorer"
(K, e, P, a, s, E, x, p, l, o, r; kerning kept). It is only drawn as the dotted watermark of the unlock screen. The font
name is unchanged (the font has no Reserved Font Name). License: [`LICENSES/OFL-1.1-DidactGothic.txt`](LICENSES/OFL-1.1-DidactGothic.txt).

## Memo / folder / recycle-bin artwork

Original pixel art by this project (MIT). The memo icon is inspired by the classic notepad motif
(ruled sheets, top binding, translucent cover); it is not derived from any Microsoft asset.
