# Keep release behavior stable for desktop app packaging.
# We still allow shrink, but avoid obfuscation/optimization that can break
# runtime reflection/serialization in packaged builds.
-dontshrink
-dontoptimize
-dontobfuscate

# Preserve metadata frequently required by Kotlin/serialization/reflection.
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod,*Annotation*

# Application entry points and app code.
-keep class app.MainKt { *; }
-keep class ui.** { *; }
-keep class model.** { *; }
-keep class service.** { *; }

# Kotlin serialization.
-keep class kotlinx.serialization.** { *; }
-keep @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers class ** {
    *** Companion;
}
-keepclassmembers class **$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class **$$serializer { *; }
-keepnames class **$$serializer

# Avro / reflection-sensitive model access.
-keep class org.apache.avro.** { *; }
-keep class com.github.avrokotlin.avro4k.** { *; }

# Jackson is used internally by Avro schema parsing at runtime.
# Keep full Jackson API used by ObjectMapper bootstrap to avoid release-only NPEs.
-keep class com.fasterxml.jackson.** { *; }

# Optional dependency warnings from transitive libraries.
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn org.brotli.dec.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.xerial.snappy.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn org.eclipse.core.**
-dontwarn org.eclipse.emf.**
-dontwarn org.osgi.framework.**
-dontwarn org.objectweb.asm.**
-dontwarn com.google.common.hash.Hashing$Crc32cMethodHandles
-dontwarn org.apache.avro.reflect.ReflectionUtil
