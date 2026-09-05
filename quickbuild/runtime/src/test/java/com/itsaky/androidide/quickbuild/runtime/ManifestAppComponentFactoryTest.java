package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import java.io.File;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Attr;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;

/**
 * Pins that the runtime AAR manifest declares no {@code android:appComponentFactory}.
 *
 * If it did, a debuggable app also pulling androidx.core would fail manifest merge at {@code processDebugMainManifest} before the proxy build's merged-manifest transform runs - the failure that once killed Quick Build provisioning for every app template. The proxy app build owns the factory; the XML is parsed so this comment's mention cannot trip the check.
 */
class ManifestAppComponentFactoryTest {

	/**
	 * Resolves this module's src/main/AndroidManifest.xml from the test working directory.
	 *
	 * Gradle runs unit tests with the working directory at the module root, so no search is needed, and no module path is hardcoded that a module move would silently invalidate.
	 *
	 * @return the manifest file; a miss throws rather than letting the test pass vacuously
	 */
	private static File locateManifest() {
		File manifest = new File(System.getProperty("user.dir"), "src/main/AndroidManifest.xml");
		if (!manifest.isFile()) {
			throw new IllegalStateException("no src/main/AndroidManifest.xml under " + manifest.getParent());
		}
		return manifest;
	}

	@Test
	void aarManifestDoesNotDeclareAppComponentFactory() throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		NodeList elements = factory.newDocumentBuilder().parse(locateManifest()).getElementsByTagName("*");
		for (int i = 0; i < elements.getLength(); i++) {
			NamedNodeMap attrs = elements.item(i).getAttributes();
			for (int j = 0; j < attrs.getLength(); j++) {
				Attr attr = (Attr) attrs.item(j);
				assertThat(attr.getLocalName()).isNotEqualTo("appComponentFactory");
			}
		}
	}
}
