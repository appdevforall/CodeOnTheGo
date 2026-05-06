# Keep plugin classes referenced from the host IDE.
-keep class com.appdevforall.forms.plugin.FormsPlugin { *; }
-keep class com.appdevforall.forms.plugin.wizard.** { *; }
-keepclassmembers class com.appdevforall.forms.plugin.** {
    public <init>(...);
}
