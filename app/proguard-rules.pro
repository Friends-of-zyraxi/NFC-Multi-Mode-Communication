# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# 保留行号信息：R8 压缩后仍可在崩溃堆栈中定位到源码行
-keepattributes SourceFile,LineNumberTable

# 隐藏原始源文件名，避免暴露包结构
-renamesourcefileattribute SourceFile

# Fluent UI 编译期使用了 @Parcelize（kotlin-parcelize），该注解在运行时不存在，
# 仅保留在库的类文件中，无需告警。以下两条对应 R8 生成的 missing_rules.txt。
-dontwarn kotlinx.android.parcel.Parcelize
-dontwarn kotlinx.parcelize.Parcelize