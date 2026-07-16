# ONNX Runtime
-keep class ai.onnxruntime.** { *; }

# Keep data classes for Gson / Room
-keep class com.holopengin.instantjpdict.data.** { *; }
-keep class com.holopengin.instantjpdict.LineResult { *; }

# Keep everything in our main package to avoid issues with reflection/JNI
-keep class com.holopengin.instantjpdict.** { *; }

# Keep UniFFI generated classes
-keep class uniffi.nav_graph_core.** { *; }

# JNA Rules
-keep class com.sun.jna.** { *; }
-keepclassmembers class com.sun.jna.** {
    public *;
}

# Added to fix R8 missing classes warnings
-dontwarn java.awt.Component
-dontwarn java.awt.GraphicsEnvironment
-dontwarn java.awt.HeadlessException
-dontwarn java.awt.Window
