package com.itsaky.androidide.lsp.java.refactor

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.itsaky.androidide.lsp.refactor.RewriteSpan
import com.itsaky.androidide.resources.R
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * One case per review finding that turns a working file into a broken one.
 *
 * Every case asserts on the *emitted source*, and where the finding is "this does not compile" it feeds
 * the result back through javac. Comparing a `RewriteSpan` in isolation hides exactly these defects.
 */
@RunWith(JUnit4::class)
class ExtractVariableSoundnessTest {
	// --- Akash: emits code that does not compile ---

	@Test
	fun `extracting a whole expression statement does not leave a bare name behind`() {
		// itsaky, CandidateExpressions.kt:223 -- `sb.append("x");` became `StringBuilder v = ...;` + `v;`
		val f = fixture("""	void m(StringBuilder sb) {${'\n'}		sb.append("x");${'\n'}	}""")
		val offered = f.planAfter("sb.append").candidates.map { it.label }
		assertThat(offered).doesNotContain("sb.append(\"x\")")
	}

	@Test
	fun `a static initializer content span starts at its brace`() {
		// itsaky, ScopeChain.kt:107 -- javac's JCBlock.pos for `static { }` is the `s` of `static`.
		val f = fixture("""	static int a = 1, b = 2;${'\n'}	static { use(a + b); }""")
		val out = f.applyAfter("a +", "v")
		// The old span started at `blockSpan.start + 1`, i.e. inside the keyword, and emitted `s` / `tatic`
		// on separate lines.
		assertThat(out).contains("static {")
		assertWithMessage(out).that(compiles(out)).isTrue()
	}

	@Test
	fun `a switch expression rule body conversion does not leave a stray semicolon`() {
		// itsaky, ScopeChain.kt:121 -- the rule's `;` is consumed separately by the parser.
		val f =
			fixture(
				"""	int m(int x, int a, int b) {${'\n'}		return switch (x) {${'\n'}			case 1 -> a + b;${'\n'}			default -> 0;${'\n'}		};${'\n'}	}""",
			)
		val out = f.applyAfter("case 1 -> a +", "v", scope = SWITCH_RULE)
		assertThat(out).doesNotContain("};;")
		assertWithMessage(out).that(compiles(out)).isTrue()
	}

	@Test
	fun `a rung whose anchor shares a line inside a multi-line block is refused`() {
		// itsaky, ExtractVariableEdit.kt:94 -- this used to fall through to LineAbove and hoist the
		// declaration above `it.next();`, reading `it` before it was assigned. Declining is the honest
		// answer: threading a declaration into a line that also holds unrelated statements is not a move
		// this refactoring makes.
		val f =
			fixture(
				"""	void m(java.util.Iterator<String> it) {${'\n'}		it.next(); use(it.hashCode() + 1);${'\n'}		tail();${'\n'}	}${'\n'}	void tail() {}""",
			)
		val plan = f.planAfter("it.hashCode() +")
		val rungs = plan.candidates.flatMap { it.scopes }.map { it.label }
		assertThat(rungs).doesNotContain(METHOD_M)
	}

	@Test
	fun `replace-all does not substitute into a case label`() {
		// itsaky, Occurrences.kt:70 -- matches are shape-checked but not position-checked.
		val f =
			fixture(
				"""	static final int A = 1, B = 2;${'\n'}	void m(int x) {${'\n'}		use(A + B);${'\n'}		switch (x) {${'\n'}			case A + B: tail(); break;${'\n'}		}${'\n'}	}${'\n'}	void tail() {}""",
			)
		val scope =
			f
				.planAfter("use(A +")
				.candidates
				.first()
				.scopes
				.last()
		assertThat(scope.occurrences).hasSize(1)
	}

	// --- Hal: emits code that does not compile ---

	@Test
	fun `a for-loop variable pins the ceiling inside the loop`() {
		// hal, Occurrences.kt:207 -- constrainingScopeFor has no ForLoopTree branch.
		val f =
			fixture(
				"""	void m(java.util.List<String> items) {${'\n'}		for (String s : items) {${'\n'}			use(s.length() + 1);${'\n'}		}${'\n'}	}""",
			)
		val scopes =
			f
				.planAfter("s.length() +")
				.candidates
				.first()
				.scopes
				.map { it.label }
		assertThat(scopes).doesNotContain(METHOD_M)
	}

