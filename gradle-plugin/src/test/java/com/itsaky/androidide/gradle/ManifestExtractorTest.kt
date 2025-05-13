package com.itsaky.androidide.gradle

import com.itsaky.androidide.gradle.utils.ManifestExtractor
import org.junit.jupiter.api.Test

/**
 * @author Akash Yadav
 */
class ManifestExtractorTest {

    companion object {
        /**
         * Sample merged manifest content.
         */
        private const val MANIFEST_CONTENT_BASE = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.myapplication1"
    android:versionCode="1"
    android:versionName="1.0" >

    <uses-sdk
        android:minSdkVersion="21"
        android:targetSdkVersion="33" />

    <application
        @@APP@@
        android:allowBackup="true"
        android:debuggable="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher"
        android:supportsRtl="true" >
        <activity
            android:name="com.example.myapplication1.MainActivity"
            android:exported="true" >
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
        """

        private val MANIFEST_CONTENT_WITH_APP = MANIFEST_CONTENT_BASE.replace("@@APP@@", "android:name=\"com.example.myapplication1.App\"")
        private val MANIFEST_CONTENT_WITHOUT_APP = MANIFEST_CONTENT_BASE.replace("@@APP@@", "")
    }

    @Test
    fun `test manifest extractor for manifest with custom application`() {
        val input = MANIFEST_CONTENT_WITH_APP.byteInputStream()
        val data = ManifestExtractor.extract(input)
        assert(data.packageName == "com.example.myapplication1")
        assert(data.applicationClass == "com.example.myapplication1.App")
    }

    @Test
    fun `test manifest extractor for manifest without custom application`() {
        val input = MANIFEST_CONTENT_WITHOUT_APP.byteInputStream()
        val data = ManifestExtractor.extract(input)
        assert(data.packageName == "com.example.myapplication1")
        assert(data.applicationClass == "")
    }
}