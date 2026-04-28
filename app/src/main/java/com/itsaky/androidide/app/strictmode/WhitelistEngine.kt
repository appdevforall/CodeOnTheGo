package com.itsaky.androidide.app.strictmode

import android.os.strictmode.DiskReadViolation
import android.os.strictmode.DiskWriteViolation
import androidx.annotation.VisibleForTesting
import com.itsaky.androidide.app.strictmode.FrameMatcher.Companion.anyOf
import com.itsaky.androidide.app.strictmode.FrameMatcher.Companion.classAndMethod
import android.os.strictmode.Violation as StrictModeViolation

/**
 * @author Akash Yadav
 */
object WhitelistEngine {
	/**
	 * A whitelist rule.
	 *
	 * @property type The type of violation to match.
	 * @property matcher The matcher to use to match the violation.
	 * @property decision The decision to take when the violation is matched.
	 */
	data class Rule(
		val type: Class<out StrictModeViolation>,
		val matcher: StackMatcher,
		val decision: Decision,
	)

	/**
	 * Whitelist engine decision.
	 */
	sealed interface Decision {
		/**
		 * Whitelist engine decision to allow the violation.
		 *
		 * @property reason The reason for allowing the violation.
		 */
		data class Allow(
			val reason: String,
		) : Decision

		/**
		 * Whitelist engine decision to log the violation.
		 */
		data object Log : Decision

		/**
		 * Whitelist engine decision to crash the process upon violation.
		 */
		data object Crash : Decision
	}

