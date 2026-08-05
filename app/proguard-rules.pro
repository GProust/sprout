# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in $ANDROID_HOME/tools/proguard/proguard-android.txt

# --- Jetpack Glance (home-screen widget) ------------------------------------
# AGP generates keep rules from the manifest, so SproutWidgetReceiver keeps its
# name — but SproutWidget isn't named in the manifest, so R8 was free to rename
# it. A diagnostics report from a release build showed exactly that: the widget
# reported its own class as "pd2", the launcher had one instance placed, and
# onUpdate fired repeatedly with provideGlance never running behind it.
#
# Glance resolves a widget's update session and keys its persisted state by
# class. An obfuscated name isn't stable across builds — each release can rename
# the same class differently — so state written by one version stops matching the
# next. That fits the symptom being an *upgrade* that leaves the widget stuck on
# its loading spinner until the app is uninstalled and reinstalled.
#
# Keeping the widget type pins that name across releases.
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { *; }
