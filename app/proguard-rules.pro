# TaskFlow R8 / ProGuard Configuration

# Preserve stack trace metadata cleanly without revealing local file paths
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# TaskFlow Firestore / Data Models, Tracking Models, & Repository Parsers
-keep class com.example.data.model.** { *; }
-keepclassmembers class com.example.data.model.** {
    <fields>;
    <methods>;
    <init>(...);
}
-keep class com.example.data.tracking.** { *; }
-keep class com.example.data.repository.** { *; }
-keep class com.example.util.AppVersionInfo { *; }
-keep class com.example.util.UnityAdsDiagnosticSnapshot { *; }

# Kotlin Metadata & Coroutines (required for reflection/serialization & Firebase Kotlin extensions)
-keep class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.**

# Firebase SDKs (Firestore, Auth, Functions, Remote Config, Storage, App Check, Play Integrity)
-keep class com.google.firebase.** { *; }
-keepclassmembers class * {
    @com.google.firebase.firestore.PropertyName <fields>;
    @com.google.firebase.firestore.PropertyName <methods>;
    @com.google.firebase.firestore.DocumentId <fields>;
    @com.google.firebase.firestore.ServerTimestamp <fields>;
    @com.google.firebase.firestore.Exclude <fields>;
    @com.google.firebase.firestore.Exclude <methods>;
    @com.google.firebase.firestore.IgnoreExtraProperties <fields>;
    @com.google.firebase.firestore.ThrowOnExtraProperties <fields>;
}
-dontwarn com.google.firebase.**
-dontwarn com.google.android.play.core.**
-dontwarn com.google.android.gms.**

# Credential Manager & Google Identity Services
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
-dontwarn androidx.credentials.**

# Unity Ads SDK (v4.12.0) reflection & WebView JavaScript bridges
-keepattributes JavascriptInterface
-keep class com.unity3d.ads.** { *; }
-keep class com.unity3d.services.** { *; }
-keep interface com.unity3d.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-dontwarn com.unity3d.**
-dontwarn com.google.ar.core.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

