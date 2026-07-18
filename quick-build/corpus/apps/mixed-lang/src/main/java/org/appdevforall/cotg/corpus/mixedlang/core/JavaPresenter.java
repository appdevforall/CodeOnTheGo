package org.appdevforall.cotg.corpus.mixedlang.core;

public class JavaPresenter {

	public String present(int value) {
		return new KotlinFormatter().formatLabel(value);
	}
}
