# ONNX Runtime
-keep class ai.onnxruntime.** { *; }

# Keep data classes for Gson / Room
-keep class com.holopengin.instantjpdict.data.** { *; }
-keep class com.holopengin.instantjpdict.LineResult { *; }

# Keep everything in our main package to avoid issues with reflection/JNI
-keep class com.holopengin.instantjpdict.** { *; }
