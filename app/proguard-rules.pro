# R8 rules for the release build.
#
# The release build shrinks, optimizes and obfuscates the app, and strips unused
# resources. Anything reached only through reflection or generated code is
# invisible to R8, so it has to be named here.

# Keep release stack traces mappable back to source.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Generic signatures and runtime annotations: Retrofit reads the generic return
# type of every service method, kotlinx.serialization reads @Serializable.
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes RuntimeVisibleTypeAnnotations,AnnotationDefault

###############################################################################
# kotlinx.serialization
###############################################################################

# The compiler plugin emits a `$$serializer` class and a `Companion.serializer()`
# for each @Serializable class; both are reached only reflectively.

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# @Serializable objects hand out their serializer through INSTANCE.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# The API DTOs (com.memex.android.data.api) and domain models
# (com.memex.android.data.model) are the JSON wire format. Keep the annotated
# classes and their generated serializers whole.
-keep @kotlinx.serialization.Serializable class com.memex.android.** { *; }
-keep,includedescriptorclasses class com.memex.android.**$$serializer { *; }

###############################################################################
# Retrofit
###############################################################################

# MemexApiService is implemented by a runtime proxy built from the method
# annotations and generic return types.
-keep,allowobfuscation,allowshrinking interface com.memex.android.data.api.MemexApiService
-keepclassmembers,allowobfuscation interface com.memex.android.data.api.MemexApiService {
    @retrofit2.http.* <methods>;
}

# Retrofit reflects on these type arguments, including the Continuation
# parameter that suspend service methods carry.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

###############################################################################
# OkHttp
###############################################################################

# OkHttp probes for optional TLS providers that this app does not bundle.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

###############################################################################
# androidx.security:security-crypto (Tink)
###############################################################################

# Tink is compiled against Error Prone's compile-time-only annotations, which
# are not on the runtime classpath. R8 reports them as missing classes.
-dontwarn com.google.errorprone.annotations.**
