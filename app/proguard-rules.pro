# This file is part of AppListUploadBlocker.
#
# AppListUploadBlocker is free software: you can redistribute it and/or modify
# it under the terms of the GNU Affero General Public License as published by
# the Free Software Foundation, either version 3 of the License.
#
# R8 keep rules for the release build.
# The release variant enables isMinifyEnabled + isShrinkResources, so every
# reflection / JNI / framework entry point below MUST stay reachable.

# ---------------------------------------------------------------------------
# 1. LSPosed / libxposed module entry point
# ---------------------------------------------------------------------------
# The framework reads META-INF/xposed/java_init.list and instantiates the class
# named there by reflection. That file contains the literal string
# "io.github.niguangowo.applistblocker.ModuleEntry", so the class name and its
# no-arg constructor may not be renamed or removed.
-keep class io.github.niguangowo.applistblocker.ModuleEntry { *; }
-keepnames class io.github.niguangowo.applistblocker.ModuleEntry

# Any XposedModule subclass is a candidate entry point.
-keep class * extends io.github.libxposed.api.XposedModule { *; }
-keepnames class * extends io.github.libxposed.api.XposedModule

# libxposed API/service is invoked reflectively by the framework side.
-keep class io.github.libxposed.** { *; }
-keepclassmembers class io.github.libxposed.** { *; }
-dontwarn io.github.libxposed.**

# ---------------------------------------------------------------------------
# 2. Notes on non-kept classes
# ---------------------------------------------------------------------------
# BlockRecordStore / ServiceBridge are intentionally NOT kept: they are only ever
# reached by direct static references (no reflection, no manifest entry), so R8 may
# inline and rename them freely. Keeping them would only bloat the APK.
#
# SettingsActivity is kept by the aapt-generated rule for the manifest-declared
# activity; no extra rule is needed here.

# ---------------------------------------------------------------------------
# 3. DexKit
# ---------------------------------------------------------------------------
# DexKit bridges Java <-> a native library (libdexkit.so) over JNI and builds
# its query DSL reflectively. Renaming any of it breaks anchor matching at
# runtime.
-keep class org.luckypray.dexkit.** { *; }
-keepclassmembers class org.luckypray.dexkit.** { *; }
-keepnames class org.luckypray.dexkit.** { *; }
-dontwarn org.luckypray.dexkit.**

# ---------------------------------------------------------------------------
# 4. Attributes the framework and Compose runtime rely on
# ---------------------------------------------------------------------------
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes SourceFile,LineNumberTable

# ---------------------------------------------------------------------------
# 5. Compose / Kotlin metadata
# ---------------------------------------------------------------------------
# Compose ships its own consumer rules; these are belt-and-braces for the
# @Composable reflection surface and Kotlin metadata lookups.
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class ** {
    @androidx.compose.runtime.Composable <methods>;
}
