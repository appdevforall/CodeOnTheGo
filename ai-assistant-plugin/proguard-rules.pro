# Keep plugin main class
-keep class com.itsaky.androidide.plugins.aiassistant.AiAssistantPlugin {
    public <init>();
    public boolean initialize(com.itsaky.androidide.plugins.PluginContext);
    public boolean activate();
    public boolean deactivate();
    public void dispose();
    public java.util.List getEditorTabs();
    public java.util.List getContextMenuItems(com.itsaky.androidide.plugins.extensions.ContextMenuContext);
    public java.util.List getMainMenuItems();
}

# Keep Fragment classes
-keep class com.itsaky.androidide.plugins.aiassistant.fragments.ChatFragment {
    public <init>();
}

# Keep plugin API related classes
-keep interface com.itsaky.androidide.plugins.IPlugin {
    *;
}

-keep interface com.itsaky.androidide.plugins.extensions.UIExtension {
    *;
}

-keep class com.itsaky.androidide.plugins.extensions.TabItem {
    *;
}

-keep class com.itsaky.androidide.plugins.extensions.MenuItem {
    *;
}

# Keep annotations
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
