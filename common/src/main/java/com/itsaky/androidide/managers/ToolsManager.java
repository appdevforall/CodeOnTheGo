/*
 * This file is part of AndroidIDE.
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidIDE is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 *
 */
package com.itsaky.androidide.managers;

import android.content.pm.PackageManager;

import androidx.annotation.NonNull;

import com.blankj.utilcode.util.FileIOUtils;
import com.blankj.utilcode.util.FileUtils;
import com.blankj.utilcode.util.ResourceUtils;
import com.itsaky.androidide.app.BaseApplication;
import com.itsaky.androidide.app.configuration.IDEBuildConfigProvider;
import com.itsaky.androidide.app.configuration.IJdkDistributionProvider;
import com.itsaky.androidide.utils.Environment;

import org.jetbrains.annotations.Contract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Arrays;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

import kotlin.io.ConstantsKt;
import kotlin.io.FilesKt;


public class ToolsManager {

    private static final Logger LOG = LoggerFactory.getLogger(ToolsManager.class);
    private static final String[] EXTRACT_JDWP_LIBS = {
            "jdwp",
            "dt_socket",
            "npt"
    };

    public static String COMMON_ASSET_DATA_DIR = "data/common";

    public static void init(@NonNull BaseApplication app, Runnable onFinish) {

        if (!IDEBuildConfigProvider.getInstance().supportsCpuAbi()) {
            LOG.error("Device not supported");
            return;
        }

        CompletableFuture.runAsync(() -> {
            // Load installed JDK distributions
            IJdkDistributionProvider.getInstance().loadDistributions();
            writeNoMediaFile();
            extractAapt2();
            extractToolingApi();
            extractCogoPlugin();
            extractGradleDists();
            // NOTE no need to extract android.jar
            //extractAndroidJar();
            extractColorScheme(app);
            extractJdwp(app);
            writeInitScript();

            deleteIdeenv();
        }).whenComplete((__, error) -> {
            if (error != null) {
                LOG.error("Error extracting tools", error);
            }

            if (onFinish != null) {
                onFinish.run();
            }
        });
    }

