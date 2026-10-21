# Keep kotpass crypto engines (reflection-free but R8 can strip unused cipher paths)
-keep class app.keemobile.kotpass.cryptography.engines.** { *; }
-keep class app.keemobile.kotpass.cryptography.format.** { *; }
-dontwarn org.apache.commons.lang3.**
