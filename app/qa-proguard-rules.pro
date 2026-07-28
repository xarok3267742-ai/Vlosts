# Android test packages share these runtime libraries with the target APK. Keep
# their complete APIs in QA so test-only call sites remain valid after target R8.
-keep class androidx.tracing.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }

# Instrumentation is compiled separately and calls these target-APK APIs. R8 may
# still optimize and obfuscate them, but their classes and members must survive.
-keep,allowoptimization,allowobfuscation class com.vslot.app.** { *; }
-keep,allowoptimization,allowobfuscation class androidx.concurrent.futures.** { *; }
-keep,allowoptimization,allowobfuscation class androidx.core.os.** { *; }
-keep,allowoptimization,allowobfuscation class androidx.datastore.preferences.core.** { *; }
-keep,allowoptimization,allowobfuscation class androidx.fragment.app.** { *; }
-keep,allowoptimization,allowobfuscation class androidx.lifecycle.** { *; }
-keep,allowoptimization,allowobfuscation class androidx.navigation.** { *; }
-keep,allowoptimization,allowobfuscation class androidx.savedstate.** { *; }