	@Test
	fun `a try-with-resources variable pins the ceiling inside the try`() {
		val f =
			fixture(
				"""	void m() throws Exception {${'\n'}		try (java.io.Reader r = null) {${'\n'}			use(r.hashCode() + 1);${'\n'}		}${'\n'}	}""",
			)
		val scopes =
			f
				.planAfter("r.hashCode() +")
				.candidates
				.first()
				.scopes
				.map { it.label }
		assertThat(scopes).doesNotContain(METHOD_M)
	}

	@Test
	fun `a one-line block does not hoist the declaration above a preceding statement`() {
		// hal, ExtractVariableEdit.kt:185 -- oneLineBlockRewrite always prepends.
		val f = fixture("""	void m() { int a = 1; use(a + 2); }""")
		val out = f.applyAfter("a +", "v")
		assertThat(out.indexOf("int a = 1")).isLessThan(out.indexOf("int v ="))
		assertThat(compiles(out)).isTrue()
	}

	@Test
	fun `a switch case label is not offered for extraction`() {
		// hal, CandidateExpressions.kt:156 -- case labels must be constant expressions.
		val f =
			fixture(
				"""	static final int FOO = 1;${'\n'}	void m(int x) {${'\n'}		switch (x) {${'\n'}			case FOO + 1: tail(); break;${'\n'}		}${'\n'}	}${'\n'}	void tail() {}""",
			)
		assertThat(f.planAfter("case FOO +").isEmpty).isTrue()
	}

	@Test
	fun `a name colliding with a later local in the same block is rejected`() {
		// hal, Occurrences.kt:229 -- Trees.getScope stops at the candidate.
		val f =
			fixture(
				"""	void m(java.util.List<String> items) {${'\n'}		use(items.size());${'\n'}		int size = 3;${'\n'}		use(size);${'\n'}	}""",
			)
		val taken =
			f
				.planAfter("items.siz")
				.candidates
				.first { it.label == "items.size()" }
				.takenNames
		assertThat(taken).contains("size")
	}

	// --- Hal: compiles but silently changes behaviour ---

	@Test
	fun `the operand of an increment is not offered`() {
		// hal, CandidateExpressions.kt:197 -- `foo(i++)` would increment the copy, not `i`.
		val f = fixture("""	void m(int i) {${'\n'}		use(i++);${'\n'}	}""")
		assertThat(f.planAfter("use(i").candidates.map { it.label }).doesNotContain("i")
	}

	@Test
	fun `a loop condition is not offered`() {
		// itsaky, CandidateExpressions.kt:164 -- hoisting it out evaluates it once, so the loop never ends.
		val f =
			fixture(
				"""	void m(java.util.Iterator<String> it) {${'\n'}		while (it.hasNext()) {${'\n'}			use(it.next().length());${'\n'}		}${'\n'}	}""",
			)
		assertThat(f.planAfter("while (it.hasNext").candidates.map { it.label })
			.doesNotContain("it.hasNext()")
	}

	@Test
	fun `the right operand of a short-circuit is not offered`() {
		// itsaky -- hoisting it out defeats the guard that made it safe.
		val f =
			fixture(
				"""	void m(String s) {${'\n'}		if (s != null && s.length() > 0) {${'\n'}			tail();${'\n'}		}${'\n'}	}${'\n'}	void tail() {}""",
			)
		assertThat(f.planAfter("&& s.length() > ").candidates.map { it.label })
			.doesNotContain("s.length() > 0")
	}

	@Test
	fun `spacing around an operator does not defeat occurrence matching`() {
		// hal, SourceNormalizer.kt:53 -- only `.` had its adjacent space dropped.
		assertThat(normalizeSource("items.size()+1")).isEqualTo(normalizeSource("items.size() + 1"))
	}

	// --- This round: the second review pass ---

