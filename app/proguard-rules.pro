# Keep CPU Spy application code
-keep class com.bvalosek.cpuspy.** { *; }
-keepclassmembers class com.bvalosek.cpuspy.** { *; }

# Keep Shizuku classes and interfaces
-keep class moe.shizuku.** { *; }
-keep interface moe.shizuku.** { *; }
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-keep class dev.rikka.shizuku.** { *; }

# Keep Porter IPC and AIDL
-keep class eu.darken.porter.** { *; }
-keep interface eu.darken.porter.** { *; }
-keep class com.bvalosek.cpuspy.porter.** { *; }

# Attributes & warnings
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-dontwarn **
