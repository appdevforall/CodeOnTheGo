@file:Suppress("ktlint:standard:max-line-length")

package com.itsaky.androidide.gradle.sqlite

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode

private const val R = "io/requery/android/database/sqlite/"
private const val A = "android/database/sqlite/"
private const val HELPER = "org/appdevforall/sqliterewrite/SqliteRewriteRuntime"

internal fun node(bytes: ByteArray) = ClassNode().also { ClassReader(bytes).accept(it, 0) }

internal fun ClassNode.method(name: String): MethodNode = methods.first { it.name == name }

internal fun MethodNode.calls(): List<MethodInsnNode> = instructions.filterIsInstance<MethodInsnNode>()

internal fun MethodNode.call(name: String): MethodInsnNode = calls().first { it.name == name }

internal fun src(
	name: String,
	body: String,
) = JavaSource(name, body)

class SqliteRewriterTest {
	private val hierarchy =
		ClassHierarchy { name ->
			when (name) {
				"p/MyActivity" -> {
					listOf(
						"android/app/Activity",
						"android/content/ContextWrapper",
						"android/content/Context",
						"java/lang/Object",
					)
				}

				"p/MyBuilder" -> {
					listOf(A + "SQLiteQueryBuilder", "java/lang/Object")
				}

				else -> {
					null
				}
			}
		}

	private fun transform(
		vararg sources: JavaSource,
		extraClasspath: List<java.io.File> = emptyList(),
	): Map<String, ByteArray> =
		BytecodeVerifier.rewriteAndVerify(Compiler.compile(sources.toList(), listOf(TestJars.androidJar) + extraClasspath))

	@Test
	fun `renames the shared classes, the error handlers and overrides, and leaves the kept ones`() {
		val out =
			transform(
				src(
					"p.Rename",
					"""
					package p;
					import android.content.Context;
					import android.database.*;
					import android.database.sqlite.*;
					public class Rename extends SQLiteOpenHelper {
						public Rename(Context c) { super(c, "n", null, 1, new DefaultDatabaseErrorHandler()); }
						@Override public void onCreate(SQLiteDatabase db) { db.execSQL("x"); }
						@Override public void onUpgrade(SQLiteDatabase db, int a, int b) {}
						public void use(SQLiteDatabase db, CursorWindow w, Cursor c, SQLiteDatabase.CursorFactory f,
								SQLiteTransactionListener l) throws SQLiteException {
							db.beginTransactionWithListener(l);
							SQLiteStatement s = db.compileStatement("y");
							SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
						}
					}
					""",
				),
			)["p/Rename"]!!
		val n = node(out)
		assertThat(n.superName).isEqualTo(R + "SQLiteOpenHelper")
		assertThat(n.methods.first { it.name == "onCreate" }.desc).isEqualTo("(L${R}SQLiteDatabase;)V")
		val init = n.method("<init>")
		assertThat(init.calls().first { it.name == "<init>" && it.owner == R + "SQLiteOpenHelper" }.desc)
			.isEqualTo(
				"(Landroid/content/Context;Ljava/lang/String;L${R}SQLiteDatabase\$CursorFactory;IL${"io/requery/android/database/DatabaseErrorHandler"};)V",
			)
		assertThat(init.instructions.filterIsInstance<TypeInsnNode>().map { it.desc })
			.contains("io/requery/android/database/DefaultDatabaseErrorHandler")
		val use = n.method("use")
		assertThat(use.desc).isEqualTo(
			"(L${R}SQLiteDatabase;Landroid/database/CursorWindow;Landroid/database/Cursor;L${R}SQLiteDatabase\$CursorFactory;L${A}SQLiteTransactionListener;)V",
		)
		assertThat(use.exceptions).containsExactly(A + "SQLiteException")
		assertThat(use.call("beginTransactionWithListener").desc).isEqualTo("(L${A}SQLiteTransactionListener;)V")
		assertThat(use.call("compileStatement").desc).isEqualTo("(Ljava/lang/String;)L${R}SQLiteStatement;")
		assertThat(use.instructions.filterIsInstance<TypeInsnNode>().map { it.desc }).contains(R + "SQLiteQueryBuilder")
	}

	@Test
	fun `rewrites String array to Object array in calls and in the cursor driver override`() {
		val out =
			transform(
				src(
					"p.Args",
					"""
					package p;
					import android.database.Cursor;
					import android.database.sqlite.*;
					public class Args implements SQLiteCursorDriver {
						public Cursor query(SQLiteDatabase.CursorFactory f, String[] args) { return null; }
						public void cursorDeactivated() {}
						public void cursorRequeried(Cursor c) {}
						public void cursorClosed() {}
						public void setBindArguments(String[] a) {}
						void calls(SQLiteDatabase db, SQLiteDatabase.CursorFactory f, SQLiteCursorDriver d, Args me, String[] a) {
							db.rawQuery("s", a);
							db.query("t", a, "w", a, null, null, null);
							db.query("t", a, "w", a, null, null, null, "1");
							db.query(true, "t", a, "w", a, null, null, null, "1");
							db.rawQueryWithFactory(f, "s", a, "t");
							db.queryWithFactory(f, true, "t", a, "w", a, null, null, null, "1");
							d.query(f, a);
							me.query(f, a);
						}
					}
					""",
				),
			)["p/Args"]!!
		val n = node(out)
		val decl = n.methods.first { it.name == "query" }
		assertThat(decl.desc).isEqualTo("(L${R}SQLiteDatabase\$CursorFactory;[Ljava/lang/Object;)Landroid/database/Cursor;")
		val calls = n.method("calls").calls()
		assertThat(calls.first { it.name == "rawQuery" }.desc)
			.isEqualTo("(Ljava/lang/String;[Ljava/lang/Object;)Landroid/database/Cursor;")
		assertThat(calls.filter { it.name == "query" && it.owner == R + "SQLiteDatabase" }.map { it.desc }).containsExactly(
			"(Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;",
			"(Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;",
			"(ZLjava/lang/String;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;",
		)
		assertThat(calls.first { it.name == "rawQueryWithFactory" }.desc).isEqualTo(
			"(L${R}SQLiteDatabase\$CursorFactory;Ljava/lang/String;[Ljava/lang/Object;Ljava/lang/String;)Landroid/database/Cursor;",
		)
		assertThat(
			calls
				.first {
					it.name == "queryWithFactory"
				}.desc,
		).contains("Ljava/lang/String;[Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)")
		// the interface call, and the call on the implementing class itself
		assertThat(calls.filter { it.name == "query" && it.owner != R + "SQLiteDatabase" }.map { it.desc }.toSet())
			.containsExactly("(L${R}SQLiteDatabase\$CursorFactory;[Ljava/lang/Object;)Landroid/database/Cursor;")
	}

