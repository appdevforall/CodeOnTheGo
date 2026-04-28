package com.itsaky.androidide.app.strictmode

import android.os.strictmode.DiskReadViolation
import android.os.strictmode.DiskWriteViolation
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test cases for all strict mode whitelist rules.
 *
 * @author Akash Yadav
 */
@RunWith(AndroidJUnit4::class)
class WhitelistRulesTest {
	@Test
	fun allow_DiskRead_on_FirebaseUserUnlockRecvSharedPrefAccess() {
		assertAllowed<DiskReadViolation>(
			// @formatter:off
			stackTraceElement("java.io.File", "exists", "File.java", 829),
			stackTraceElement("android.app.ContextImpl", "getDataDir", "ContextImpl.java", 3476),
			stackTraceElement("android.app.ContextImpl", "getPreferencesDir", "ContextImpl.java", 790),
			stackTraceElement("android.app.ContextImpl", "getSharedPreferencesPath", "ContextImpl.java", 1029),
			stackTraceElement("android.app.ContextImpl", "getSharedPreferences", "ContextImpl.java", 632),
			stackTraceElement("com.google.firebase.internal.DataCollectionConfigStorage", "<init>", "DataCollectionConfigStorage.java", 45),
			stackTraceElement("com.google.firebase.FirebaseApp", "lambda\$new$0\$com-google-firebase-FirebaseApp", "FirebaseApp.java", 448),
			stackTraceElement("com.google.firebase.FirebaseApp$\$ExternalSyntheticLambda0", "get", "D8$\$SyntheticClass", 0),
			stackTraceElement("com.google.firebase.components.Lazy", "get", "Lazy.java", 53),
			stackTraceElement("com.google.firebase.FirebaseApp", "isDataCollectionDefaultEnabled", "FirebaseApp.java", 371),
			stackTraceElement("com.google.firebase.analytics.connector.AnalyticsConnectorImpl", "getInstance", "com.google.android.gms:play-services-measurement-api@@22.1.2", 31),
			stackTraceElement("com.google.firebase.FirebaseApp", "initializeAllApis", "FirebaseApp.java", 607),
			stackTraceElement("com.google.firebase.FirebaseApp", "access$300", "FirebaseApp.java", 91),
			stackTraceElement("com.google.firebase.FirebaseApp\$UserUnlockReceiver", "onReceive", "FirebaseApp.java", 672),
			// @formatter:on
		)
	}

	@Test
	fun allow_DiskRead_on_MiuiMultiLangHelperTextViewDraw() {
		assertAllowed<DiskReadViolation>(
			stackTraceElement("java.io.File", "exists"),
			stackTraceElement("miui.util.font.MultiLangHelper", "initMultiLangInfo"),
			stackTraceElement("miui.util.font.MultiLangHelper", "<clinit>"),
			stackTraceElement("android.graphics.LayoutEngineStubImpl", "drawTextBegin"),
			stackTraceElement("android.widget.TextView", "onDraw"),
		)
	}

	@Test
	fun allow_DiskRead_on_MtkScnModuleIsGameAppCheck() {
		assertAllowed<DiskReadViolation>(
			// @formatter:off
			stackTraceElement("java.io.File", "length"),
			stackTraceElement("com.mediatek.scnmodule.ScnModule", "isGameAppFileSize"),
			stackTraceElement("com.mediatek.scnmodule.ScnModule", "isGameApp"),
			stackTraceElement("com.mediatek.scnmodule.ScnModule", "notifyAppisGame"),
			stackTraceElement("com.mediatek.powerhalwrapper.PowerHalWrapper", "amsBoostNotify"),
			// @formatter:on
		)
	}

