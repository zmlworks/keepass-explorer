# No reflection, no serialization: nothing to keep beyond the defaults.

# kotpass (vendored)
-keep class app.keemobile.kotpass.** { *; }
-dontwarn app.keemobile.kotpass.**
-dontwarn org.apache.commons.lang3.**
-dontwarn org.redundent.**
