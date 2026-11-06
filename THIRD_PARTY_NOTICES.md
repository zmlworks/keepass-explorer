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


## Material Symbols (entry icons) — Apache License 2.0

Subset of [Material Symbols Outlined](https://github.com/google/material-design-icons) (© Google LLC):
`key`, `mail`, `public`, `wifi`, `restaurant`, `person`, `credit_card`, `account_balance`,
`shopping_cart`, `sports_esports`, `smartphone`, `lock`, `home`, `star`, `sticky_note_2`.
Bundled as `app/src/main/res/drawable/ms_*.xml`, tinted navy (`#000080`).
Full license: [`LICENSES/MaterialSymbols-Apache-2.0.txt`](LICENSES/MaterialSymbols-Apache-2.0.txt).
