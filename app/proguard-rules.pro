# ============================================================================
# Lean R8 / ProGuard rules. The UI and most code IS shrunk + obfuscated; only
# the JNI and reflection-reached surfaces below are kept so the app still runs.
# ============================================================================

# --- Neko libcore (gomobile) ----------------------------------------------
# Native code calls these generated classes and Lean's platform bridges by name.
-keep class libcore.** { *; }
-keep interface libcore.** { *; }
-keep class go.** { *; }
-keep interface go.** { *; }
-dontwarn go.**
-dontwarn libcore.**

# Our platform bridge is invoked from native. Keep the core package to protect
# JNI callbacks and service entry points from member renaming.
-keep class com.th3web.lean.core.** { *; }

# --- kotlinx.serialization -------------------------------------------------
# The on-disk store (StoreData/Profile/Subscription), the polymorphic Outbound
# sealed hierarchy (its "type" discriminator + @SerialName) and Settings/enums are
# (de)serialized reflectively via generated $$serializer classes. Keep the model +
# the serializer machinery, and the annotations the discriminator relies on.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,Signature,EnclosingMethod
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.th3web.lean.**$$serializer { *; }
-keepclasseswithmembers class com.th3web.lean.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# The serializable models + the Settings layer (polymorphic + enum decode).
-keep class com.th3web.lean.data.model.** { *; }
-keep class com.th3web.lean.data.Settings { *; }
-keep class com.th3web.lean.data.StoreData { *; }
-keepclassmembers class com.th3web.lean.data.** { <fields>; }
-dontnote kotlinx.serialization.**

# --- enums (serialization uses valueOf/values) -----------------------------
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- WorkManager (workers instantiated reflectively) -----------------------
-keep class * extends androidx.work.ListenableWorker { public <init>(...); }
-keep class * extends androidx.work.CoroutineWorker { public <init>(...); }

# --- misc: Compose/AGP ship their own rules; quiet known false positives ----
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