	@Test
	fun `converts the cancellation signal right before the call`() {
		val out =
			transform(
				src(
					"p.Signal",
					"""
					package p;
					import android.database.sqlite.*;
					import android.os.CancellationSignal;
					public class Signal {
						void go(SQLiteDatabase db, SQLiteQueryBuilder qb, SQLiteDatabase.CursorFactory f, CancellationSignal s, String[] a) {
							db.rawQuery("x", a, s);
							db.validateSql("x", s);
							qb.query(db, a, "w", a, null, null, null, "1", s);
							db.rawQueryWithFactory(f, "x", a, "t", s);
						}
					}
					""",
				),
			)["p/Signal"]!!
		val go = node(out).method("go")
		val insns = go.instructions.toArray().toList()
		for (name in listOf("rawQuery", "validateSql", "query", "rawQueryWithFactory")) {
			val call = go.call(name)
			assertThat(call.desc).contains("Landroidx/core/os/CancellationSignal;)")
			val before = insns[insns.indexOf(call) - 1] as MethodInsnNode
			assertThat(before.owner).isEqualTo(HELPER)
			assertThat(before.name).isEqualTo("toCancellationSignal")
			assertThat(before.desc).isEqualTo("(Landroid/os/CancellationSignal;)Landroidx/core/os/CancellationSignal;")
		}
		assertThat(go.call("rawQuery").desc).isEqualTo(
			"(Ljava/lang/String;[Ljava/lang/Object;Landroidx/core/os/CancellationSignal;)Landroid/database/Cursor;",
		)
	}

	@Test
	fun `redirects open flags and the context calls (also on subclasses), and leaves androidx's factory alone`() {
		val out =
			transform(
				src(
					"androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory",
					"package androidx.sqlite.db.framework; public class FrameworkSQLiteOpenHelperFactory { public FrameworkSQLiteOpenHelperFactory() {} }",
				),
				src(
					"p.MyActivity",
					"package p; public class MyActivity extends android.app.Activity {" +
						" void own(android.database.sqlite.SQLiteDatabase.CursorFactory f) {" +
						" this.openOrCreateDatabase(\"n\", 0, f); deleteDatabase(\"n\"); } }",
				),
				src(
					"p.Redirect",
					"""
					package p;
					import android.content.Context;
					import android.database.DatabaseErrorHandler;
					import android.database.sqlite.*;
					public class Redirect {
						Object go(Context c, SQLiteDatabase.CursorFactory f, DatabaseErrorHandler h, MyActivity a, Other o) {
							SQLiteDatabase.openDatabase("p", f, SQLiteDatabase.OPEN_READONLY);
							SQLiteDatabase.openDatabase("p", f, SQLiteDatabase.CREATE_IF_NECESSARY, h);
							SQLiteDatabase.openOrCreateDatabase("p", f);
							c.openOrCreateDatabase("n", 0, f);
							c.openOrCreateDatabase("n", 0, f, h);
							c.deleteDatabase("n");
							a.deleteDatabase("n");
							o.deleteDatabase("n");
							return new androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory();
						}
					}
					""",
				),
				src("p.Other", "package p; public class Other { public boolean deleteDatabase(String n) { return true; } }"),
			)
		val go = node(out["p/Redirect"]!!).method("go")

		fun redirected(name: String) = go.calls().filter { it.owner == HELPER && it.name == name }

		assertThat(redirected("openDatabase").map { it.desc }).containsExactly(
			"(Ljava/lang/String;L${R}SQLiteDatabase\$CursorFactory;I)L${R}SQLiteDatabase;",
			"(Ljava/lang/String;L${R}SQLiteDatabase\$CursorFactory;IL${"io/requery/android/database/DatabaseErrorHandler"};)L${R}SQLiteDatabase;",
		)
		assertThat(redirected("openOrCreateDatabase").map { it.desc }).containsExactly(
			"(Landroid/content/Context;Ljava/lang/String;IL${R}SQLiteDatabase\$CursorFactory;)L${R}SQLiteDatabase;",
			"(Landroid/content/Context;Ljava/lang/String;IL${R}SQLiteDatabase\$CursorFactory;Lio/requery/android/database/DatabaseErrorHandler;)L${R}SQLiteDatabase;",
		)
		// Context, the Activity subclass; not the unrelated class with the same method
		assertThat(redirected("deleteDatabase")).hasSize(2)
		assertThat(go.calls().any { it.owner == "p/Other" && it.name == "deleteDatabase" }).isTrue()
		// without flags: stays a plain, renamed call
		assertThat(go.calls().any { it.owner == R + "SQLiteDatabase" && it.name == "openOrCreateDatabase" }).isTrue()
		// requery's factory ignores noBackupDirectory, allowDataLossOnRecovery and the framework's cross-process
		// lock, so Room and WorkManager keep androidx's, which runs on requery through the renamed classes
		assertThat(go.instructions.filterIsInstance<TypeInsnNode>().map { it.desc })
			.containsExactly("androidx/sqlite/db/framework/FrameworkSQLiteOpenHelperFactory")
		assertThat(
			go.calls().first { it.name == "<init>" }.owner,
		).isEqualTo("androidx/sqlite/db/framework/FrameworkSQLiteOpenHelperFactory")

		val own = node(out["p/MyActivity"]!!).method("own")
		assertThat(own.calls().map { it.owner + "." + it.name }).containsExactly("$HELPER.openOrCreateDatabase", "$HELPER.deleteDatabase")
	}

