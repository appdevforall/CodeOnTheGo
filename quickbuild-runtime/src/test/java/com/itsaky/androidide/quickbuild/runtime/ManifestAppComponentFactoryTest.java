package com.itsaky.androidide.quickbuild.runtime;

import static com.google.common.truth.Truth.assertThat;

import java.io.File;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Attr;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;

/**
 * ADFA-4128 Bug 2 regression. The runtime AAR must NOT declare {@code android:appComponentFactory}: a debuggable app that also pulls androidx.core (which declares {@code androidx.core.app.CoreComponentFactory}) then fails manifest merge at {@code processDebugMainManifest} - before the setup build's merged-manifest transform runs - which killed Quick Build provisioning for every app template. The setup build owns the factory (QuickBuildManifestTransformer sets it on the merged manifest). If someone re-adds the attribute to the AAR manifest, this test fails.
 *
 * <p>
 * Parses the XML (so the explanatory comment that mentions the attribute name doesn't trip the check) and asserts no element declares an appComponentFactory attribute.
 */
class ManifestAppComponentFactoryTest {

	/** Walk up from the test working directory to this module's src/main/AndroidManifest.xml. */
	private static File locateManifest() {
		File dir = new File(System.getProperty("user.dir"));
		while (dir != null) {
			File candidate = new File(dir, "quickbuild-runtime/src/main/AndroidManifest.xml");
			if (candidate.isFile()) {
				return candidate;
			}
			File local = new File(dir, "src/main/AndroidManifest.xml");
			if (local.isFile() && dir.getName().equals("quickbuild-runtime")) {
				return local;
			}
			dir = dir.getParentFile();
		}
		throw new IllegalStateException(
				"could not locate quickbuild-runtime/src/main/AndroidManifest.xml from "
						+ System.getProperty("user.dir"));
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
