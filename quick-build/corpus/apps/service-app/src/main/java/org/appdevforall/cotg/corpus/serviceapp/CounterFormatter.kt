package org.appdevforall.cotg.corpus.serviceapp

/**
 * Leaf helper used only by CounterService. Editing this class (not done in this app's
 * scripted edits) recompiles a non-component class: the accepted live-instance residual
 * from the design contract, where a running service keeps its old helper until the
 * next restart.
 */
object CounterFormatter {
	fun describe(startCount: Int): String =
		if (startCount == 1) "first run" else "run number $startCount"
}
