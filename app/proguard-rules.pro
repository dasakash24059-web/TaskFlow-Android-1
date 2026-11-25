# TaskFlow R8 / ProGuard Configuration

# Preserve stack trace metadata cleanly without revealing local file paths
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# TaskFlow Firestore / Model POJOs & Serialization
-keepclassmembers class com.example.data.model.** {
    <fields>;
    <methods>;
}
-keep class com.example.data.model.** { *; }

# Firebase SDKs (Firestore, Auth, Functions, Remote Config, Storage, App Check, Play Integrity)
-keepclassmembers class * {
    @com.google.firebase.firestore.PropertyName <fields>;
    @com.google.firebase.firestore.PropertyName <methods>;
    @com.google.firebase.firestore.DocumentId <fields>;
    @com.google.firebase.firestore.ServerTimestamp <fields>;
    @com.google.firebase.firestore.Exclude <fields>;
    @com.google.firebase.firestore.Exclude <methods>;
}
-dontwarn com.google.firebase.**
-dontwarn com.google.android.play.core.**

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