	@Test
	fun `branchy and looping samples of every rule kind verify, frames included`() {
		val out =
			transform(
				src(
					"p.Branchy",
					"""
					package p;
					import android.content.Context;
					import android.database.Cursor;
					import android.database.DatabaseErrorHandler;
					import android.database.sqlite.*;
					import android.os.CancellationSignal;
					public class Branchy implements SQLiteCursorDriver {
						public Cursor query(SQLiteDatabase.CursorFactory f, String[] args) {
							// four locals at once make javac write a full frame, which names the parameter's type
							int n = 0, a = 1, b = 2, c = 3;
							for (int i = 0; i < 3; i++) {
								if (f == null) {
									n++;
								} else if (args == null) {
									n--;
								}
							}
							if (n + a + b + c > 1) {
								return null;
							}
							return null;
						}
						public void cursorDeactivated() {}
						public void cursorRequeried(Cursor c) {}
						public void cursorClosed() {}
						public void setBindArguments(String[] a) {}

						Cursor descriptors(SQLiteDatabase db, SQLiteDatabase.CursorFactory f, String[] a, boolean b, CancellationSignal s) {
							Cursor c = null;
							for (int i = 0; i < 3; i++) {
								if (b) {
									c = db.rawQuery("x", a);
								} else if (s != null) {
									c = db.rawQuery("y", a, s);
								} else {
									c = db.query("t", a, "w", a, null, null, null, "1");
								}
								if (c != null && i == 1) {
									c = db.rawQueryWithFactory(f, "z", a, "t");
								}
							}
							return c;
						}

						Object redirects(Context ctx, SQLiteDatabase.CursorFactory f, DatabaseErrorHandler h, boolean b, String name) {
							SQLiteDatabase db;
							if (b) {
								db = SQLiteDatabase.openDatabase(name, f, SQLiteDatabase.OPEN_READONLY);
							} else {
								db = ctx.openOrCreateDatabase(name, 0, f, h);
							}
							for (int i = 0; i < 2; i++) {
								if (ctx.deleteDatabase(name + i)) {
									db = SQLiteDatabase.openDatabase(name, f, SQLiteDatabase.CREATE_IF_NECESSARY, h);
								}
							}
							return db;
						}

						Object joins(SQLiteDatabase db, boolean b, SQLiteQueryBuilder qb) {
							SQLiteClosable c = b ? db : db.compileStatement("x");
							SQLiteProgram p = b ? db.compileStatement("y") : null;
							while (c != null && b) {
								c.close();
								c = p;
								b = false;
							}
							return qb != null ? c : p;
						}
					}
					""",
				),
			)
		assertThat(out["p/Branchy"]).isNotNull()
	}

	@Test
	fun `the androidx factory class is rewritten like the rest of its package, not excluded`() {
		val name = "androidx/sqlite/db/framework/FrameworkSQLiteOpenHelperFactory"
		val out =
			transform(
				src(
					"androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory",
					"""
					package androidx.sqlite.db.framework;
					import android.content.Context;
					import android.database.sqlite.SQLiteOpenHelper;
					public class FrameworkSQLiteOpenHelperFactory {
						public SQLiteOpenHelper create(Context c, String n) { return null; }
					}
					""",
				),
			)
		val n = node(out[name]!!)
		assertThat(n.name).isEqualTo(name)
		assertThat(
			n.method("create").desc,
		).isEqualTo("(Landroid/content/Context;Ljava/lang/String;)L${R}SQLiteOpenHelper;")
		assertThat(RewritePolicy.isExcluded(name)).isFalse()
	}

	@Test
	fun `fails with class, method and line for members requery lacks, collecting all of them`() {
		val sources =
			src(
				"p.Bad",
				"""
				package p;
				import android.database.sqlite.*;
				public class Bad {
					void one(SQLiteDatabase db) {
						db.execPerConnectionSQL("x", null);
					}
					void two(SQLiteDatabase db, SQLiteDatabase.OpenParams params) {
						db.setCustomScalarFunction("f", s -> s);
						SQLiteDatabase.openDatabase(new java.io.File("f"), params);
						new SQLiteDatabase.OpenParams.Builder().build();
					}
					void three(MyBuilder b, SQLiteQueryBuilder q) {
						b.isDistinct();
						q.setStrictGrammar(true);
					}
				}
				""",
			)
		val classes =
			Compiler.compile(
				listOf(
					sources,
					src("p.MyBuilder", "package p; public class MyBuilder extends android.database.sqlite.SQLiteQueryBuilder {}"),
				),
			)
		val e = assertThrows(SqliteRewriteException::class.java) { SqliteRewriter.rewrite(classes["p/Bad"]!!, hierarchy) }
		val message = e.message!!
		assertThat(message).contains("p.Bad")
		assertThat(message).contains("one(Landroid/database/sqlite/SQLiteDatabase;)V (line 6)")
		assertThat(message).contains("SQLiteDatabase.execPerConnectionSQL")
		assertThat(message).contains("two(")
		assertThat(message).contains("setCustomScalarFunction")
		assertThat(message).contains("OpenParams")
		assertThat(message).contains("(line 9)")
		assertThat(message).contains("(line 10)")
		assertThat(message).contains("(line 11)")
		// a subclass of SQLiteQueryBuilder is resolved through the hierarchy
		assertThat(message).contains("SQLiteQueryBuilder.isDistinct")
		assertThat(message).contains("SQLiteQueryBuilder.setStrictGrammar")
	}

	@Test
	fun `the failure says how to build when the class is in a library`() {
		val classes =
			Compiler.compile(
				listOf(
					src(
						"p.Lib",
						"""
						package p;
						public class Lib {
							void one(android.database.sqlite.SQLiteDatabase db) {
								db.execPerConnectionSQL("x", null);
							}
						}
						""",
					),
				),
			)
		val e = assertThrows(SqliteRewriteException::class.java) { SqliteRewriter.rewrite(classes["p/Lib"]!!, hierarchy) }
		val lastLine = e.message!!.lines().last()
		assertThat(lastLine).contains("in a library")
		assertThat(lastLine).contains("comment out cogo.sqliteDebugger=true in gradle.properties")
	}

