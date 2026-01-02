

package com.itsaky.androidide.templates.impl.pluginProject

fun pluginLayoutXml(data: PluginTemplateData): String = """
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp"
    android:gravity="center">

    <TextView
        android:id="@+id/titleText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="${data.pluginName}"
        android:textSize="24sp"
        android:textStyle="bold"
        android:layout_marginBottom="8dp" />

    <TextView
        android:id="@+id/descriptionText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="${data.description}"
        android:textSize="14sp"
        android:textColor="@android:color/darker_gray"
        android:layout_marginBottom="24dp"
        android:gravity="center" />

    <TextView
        android:id="@+id/statusText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Ready"
        android:textSize="16sp"
        android:layout_marginBottom="16dp" />

    <Button
        android:id="@+id/actionButton"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Perform Action"
        android:minWidth="200dp" />

</LinearLayout>
""".trimIndent()

fun pluginStringsXml(data: PluginTemplateData): String = """
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">${data.pluginName}</string>
    <string name="plugin_description">${data.description}</string>
</resources>
""".trimIndent()