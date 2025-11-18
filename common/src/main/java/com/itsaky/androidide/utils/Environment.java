/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.itsaky.androidide.utils;

import static org.adfa.constants.ConstantsKt.GRADLE_DISTRIBUTION_VERSION;
import static org.adfa.constants.ConstantsKt.LOGSENDER_AAR_NAME;

import android.annotation.SuppressLint;
import android.content.Context;

import androidx.annotation.NonNull;
import com.blankj.utilcode.util.FileUtils;
import com.itsaky.androidide.app.BaseApplication;
import com.itsaky.androidide.app.configuration.IDEBuildConfigProvider;
import com.itsaky.androidide.buildinfo.BuildInfo;
import java.io.File;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressLint("SdCardPath")
public final class Environment {

	public static final String PROJECTS_FOLDER = "CodeOnTheGoProjects";
	public static final String DEFAULT_ROOT = "/data/data/" + BuildInfo.PACKAGE_NAME + "/files";
	public static final String DEFAULT_HOME = DEFAULT_ROOT + "/home";
	private static final String DEFAULT_ANDROID_HOME = DEFAULT_HOME + "/android-sdk";
	public static final String GRADLE_CACHE_DIR = DEFAULT_HOME + "/.gradle";
	private static final String ANDROID_JAR_HOME = DEFAULT_ANDROID_HOME + "/platforms/android-33";
	public static final String DEFAULT_PREFIX = DEFAULT_ROOT + "/usr";
	public static final String DEFAULT_JAVA_HOME = DEFAULT_PREFIX + "/lib/jvm/java-21-openjdk";
	private static final String ANDROIDIDE_PROJECT_CACHE_DIR = SharedEnvironment.PROJECT_CACHE_DIR_NAME;
	private static final String DATABASE_NAME = "documentation.db";

	private static final Logger LOG = LoggerFactory.getLogger(Environment.class);
	public static File ROOT;
	public static File PREFIX;
	public static File HOME;
	public static File ANDROIDIDE_HOME;
	public static File ANDROIDIDE_UI;
	public static File JAVA_HOME;
	public static File ANDROID_HOME;
	public static File TMP_DIR;
	public static File BIN_DIR;
	public static File OPT_DIR;
	public static File LOGSENDER_DIR;
	public static File LOGSENDER_AAR;
	public static File PROJECTS_DIR;

	// split assets vars
	public static File DOWNLOAD_DIR;
	public static File SPLIT_ASSETS_ZIP;

	/**
	 * Used by Java LSP until the project is initialized.
	 */
	public static File ANDROID_JAR;

	public static File TOOLING_API_JAR;

	public static File COGO_PLUGIN_JAR;

	public static File INIT_SCRIPT;
	public static File GRADLE_USER_HOME;
	public static File AAPT2;
	public static File JAVA;
	public static File BASH_SHELL;
	public static File LOGIN_SHELL;

	public static File GRADLE_DISTS;

	public static File DOC_DB;
	public static File LOCAL_MAVEN_DIR;

	public static File GRADLE_GEN_JARS;

	public static File KEYSTORE_DIR;
	public static File KEYSTORE_RELEASE;
	public static File KEYSTORE_PROPERTIES;
	public static String KEYSTORE_RELEASE_NAME = "release.keystore";
	public static String KEYSTORE_PROPERTIES_NAME = "release.properties";
	public static String KEYSTORE_PROP_STOREFILE = "storeFile";
	public static String KEYSTORE_PROP_STOREPWD = "storePassword";
	public static String KEYSTORE_PROP_KEYALIAS = "keyAlias";
	public static String KEYSTORE_PROP_KEYPWD = "keyPassword";
	public static final Integer KEYSTORE_PWD_LEN = 8;
	public static final Integer KEYSTORE_ALIAS_LEN = 14;
	public static final Integer KEYSTORE_KEY_SIZE = 2048;
	public static final long KEYSTORE_EXPIRY_5YRS = 365L * 5 * 24 * 60 * 60 * 1000;
	public static final String[] KEYSTORE_EU_COUNTRY_CODES = {
			"AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR",
			"DE", "GR", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL",
			"PL", "PT", "RO", "SK", "SI", "ES", "SE"
	};

	public static String getArchitecture() {
		return IDEBuildConfigProvider.getInstance().getCpuAbiName();
	}

	public static File getProjectCacheDir(File projectDir) {
		return new File(projectDir, ANDROIDIDE_PROJECT_CACHE_DIR);
	}