	@Test
	fun allow_DiskRead_on_Firebase_DataCollection() {
		assertAllowed<DiskReadViolation>(
			// @formatter:off
			stackTraceElement("android.os.StrictMode\$AndroidBlockGuardPolicy", "onReadFromDisk", "StrictMode.java", 1766),
			stackTraceElement("android.app.SharedPreferencesImpl", "awaitLoadedLocked", "SharedPreferencesImpl.java", 283),
			stackTraceElement("android.app.SharedPreferencesImpl", "contains", "SharedPreferencesImpl.java", 361),
			stackTraceElement("com.google.firebase.internal.DataCollectionConfigStorage", "readAutoDataCollectionEnabled", "DataCollectionConfigStorage.java", 102),
			stackTraceElement("com.google.firebase.internal.DataCollectionConfigStorage", "<init>", "DataCollectionConfigStorage.java", 48),
			stackTraceElement("com.google.firebase.FirebaseApp", "lambda\$new\$0\$com-google-firebase-FirebaseApp", "FirebaseApp.java", 448),
			stackTraceElement("com.google.firebase.FirebaseApp\$\$ExternalSyntheticLambda0", "get", "D8\$\$SyntheticClass", 0),
			stackTraceElement("com.google.firebase.components.Lazy", "get", "Lazy.java", 53),
			stackTraceElement("com.google.firebase.FirebaseApp", "isDataCollectionDefaultEnabled", "FirebaseApp.java", 371),
			stackTraceElement("com.google.firebase.analytics.connector.AnalyticsConnectorImpl", "getInstance", "play-services-measurement-api@@22.1.2", 31),
			stackTraceElement("com.google.firebase.FirebaseApp\$UserUnlockReceiver", "onReceive", "FirebaseApp.java", 672)
			// @formatter:on
		)
	}

	@Test
	fun allow_DiskRead_on_ServiceLoader_JdkDistributionProvider() {
		assertAllowed<DiskReadViolation>(
			stackTraceElement("com.itsaky.androidide.utils.ServiceLoader", "parse"),
			stackTraceElement(
				"com.itsaky.androidide.app.configuration.IJdkDistributionProvider\$Companion",
				"_instance_delegate\$lambda\$0"
			),
		)
	}

	@Test
	fun allow_DiskRead_on_OnboardingActivity_ToolsCheck() {
		assertAllowed<DiskReadViolation>(
			stackTraceElement("java.io.File", "exists"),
			stackTraceElement(
				"com.itsaky.androidide.activities.OnboardingActivity",
				"checkToolsIsInstalled"
			),
		)
	}

	@Test
	fun allow_DiskRead_on_MtkBoostFwkIsGameApp() {
		assertAllowed<DiskReadViolation>(
			// @formatter:off
			stackTraceElement("java.io.File", "exists"),
			stackTraceElement("com.mediatek.boostfwk.utils.Util", "isGameApp"),
			stackTraceElement("com.mediatek.boostfwk.utils.TasksUtil", "isGameAPP"),
			stackTraceElement("com.mediatek.boostfwk.identify.scroll.ScrollIdentify", "checkAppType"),
			stackTraceElement("com.mediatek.boostfwk.identify.scroll.ScrollIdentify", "dispatchScenario"),
			// @formatter:on
		)
	}

	@Test
	fun allow_DiskRead_on_MtkAsyncDrawableCachePutCacheList() {
		assertAllowed<DiskReadViolation>(
			// @formatter:off
			stackTraceElement("java.io.File", "exists"),
			stackTraceElement("android.app.SharedPreferencesImpl", "writeToFile"),
			stackTraceElement("android.app.SharedPreferencesImpl\$EditorImpl", "commit"),
			stackTraceElement("com.mediatek.res.AsyncDrawableCache", "storeDrawableId"),
			stackTraceElement("com.mediatek.res.AsyncDrawableCache", "putCacheList"),
			stackTraceElement("com.mediatek.res.ResOptExtImpl", "putCacheList"),
			stackTraceElement("android.content.res.ResourcesImpl", "cacheDrawable"),
			// @formatter:on
		)
	}

	@Test
	fun allow_DiskRead_on_MtkAsyncDrawableCachePutCacheList_OsStatVariant() {
		assertAllowed<DiskReadViolation>(
			// @formatter:off
			stackTraceElement("android.system.Os", "stat"),
			stackTraceElement("android.app.SharedPreferencesImpl", "writeToFile"),
			stackTraceElement("android.app.SharedPreferencesImpl\$EditorImpl", "commit"),
			stackTraceElement("com.mediatek.res.AsyncDrawableCache", "storeDrawableId"),
			stackTraceElement("com.mediatek.res.AsyncDrawableCache", "putCacheList"),
			stackTraceElement("com.mediatek.res.ResOptExtImpl", "putCacheList"),
			stackTraceElement("android.content.res.ResourcesImpl", "cacheDrawable"),
			// @formatter:on
		)
	}

