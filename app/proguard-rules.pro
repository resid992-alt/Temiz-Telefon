-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
-keep class az.temiz.MainActivity$Bridge { *; }
-keep class az.temiz.CleanReceiver { *; }
-keep class az.temiz.BootReceiver { *; }
-keep class az.temiz.CleanJobService { *; }
