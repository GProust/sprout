# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in $ANDROID_HOME/tools/proguard/proguard-android.txt

# --- Jetpack Glance (home-screen widget) ------------------------------------
# The widget no longer goes through GlanceAppWidget's update session — that
# accepted updates and silently never started, only ever on minified builds
# (see SproutWidget.kt). It still uses Glance to *compose* its RemoteViews,
# which the in-app self-test showed working in exactly those builds, so keep
# the library intact rather than re-open that question for the sake of a few
# kilobytes.
-keep class androidx.glance.** { *; }