	@Test
	fun allow_DiskWrite_on_MtkAsyncDrawableCachePutCacheList() {
		assertAllowed<DiskWriteViolation>(
			// @formatter:off
			stackTraceElement("libcore.io.IoBridge", "open"),
			stackTraceElement("java.io.FileOutputStream", "<init>"),
			stackTraceElement("android.app.SharedPreferencesImpl", "createFileOutputStream"),
			stackTraceElement("android.app.SharedPreferencesImpl", "writeToFile"),
			stackTraceElement("android.app.SharedPreferencesImpl\$EditorImpl", "commit"),
			stackTraceElement("com.mediatek.res.AsyncDrawableCache", "storeDrawableId"),
			stackTraceElement("com.mediatek.res.AsyncDrawableCache", "putCacheList"),
			stackTraceElement("com.mediatek.res.ResOptExtImpl", "putCacheList"),
			// @formatter:on
		)
	}

	@Test
	fun allow_DiskWrite_on_MtkAsyncDrawableCache_FileOutputStreamWrite() {
		assertAllowed<DiskWriteViolation>(
			// @formatter:off
			stackTraceElement("java.io.FileOutputStream", "write"),
			stackTraceElement("com.android.internal.util.FastXmlSerializer", "flushBytes"),
			stackTraceElement("com.android.internal.util.FastXmlSerializer", "flush"),
			stackTraceElement("com.android.internal.util.FastXmlSerializer", "endDocument"),
			stackTraceElement("com.android.internal.util.XmlSerializerWrapper", "endDocument"),
			stackTraceElement("com.android.internal.util.XmlUtils", "writeMapXml"),
			stackTraceElement("android.app.SharedPreferencesImpl", "writeToFile"),
			stackTraceElement("android.app.SharedPreferencesImpl\$EditorImpl", "commit"),
			stackTraceElement("com.mediatek.res.AsyncDrawableCache", "storeDrawableId"),
			stackTraceElement("com.mediatek.res.AsyncDrawableCache", "putCacheList"),
			stackTraceElement("com.mediatek.res.ResOptExtImpl", "putCacheList"),
			// @formatter:on
		)
	}

	@Test
	fun allow_DiskWrite_on_MtkAsyncDrawableCache_FileDelete() {
		assertAllowed<DiskWriteViolation>(
			// @formatter:off
			stackTraceElement("java.io.UnixFileSystem", "delete"),
			stackTraceElement("java.io.File", "delete"),
			stackTraceElement("android.app.SharedPreferencesImpl", "writeToFile"),
			stackTraceElement("android.app.SharedPreferencesImpl\$EditorImpl", "commit"),
			stackTraceElement("com.mediatek.res.AsyncDrawableCache", "storeDrawableId"),
			stackTraceElement("com.mediatek.res.AsyncDrawableCache", "putCacheList"),
			stackTraceElement("com.mediatek.res.ResOptExtImpl", "putCacheList"),
			// @formatter:on
		)
	}

	@Test
	fun allow_DiskWrite_on_MtkAsyncDrawableCache_OsChmod() {
		assertAllowed<DiskWriteViolation>(
			// @formatter:off
			stackTraceElement("android.system.Os", "chmod"),
			stackTraceElement("android.os.FileUtils", "setPermissions"),
			stackTraceElement("android.app.ContextImpl", "setFilePermissionsFromMode"),
			stackTraceElement("android.app.SharedPreferencesImpl", "writeToFile"),
			stackTraceElement("android.app.SharedPreferencesImpl\$EditorImpl", "commit"),
			stackTraceElement("com.mediatek.res.AsyncDrawableCache", "storeDrawableId"),
			stackTraceElement("com.mediatek.res.AsyncDrawableCache", "putCacheList"),
			stackTraceElement("com.mediatek.res.ResOptExtImpl", "putCacheList"),
			// @formatter:on
		)
	}
}
