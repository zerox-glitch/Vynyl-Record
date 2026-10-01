# Vynyl native turntable — release shrinking rules.
#
# The renderer reaches OpenGL ES through the framework, so no reflection rules are
# required for it. Media3 ships its own consumer rules; these entries only protect the
# small reflective surface used by Android's view/Compose infrastructure.

-keepattributes SourceFile,LineNumberTable

# Keep the public controller/renderer API stable for integrators that subclass or reflect.
-keep public class com.vynylrecord.turntable.player.TurntableController { public *; }
-keep public class com.vynylrecord.turntable.player.TurntableUiState { *; }
-keep public class com.vynylrecord.turntable.model.** { *; }

# Compose / AndroidX
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }

# GLSurfaceView subclasses are instantiated by the framework.
-keep class * extends android.opengl.GLSurfaceView { *; }
-keep class * extends android.app.Activity { *; }
