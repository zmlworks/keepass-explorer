# No reflection, no serialization: nothing to keep beyond the defaults.

# kotpass (vendored)
-keep class app.keemobile.kotpass.** { *; }
-dontwarn app.keemobile.kotpass.**
-dontwarn org.apache.commons.lang3.**
-dontwarn org.redundent.**

# zxcvbn4j loads its dictionaries / keyboard maps as Java resources next to its classes: keep the package names
-keep class com.nulabinc.zxcvbn.** { *; }