	@Test
	fun `a missing member is found through every renamed ancestor of the owner`() {
		val classes =
			Compiler.compile(
				listOf(
					src(
						"p.Ancestors",
						"""
						package p;
						import android.database.sqlite.*;
						public class Ancestors {
							int program(SQLiteStatement stmt, SQLiteQuery query) { return stmt.getUniqueId() + query.getUniqueId(); }
							void closable(SQLiteDatabase db) { db.releaseReferenceFromContainer(); }
							void subclass(MyBuilder b) { b.isStrict(); b.close(); }
						}
						""",
					),
					src(
						"p.MyBuilder",
						"package p; public class MyBuilder extends android.database.sqlite.SQLiteQueryBuilder { void close() {} }",
					),
				),
			)
		val e = assertThrows(SqliteRewriteException::class.java) { SqliteRewriter.rewrite(classes["p/Ancestors"]!!, hierarchy) }
		val message = e.message!!
		assertThat(message).contains("SQLiteProgram.getUniqueId")
		assertThat(message).contains("SQLiteClosable.releaseReferenceFromContainer")
		assertThat(message).contains("SQLiteQueryBuilder.isStrict")
		assertThat(message).doesNotContain("close")
	}

	@Test
	fun `hierarchy lookups are made once per owner and not for classes that cannot be SQLite subclasses`() {
		val classes =
			Compiler.compile(
				listOf(
					src(
						"p.Chatty",
						"""
						package p;
						import android.database.sqlite.*;
						public class Chatty {
							int go(String s, StringBuilder sb, java.util.List<String> l, android.widget.TextView tv, Mine a, Mine b, SQLiteStatement st) {
								sb.append(s.length()).append(s.trim()).append(l.size());
								tv.setText(s); tv.setText(s);
								a.run(); a.run(); b.run();
								return st.getUniqueId() > 0 ? 1 : 0;
							}
						}
						""",
					),
					src("p.Mine", "package p; public class Mine { void run() {} }"),
				),
			)
		val asked = mutableListOf<String>()
		val counting =
			ClassHierarchy { name ->
				asked += name
				null
			}
		try {
			SqliteRewriter.rewrite(classes["p/Chatty"]!!, counting)
		} catch (e: SqliteRewriteException) {
			// getUniqueId is reported, which is not what this test is about
		}
		assertThat(asked.filter { it.startsWith("java/") || it.startsWith("android/") }).isEmpty()
		assertThat(asked.groupingBy { it }.eachCount().filterValues { it > 1 }).isEmpty()
		// ordinary calls on app classes need no lookup: no rule can match their name or descriptor
		assertThat(asked).doesNotContain("p/Mine")
	}

	@Test
	fun `a call whose name a rule lists, or whose descriptor has a CursorWindow, still looks the owner up`() {
		val classes =
			Compiler.compile(
				listOf(
					src(
						"p.Named",
						"""
						package p;
						public class Named {
							void go(Mine a, Other b, Third c, android.database.CursorWindow w) {
								a.getUniqueId();
								a.getUniqueId();
								b.fetch(w);
								c.ordinary();
							}
						}
						""",
					),
					src("p.Mine", "package p; public class Mine { int getUniqueId() { return 0; } }"),
					src("p.Other", "package p; public class Other { void fetch(android.database.CursorWindow w) {} }"),
					src("p.Third", "package p; public class Third { void ordinary() {} }"),
				),
			)
		val asked = mutableListOf<String>()
		SqliteRewriter.rewrite(
			classes["p/Named"]!!,
			ClassHierarchy { name ->
				asked += name
				null
			},
		)
		assertThat(asked).containsExactly("p/Mine", "p/Other")
	}

	@Test
	fun `field accesses and constructors of app classes trigger no hierarchy lookup`() {
		val classes =
			Compiler.compile(
				listOf(
					src(
						"p.Fields",
						"""
						package p;
						public class Fields {
							int go(Holder h) { h.count++; return h.count + new Holder().count; }
						}
						""",
					),
					src("p.Holder", "package p; public class Holder { int count; }"),
				),
			)
		val asked = mutableListOf<String>()
		SqliteRewriter.rewrite(
			classes["p/Fields"]!!,
			ClassHierarchy { name ->
				asked += name
				null
			},
		)
		assertThat(asked).isEmpty()
	}

	@Test
	fun `DatabaseUtils helpers that take SQLite types are redirected to the runtime`() {
		val out =
			transform(
				src(
					"p.Utils",
					"""
					package p;
					import android.database.DatabaseUtils;
					import android.database.sqlite.*;
					public class Utils {
						long go(SQLiteDatabase db, SQLiteStatement st, String[] a, boolean b) {
							long n = 0;
							if (b) {
								n += DatabaseUtils.queryNumEntries(db, "t");
								n += DatabaseUtils.queryNumEntries(db, "t", "x");
							} else {
								n += DatabaseUtils.queryNumEntries(db, "t", "x", a);
							}
							for (int i = 0; i < 2; i++) {
								n += DatabaseUtils.longForQuery(db, "q", a) + DatabaseUtils.longForQuery(st, a);
								n += DatabaseUtils.stringForQuery(db, "q", a).length() + DatabaseUtils.stringForQuery(st, a).length();
							}
							return n;
						}
					}
					""",
				),
			)["p/Utils"]!!
		val calls = node(out).method("go").calls()
		assertThat(calls.filter { it.owner == "android/database/DatabaseUtils" }).isEmpty()
		val redirected = calls.filter { it.owner == HELPER }
		assertThat(redirected.map { it.name }.groupingBy { it }.eachCount())
			.containsExactly("queryNumEntries", 3, "longForQuery", 2, "stringForQuery", 2)
		assertThat(redirected.map { it.opcode }.toSet()).containsExactly(org.objectweb.asm.Opcodes.INVOKESTATIC)
		assertThat(redirected.first { it.name == "longForQuery" && it.desc.contains("Statement") }.desc)
			.isEqualTo("(L${R}SQLiteStatement;[Ljava/lang/String;)J")
		assertThat(redirected.first { it.name == "queryNumEntries" && it.desc.contains("Ljava/lang/String;Ljava/lang/String;)") }.desc)
			.isEqualTo("(L${R}SQLiteDatabase;Ljava/lang/String;Ljava/lang/String;)J")
	}

