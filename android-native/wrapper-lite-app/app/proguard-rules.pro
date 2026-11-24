-dontwarn org.jetbrains.annotations.**

-keepclasseswithmembernames class * {
    native <methods>;
}

-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

-keep class com.annieplayer.wrapperlite.MainActivity { *; }
-keep class com.annieplayer.wrapperlite.qemu.WrapperService { *; }
