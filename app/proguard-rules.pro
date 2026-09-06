# MT 插件 SDK 官方模板默认规则
-keepattributes SourceFile,LineNumberTable,*Annotation*

# 保留插件对外接口类（框架通过反射实例化）
-keep class com.mtplugin.aieditor.AiEditToolMenu { *; }
-keep class com.mtplugin.aieditor.settings.AiEditPreference { *; }

# OkHttp（SDK 内置，保守保留）
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# bin.mt.json（SDK 内置 JSON 库）
-dontwarn bin.mt.json.**
-keep class bin.mt.json.** { *; }