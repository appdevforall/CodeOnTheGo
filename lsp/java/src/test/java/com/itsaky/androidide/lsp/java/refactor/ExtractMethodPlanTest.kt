package com.itsaky.androidide.lsp.java.refactor

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * One case per analysis rule (R5-R10) and one per refusal reason (R14).
 *
 * Where a rule exists so the result compiles -- `throws`, `static`, captured types -- the case feeds
 * the rewritten file back through javac. A signature that merely looks plausible is exactly the failure
 * mode these rules exist to prevent, and asserting on emitted text alone would not catch it.
 */
@RunWith(JUnit4::class)
class ExtractMethodPlanTest {
	private val fixtures = mutableListOf<JavacFixture>()

	@After
	fun tearDown() = fixtures.forEach(JavacFixture::close)

	// --- parameters (R5) ---

	@Test
	fun `captured locals become parameters in first-use order`() {
		val f =
			fixture(
				"""	void m() {${'\n'}		int a = 1;${'\n'}		String s = "x";${'\n'}		use(a);${'\n'}		use(s);${'\n'}	}""",
			)

		val plan = f.methodPlanOver("use(a);${'\n'}		use(s);")

		val candidate = plan.candidates.single()
		assertThat(candidate.parameters.map { "${it.typeText} ${it.name}" })
			.containsExactly("int a", "String s")
			.inOrder()
		assertWithMessage(f.applyMethod(plan, "report")).that(compiles(f.applyMethod(plan, "report"))).isTrue()
	}

	@Test
	fun `a field needs no parameter`() {
		val f =
			fixture(
				"""	private int count = 1;${'\n'}	void m() {${'\n'}		use(count);${'\n'}	}""",
			)

		val plan = f.methodPlanOver("use(count);")

		assertThat(plan.candidates.single().parameters).isEmpty()
		val out = f.applyMethod(plan, "report")
		assertWithMessage(out).that(compiles(out)).isTrue()
	}

	// --- return type and call site (R6) ---

	@Test
	fun `a void expression produces a void method and a bare call`() {
		val f = fixture("""	void m(int a) {${'\n'}		use(a);${'\n'}	}""")

		val candidate = f.methodPlanAfter("use").candidates.first { it.label == "use(a)" }

		assertThat(candidate.returnTypeText).isEqualTo("void")
		assertThat(candidate.callSite).isEqualTo(CallSiteForm.Call)
	}

	@Test
	fun `an expression produces a returning method`() {
		val f = fixture("""	int m(int a, int b) {${'\n'}		return a + b;${'\n'}	}""")

		val plan = f.methodPlanAfter("a +")

		val candidate = plan.candidates.first()
		assertThat(candidate.returnTypeText).isEqualTo("int")
		assertThat(candidate.signatureText("sum")).isEqualTo("private int sum(int a, int b)")
		val out = f.applyMethod(plan, "sum")
		assertThat(out).contains("return sum(a, b);")
		assertWithMessage(out).that(compiles(out)).isTrue()
	}

	// --- outputs (R7) ---

	@Test
	fun `a single output is assigned back at the call site`() {
		val f =
			fixture(
				"""	int m() {${'\n'}		int total = 0;${'\n'}		total = total + 1;${'\n'}		return total;${'\n'}	}""",
			)

		val plan = f.methodPlanOver("int total = 0;${'\n'}		total = total + 1;")

		val candidate = plan.candidates.single()
		assertThat(candidate.callSite).isEqualTo(CallSiteForm.AssignOutput("int", "total"))
		assertThat(candidate.returnTypeText).isEqualTo("int")
		val out = f.applyMethod(plan, "runningTotal")
		assertThat(out).contains("int total = runningTotal();")
		assertThat(out).contains("return total;")
		assertWithMessage(out).that(compiles(out)).isTrue()
	}

	@Test
	fun `two outputs are declined and named`() {
		val f =
			fixture(
				"""	int m() {${'\n'}		int a = 1;${'\n'}		int b = 2;${'\n'}		return a + b;${'\n'}	}""",
			)

		val plan = f.methodPlanOver("int a = 1;${'\n'}		int b = 2;")

		assertThat(plan.candidates).isEmpty()
		assertThat(plan.refusal).isEqualTo(ExtractionRefusal.MultipleOutputs(listOf("a", "b")))
	}

	@Test
	fun `assigning a variable declared outside the region is declined and named`() {
		val f =
			fixture(
				"""	int m(int[] values) {${'\n'}		int sum = 0;${'\n'}		for (int v : values) {${'\n'}			sum += v;${'\n'}		}${'\n'}		return sum;${'\n'}	}""",
			)

		val plan = f.methodPlanOver("for (int v : values) {${'\n'}			sum += v;${'\n'}		}")

		assertThat(plan.refusal).isEqualTo(ExtractionRefusal.ReassignsOuterVar("sum"))
	}

