package com.itsaky.androidide.services.builder

import com.google.common.truth.Truth.assertThat
import com.itsaky.androidide.services.builder.GradleBuildTuner.toGradleBuildParams
import org.junit.Test

/** @author Akash Yadav */
class GradleBuildParamsTest {
	private fun jvmConfig(
		xmsMb: Int = 256,
		xmxMb: Int = 2048,
		maxMetaspaceSize: Int = 256,
		reservedCodeCacheSize: Int = 128,
		gcType: GcType = GcType.Default,
		heapDumpOnOutOfMemory: Boolean = false,
	) = JvmConfig(
		xmsMb = xmsMb,
		xmxMb = xmxMb,
		maxMetaspaceSize = maxMetaspaceSize,
		reservedCodeCacheSize = reservedCodeCacheSize,
		gcType = gcType,
		heapDumpOnOutOfMemory = heapDumpOnOutOfMemory,
	)

	private fun gradleDaemonConfig(
		daemonEnabled: Boolean = true,
		jvm: JvmConfig = jvmConfig(),
		maxWorkers: Int = 4,
		parallel: Boolean = true,
		caching: Boolean = true,
		configureOnDemand: Boolean = false,
		vfsWatch: Boolean = true,
		configurationCache: Boolean = false,
	) = GradleDaemonConfig(
		daemonEnabled = daemonEnabled,
		jvm = jvm,
		maxWorkers = maxWorkers,
		parallel = parallel,
		caching = caching,
		configureOnDemand = configureOnDemand,
		vfsWatch = vfsWatch,
		configurationCache = configurationCache,
	)

	private fun tuningConfig(
		gradle: GradleDaemonConfig = gradleDaemonConfig(),
		kotlin: KotlinCompilerExecution = KotlinCompilerExecution.InProcess(incremental = true),
		dex: DexConfig = DexConfig(enableR8 = false),
		aapt2: Aapt2Config =
			Aapt2Config(
				enableDaemon = true,
				threadPoolSize = 2,
				enableResourceOptimizations = false,
			),
	) = GradleTuningConfig(gradle = gradle, kotlin = kotlin, dex = dex, aapt2 = aapt2)

	@Test
	fun `daemon enabled does not add --no-daemon flag`() {
		val params =
			toGradleBuildParams(tuningConfig(gradle = gradleDaemonConfig(daemonEnabled = true)))
		assertThat(params.gradleArgs).doesNotContain("--no-daemon")
	}

	@Test
	fun `daemon disabled adds --no-daemon flag`() {
		val params =
			toGradleBuildParams(tuningConfig(gradle = gradleDaemonConfig(daemonEnabled = false)))
		assertThat(params.gradleArgs).contains("--no-daemon")
	}

	@Test
	fun `max workers flag is included`() {
		val params = toGradleBuildParams(tuningConfig(gradle = gradleDaemonConfig(maxWorkers = 8)))
		assertThat(params.gradleArgs).contains("--max-workers=8")
	}

	@Test
	fun `max workers value reflects config`() {
		val params = toGradleBuildParams(tuningConfig(gradle = gradleDaemonConfig(maxWorkers = 1)))
		assertThat(params.gradleArgs).contains("--max-workers=1")
	}

	@Test
	fun `parallel enabled adds --parallel`() {
		val params = toGradleBuildParams(tuningConfig(gradle = gradleDaemonConfig(parallel = true)))
		assertThat(params.gradleArgs).contains("--parallel")
		assertThat(params.gradleArgs).doesNotContain("--no-parallel")
	}

	@Test
	fun `parallel disabled adds --no-parallel`() {
		val params =
			toGradleBuildParams(tuningConfig(gradle = gradleDaemonConfig(parallel = false)))
		assertThat(params.gradleArgs).contains("--no-parallel")
		assertThat(params.gradleArgs).doesNotContain("--parallel")
	}

	@Test
	fun `caching enabled adds --build-cache`() {
		val params = toGradleBuildParams(tuningConfig(gradle = gradleDaemonConfig(caching = true)))
		assertThat(params.gradleArgs).contains("--build-cache")
		assertThat(params.gradleArgs).doesNotContain("--no-build-cache")
	}

	@Test
	fun `caching disabled adds --no-build-cache`() {
		val params = toGradleBuildParams(tuningConfig(gradle = gradleDaemonConfig(caching = false)))
		assertThat(params.gradleArgs).contains("--no-build-cache")
		assertThat(params.gradleArgs).doesNotContain("--build-cache")
	}