	@Test
	fun `platform methods with SQLite types that have no requery counterpart fail the build`() {
		val classes =
			Compiler.compile(
				listOf(
					src(
						"p.BadUtils",
						"""
						package p;
						import android.database.DatabaseUtils;
						import android.database.sqlite.*;
						public class BadUtils {
							void one(SQLiteDatabase db, SQLiteStatement st, String[] a, SQLiteProgram p) throws Exception {
								DatabaseUtils.blobFileDescriptorForQuery(db, "q", a);
								DatabaseUtils.blobFileDescriptorForQuery(st, a);
								DatabaseUtils.bindObjectToProgram(p, 1, "x");
								new DatabaseUtils.InsertHelper(db, "t");
							}
						}
						""",
					),
				),
			)
		val e = assertThrows(SqliteRewriteException::class.java) { SqliteRewriter.rewrite(classes["p/BadUtils"]!!, hierarchy) }
		val message = e.message!!
		assertThat(message).contains("p.BadUtils")
		assertThat(message).contains("DatabaseUtils.blobFileDescriptorForQuery")
		assertThat(message).contains("DatabaseUtils.bindObjectToProgram")
		assertThat(message).contains("DatabaseUtils\$InsertHelper.<init>")
		for (line in 7..10) assertThat(message).contains("(line $line)")
	}

	@Test
	fun `Context database calls that cannot be redirected fail the build`() {
		val classes =
			Compiler.compile(
				listOf(
					src(
						"p.SuperCall",
						"""
						package p;
						public class SuperCall extends android.app.Activity {
							void go(android.database.sqlite.SQLiteDatabase.CursorFactory f) {
								super.openOrCreateDatabase("n", 0, f);
							}
							boolean del() {
								return super.deleteDatabase("n");
							}
						}
						""",
					),
					src(
						"p.OverridesBoth",
						"""
						package p;
						import android.database.sqlite.*;
						public class OverridesBoth extends android.content.ContextWrapper {
							public OverridesBoth() { super(null); }
							@Override public SQLiteDatabase openOrCreateDatabase(String n, int m, SQLiteDatabase.CursorFactory f) { return null; }
							@Override public boolean deleteDatabase(String n) { return true; }
						}
						""",
					),
					src(
						"p.Unknown",
						"""
						package p;
						public class Unknown {
							boolean go(q.Lib lib, android.database.sqlite.SQLiteDatabase.CursorFactory f) {
								lib.openOrCreateDatabase("n", 0, f);
								return lib.deleteDatabase("n");
							}
						}
						""",
					),
					src(
						"q.Lib",
						"""
						package q;
						public class Lib {
							public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(String n, int m, android.database.sqlite.SQLiteDatabase.CursorFactory f) { return null; }
							public boolean deleteDatabase(String n) { return true; }
						}
						""",
					),
				),
			)

		fun failure(name: String) =
			assertThrows(SqliteRewriteException::class.java) {
				SqliteRewriter.rewrite(classes[name]!!, hierarchy)
			}.message!!
		failure("p/SuperCall").let {
			assertThat(it).contains("go(")
			assertThat(it).contains("del(")
			assertThat(it).contains("super")
		}
		failure("p/OverridesBoth").let {
			assertThat(it).contains("overrides Context.openOrCreateDatabase")
			assertThat(it).contains("overrides Context.deleteDatabase")
		}
		failure("p/Unknown").let {
			assertThat(it).contains("q/Lib")
			assertThat(it).contains("(line 5)")
			assertThat(it).contains("(line 6)")
		}
	}

	@Test
	fun `method references get the same rules as calls, or fail asking for a lambda`() {
		val classes =
			Compiler.compile(
				listOf(
					src(
						"p.Refs",
						"""
						package p;
						import android.content.Context;
						import android.database.Cursor;
						import android.database.sqlite.*;
						public class Refs {
							interface Open { SQLiteDatabase open(String n, int m, SQLiteDatabase.CursorFactory f); }
							interface Opener { SQLiteDatabase open(String n, SQLiteDatabase.CursorFactory f, int flags); }
							Object rawQuery(SQLiteDatabase db) {
								java.util.function.BiFunction<String, String[], Cursor> f = db::rawQuery;
								return f;
							}
							Object delete(Context ctx) {
								java.util.function.Predicate<String> p = ctx::deleteDatabase;
								return p;
							}
							Object open(Context ctx) {
								Open o = ctx::openOrCreateDatabase;
								return o;
							}
							Object staticOpen() {
								Opener o = SQLiteDatabase::openDatabase;
								return o;
							}
							Object missing(SQLiteDatabase db) {
								Runnable r = db::releaseReferenceFromContainer;
								return r;
							}
						}
						""",
					),
				),
			)
		val message =
			assertThrows(SqliteRewriteException::class.java) { SqliteRewriter.rewrite(classes["p/Refs"]!!, hierarchy) }.message!!
		for (method in listOf("rawQuery", "delete", "open", "staticOpen", "missing")) {
			assertThat(message).contains("$method(")
		}
		assertThat(message).contains("method reference")
		assertThat(message).contains("lambda")
	}

	@Test
	fun `method references and lambdas that need no rule are left alone`() {
		val out =
			transform(
				src(
					"p.FineRefs",
					"""
					package p;
					import android.database.Cursor;
					import android.database.sqlite.*;
					public class FineRefs {
						Object go(SQLiteDatabase db, java.util.List<String> l) {
							Runnable begin = db::beginTransaction;
							java.util.function.Function<String, Integer> len = String::length;
							java.util.function.Supplier<SQLiteStatement> s = () -> db.compileStatement("x");
							java.util.function.Function<String, Cursor> q = a -> db.rawQuery(a, null);
							l.forEach(x -> db.execSQL(x));
							return new Object[] {begin, len, s, q};
						}
					}
					""",
				),
			)["p/FineRefs"]!!
		val n = node(out)
		assertThat(
			n.methods
				.flatMap { it.calls() }
				.filter { it.name == "rawQuery" }
				.map { it.desc },
		).containsExactly("(Ljava/lang/String;[Ljava/lang/Object;)Landroid/database/Cursor;")
	}

