package org.appdevforall.cotg.corpus.mixedlang.core;

/** Java class called from Kotlin (OrderService) - the harder cross-language direction: kotlinc
 * has no visibility into a same-module .java source unless explicitly given both allSources. */
public class JavaCalculator {

	private static final String MARKER = "QB_JAVA_SIG_MARKER_V2";

	public long computeTotal(int a, int b) {
		return (long) a + b;
	}
}