	@Test
	fun `configureOnDemand enabled adds --configure-on-demand`() {
		val params =
			toGradleBuildParams(tuningConfig(gradle = gradleDaemonConfig(configureOnDemand = true)))
		assertThat(params.gradleArgs).contains("--configure-on-demand")
		assertThat(params.gradleArgs).doesNotContain("--no-configure-on-demand")
	}

	@Test
	fun `configureOnDemand disabled adds --no-configure-on-demand`() {
		val params =
			toGradleBuildParams(tuningConfig(gradle = gradleDaemonConfig(configureOnDemand = false)))
		assertThat(params.gradleArgs).contains("--no-configure-on-demand")
		assertThat(params.gradleArgs).doesNotContain("--configure-on-demand")
	}

	@Test
	fun `configurationCache enabled adds --configuration-cache`() {
		val params =
			toGradleBuildParams(tuningConfig(gradle = gradleDaemonConfig(configurationCache = true)))
		assertThat(params.gradleArgs).contains("--configuration-cache")
		assertThat(params.gradleArgs).doesNotContain("--no-configuration-cache")
	}

	@Test
	fun `configurationCache disabled adds --no-configuration-cache`() {
		val params =
			toGradleBuildParams(tuningConfig(gradle = gradleDaemonConfig(configurationCache = false)))
		assertThat(params.gradleArgs).contains("--no-configuration-cache")
		assertThat(params.gradleArgs).doesNotContain("--configuration-cache")
	}

	@Test
	fun `vfsWatch enabled adds --watch-fs`() {
		val params = toGradleBuildParams(tuningConfig(gradle = gradleDaemonConfig(vfsWatch = true)))
		assertThat(params.gradleArgs).contains("--watch-fs")
		assertThat(params.gradleArgs).doesNotContain("--no-watch-fs")
	}

	@Test
	fun `vfsWatch disabled adds --no-watch-fs`() {
		val params =
			toGradleBuildParams(tuningConfig(gradle = gradleDaemonConfig(vfsWatch = false)))
		assertThat(params.gradleArgs).contains("--no-watch-fs")
		assertThat(params.gradleArgs).doesNotContain("--watch-fs")
	}

	@Test
	fun `kotlin InProcess sets execution strategy to in-process`() {
		val params =
			toGradleBuildParams(
				tuningConfig(kotlin = KotlinCompilerExecution.InProcess(incremental = true)),
			)
		assertThat(params.gradleArgs).contains("-Pkotlin.compiler.execution.strategy=in-process")
	}

	@Test
	fun `kotlin InProcess with incremental true sets incremental property`() {
		val params =
			toGradleBuildParams(
				tuningConfig(kotlin = KotlinCompilerExecution.InProcess(incremental = true)),
			)
		assertThat(params.gradleArgs).contains("-Pkotlin.incremental=true")
	}

	@Test
	fun `kotlin InProcess with incremental false sets incremental property`() {
		val params =
			toGradleBuildParams(
				tuningConfig(kotlin = KotlinCompilerExecution.InProcess(incremental = false)),
			)
		assertThat(params.gradleArgs).contains("-Pkotlin.incremental=false")
	}

	@Test
	fun `kotlin InProcess does not add daemon JVM options`() {
		val params =
			toGradleBuildParams(
				tuningConfig(kotlin = KotlinCompilerExecution.InProcess(incremental = true)),
			)
		assertThat(params.gradleArgs).doesNotContain("-Pkotlin.daemon.jvm.options")
		val hasDaemonJvmOpts =
			params.gradleArgs.any { it.startsWith("-Pkotlin.daemon.jvm.options") }
		assertThat(hasDaemonJvmOpts).isFalse()
	}

	@Test
	fun `kotlin InProcess does not add daemon strategy`() {
		val params =
			toGradleBuildParams(
				tuningConfig(kotlin = KotlinCompilerExecution.InProcess(incremental = true)),
			)
		assertThat(params.gradleArgs).doesNotContain("-Pkotlin.compiler.execution.strategy=daemon")
	}

	@Test
	fun `kotlin Daemon sets execution strategy to daemon`() {
		val params =
			toGradleBuildParams(
				tuningConfig(
					kotlin =
						KotlinCompilerExecution.Daemon(
							incremental = true,
							jvm = jvmConfig(),
						),
				),
			)
		assertThat(params.gradleArgs).contains("-Pkotlin.compiler.execution.strategy=daemon")
	}

