# Shizuku 通过 ContentProvider + Binder 工作，且 newProcess 是反射调用，全部保留
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-keepclassmembers class rikka.shizuku.Shizuku { *; }

# 四大组件（manifest 已引用，显式再保一遭）
-keep class com.operit.xmode.MainActivity { *; }
-keep class com.operit.xmode.MonitorService { *; }

# 保留行号，便于崩溃定位
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
