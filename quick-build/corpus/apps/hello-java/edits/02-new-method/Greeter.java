package org.appdevforall.cotg.corpus.hellojava;

/** Pure logic: builds the greeting shown on the main screen. No Android imports. */
public class Greeter {

	public String greet(String name) {
		return "Greetings, " + name + "! The quick build reloaded this method.";
	}

	public String farewell(String name) {
		return "Farewell, " + name + "! This new method was added by a live edit.";
	}
}