	@Test
	fun `kotlin Daemon with incremental true sets incremental property`() {
		val params =
			toGradleBuildParams(
				tuningConfig(
					kotlin =
						KotlinCompilerExecution.Daemon(
							incremental = true,
							jvm = jvmConfig(),
						),
				),
			)
		assertThat(params.gradleArgs).contains("-Pkotlin.incremental=true")
	}

	@Test
	fun `kotlin Daemon with incremental false sets incremental property`() {
		val params =
			toGradleBuildParams(
				tuningConfig(
					kotlin =
						KotlinCompilerExecution.Daemon(
							incremental = false,
							jvm = jvmConfig(),
						),
				),
			)
		assertThat(params.gradleArgs).contains("-Pkotlin.incremental=false")
	}

	@Test
	fun `kotlin Daemon adds daemon JVM options property`() {
		val params =
			toGradleBuildParams(
				tuningConfig(
					kotlin =
						KotlinCompilerExecution.Daemon(
							incremental = true,
							jvm = jvmConfig(xmsMb = 128, xmxMb = 512),
						),
				),
			)
		val daemonJvmOptsArg =
			params.gradleArgs.firstOrNull {
				it.startsWith("-Pkotlin.daemon.jvm.options=")
			}
		assertThat(daemonJvmOptsArg).isNotNull()
	}

	@Test
	fun `kotlin Daemon JVM options contain heap flags`() {
		val params =
			toGradleBuildParams(
				tuningConfig(
					kotlin =
						KotlinCompilerExecution.Daemon(
							incremental = true,
							jvm = jvmConfig(xmsMb = 128, xmxMb = 512),
						),
				),
			)
		val daemonJvmOptsArg =
			params.gradleArgs.first { it.startsWith("-Pkotlin.daemon.jvm.options=") }
		val optsValue = daemonJvmOptsArg.removePrefix("-Pkotlin.daemon.jvm.options=")
		assertThat(optsValue).contains("-Xms128m")
		assertThat(optsValue).contains("-Xmx512m")
	}

	@Test
	fun `kotlin Daemon does not add in-process strategy`() {
		val params =
			toGradleBuildParams(
				tuningConfig(
					kotlin =
						KotlinCompilerExecution.Daemon(
							incremental = true,
							jvm = jvmConfig(),
						),
				),
			)
		assertThat(params.gradleArgs).doesNotContain("-Pkotlin.compiler.execution.strategy=in-process")
	}

	@Test
	fun `R8 enabled sets enableR8 to true`() {
		val params = toGradleBuildParams(tuningConfig(dex = DexConfig(enableR8 = true)))
		assertThat(params.gradleArgs).contains("-Pandroid.enableR8=true")
	}

	@Test
	fun `R8 disabled sets enableR8 to false`() {
		val params = toGradleBuildParams(tuningConfig(dex = DexConfig(enableR8 = false)))
		assertThat(params.gradleArgs).contains("-Pandroid.enableR8=false")
	}

	@Test
	fun `aapt2 daemon enabled sets enableAapt2Daemon to true`() {
		val params =
			toGradleBuildParams(
				tuningConfig(
					aapt2 =
						Aapt2Config(
							enableDaemon = true,
							threadPoolSize = 2,
							enableResourceOptimizations = false,
						),
				),
			)
		assertThat(params.gradleArgs).contains("-Pandroid.enableAapt2Daemon=true")
	}

	@Test
	fun `aapt2 daemon disabled sets enableAapt2Daemon to false`() {
		val params =
			toGradleBuildParams(
				tuningConfig(
					aapt2 =
						Aapt2Config(
							enableDaemon = false,
							threadPoolSize = 2,
							enableResourceOptimizations = false,
						),
				),
			)
		assertThat(params.gradleArgs).contains("-Pandroid.enableAapt2Daemon=false")
	}

	@Test
	fun `aapt2 thread pool size is included`() {
		val params =
			toGradleBuildParams(
				tuningConfig(
					aapt2 =
						Aapt2Config(
							enableDaemon = true,
							threadPoolSize = 6,
							enableResourceOptimizations = false,
						),
				),
			)
		assertThat(params.gradleArgs).contains("-Pandroid.aapt2ThreadPoolSize=6")
	}

	@Test
	fun `aapt2 resource optimizations enabled`() {
		val params =
			toGradleBuildParams(
				tuningConfig(
					aapt2 =
						Aapt2Config(
							enableDaemon = true,
							threadPoolSize = 2,
							enableResourceOptimizations = true,
						),
				),
			)
		assertThat(params.gradleArgs).contains("-Pandroid.enableResourceOptimizations=true")
	}

