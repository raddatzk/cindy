# kotlinx.serialization ships consumer rules for its generated serializers.

# MediaPipe Tasks ships no consumer rules. Its native code looks up framework classes and methods
# by name over JNI, the lite protos are read reflectively by field name, and Flogger loads its
# backend by class name. Renaming or removing any of them only fails at runtime.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.common.flogger.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
# Graph profiling and graph templates, which the Tasks AAR does not bundle and Cindy never calls.
-dontwarn com.google.mediapipe.proto.CalculatorProfileProto$CalculatorProfile
-dontwarn com.google.mediapipe.proto.GraphTemplateProto$CalculatorGraphTemplate
# The inert data-transport stand-ins MediaPipe's usage logger links against (see NoOpTransport.kt).
-keep class com.google.android.datatransport.** { *; }
