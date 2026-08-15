# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the SDK's default proguard files.

# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.clawmate.app.**$$serializer { *; }
-keepclassmembers class com.clawmate.app.** { *** Companion; }