	@Test
	fun `aapt2 resource optimizations disabled`() {
		val params =
			toGradleBuildParams(
				tuningConfig(
					aapt2 =
						Aapt2Config(
							enableDaemon = true,
							threadPoolSize = 2,
							enableResourceOptimizations = false,
						),
				),
			)
		assertThat(params.gradleArgs).contains("-Pandroid.enableResourceOptimizations=false")
	}

	@Test
	fun `jvm args contain Xms`() {
		val params =
			toGradleBuildParams(tuningConfig(gradle = gradleDaemonConfig(jvm = jvmConfig(xmsMb = 512))))
		assertThat(params.jvmArgs).contains("-Xms512m")
	}

	@Test
	fun `jvm args contain Xmx`() {
		val params =
			toGradleBuildParams(tuningConfig(gradle = gradleDaemonConfig(jvm = jvmConfig(xmxMb = 4096))))
		assertThat(params.jvmArgs).contains("-Xmx4096m")
	}

	@Test
	fun `jvm args contain MaxMetaspaceSize`() {
		val params =
			toGradleBuildParams(
				tuningConfig(
					gradle =
						gradleDaemonConfig(
							jvm = jvmConfig(maxMetaspaceSize = 384),
						),
				),
			)
		assertThat(params.jvmArgs).contains("-XX:MaxMetaspaceSize=384m")
	}

	@Test
	fun `jvm args contain ReservedCodeCacheSize`() {
		val params =
			toGradleBuildParams(
				tuningConfig(
					gradle =
						gradleDaemonConfig(
							jvm = jvmConfig(reservedCodeCacheSize = 256),
						),
				),
			)
		assertThat(params.jvmArgs).contains("-XX:ReservedCodeCacheSize=256m")
	}

	@Test
	fun `GcType Default adds no GC flags`() {
		val params =
			toGradleBuildParams(
				tuningConfig(gradle = gradleDaemonConfig(jvm = jvmConfig(gcType = GcType.Default))),
			)
		val gcFlags = params.jvmArgs.filter { it.contains("GC") || it.contains("Gc") }
		assertThat(gcFlags).isEmpty()
	}

	@Test
	fun `GcType Serial adds UseSerialGC`() {
		val params =
			toGradleBuildParams(
				tuningConfig(gradle = gradleDaemonConfig(jvm = jvmConfig(gcType = GcType.Serial))),
			)
		assertThat(params.jvmArgs).contains("-XX:+UseSerialGC")
	}

	@Test
	fun `GcType Serial does not add UseG1GC`() {
		val params =
			toGradleBuildParams(
				tuningConfig(gradle = gradleDaemonConfig(jvm = jvmConfig(gcType = GcType.Serial))),
			)
		assertThat(params.jvmArgs).doesNotContain("-XX:+UseG1GC")
	}

	@Test
	fun `GcType Generational adds UseG1GC`() {
		val params =
			toGradleBuildParams(
				tuningConfig(
					gradle =
						gradleDaemonConfig(
							jvm =
								jvmConfig(
									gcType =
										GcType.Generational(
											useAdaptiveIHOP = null,
											softRefLRUPolicyMSPerMB = null,
										),
								),
						),
				),
			)
		assertThat(params.jvmArgs).contains("-XX:+UseG1GC")
	}

	@Test
	fun `GcType Generational does not add UseSerialGC`() {
		val params =
			toGradleBuildParams(
				tuningConfig(
					gradle =
						gradleDaemonConfig(
							jvm =
								jvmConfig(
									gcType =
										GcType.Generational(
											useAdaptiveIHOP = null,
											softRefLRUPolicyMSPerMB = null,
										),
								),
						),
				),
			)
		assertThat(params.jvmArgs).doesNotContain("-XX:+UseSerialGC")
	}

	@Test
	fun `GcType Generational with adaptiveIHOP true enables G1UseAdaptiveIHOP`() {
		val params =
			toGradleBuildParams(
				tuningConfig(
					gradle =
						gradleDaemonConfig(
							jvm =
								jvmConfig(
									gcType =
										GcType.Generational(
											useAdaptiveIHOP = true,
											softRefLRUPolicyMSPerMB = null,
										),
								),
						),
				),
			)
		assertThat(params.jvmArgs).contains("-XX:+G1UseAdaptiveIHOP")
		assertThat(params.jvmArgs).doesNotContain("-XX:-G1UseAdaptiveIHOP")
	}

