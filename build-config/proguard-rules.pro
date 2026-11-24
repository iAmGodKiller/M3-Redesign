-dontobfuscate
-dontoptimize
-keepattributes *
-keep class app.morphe.** {
  *;
}
-keep class com.google.** {
  *;
}
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn javax.lang.model.element.Modifier

-dontwarn app.morphe.extension.music.**
-keep class app.morphe.extension.music.patches.miniplayer.** { *; }
