package org.appdevforall.cotg.corpus.mixedlang.core;

/**
 * Java class called from Kotlin (OrderService) - the harder cross-language direction: kotlinc has no visibility into a same-module .java source unless explicitly given both allSources.
 */
public class JavaCalculator {

	public int computeTotal(int a, int b) {
		return a + b;
	}
}