	@VisibleForTesting
	internal val rules =
		buildStrictModeWhitelist {

			// When adding a new rule, add rules to the bottom of the whitelist and ensure it is covered
			// by test cases in WhitelistRulesTest.kt

			rule {
				ofType<DiskReadViolation>()
				allow(
					"""
					Firebase's UserUnlockReceiver tries to access shared preferences after device reboot,
					which may happen on the main thread, resulting in a DiskReadViolation. Since we can't
					control when UserUnlockReceiver is called, we allow this violation.
					""".trimIndent(),
				)

				matchAdjacentFramesInOrder(
					listOf(
						listOf(
							classAndMethod("android.app.ContextImpl", "getSharedPreferences"),
							classAndMethod(
								"com.google.firebase.internal.DataCollectionConfigStorage",
								"<init>",
							),
						),
						listOf(
							classAndMethod(
								"com.google.firebase.FirebaseApp\$UserUnlockReceiver",
								"onReceive",
							),
						),
					),
				)
			}

			rule {
				ofType<DiskReadViolation>()
				allow(
					"""
					Firebase's DataCollectionConfigStorage reads the 'auto data collection' flag from
					SharedPreferences during initialization triggered by UserUnlockReceiver. This causes
					a DiskReadViolation on the main thread. Since this is an internal behavior of the
					Firebase SDK that we cannot control, we allow this violation.
					""".trimIndent(),
				)

				matchAdjacentFramesInOrder(
					listOf(
						listOf(
							classAndMethod(
								"com.google.firebase.internal.DataCollectionConfigStorage",
								"readAutoDataCollectionEnabled"
							),
							classAndMethod(
								"com.google.firebase.internal.DataCollectionConfigStorage",
								"<init>"
							),
						),
						listOf(
							classAndMethod(
								"com.google.firebase.FirebaseApp\$UserUnlockReceiver",
								"onReceive"
							),
						),
					),
				)
			}

			rule {
				ofType<DiskReadViolation>()
				allow(
					"""
					MIUI's TextView implementation has a MultiLangHelper which is invoked during draw.
					For some reason, it tries to check whether a file exists, resulting in a
					DiskReadViolation. Since we can't control when MultiLangHelper is called, we allow
					this violation.
					""".trimIndent(),
				)

				matchAdjacentFrames(
					classAndMethod("miui.util.font.MultiLangHelper", "initMultiLangInfo"),
					classAndMethod("miui.util.font.MultiLangHelper", "<clinit>"),
					classAndMethod("android.graphics.LayoutEngineStubImpl", "drawTextBegin"),
					classAndMethod("android.widget.TextView", "onDraw"),
				)
			}

			rule {
				ofType<DiskReadViolation>()
				allow(
					"""
					MIUI's AccessController checks whether an access-control password file exists
					during activity transitions (startActivity). This happens in the system server
					and is reported back via Binder. Since we can't control when AccessController
					is called, we allow this violation.
					""".trimIndent(),
				)

				matchAdjacentFrames(
					classAndMethod("java.io.File", "exists"),
					classAndMethod("com.miui.server.AccessController", "haveAccessControlPassword"),
					classAndMethod("com.miui.server.SecurityManagerService", "haveAccessControlPassword"),
				)
			}

			rule {
				ofType<DiskReadViolation>()
				allow(
					"""
					On MediaTek devices, the 'ScnModule' is primarily used for scenario detection and
					power management, like detecting whether a running app is a game. When doing this
					check, it tries to read a file, resulting in a DiskReadViolation. Since we can't
					control when ScnModule is called, we allow this violation.
					""".trimIndent(),
				)

				matchAdjacentFrames(
					classAndMethod("java.io.File", "length"),
					classAndMethod("com.mediatek.scnmodule.ScnModule", "isGameAppFileSize"),
					classAndMethod("com.mediatek.scnmodule.ScnModule", "isGameApp"),
				)
			}

			rule {
				ofType<DiskReadViolation>()
				allow(
					"""
					Oplus/ColorOS UIFirst writes to proc nodes during activity transitions (startActivity),
					which triggers a DiskReadViolation (File.exists) on some devices/ROM versions.
					This happens in framework code and is outside app control, so we allow it.
					""".trimIndent(),
				)

				matchAdjacentFrames(
					classAndMethod("java.io.File", "exists"),
					classAndMethod("com.oplus.uifirst.Utils", "writeProcNode"),
					classAndMethod("com.oplus.uifirst.OplusUIFirstManager", "writeProcNode"),
					classAndMethod("com.oplus.uifirst.OplusUIFirstManager", "setBinderThreadUxFlag"),
				)
			}

			rule {
				ofType<DiskReadViolation>()
				allow(
					"""
					IJdkDistributionProvider.getInstance() lazily initializes via ServiceLoader,
					which reads from a JAR/ZIP on disk. This is triggered from
					OnboardingActivity.onCreate to check if setup is completed. The lazy init
					is a one-time cost and cannot be deferred.
					""".trimIndent(),
				)

				matchFramesInOrder(
					classAndMethod("com.itsaky.androidide.utils.ServiceLoader", "parse"),
					classAndMethod("com.itsaky.androidide.app.configuration.IJdkDistributionProvider\$Companion", "_instance_delegate\$lambda\$0"),
				)
			}

			rule {
				ofType<DiskReadViolation>()
				allow(
					"""
					OnboardingActivity checks if ANDROID_HOME exists during onCreate to
					determine if setup is completed. This File.exists() call is a lightweight
					stat check required before navigating to the main screen.
					""".trimIndent(),
				)

				matchFramesInOrder(
					classAndMethod("java.io.File", "exists"),
					classAndMethod("com.itsaky.androidide.activities.OnboardingActivity", "checkToolsIsInstalled"),
				)
			}

			rule {
				ofType<DiskReadViolation>()
				allow(
					"""
					On MediaTek devices, BoostFwk's 'Util.isGameApp' is invoked from ScrollIdentify
					(via OverScroller's BoostFwkManagerImpl.perfHint) when a RecyclerView/ViewPager2
					is initialized. It checks for a file's existence, resulting in a DiskReadViolation.
					Since we can't control when BoostFwk performs scenario detection, we allow this
					violation.
					""".trimIndent(),
				)

				matchAdjacentFrames(
					classAndMethod("java.io.File", "exists"),
					classAndMethod("com.mediatek.boostfwk.utils.Util", "isGameApp"),
					classAndMethod("com.mediatek.boostfwk.utils.TasksUtil", "isGameAPP"),
					classAndMethod("com.mediatek.boostfwk.identify.scroll.ScrollIdentify", "checkAppType"),
				)
			}

			rule {
				ofType<DiskReadViolation>()
				allow(
					"""
					On MediaTek devices, AsyncDrawableCache hooks into Resources.loadDrawable and
					synchronously commits to a SharedPreferences-backed cache. This is triggered by
					routine layout inflation and produces a DiskReadViolation. We anchor this rule
					with a java.io disk-access frame at the top and the SharedPreferencesImpl
					commit frame immediately adjacent to the vendor chain, so it only fires on the
					actual prefs-write path inside this vendor caching code.
					""".trimIndent(),
				)

				matchAdjacentFramesInOrder(
					listOf(
						listOf(
							anyOf(
								classAndMethod("java.io.File", "exists"),
								classAndMethod("java.io.FileInputStream", "<init>"),
								classAndMethod("java.io.FileOutputStream", "<init>"),
								classAndMethod("java.io.RandomAccessFile", "<init>"),
								classAndMethod("android.system.Os", "stat"),
							),
						),
						listOf(
							classAndMethod("android.app.SharedPreferencesImpl\$EditorImpl", "commit"),
							classAndMethod("com.mediatek.res.AsyncDrawableCache", "storeDrawableId"),
							classAndMethod("com.mediatek.res.AsyncDrawableCache", "putCacheList"),
							classAndMethod("com.mediatek.res.ResOptExtImpl", "putCacheList"),
						),
					),
				)
			}

			rule {
				ofType<DiskWriteViolation>()
				allow(
					"""
					On MediaTek devices, AsyncDrawableCache hooks into Resources.loadDrawable and
					synchronously commits to a SharedPreferences-backed cache. The same path also
					produces a DiskWriteViolation when the prefs file is written. We anchor this
					rule with a concrete write-path frame at the top and the SharedPreferencesImpl
					commit frame immediately adjacent to the vendor chain, so it only fires on the
					actual prefs-write path inside this vendor caching code.
					""".trimIndent(),
				)

				matchAdjacentFramesInOrder(
					listOf(
						listOf(
							anyOf(
								classAndMethod("java.io.FileOutputStream", "<init>"),
								classAndMethod("java.io.FileOutputStream", "write"),
								classAndMethod("java.io.File", "delete"),
								classAndMethod("android.system.Os", "chmod"),
							),
						),
						listOf(
							classAndMethod("android.app.SharedPreferencesImpl\$EditorImpl", "commit"),
							classAndMethod("com.mediatek.res.AsyncDrawableCache", "storeDrawableId"),
							classAndMethod("com.mediatek.res.AsyncDrawableCache", "putCacheList"),
							classAndMethod("com.mediatek.res.ResOptExtImpl", "putCacheList"),
						),
					),
				)
			}
		}

	/**
	 * Evaluates the given [violation] and returns a decision based on the whitelist.
	 */
	fun evaluate(violation: ViolationDispatcher.Violation): Decision {
		val frames = violation.frames
		rules.firstOrNull { rule ->
			if (rule.type.isInstance(violation.violation) && rule.matcher.matches(frames)) {
				return rule.decision
			}

			false
		}

		if (StrictModeManager.config.isReprieveEnabled) {
			return Decision.Log
		}

		return Decision.Crash
	}
}