	public static void init(Context context) {
		var arch = getArchitecture();
		DOWNLOAD_DIR = new File(FileUtil.getExternalStorageDir(), "Download");
		SPLIT_ASSETS_ZIP = new File(DOWNLOAD_DIR, "assets-" + arch + ".zip");

		ROOT = mkdirIfNotExists(new File(DEFAULT_ROOT));
		PREFIX = mkdirIfNotExists(new File(ROOT, "usr"));
		HOME = mkdirIfNotExists(new File(ROOT, "home"));
		ANDROIDIDE_HOME = mkdirIfNotExists(new File(HOME, ".androidide"));
		TMP_DIR = mkdirIfNotExists(new File(PREFIX, "tmp"));
		BIN_DIR = mkdirIfNotExists(new File(PREFIX, "bin"));
		OPT_DIR = mkdirIfNotExists(new File(PREFIX, "opt"));
		LOGSENDER_DIR = mkdirIfNotExists(new File(ANDROIDIDE_HOME, "logsender"));
		LOGSENDER_AAR = mkdirIfNotExists(new File(LOGSENDER_DIR, LOGSENDER_AAR_NAME));
		PROJECTS_DIR = mkdirIfNotExists(new File(FileUtil.getExternalStorageDir(), PROJECTS_FOLDER));
		// NOTE: change location of android.jar from ANDROIDIDE_HOME to inside android-sdk
		// and don't create the dir if it doesn't exist
		ANDROID_JAR = new File(ANDROID_JAR_HOME, "android.jar");
		TOOLING_API_JAR = new File(mkdirIfNotExists(new File(ANDROIDIDE_HOME, "tooling-api")),
				"tooling-api-all.jar");
		COGO_PLUGIN_JAR = new File(mkdirIfNotExists(new File(ANDROIDIDE_HOME, "plugin")),
				"cogo-plugin.jar");
		ANDROIDIDE_UI = mkdirIfNotExists(new File(ANDROIDIDE_HOME, "ui"));

		INIT_SCRIPT = new File(mkdirIfNotExists(new File(ANDROIDIDE_HOME, "init")), "init.gradle");
		GRADLE_USER_HOME = new File(HOME, ".gradle");

		ANDROID_HOME = new File(DEFAULT_ANDROID_HOME);
		JAVA_HOME = new File(DEFAULT_JAVA_HOME);

		AAPT2 = new File(ANDROID_HOME, "build-tools/35.0.0/aapt2");

		JAVA = new File(JAVA_HOME, "bin/java");
		BASH_SHELL = new File(BIN_DIR, "bash");
		LOGIN_SHELL = new File(BIN_DIR, "login");

		GRADLE_DISTS = mkdirIfNotExists(new File(ANDROIDIDE_HOME, "gradle-dists"));
		LOCAL_MAVEN_DIR = mkdirIfNotExists(new File(HOME, "maven/localMvnRepository"));

		System.setProperty("user.home", HOME.getAbsolutePath());

		DOC_DB = context.getDatabasePath(DATABASE_NAME);

		GRADLE_GEN_JARS = mkdirIfNotExists(new File(GRADLE_CACHE_DIR, "caches/" +
				GRADLE_DISTRIBUTION_VERSION + "/generated-gradle-jars"));

		KEYSTORE_DIR = mkdirIfNotExists(new File(ANDROIDIDE_HOME, "keystore"));
		KEYSTORE_RELEASE = new File(KEYSTORE_DIR, KEYSTORE_RELEASE_NAME);
		KEYSTORE_PROPERTIES = new File(KEYSTORE_DIR, KEYSTORE_PROPERTIES_NAME);
	}

	public static File mkdirIfNotExists(File in) {
		if (in != null && !in.exists()) {
			FileUtils.createOrExistsDir(in);
		}

		return in;
	}

	public static void putEnvironment(Map<String, String> env, boolean forFailsafe) {

		env.put("HOME", HOME.getAbsolutePath());
		env.put("ANDROID_HOME", ANDROID_HOME.getAbsolutePath());
		env.put("ANDROID_SDK_ROOT", ANDROID_HOME.getAbsolutePath());
		env.put("ANDROID_USER_HOME", HOME.getAbsolutePath() + "/.android");
		env.put("JAVA_HOME", JAVA_HOME.getAbsolutePath());
		env.put("GRADLE_USER_HOME", GRADLE_USER_HOME.getAbsolutePath());
		env.put("SYSROOT", PREFIX.getAbsolutePath());
		env.put("PROJECTS", PROJECTS_DIR.getAbsolutePath());

		// add user envs for non-failsafe sessions
		if (!forFailsafe) {
			// No mirror select
			env.put("TERMUX_PKG_NO_MIRROR_SELECT", "true");
		}
	}

	public static void setExecutable(@NonNull final File file) {
		if (!file.setExecutable(true)) {
			LOG.error("Unable to set executable permissions to file: {}", file);
		}
	}

	public static void setProjectDir(@NonNull File file) {
		PROJECTS_DIR = new File(file.getAbsolutePath());
	}
}