	@Test
	fun `fails for a cast of an SQLite cursor to AbstractWindowedCursor but not for other values`() {
		val classes =
			Compiler.compile(
				listOf(
					src(
						"p.Casts",
						"""
						package p;
						import android.database.*;
						import android.database.sqlite.*;
						public class Casts {
							Object viaLocal(SQLiteDatabase db) {
								Cursor c = db.rawQuery("x", null);
								return (AbstractWindowedCursor) c;
							}
							Object viaSqliteCursor(SQLiteCursor c) {
								Object o = c;
								return (AbstractWindowedCursor) o;
							}
						}
						""",
					),
					src(
						"p.Fine",
						"""
						package p;
						import android.database.*;
						public class Fine {
							Object other(Cursor fromContentProvider) {
								return (AbstractWindowedCursor) fromContentProvider;
							}
							boolean guarded(Cursor c) {
								return c instanceof AbstractWindowedCursor;
							}
						}
						""",
					),
				),
			)
		val e = assertThrows(SqliteRewriteException::class.java) { SqliteRewriter.rewrite(classes["p/Casts"]!!, hierarchy) }
		assertThat(e.message).contains("viaLocal")
		assertThat(e.message).contains("viaSqliteCursor")
		assertThat(e.message).contains("AbstractWindowedCursor")
		SqliteRewriter.rewrite(classes["p/Fine"]!!, hierarchy)
	}

	@Test
	fun `taint of an SQLite cursor survives a join point`() {
		val classes =
			Compiler.compile(
				listOf(
					src(
						"p.Joins",
						"""
						package p;
						import android.database.*;
						import android.database.sqlite.*;
						public class Joins {
							Object assignedOnOneBranch(Cursor other, SQLiteDatabase db, boolean b) {
								Cursor c = other;
								if (b) {
									c = db.rawQuery("x", null);
								}
								return (AbstractWindowedCursor) c;
							}
							Object assignedInElse(Cursor other, SQLiteDatabase db, boolean b) {
								Cursor c;
								if (b) {
									c = other;
								} else {
									c = db.rawQuery("x", null);
								}
								return (AbstractWindowedCursor) c;
							}
							Object assignedInLoop(Cursor other, SQLiteDatabase db, int n) {
								Cursor c = other;
								for (int i = 0; i < n; i++) {
									c = db.rawQuery("x", null);
								}
								return (AbstractWindowedCursor) c;
							}
						}
						""",
					),
				),
			)
		val e = assertThrows(SqliteRewriteException::class.java) { SqliteRewriter.rewrite(classes["p/Joins"]!!, hierarchy) }
		assertThat(e.message).contains("assignedOnOneBranch")
		assertThat(e.message).contains("assignedInElse")
		assertThat(e.message).contains("assignedInLoop")
	}

	@Test
	fun `casts of an SQLite cursor to AbstractCursor and CrossProcessCursor fail too`() {
		val classes =
			Compiler.compile(
				listOf(
					src(
						"p.MoreCasts",
						"""
						package p;
						import android.database.*;
						import android.database.sqlite.*;
						public class MoreCasts {
							Object toAbstract(SQLiteDatabase db) {
								return (AbstractCursor) db.rawQuery("x", null);
							}
							Object toCrossProcess(SQLiteCursor c) {
								Object o = c;
								return (CrossProcessCursor) o;
							}
							boolean guarded(SQLiteDatabase db) {
								return db.rawQuery("x", null) instanceof CrossProcessCursor;
							}
						}
						""",
					),
					src(
						"p.FineCasts",
						"""
						package p;
						import android.database.*;
						public class FineCasts {
							Object other(Cursor fromContentProvider) {
								return (AbstractCursor) fromContentProvider;
							}
							Object cross(Cursor fromContentProvider) {
								return (CrossProcessCursor) fromContentProvider;
							}
						}
						""",
					),
				),
			)
		val e = assertThrows(SqliteRewriteException::class.java) { SqliteRewriter.rewrite(classes["p/MoreCasts"]!!, hierarchy) }
		assertThat(e.message).contains("toAbstract")
		assertThat(e.message).contains("android.database.AbstractCursor")
		assertThat(e.message).contains("toCrossProcess")
		assertThat(e.message).contains("android.database.CrossProcessCursor")
		assertThat(e.message).doesNotContain("guarded")
		SqliteRewriter.rewrite(classes["p/FineCasts"]!!, hierarchy)
	}

	@Test
	fun `a call through CursorWindow of a renamed class fails`() {
		val classes =
			Compiler.compile(
				listOf(
					src(
						"p.Window",
						"package p; public class Window { Object go(android.database.sqlite.SQLiteCursor c) { return c.getWindow(); } }",
					),
				),
			)
		val e = assertThrows(SqliteRewriteException::class.java) { SqliteRewriter.rewrite(classes["p/Window"]!!, hierarchy) }
		assertThat(e.message).contains("CursorWindow")
	}

