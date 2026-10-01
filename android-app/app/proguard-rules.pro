# Vynyl Record — release shrinking rules.
#
# Nothing here is a workaround for a library: the app has no reflection-driven plugin systems, no
# serialisation framework and no JNI. These rules exist so that the release build keeps the few things
# that are looked up by name at runtime.

# Room generates implementations of the @Dao interfaces by name.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# Media3 / ExoPlayer loads renderers reflectively in a few places.
-dontwarn com.google.android.exoplayer2.**

# WorkManager instantiates Worker classes by name from the Room-persisted work specification.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
-keep class * extends androidx.work.WorkerParameters { *; }

# Kotlin metadata is used by Room's generated code and by the Compose compiler's stability checks.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,Signature,EnclosingMethod

# The bundle format is a contract with other installs of this app: do not rename its model classes.
-keep class com.vynylrecord.app.core.storage.bundle.** { *; }

# Keep line numbers so a crash report from a user is readable if they choose to send one by hand.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
