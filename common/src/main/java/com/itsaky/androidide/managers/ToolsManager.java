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

import static org.adfa.constants.ConstantsKt.V7_KEY;
import static org.adfa.constants.ConstantsKt.V8_KEY;

import android.content.res.AssetManager;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import com.aayushatharva.brotli4j.Brotli4jLoader;
import com.aayushatharva.brotli4j.decoder.BrotliInputStream;
import com.blankj.utilcode.util.FileIOUtils;
import com.blankj.utilcode.util.FileUtils;
import com.blankj.utilcode.util.ResourceUtils;
import com.itsaky.androidide.app.BaseApplication;
import com.itsaky.androidide.app.configuration.IDEBuildConfigProvider;
import com.itsaky.androidide.app.configuration.IJdkDistributionProvider;
import com.itsaky.androidide.utils.Environment;
import com.itsaky.androidide.utils.IoUtilsKt;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Date;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.jetbrains.annotations.Contract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ToolsManager {

	private static final Logger LOG = LoggerFactory.getLogger(ToolsManager.class);

	public static String COMMON_ASSET_DATA_DIR = "data/common";

	public static String DATABASE_ASSET_DATA_DIR = "database";

	private static final String CHAR_POOL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

	public static String generateIssuerDN() {
		SecureRandom random = new SecureRandom();
		String country = Environment.KEYSTORE_EU_COUNTRY_CODES[random.nextInt(Environment.KEYSTORE_EU_COUNTRY_CODES.length)];
		return String.format("C=%s, O=, CN=", country);
	}

	/**
	 * Keywords: [assets, gradle, gradleWrapper, localJars, Jars, Jar, ProjectTemplate, postRecipe ] ~/AndroidIDE/app/build/intermediates/assets/debug/mergeDebugAssets/data/common Why do we need build/intermediates/*** folder when we can just use assets? I don't know. During my short search I wasn't able to find anything meaningful in regards to that folder. The fact is that app copies assets from data/common folder. And to add any new libs using existing mechanisms
	 *
	 * @param name
	 *            - asset name
	 * @return Full path to debug/compressed_assets/name
	 * @see ToolsManager getCommonAsset(String name)
	 * @see ResourceUtils copyFileFromAssets We have to put our files under data/common folder. And add new postRecipe entry to templates
	 */

	@NonNull
	@Contract(pure = true)
	public static String getCommonAsset(String name) {
		return COMMON_ASSET_DATA_DIR + "/" + name;
	}

	@NonNull
	@Contract(pure = true)
	public static String getDatabaseAsset(String name) {
		return DATABASE_ASSET_DATA_DIR + "/" + name;
	}

	public static void init(@NonNull BaseApplication app, Runnable onFinish) {

		if (!IDEBuildConfigProvider.getInstance().supportsCpuAbi()) {
			LOG.error("Device not supported");
			return;
		}

		CompletableFuture.runAsync(() -> {
			// Load installed JDK distributions
			IJdkDistributionProvider.getInstance().loadDistributions();

			updateToolingJar(app.getAssets());
			extractLogSender(app);

			writeNoMediaFile();
			extractCogoPlugin();
			extractColorScheme(app);
			writeInitScript();
			generateKeystore();
			deleteIdeenv();

			LOG.info("Tools extraction completed");
		}).whenComplete((__, error) -> {
			if (error != null) {
				LOG.error("Error extracting tools", error);
			}

			if (onFinish != null) {
				onFinish.run();
			}
		});
	}

	private static void deleteIdeenv() {
		final var file = new File(Environment.BIN_DIR, "ideenv");
		if (file.exists() && !file.delete()) {
			LOG.warn("Unable to delete file: {}", file);
		}
	}

	private static void extractCogoPlugin() {
		if (Environment.COGO_PLUGIN_JAR.exists()) {
			FileUtils.delete(Environment.COGO_PLUGIN_JAR);
		}

		ResourceUtils.copyFileFromAssets(getCommonAsset("cogo-plugin.jar"),
				Environment.COGO_PLUGIN_JAR.getAbsolutePath());
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

	@WorkerThread
	private static void extractLogSender(BaseApplication app) {
		if (Environment.LOGSENDER_AAR.exists()) {
			FileUtils.delete(Environment.LOGSENDER_AAR);
		}

		Environment.mkdirIfNotExists(Environment.LOGSENDER_DIR);

		final var variant = Build.SUPPORTED_ABIS[0].contains(V8_KEY) ? V8_KEY : V7_KEY;
		ResourceUtils.copyFileFromAssets(getCommonAsset("logsender-" + variant + "-release.aar"),
				Environment.LOGSENDER_AAR.getAbsolutePath());
	}

	private static void generateKeystore() {
		try {
			String keystorePath = Environment.KEYSTORE_RELEASE.getPath();
			String alias = generateRandomPassword(Environment.KEYSTORE_ALIAS_LEN);

			LOG.debug("Generating keystore at: {}", keystorePath);

			Security.addProvider(new BouncyCastleProvider());

			String storePassword = generateRandomPassword(Environment.KEYSTORE_PWD_LEN);
			String keyPassword = storePassword;

			KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA", "BC");
			keyGen.initialize(Environment.KEYSTORE_KEY_SIZE);
			KeyPair keyPair = keyGen.generateKeyPair();

			Date now = new Date();
			Date expiry = new Date(now.getTime() + Environment.KEYSTORE_EXPIRY_5YRS);

			X500Name issuer = new X500Name(generateIssuerDN());
			X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
					issuer,
					BigInteger.valueOf(System.currentTimeMillis()),
					now,
					expiry,
					issuer,
					keyPair.getPublic());

			ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSAEncryption")
					.build(keyPair.getPrivate());

			X509Certificate certificate = new JcaX509CertificateConverter()
					.getCertificate(certBuilder.build(signer));

			KeyStore keyStore = KeyStore.getInstance("PKCS12");
			keyStore.load(null, null);
			keyStore.setKeyEntry(alias, keyPair.getPrivate(), keyPassword.toCharArray(), new java.security.cert.Certificate[]{certificate});

			File keystoreFile = new File(keystorePath);
			keystoreFile.getParentFile().mkdirs();

			try (FileOutputStream fos = new FileOutputStream(keystoreFile)) {
				keyStore.store(fos, storePassword.toCharArray());
			}

			// Write to keystore.properties
			File propsFile = Environment.KEYSTORE_PROPERTIES;
			Properties props = new Properties();
			props.setProperty(Environment.KEYSTORE_PROP_STOREFILE, keystoreFile.getName());
			props.setProperty(Environment.KEYSTORE_PROP_STOREPWD, storePassword);
			props.setProperty(Environment.KEYSTORE_PROP_KEYALIAS, alias);
			props.setProperty(Environment.KEYSTORE_PROP_KEYPWD, keyPassword);

			try (FileWriter writer = new FileWriter(propsFile)) {
				props.store(writer, "Generated keystore credentials");
			}

			LOG.debug("Keystore generated at: {}", keystoreFile.getAbsolutePath());
			LOG.debug("Passwords saved to: {}", propsFile.getAbsolutePath());
		} catch (Exception e) {
			LOG.error("Failed to generate keystore!! ", e);
		}
	}

	private static String generateRandomPassword(int length) {
		SecureRandom random = new SecureRandom();
		StringBuilder sb = new StringBuilder(length);
		for (int i = 0; i < length; i++) {
			sb.append(CHAR_POOL.charAt(random.nextInt(CHAR_POOL.length())));
		}
		return sb.toString();
	}

	@NonNull
	private static String readInitScript() {
		return ResourceUtils.readAssets2String(getCommonAsset("androidide.init.gradle"));
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

	@WorkerThread
	private static void updateToolingJar(AssetManager assets) {
		// Ensure relevant shared libraries are loaded
		Brotli4jLoader.ensureAvailability();

		final var toolingJarName = "tooling-api-all.jar";
		InputStream toolingJarStream;
		try {
			toolingJarStream = assets.open(ToolsManager.getCommonAsset(toolingJarName));
		} catch (IOException e) {
			try {
				toolingJarStream = new BrotliInputStream(assets.open(ToolsManager.getCommonAsset(toolingJarName + ".br")));
			} catch (IOException e2) {
				LOG.error("Tooling jar not found in assets {}", e2.getMessage());
				return;
			}
		}

		try {
			final var toolingJarFile = Environment.TOOLING_API_JAR;
			if (toolingJarFile.exists()) {
				FileUtils.delete(toolingJarFile);
			}

			Objects.requireNonNull(toolingJarFile.getParentFile()).mkdirs();
			try (final var fos = new FileOutputStream(toolingJarFile)) {
				IoUtilsKt.transferToStream(toolingJarStream, fos);
			}
		} catch (Throwable err) {
			LOG.error("Failed to copy tooling API jar", err);
		} finally {
			try {
				toolingJarStream.close();
			} catch (IOException e) {
				LOG.error("Failed to close tooling API jar stream", e);
			}
		}
	}

	private static void writeInitScript() {
		if (Environment.INIT_SCRIPT.exists()) {
			FileUtils.delete(Environment.INIT_SCRIPT);
		}
		FileIOUtils.writeFileFromString(Environment.INIT_SCRIPT, readInitScript());
	}

	private static void writeNoMediaFile() {
		final var noMedia = new File(Environment.PROJECTS_DIR, ".nomedia");
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

}
