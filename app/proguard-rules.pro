# Moshi / Retrofit reflective models
-keep class com.moviebox.tv.data.dto.** { *; }
-keepclassmembers class * { @com.squareup.moshi.Json *; }
-dontwarn okhttp3.**
-dontwarn retrofit2.**
