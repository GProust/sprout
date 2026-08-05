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

# That alone did NOT fix it. With the name pinned, a report still showed Glance
# aware of the widget ("Glance sees: 1"), onUpdate handing off cleanly, and
# provideGlance never running — while the in-app self-test composed and inflated
# the very same UI. So composition is fine and it is Glance's *update session*
# that dies, silently, and only in minified builds; a debug build of the same
# commit drives the same widget correctly on the same Android version.
#
# Glance starts that session through coroutines and reads the widget's state
# from DataStore before composing, none of it reachable from our code, so R8
# sees it as unused. Keep both libraries whole rather than guess which member
# went missing — the widget is broken in every release build, which is worth
# more than the handful of kilobytes.
-keep class androidx.glance.** { *; }
-keep class androidx.datastore.** { *; }