	@Test
	fun `writing through a captured array is not a reassignment`() {
		val f =
			fixture(
				"""	void m(int[] values) {${'\n'}		values[0] = 1;${'\n'}		use(values[0]);${'\n'}	}""",
			)

		val plan = f.methodPlanOver("values[0] = 1;${'\n'}		use(values[0]);")

		assertThat(plan.refusal).isNull()
		val out = f.applyMethod(plan, "seed")
		assertWithMessage(out).that(compiles(out)).isTrue()
	}

	// --- exits (R8) ---

	@Test
	fun `a return in the middle of the region is declined`() {
		val f =
			fixture(
				"""	int m(int x) {${'\n'}		if (x > 0) {${'\n'}			return 1;${'\n'}		}${'\n'}		use(x);${'\n'}		return 0;${'\n'}	}""",
			)

		val plan = f.methodPlanOver("if (x > 0) {${'\n'}			return 1;${'\n'}		}${'\n'}		use(x);")

		assertThat(plan.refusal).isEqualTo(ExtractionRefusal.ExitsRegion)
	}

	@Test
	fun `a break targeting a loop inside the region is not an exit`() {
		val f =
			fixture(
				"""	void m(int n) {${'\n'}		for (int i = 0; i < n; i++) {${'\n'}			if (i == 2) {${'\n'}				break;${'\n'}			}${'\n'}		}${'\n'}		use(n);${'\n'}	}""",
			)

		val plan = f.methodPlanOver("for (int i = 0; i < n; i++) {${'\n'}			if (i == 2) {${'\n'}				break;${'\n'}			}${'\n'}		}")

		assertThat(plan.refusal).isNull()
		val out = f.applyMethod(plan, "scan")
		assertWithMessage(out).that(compiles(out)).isTrue()
	}

	@Test
	fun `a break targeting a loop outside the region is declined`() {
		val f =
			fixture(
				"""	void m(int n) {${'\n'}		for (int i = 0; i < n; i++) {${'\n'}			use(i);${'\n'}			break;${'\n'}		}${'\n'}	}""",
			)

		val plan = f.methodPlanOver("use(i);${'\n'}			break;")

		assertThat(plan.refusal).isEqualTo(ExtractionRefusal.ExitsRegion)
	}

	@Test
	fun `a tail return moves with the region`() {
		val f =
			fixture(
				"""	int m(int a, int b) {${'\n'}		use(a);${'\n'}		return a + b;${'\n'}	}""",
			)

		val plan = f.methodPlanOver("use(a);${'\n'}		return a + b;")

		val candidate = plan.candidates.single()
		assertThat(candidate.callSite).isEqualTo(CallSiteForm.Return)
		assertThat(candidate.returnTypeText).isEqualTo("int")
		val out = f.applyMethod(plan, "finish")
		assertThat(out).contains("return finish(a, b);")
		assertWithMessage(out).that(compiles(out)).isTrue()
	}

	@Test
	fun `a return belonging to a lambda inside the region is not an exit`() {
		val f =
			fixture(
				"""	void m(java.util.List<String> items) {${'\n'}		items.removeIf(s -> {${'\n'}			return s.isEmpty();${'\n'}		});${'\n'}	}""",
			)

		val plan = f.methodPlanOver("items.removeIf(s -> {${'\n'}			return s.isEmpty();${'\n'}		});")

		assertThat(plan.refusal).isNull()
		val out = f.applyMethod(plan, "prune")
		assertWithMessage(out).that(compiles(out)).isTrue()
	}

	// --- modifiers and throws (R10) ---

	@Test
	fun `a static anchor produces a static method`() {
		val f = fixture("""	static int m(int a) {${'\n'}		return a + 1;${'\n'}	}""")

		val plan = f.methodPlanAfter("a +")

		assertThat(plan.candidates.first().modifiers).containsExactly("private", "static").inOrder()
		val out = f.applyMethod(plan, "bumped")
		assertWithMessage(out).that(compiles(out)).isTrue()
	}

	@Test
	fun `a checked exception the region throws is declared`() {
		val f =
			fixture(
				"""	void m(java.io.InputStream in) throws java.io.IOException {${'\n'}		in.read();${'\n'}	}""",
			)

		val plan = f.methodPlanOver("in.read();")

		assertThat(plan.candidates.single().thrownTypes).containsExactly("java.io.IOException")
		val out = f.applyMethod(plan, "drain")
		assertWithMessage(out).that(compiles(out)).isTrue()
	}

	@Test
	fun `a checked exception the region itself catches is not declared`() {
		val f =
			fixture(
				"""	void m(java.io.InputStream in) {${'\n'}		try {${'\n'}			in.read();${'\n'}		} catch (java.io.IOException e) {${'\n'}			use(e);${'\n'}		}${'\n'}	}""",
			)

		val plan = f.methodPlanOver("try {${'\n'}			in.read();${'\n'}		} catch (java.io.IOException e) {${'\n'}			use(e);${'\n'}		}")

		assertThat(plan.candidates.single().thrownTypes).isEmpty()
		val out = f.applyMethod(plan, "drain")
		assertThat(out).doesNotContain("throws")
		assertWithMessage(out).that(compiles(out)).isTrue()
	}