	@Test
	fun `the helper package is renamed but neither redirected nor failed, and framework only skips the fail rule`() {
		val classes =
			Compiler.compile(
				listOf(
					src(
						"org.appdevforall.sqliterewrite.SqliteRewriteRuntime",
						"""
						package org.appdevforall.sqliterewrite;
						import android.database.sqlite.SQLiteDatabase;
						public class SqliteRewriteRuntime {
							public static SQLiteDatabase openDatabase(String p, SQLiteDatabase.CursorFactory f, int fl) {
								return SQLiteDatabase.openDatabase(p, f, fl);
							}
							public static void bad(SQLiteDatabase db) { db.execPerConnectionSQL("x", null); }
						}
						""",
					),
					src(
						"androidx.sqlite.db.framework.FrameworkSQLiteDatabase",
						"""
						package androidx.sqlite.db.framework;
						import android.database.sqlite.SQLiteDatabase;
						public class FrameworkSQLiteDatabase {
							void exec(SQLiteDatabase db, String[] a) {
								db.execPerConnectionSQL("x", null);
								db.rawQuery("x", a);
							}
						}
						""",
					),
				),
			)
		val helper = node(SqliteRewriter.rewrite(classes["org/appdevforall/sqliterewrite/SqliteRewriteRuntime"]!!, hierarchy))
		val inner = helper.method("openDatabase").call("openDatabase")
		assertThat(inner.owner).isEqualTo(R + "SQLiteDatabase")
		assertThat(inner.desc).isEqualTo("(Ljava/lang/String;L${R}SQLiteDatabase\$CursorFactory;I)L${R}SQLiteDatabase;")

		val framework = node(SqliteRewriter.rewrite(classes["androidx/sqlite/db/framework/FrameworkSQLiteDatabase"]!!, hierarchy))
		assertThat(framework.method("exec").call("rawQuery").desc).contains("[Ljava/lang/Object;")
	}

	@Test
	fun `a type test on OpenParams fails too`() {
		val classes =
			Compiler.compile(
				listOf(
					src(
						"p.Fields",
						"package p; public class Fields { Object go(Object o) { return o instanceof android.database.sqlite.SQLiteDatabase.OpenParams; } }",
					),
				),
			)
		assertThrows(SqliteRewriteException::class.java) { SqliteRewriter.rewrite(classes["p/Fields"]!!, hierarchy) }
	}

	@Test
	fun `androidx core's SQLiteCursorCompat helper is rewritten but never fails the build`() {
		val classes =
			Compiler.compile(
				listOf(
					src(
						"androidx.core.database.sqlite.SQLiteCursorCompat",
						"""
						package androidx.core.database.sqlite;
						public class SQLiteCursorCompat {
							public static void setFillWindowForwardOnly(android.database.sqlite.SQLiteCursor c, boolean b) {
								c.setFillWindowForwardOnly(b);
							}
						}
						""",
					),
				),
			)
		val out = node(SqliteRewriter.rewrite(classes["androidx/core/database/sqlite/SQLiteCursorCompat"]!!, hierarchy))
		assertThat(out.method("setFillWindowForwardOnly").desc).isEqualTo("(L${R}SQLiteCursor;Z)V")
		assertThat(RewritePolicy.forClass("androidx/core/database/sqlite/SQLiteCursorCompat\$Api28Impl")).isEqualTo(RewritePolicy.NO_FAIL)
	}

	@Test
	fun `a cursor driver that uses its String array parameter verifies and links, with every kind of frame`() {
		val out =
			transform(
				src(
					"p.Driver",
					"""
					package p;
					import android.database.Cursor;
					import android.database.sqlite.*;
					public class Driver implements SQLiteCursorDriver {
						static int sink(String[] a) { return a.length; }
						public Cursor query(SQLiteDatabase.CursorFactory f, String[] args) {
							int x = args[0].length();
							if (f == null) {
								x += sink(args);
							}
							int a = 1, b = 2, c = 3, d = 4;
							for (int i = 0; i < x; i++) {
								if (args.length > i) {
									x += args[i].length();
								} else {
									x += sink(args);
								}
							}
							return x > a + b + c + d ? null : null;
						}
						public void cursorDeactivated() {}
						public void cursorRequeried(Cursor c) {}
						public void cursorClosed() {}
						public void setBindArguments(String[] a) {}
					}
					""",
				),
				src(
					"p.LoopFirst",
					"""
					package p;
					import android.database.Cursor;
					import android.database.sqlite.*;
					public abstract class LoopFirst implements SQLiteCursorDriver {
						public Cursor query(SQLiteDatabase.CursorFactory f, String[] args) {
							for (;;) {
								if (args[0].length() > 1) {
									return null;
								}
								Driver.sink(args);
							}
						}
					}
					""",
				),
			)
		val query = node(out["p/Driver"]!!).method("query")
		assertThat(query.desc).isEqualTo("(L${R}SQLiteDatabase\$CursorFactory;[Ljava/lang/Object;)Landroid/database/Cursor;")
		val first = query.instructions.filter { it.opcode >= 0 }.take(3)
		assertThat(first.map { it.opcode }).containsExactly(25, 192, 58).inOrder() // ALOAD, CHECKCAST, ASTORE
		assertThat((first[1] as TypeInsnNode).desc).isEqualTo("[Ljava/lang/String;")
	}

	@Test
	fun `SQL text with COLLATE UNICODE fails the build, with class, method and line`() {
		val classes =
			Compiler.compile(
				listOf(
					src(
						"p.Dao",
						"""
						package p;
						public class Dao {
							static final String K = "SELECT * FROM t ORDER BY n Collate  Unicode";
							String direct() {
								return "SELECT * FROM t ORDER BY n COLLATE\n\tUNICODE".trim();
							}
							String viaConstant() { return K; }
							String fine() { return "SELECT * FROM t ORDER BY n COLLATE NOCASE; collateral unicode"; }
						}
						""",
					),
				),
			)
		val e = assertThrows(SqliteRewriteException::class.java) { SqliteRewriter.rewrite(classes["p/Dao"]!!) }
		val message = e.message!!
		assertThat(message).contains("p.Dao")
		assertThat(message).contains("direct()Ljava/lang/String; (line ")
		assertThat(message).contains("viaConstant()Ljava/lang/String; (line ")
		assertThat(message).contains("COLLATE UNICODE")
		assertThat(message).doesNotContain("fine")
	}

	@Test
	fun `COLLATE LOCALIZED only warns`() {
		val classes =
			Compiler.compile(
				listOf(
					src(
						"p.Sorted",
						"package p; public class Sorted { String q() { return \"SELECT * FROM t ORDER BY n collate localized\"; } String n() { return \"x\"; } }",
					),
				),
			)
		val warnings = mutableListOf<String>()
		SqliteRewriter.rewrite(classes["p/Sorted"]!!, warn = { warnings += it })
		assertThat(warnings).hasSize(1)
		assertThat(warnings.single()).contains("p.Sorted.q")
		assertThat(warnings.single()).contains("line ")
		assertThat(warnings.single()).contains("byte")
	}