    private static void extractJdwp(final BaseApplication app) {
        try {
            final var packageManager = app.getPackageManager();
            final var packageInfo = packageManager.getPackageInfo(app.getPackageName(), PackageManager.GET_META_DATA);
            Objects.requireNonNull(packageManager, "Unable to get package info for current context");

            final var appInfo = packageInfo.applicationInfo;
            Objects.requireNonNull(appInfo, "Unable to get application info for current context");

            final var nativeLibsDir = new File(appInfo.nativeLibraryDir);

            for (var libName : EXTRACT_JDWP_LIBS) {
                final var libIN = new File(nativeLibsDir, "lib" + libName + ".so");

                if (!libIN.exists()) {
                    throw new IllegalStateException("Unable to find lib" + libName + ".so in " + nativeLibsDir.getAbsolutePath());
                }

                final var buildConfigProvider = IDEBuildConfigProvider.getInstance();
                final var libOUTDir = new File(Environment.JDWP_LIB_DIR, buildConfigProvider.getDeviceArch().getAbi());
                final var libOUT = new File(libOUTDir, libIN.getName());
                if (!libOUTDir.exists() && !libOUTDir.mkdirs()) {
                    throw new RuntimeException("Unable to create directory " + libOUT.getParentFile().getAbsolutePath());
                }

                if (!libOUT.exists()) {
                    FileUtils.copy(libIN, libOUT);
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private static void extractColorScheme(final BaseApplication app) {
        final var defPath = "editor/schemes";
        final var dir = new File(Environment.ANDROIDIDE_UI, defPath);
        try {
            for (final String asset : app.getAssets().list(defPath)) {

                final var prop = new File(dir, asset + "/" + "scheme.prop");
                if (prop.exists() && !shouldExtractScheme(app, new File(dir, asset),
                        defPath + "/" + asset)) {
                    continue;
                }

                final File schemeDir = new File(dir, asset);
                if (schemeDir.exists()) {
                    schemeDir.delete();
                }

                ResourceUtils.copyFileFromAssets(defPath + "/" + asset, schemeDir.getAbsolutePath());
            }
        } catch (IOException e) {
            LOG.error("Failed to extract color schemes", e);
        }
    }

    private static boolean shouldExtractScheme(final BaseApplication app, final File dir,
                                               final String path) throws IOException {

        final var schemePropFile = new File(dir, "scheme.prop");
        if (!schemePropFile.exists()) {
            return true;
        }

        final var files = app.getAssets().list(path);
        if (Arrays.stream(files).noneMatch("scheme.prop"::equals)) {
            // no scheme.prop file
            return true;
        }

        try {
            final var props = new Properties();
            Reader reader = new InputStreamReader(app.getAssets().open(path + "/scheme.prop"));
            props.load(reader);
            reader.close();

            final var version = Integer.parseInt(props.getProperty("scheme.version", "0"));
            if (version == 0) {
                return true;
            }

            props.clear();

            reader = new FileReader(schemePropFile);
            props.load(reader);
            reader.close();

            final var fileVersion = Integer.parseInt(props.getProperty("scheme.version", "0"));
            if (fileVersion < 0) {
                return true;
            }

            return version > fileVersion;
        } catch (Throwable err) {
            LOG.error("Failed to read color scheme version for scheme '{}'", path, err);
            return false;
        }
    }

    private static void writeNoMediaFile() {
        final var noMedia = new File(BaseApplication.getBaseInstance().getProjectsDir(), ".nomedia");
        if (!noMedia.exists()) {
            try {
                if (!noMedia.createNewFile()) {
                    LOG.error("Failed to create .nomedia file in projects directory");
                }
            } catch (IOException e) {
                LOG.error("Failed to create .nomedia file in projects directory");
            }
        }
    }

    private static void extractAndroidJar() {
        if (!Environment.ANDROID_JAR.exists()) {
            ResourceUtils.copyFileFromAssets(getCommonAsset("android.jar"),
                    Environment.ANDROID_JAR.getAbsolutePath());
        }
    }

    private static void deleteIdeenv() {
        final var file = new File(Environment.BIN_DIR, "ideenv");
        if (file.exists() && !file.delete()) {
            LOG.warn("Unable to delete file: {}", file);
        }
    }

    /**
     * Keywords: [assets, gradle, gradleWrapper, localJars, Jars, Jar, ProjectTemplate, postRecipe ]
     * ~/AndroidIDE/app/build/intermediates/assets/debug/mergeDebugAssets/data/common
     * Why do we need build/intermediates/*** folder when we can just use assets? I don't know.
     * During my short search I wasn't able to find anything meaningful in regards to that folder.
     * The fact is that app copies assets from data/common folder.
     * And to add any new libs using existing mechanisms
     *
     * @param name - asset name
     * @return Full path to debug/compressed_assets/name
     * @see ToolsManager getCommonAsset(String name)
     * @see ResourceUtils copyFileFromAssets
     * We have to put our files under data/common folder.  And add new postRecipe entry to templates
     */

    @NonNull
    @Contract(pure = true)
    public static String getCommonAsset(String name) {
        return COMMON_ASSET_DATA_DIR + "/" + name;
    }

    private static void extractAapt2() {
        if (!Environment.AAPT2.exists()) {
            final var context = BaseApplication.getBaseInstance();
            final var nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;
            final var sourceAapt2 = new File(nativeLibraryDir, "libaapt2.so");
            if (sourceAapt2.exists() && sourceAapt2.isFile()) {
                FilesKt.copyTo(sourceAapt2, Environment.AAPT2, true, ConstantsKt.DEFAULT_BUFFER_SIZE);
            } else {
                LOG.error("{} file does not exist! This can be problematic.", sourceAapt2);
            }
        }

        if (!Environment.AAPT2.canExecute() && !Environment.AAPT2.setExecutable(true)) {
            LOG.error("Cannot set executable permissions to AAPT2 binary");
        }
    }

    private static void extractToolingApi() {
        if (Environment.TOOLING_API_JAR.exists()) {
            FileUtils.delete(Environment.TOOLING_API_JAR);
        }

        ResourceUtils.copyFileFromAssets(getCommonAsset("tooling-api-all.jar"),
                Environment.TOOLING_API_JAR.getAbsolutePath());
    }

    private static void extractCogoPlugin() {
        if (Environment.COGO_PLUGIN_JAR.exists()) {
            FileUtils.delete(Environment.COGO_PLUGIN_JAR);
        }

        ResourceUtils.copyFileFromAssets(getCommonAsset("cogo-plugin.jar"),
                Environment.COGO_PLUGIN_JAR.getAbsolutePath());
    }

    private static void extractGradleDists() {
        String[] binToCopy = {"gradle-8.7-bin.zip"};
        for (String binFile : binToCopy) {
            ResourceUtils.copyFileFromAssets(getCommonAsset(binFile),
                    Environment.GRADLE_DISTS.getAbsolutePath() + File.separator + binFile);
        }
    }

    private static void writeInitScript() {
        if (Environment.INIT_SCRIPT.exists()) {
            FileUtils.delete(Environment.INIT_SCRIPT);
        }
        FileIOUtils.writeFileFromString(Environment.INIT_SCRIPT, readInitScript());
    }

    @NonNull
    private static String readInitScript() {
        return ResourceUtils.readAssets2String(getCommonAsset("androidide.init.gradle"));
    }

}