	@Test
	fun `an unchecked exception is not declared`() {
		val f =
			fixture(
				"""	void m(int x) {${'\n'}		if (x < 0) {${'\n'}			throw new IllegalArgumentException("x");${'\n'}		}${'\n'}	}""",
			)

		val plan = f.methodPlanOver("if (x < 0) {${'\n'}			throw new IllegalArgumentException(\"x\");${'\n'}		}")

		assertThat(plan.candidates.single().thrownTypes).isEmpty()
		val out = f.applyMethod(plan, "reject")
		assertWithMessage(out).that(compiles(out)).isTrue()
	}

	@Test
	fun `a type parameter of the anchor method is declined and named`() {
		val f =
			fixture(
				"""	<T> T m(T value) {${'\n'}		use(value);${'\n'}		return value;${'\n'}	}""",
			)

		val plan = f.methodPlanOver("use(value);")

		assertThat(plan.refusal).isEqualTo(ExtractionRefusal.UsesTypeParameter("T"))
	}

	// --- target (R4) ---

	@Test
	fun `a region in an anonymous class anchors on that class`() {
		val f =
			fixture(
				"""	Runnable m(int a) {${'\n'}		return new Runnable() {${'\n'}			@Override${'\n'}			public void run() {${'\n'}				use(a + 1);${'\n'}			}${'\n'}		};${'\n'}	}""",
			)

		val plan = f.methodPlanAfter("a +")
		val out = f.applyMethod(plan, "bumped")

		// Inserted after run(), still inside the anonymous class body, so `};` still closes it last.
		assertThat(out.indexOf("private int bumped")).isGreaterThan(out.indexOf("public void run()"))
		assertThat(out.indexOf("private int bumped")).isLessThan(out.indexOf("};"))
		assertWithMessage(out).that(compiles(out)).isTrue()
	}

	@Test
	fun `a local class the region uses but does not contain is declined`() {
		val f =
			fixture(
				"""	void m() {${'\n'}		class Helper {${'\n'}			int value() { return 1; }${'\n'}		}${'\n'}		Helper h = new Helper();${'\n'}		use(h.value());${'\n'}	}""",
			)

		val plan = f.methodPlanOver("use(h.value());")

		assertThat(plan.refusal).isEqualTo(ExtractionRefusal.CapturedLocalDeclaration("h"))
	}

	@Test
	fun `a local class the region constructs but does not declare is declined`() {
		val f =
			fixture(
				"""	void m() {${'\n'}		class Helper {${'\n'}			int value() { return 1; }${'\n'}		}${'\n'}		use(new Helper().value());${'\n'}	}""",
			)

		val plan = f.methodPlanOver("use(new Helper().value());")

		assertThat(plan.refusal).isEqualTo(ExtractionRefusal.CapturedLocalDeclaration("Helper"))
	}

	@Test
	fun `a local class the region declares and the code after it names is declined`() {
		val f =
			fixture(
				"""	void m() {${'\n'}		class Helper {${'\n'}			int value() { return 1; }${'\n'}		}${'\n'}		Helper h = new Helper();${'\n'}		use(h.value());${'\n'}	}""",
			)

		val plan = f.methodPlanOver("class Helper {${'\n'}			int value() { return 1; }${'\n'}		}")

		assertThat(plan.refusal).isEqualTo(ExtractionRefusal.CapturedLocalDeclaration("Helper"))
	}

	@Test
	fun `a constructor delegation cannot be extracted`() {
		val f =
			fixture(
				"""	Fixture() {}${'\n'}	Fixture(int a) {${'\n'}		this();${'\n'}		use(a);${'\n'}	}""",
			)

		val plan = f.methodPlanOver("this();${'\n'}		use(a);")

		assertThat(plan.refusal).isEqualTo(ExtractionRefusal.NotASingleRegion)
	}

	// --- names (R12) ---

	@Test
	fun `taken names include inherited members`() {
		val f = fixture("""	int m(int a) {${'\n'}		return a + 1;${'\n'}	}""")

		val takenNames =
			f
				.methodPlanAfter("a +")
				.candidates
				.first()
				.takenNames

		assertThat(takenNames).contains("toString")
		assertThat(takenNames).contains("m")
	}

	@Test
	fun `a statement range is named extracted`() {
		val f = fixture("""	void m(int a) {${'\n'}		use(a);${'\n'}	}""")

		val plan = f.methodPlanOver("use(a);")

		assertThat(plan.candidates.single().suggestedName).isEqualTo("extracted")
	}

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
}
