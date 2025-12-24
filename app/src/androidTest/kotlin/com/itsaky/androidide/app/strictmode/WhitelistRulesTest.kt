package com.itsaky.androidide.app.strictmode

import android.os.strictmode.DiskReadViolation
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
}
