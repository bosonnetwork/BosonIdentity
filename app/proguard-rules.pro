# App-level R8 rules. The Boson stack's rules come from :core:boson's consumer rules; AndroidX, Hilt,
# CameraX and ML Kit ship their own.

-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod,*Annotation*

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**
