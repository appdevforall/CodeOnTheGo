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

import static org.adfa.constants.ConstantsKt.JDWP_AAR_NAME;
import static org.adfa.constants.ConstantsKt.LOGSENDER_AAR_NAME;

import android.annotation.SuppressLint;

import androidx.annotation.NonNull;

import com.blankj.utilcode.util.FileUtils;
import com.itsaky.androidide.app.BaseApplication;
import com.itsaky.androidide.app.configuration.IDEBuildConfigProvider;
import com.itsaky.androidide.buildinfo.BuildInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Map;

@SuppressLint("SdCardPath")
public final class Environment {

  public static final String PROJECTS_FOLDER = "AndroidIDEProjects";
  public static final String DEFAULT_ROOT = "/data/data/" + BuildInfo.PACKAGE_NAME + "/files";
  public static final String DEFAULT_HOME = DEFAULT_ROOT + "/home";
  private static final String DEFAULT_ANDROID_HOME = DEFAULT_HOME + "/android-sdk";
  public static final String GRADLE_CACHE_DIR = DEFAULT_HOME + "/.gradle";
  private static final String ANDROID_JAR_HOME = DEFAULT_ANDROID_HOME + "/platforms/android-33";
  public static final String DEFAULT_PREFIX = DEFAULT_ROOT + "/usr";
  public static final String DEFAULT_JAVA_HOME = DEFAULT_PREFIX + "/lib/jvm/java-21-openjdk";
  private static final String ANDROIDIDE_PROJECT_CACHE_DIR = ".androidide";

  private static final  String DATABASE_NAME = "documentation.db";
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
  public static File JDWP_DIR;
  public static File JDWP_LIB_DIR;
  public static File JDWP_AAR;
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

  public static void init() {
    var arch = getArchitecture();
    DOWNLOAD_DIR = new File(FileUtil.getExternalStorageDir(), "Download");
    SPLIT_ASSETS_ZIP = new File(DOWNLOAD_DIR, "assets-" + arch + ".zip");

    ROOT = mkdirIfNotExits(new File(DEFAULT_ROOT));
    PREFIX = mkdirIfNotExits(new File(ROOT, "usr"));
    HOME = mkdirIfNotExits(new File(ROOT, "home"));
    ANDROIDIDE_HOME = mkdirIfNotExits(new File(HOME, ".androidide"));
    TMP_DIR = mkdirIfNotExits(new File(PREFIX, "tmp"));
    BIN_DIR = mkdirIfNotExits(new File(PREFIX, "bin"));
    OPT_DIR = mkdirIfNotExits(new File(PREFIX, "opt"));
    JDWP_DIR = mkdirIfNotExits(new File(OPT_DIR, "oj-libjdwp"));
    JDWP_LIB_DIR = mkdirIfNotExits(new File(JDWP_DIR, "jniLibs"));
    JDWP_AAR = mkdirIfNotExits(new File(JDWP_DIR, JDWP_AAR_NAME));
    LOGSENDER_DIR = mkdirIfNotExits(new File(OPT_DIR, "logsender"));
    LOGSENDER_AAR = mkdirIfNotExits(new File(LOGSENDER_DIR, LOGSENDER_AAR_NAME));
    PROJECTS_DIR = mkdirIfNotExits(new File(FileUtil.getExternalStorageDir(), PROJECTS_FOLDER));
    // NOTE: change location of android.jar from ANDROIDIDE_HOME to inside android-sdk
    //       and don't create the dir if it doesn't exist
    ANDROID_JAR = new File(ANDROID_JAR_HOME, "android.jar");
    TOOLING_API_JAR = new File(mkdirIfNotExits(new File(ANDROIDIDE_HOME, "tooling-api")),
        "tooling-api-all.jar");
    COGO_PLUGIN_JAR = new File(mkdirIfNotExits(new File(ANDROIDIDE_HOME, "plugin")),
            "cogo-plugin.jar");
    AAPT2 = new File(ANDROIDIDE_HOME, "aapt2");
    ANDROIDIDE_UI = mkdirIfNotExits(new File(ANDROIDIDE_HOME, "ui"));

    INIT_SCRIPT = new File(mkdirIfNotExits(new File(ANDROIDIDE_HOME, "init")), "init.gradle");
    GRADLE_USER_HOME = new File(HOME, ".gradle");

    ANDROID_HOME = new File(DEFAULT_ANDROID_HOME);
    JAVA_HOME = new File(DEFAULT_JAVA_HOME);

    JAVA = new File(JAVA_HOME, "bin/java");
    BASH_SHELL = new File(BIN_DIR, "bash");
    LOGIN_SHELL = new File(BIN_DIR, "login");

    GRADLE_DISTS = mkdirIfNotExits(new File(ANDROIDIDE_HOME, "gradle-dists"));
    LOCAL_MAVEN_DIR = mkdirIfNotExits(new File(HOME, "maven/localMvnRepository"));

    setExecutable(JAVA);
    setExecutable(BASH_SHELL);

    System.setProperty("user.home", HOME.getAbsolutePath());

    DOC_DB = BaseApplication.getBaseInstance().getDatabasePath(DATABASE_NAME);

    GRADLE_GEN_JARS = mkdirIfNotExits(new File(GRADLE_CACHE_DIR, "caches/8.7/generated-gradle-jars"));
  }

  public static File mkdirIfNotExits(File in) {
    if (in != null && !in.exists()) {
      FileUtils.createOrExistsDir(in);
    }

    return in;
  }

  public static void setExecutable(@NonNull final File file) {
    if (!file.setExecutable(true)) {
      LOG.error("Unable to set executable permissions to file: {}", file);
    }
  }

  public static void setProjectDir(@NonNull File file) {
    PROJECTS_DIR = new File(file.getAbsolutePath());
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

  public static File getProjectCacheDir(File projectDir) {
    return new File(projectDir, ANDROIDIDE_PROJECT_CACHE_DIR);
  }

  public static String getArchitecture() {
    return IDEBuildConfigProvider.getInstance().getCpuAbiName();
  }
}