	@Test
	fun `GcType Generational with adaptiveIHOP false disables G1UseAdaptiveIHOP`() {
		val params =
			toGradleBuildParams(
				tuningConfig(
					gradle =
						gradleDaemonConfig(
							jvm =
								jvmConfig(
									gcType =
										GcType.Generational(
											useAdaptiveIHOP = false,
											softRefLRUPolicyMSPerMB = null,
										),
								),
						),
				),
			)
		assertThat(params.jvmArgs).contains("-XX:-G1UseAdaptiveIHOP")
		assertThat(params.jvmArgs).doesNotContain("-XX:+G1UseAdaptiveIHOP")
	}

	@Test
	fun `GcType Generational with adaptiveIHOP null omits G1UseAdaptiveIHOP`() {
		val params =
			toGradleBuildParams(
				tuningConfig(
					gradle =
						gradleDaemonConfig(
							jvm =
								jvmConfig(
									gcType =
										GcType.Generational(
											useAdaptiveIHOP = null,
											softRefLRUPolicyMSPerMB = null,
										),
								),
						),
				),
			)
		assertThat(params.jvmArgs).doesNotContain("-XX:+G1UseAdaptiveIHOP")
		assertThat(params.jvmArgs).doesNotContain("-XX:-G1UseAdaptiveIHOP")
	}

	@Test
	fun `GcType Generational with softRefLRUPolicyMSPerMB set adds flag`() {
		val params =
			toGradleBuildParams(
				tuningConfig(
					gradle =
						gradleDaemonConfig(
							jvm =
								jvmConfig(
									gcType =
										GcType.Generational(
											useAdaptiveIHOP = null,
											softRefLRUPolicyMSPerMB = 50,
										),
								),
						),
				),
			)
		assertThat(params.jvmArgs).contains("-XX:SoftRefLRUPolicyMSPerMB=50")
	}

	@Test
	fun `GcType Generational with softRefLRUPolicyMSPerMB null omits flag`() {
		val params =
			toGradleBuildParams(
				tuningConfig(
					gradle =
						gradleDaemonConfig(
							jvm =
								jvmConfig(
									gcType =
										GcType.Generational(
											useAdaptiveIHOP = null,
											softRefLRUPolicyMSPerMB = null,
										),
								),
						),
				),
			)
		val hasSoftRef = params.jvmArgs.any { it.startsWith("-XX:SoftRefLRUPolicyMSPerMB") }
		assertThat(hasSoftRef).isFalse()
	}

	@Test
	fun `heapDumpOnOutOfMemory enabled adds HeapDumpOnOutOfMemoryError`() {
		val params =
			toGradleBuildParams(
				tuningConfig(gradle = gradleDaemonConfig(jvm = jvmConfig(heapDumpOnOutOfMemory = true))),
			)
		assertThat(params.jvmArgs).contains("-XX:+HeapDumpOnOutOfMemoryError")
	}

	@Test
	fun `heapDumpOnOutOfMemory disabled omits HeapDumpOnOutOfMemoryError`() {
		val params =
			toGradleBuildParams(
				tuningConfig(gradle = gradleDaemonConfig(jvm = jvmConfig(heapDumpOnOutOfMemory = false))),
			)
		assertThat(params.jvmArgs).doesNotContain("-XX:+HeapDumpOnOutOfMemoryError")
	}

	@Test
	fun `gradle args do not bleed into jvm args`() {
		val params = toGradleBuildParams(tuningConfig())
		val jvmArgsHaveGradleFlags =
			params.jvmArgs.any { it.startsWith("--") || it.startsWith("-P") }
		assertThat(jvmArgsHaveGradleFlags).isFalse()
	}

	@Test
	fun `jvm args do not bleed into gradle args`() {
		val params = toGradleBuildParams(tuningConfig())
		val gradleArgsHaveJvmFlags =
			params.gradleArgs.any {
				it.startsWith("-Xms") || it.startsWith("-Xmx") || it.startsWith("-XX:")
			}
		assertThat(gradleArgsHaveJvmFlags).isFalse()
	}

	@Test
	fun `each boolean gradle flag appears exactly once`() {
		val params = toGradleBuildParams(tuningConfig())
		val boolFlags =
			listOf(
				"--parallel",
				"--no-parallel",
				"--build-cache",
				"--no-build-cache",
				"--configure-on-demand",
				"--no-configure-on-demand",
				"--configuration-cache",
				"--no-configuration-cache",
				"--watch-fs",
				"--no-watch-fs",
			)
		for (flag in boolFlags) {
			val count = params.gradleArgs.count { it == flag }
			assertThat(count).isAtMost(1)
		}
	}
}
