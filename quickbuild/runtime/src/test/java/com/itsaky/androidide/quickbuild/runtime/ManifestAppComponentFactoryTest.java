package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import java.io.File;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Attr;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;

/**
 * ADFA-4128 Bug 2 regression. The runtime AAR must NOT declare {@code android:appComponentFactory}: a debuggable app that also pulls androidx.core (which declares {@code androidx.core.app.CoreComponentFactory}) then fails manifest merge at {@code processDebugMainManifest} - before the proxy app build's merged-manifest transform runs - which killed Quick Build provisioning for every app template. The proxy app build owns the factory (QuickBuildManifestTransformer sets it on the merged manifest). If someone re-adds the attribute to the AAR manifest, this test fails.
 *
 * <p>
 * Parses the XML (so the explanatory comment that mentions the attribute name doesn't trip the check) and asserts no element declares an appComponentFactory attribute.
 */
class ManifestAppComponentFactoryTest {

	/**
	 * This module's src/main/AndroidManifest.xml. Gradle runs unit tests with the working directory at the module root, so no search is needed - and nothing here hardcodes the module's path, which a module move would otherwise silently invalidate.
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