	@Test
	fun `a for update expression is not offered`() {
		// coderabbit, CandidateExpressions.kt:231 -- a `for` update is an ExpressionStatementTree, so the
		// statement boundary read it as a fixed evaluation point and the only rung was outside the loop.
		val f =
			fixture(
				"""	void m(int n) {${'\n'}		for (int i = 0; i < n; i = step(i + 1)) {${'\n'}			tail();${'\n'}		}${'\n'}	}${'\n'}	static int step(int v) { return v; }${'\n'}	void tail() {}""",
			)
		assertThat(f.planAfter("i = step(i +").candidates.map { it.label }).doesNotContain("i + 1")
	}

	@Test
	fun `hoisting out of a loop past a write to a read variable is refused`() {
		// hal, ExtractVariablePlanner.kt:162 -- the outer rung froze the value at its first iteration.
		val f =
			fixture(
				"""	void m() {${'\n'}		int limit = 0;${'\n'}		while (limit < 10) {${'\n'}			use(limit + 1);${'\n'}			limit++;${'\n'}		}${'\n'}	}""",
			)
		val rungs =
			f
				.planAfter("use(limit +")
				.candidates
				.first()
				.scopes
				.map { it.label }
		assertThat(rungs).doesNotContain(METHOD_M)
		// The rung inside the loop is still offered, so the action stays usable.
		assertThat(rungs).contains(WHILE_LOOP)
	}

	@Test
	fun `hoisting over a write on the way to an outer rung is refused`() {
		// The same defect one shape along: the anchor is the `if`, so the declaration would land before
		// the assignment the expression reads.
		val f =
			fixture(
				"""	void m(boolean c) {${'\n'}		int limit = 0;${'\n'}		if (c) {${'\n'}			limit = 5;${'\n'}			use(limit + 1);${'\n'}		}${'\n'}	}""",
			)
		val rungs =
			f
				.planAfter("use(limit +")
				.candidates
				.first()
				.scopes
				.map { it.label }
		assertThat(rungs).doesNotContain(METHOD_M)
	}

	@Test
	fun `a plan built with no open document carries no version to compare`() {
		// hal, ExtractVariableAction.kt:169 -- a -1 sentinel compared equal to itself and so passed the
		// staleness guard it existed to fail.
		val f = fixture("""	void m(int a, int b) {${'\n'}		use(a + b);${'\n'}	}""")
		val cursor = f.cursorAfter("a +")
		val plan = buildExtractionPlan(f.task, f.root, f.text, cursor, cursor, documentVersion = null)
		assertThat(plan.candidates).isNotEmpty()
		assertThat(plan.documentVersion).isNull()
	}

	@Test
	fun `an occurrence search does not reach past the rung it was asked about`() {
		// hal, Occurrences.kt:85 -- the walk covered the whole compilation unit per rung per candidate.
		val f =
			fixture(
				"""	void m(java.util.List<String> items) {${'\n'}		use(items.size());${'\n'}	}${'\n'}	void other(java.util.List<String> items) {${'\n'}		use(items.size());${'\n'}	}""",
			)
		val scopes =
			f
				.planAfter("use(items.siz")
				.candidates
				.first { it.label == "items.size()" }
				.scopes
		// `other` spells the same expression over a different `items`, and is outside the rung anyway.
		assertThat(scopes.map { it.occurrences.size }).containsExactly(1)
	}

	@After
	fun closeFixtures() {
		fixtures.forEach(JavacFixture::close)
		fixtures.clear()
	}

	private val fixtures = mutableListOf<JavacFixture>()

	// Registered rather than `use`d so each case still reads as a straight line; the fixture holds a
	// JavaFileManager, so it has to be closed either way.
	private fun fixture(body: String) =
		JavacFixture(
			"""
			|class Fixture {
			|$body
			|	static void use(int value) {}
			|	static void use(Object value) {}
			|}
			""".trimMargin(),
		).also { fixtures += it }

	private companion object {
		val METHOD_M = ScopeLabel(R.string.label_extract_scope_method, "m")
		val SWITCH_RULE = ScopeLabel(R.string.label_extract_scope_switch_rule)
		val WHILE_LOOP = ScopeLabel(R.string.label_extract_scope_while_loop)
	}
}