	@Test
	fun `calls on android's CursorWindow are not reported even when the hierarchy says it extends SQLiteClosable`() {
		val classes =
			Compiler.compile(
				listOf(
					src(
						"p.Windows",
						"""
						package p;
						import android.database.CursorWindow;
						public class Windows {
							void go(CursorWindow w) { w.releaseReferenceFromContainer(); w.close(); }
							Object parcel(android.os.Parcel p) { return CursorWindow.newFromParcel(p); }
						}
						""",
					),
				),
			)
		val withClosable =
			ClassHierarchy { name ->
				if (name == "android/database/CursorWindow") listOf(A + "SQLiteClosable", "java/lang/Object") else null
			}
		SqliteRewriter.rewrite(classes["p/Windows"]!!, withClosable)
	}

	@Test
	fun `inherited deprecated cursor members that requery lacks fail the build`() {
		val classes =
			Compiler.compile(
				listOf(
					src(
						"p.CursorUser",
						"""
						package p;
						import android.database.sqlite.SQLiteCursor;
						public class CursorUser {
							boolean types(SQLiteCursor c) { return c.isBlob(0) || c.isFloat(1) || c.isLong(2) || c.isString(3); }
							Object uris(SQLiteCursor c) { return c.getNotificationUris(); }
						}
						""",
					),
					src(
						"p.MyCursor",
						"""
						package p;
						import android.database.sqlite.*;
						public class MyCursor extends SQLiteCursor {
							public MyCursor(SQLiteCursorDriver d, String t, SQLiteQuery q) { super(d, t, q); }
							boolean updated(int i) { return isFieldUpdated(i) || getUpdatedField(i) != null; }
							Object window() { return mWindow; }
							boolean fine() { return isClosed() && getCount() > 0; }
						}
						""",
					),
				),
			)
		val e = assertThrows(SqliteRewriteException::class.java) { SqliteRewriter.rewrite(classes["p/CursorUser"]!!, hierarchy) }
		for (name in listOf("isBlob", "isFloat", "isLong", "isString", "getNotificationUris")) {
			assertThat(e.message).contains("SQLiteCursor.$name")
		}
		val viaSubclass =
			ClassHierarchy { name ->
				if (name ==
					"p/MyCursor"
				) {
					listOf(A + "SQLiteCursor", "android/database/AbstractWindowedCursor", "java/lang/Object")
				} else {
					null
				}
			}
		val e2 = assertThrows(SqliteRewriteException::class.java) { SqliteRewriter.rewrite(classes["p/MyCursor"]!!, viaSubclass) }
		assertThat(e2.message).contains("SQLiteCursor.isFieldUpdated")
		assertThat(e2.message).contains("SQLiteCursor.getUpdatedField")
		assertThat(e2.message).contains("SQLiteCursor.mWindow")
		assertThat(e2.message).doesNotContain("isClosed")
		assertThat(e2.message).doesNotContain("getCount")
	}

	@Test
	fun `a cast guarded by an instanceof of the same type on the same value is not reported`() {
		val classes =
			Compiler.compile(
				listOf(
					src(
						"p.Guarded",
						"""
						package p;
						import android.database.*;
						import android.database.sqlite.*;
						public class Guarded {
							Object ifBlock(SQLiteDatabase db) {
								Cursor c = db.rawQuery("x", null);
								if (c instanceof AbstractWindowedCursor) {
									AbstractWindowedCursor w = (AbstractWindowedCursor) c;
									return w.getWindow();
								}
								return null;
							}
							Object and(SQLiteDatabase db) {
								Cursor c = db.rawQuery("x", null);
								return c instanceof AbstractCursor && ((AbstractCursor) c).getCount() > 0;
							}
							Object ternary(SQLiteDatabase db) {
								Cursor c = db.rawQuery("x", null);
								return c instanceof CrossProcessCursor ? (CrossProcessCursor) c : null;
							}
							Object early(SQLiteDatabase db) {
								Cursor c = db.rawQuery("x", null);
								if (!(c instanceof AbstractWindowedCursor)) {
									return null;
								}
								return (AbstractWindowedCursor) c;
							}
						}
						""",
					),
					src(
						"p.Unguarded",
						"""
						package p;
						import android.database.*;
						import android.database.sqlite.*;
						public class Unguarded {
							Object otherType(SQLiteDatabase db) {
								Cursor c = db.rawQuery("x", null);
								if (c instanceof AbstractCursor) {
									return (AbstractWindowedCursor) c;
								}
								return null;
							}
							Object otherValue(SQLiteDatabase db, Cursor d) {
								Cursor c = db.rawQuery("x", null);
								if (d instanceof AbstractWindowedCursor) {
									return (AbstractWindowedCursor) c;
								}
								return null;
							}
							Object reassigned(SQLiteDatabase db) {
								Cursor c = db.rawQuery("x", null);
								if (c instanceof AbstractWindowedCursor) {
									c = db.rawQuery("y", null);
									return (AbstractWindowedCursor) c;
								}
								return null;
							}
							Object afterTheBlock(SQLiteDatabase db) {
								Cursor c = db.rawQuery("x", null);
								if (c instanceof AbstractWindowedCursor) {
									c.close();
								}
								return (AbstractWindowedCursor) c;
							}
							Object negatedWithoutExit(SQLiteDatabase db) {
								Cursor c = db.rawQuery("x", null);
								if (!(c instanceof AbstractWindowedCursor)) {
									c.close();
								}
								return (AbstractWindowedCursor) c;
							}
						}
						""",
					),
				),
			)
		SqliteRewriter.rewrite(classes["p/Guarded"]!!, hierarchy)
		val e = assertThrows(SqliteRewriteException::class.java) { SqliteRewriter.rewrite(classes["p/Unguarded"]!!, hierarchy) }
		for (name in listOf("otherType", "otherValue", "reassigned", "afterTheBlock", "negatedWithoutExit")) {
			assertThat(e.message).contains(name)
		}
	}
}
