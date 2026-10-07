# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep TuneURL SDK classes (the native library looks them up by name)
-keep class com.dekidea.tuneurl.** { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# stations.json is read into these classes by field name.
-keep class com.tuneurlradio.app.domain.model.** { *; }
-keepattributes Signature, *Annotation*

# Keep readable line numbers in crash reports,
# and hide the original source file name.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile