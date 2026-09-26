# ProGuard rules for testApp

# Keep test activity for instrumentation
-keep class dev.ujhhgtg.lsparanoid.testapp.MainActivity {
    public <init>(...);
    public <methods>;
}

# Keep test utility class
-keep class dev.ujhhgtg.lsparanoid.testapp.StringUtils {
    public static <methods>;
}

# Standard Android rules
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# Fix for missing ErrorProne annotations
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**

# Note: LSParanoid's consumer rules from core module will be automatically
# applied to keep the Deobfuscator infrastructure intact

# Called from the separate instrumentation APK in minified runtime tests.
-keep class dev.ujhhgtg.lsparanoid.testapp.SentinelStrings {
    public static <methods>;
}

# AndroidX Test shares the target APK's Kotlin runtime. Keep it in this fixture because
# the separately compiled runner uses Kotlin paths absent from the Java application.
# This rule is deliberately not a plugin consumer rule.
-keep class kotlin.** { *; }

-keep class dev.ujhhgtg.lsparanoid.testapp.ResourceFixture {
    public static <methods>;
}

# These non-final IDs are accessed from the separately compiled instrumentation APK.
-keep class dev.ujhhgtg.lsparanoid.testapp.R$string { *; }
-keep class dev.ujhhgtg.lsparanoid.testapp.R$plurals { *; }
-keep class dev.ujhhgtg.lsparanoid.testapp.R$array { *; }
