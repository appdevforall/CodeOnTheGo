@file:Suppress("ktlint:standard:max-line-length")

package com.itsaky.androidide.gradle.sqlite

/**
 * The rule tables of the android.database.sqlite -> requery rewrite (ADFA-7138), from the Wave 0
 * comparison of android.jar against requery 3.49.0. Names are JVM internal names and descriptors
 * with Android's original names: the rename runs after the other rules.
 */
internal object SqliteRewriteRules {
	const val SQLITE_PKG = "android/database/sqlite/"
	private const val REQUERY_SQLITE_PKG = "io/requery/android/database/sqlite/"
	private const val REQUERY_DB_PKG = "io/requery/android/database/"

	const val DATABASE = "${SQLITE_PKG}SQLiteDatabase"
	const val CURSOR_FACTORY = "${SQLITE_PKG}SQLiteDatabase\$CursorFactory"
	const val CURSOR_DRIVER = "${SQLITE_PKG}SQLiteCursorDriver"
	const val OPEN_HELPER = "${SQLITE_PKG}SQLiteOpenHelper"
	const val QUERY_BUILDER = "${SQLITE_PKG}SQLiteQueryBuilder"
	const val SQLITE_CURSOR = "${SQLITE_PKG}SQLiteCursor"
	const val OPEN_PARAMS = "${SQLITE_PKG}SQLiteDatabase\$OpenParams"
	const val ERROR_HANDLER = "android/database/DatabaseErrorHandler"
	const val ABSTRACT_WINDOWED_CURSOR = "android/database/AbstractWindowedCursor"
	const val ABSTRACT_CURSOR = "android/database/AbstractCursor"
	const val CROSS_PROCESS_CURSOR = "android/database/CrossProcessCursor"

	/**
	 * Android cursor types that requery's cursors do not extend or implement: its SQLiteCursor extends
	 * its own AbstractWindowedCursor and AbstractCursor, which implement only android.database.Cursor.
	 */
	val ANDROID_ONLY_CURSOR_TYPES: Set<String> = setOf(ABSTRACT_WINDOWED_CURSOR, ABSTRACT_CURSOR, CROSS_PROCESS_CURSOR)
	const val CURSOR_WINDOW = "android/database/CursorWindow"
	const val ANDROID_SIGNAL = "android/os/CancellationSignal"
	const val CORE_SIGNAL = "androidx/core/os/CancellationSignal"
	const val CONTEXT = "android/content/Context"

	val COLLATE_UNICODE = Regex("COLLATE\\s+UNICODE", RegexOption.IGNORE_CASE)
	val COLLATE_LOCALIZED = Regex("COLLATE\\s+LOCALIZED", RegexOption.IGNORE_CASE)

	const val HELPER = "org/appdevforall/sqliterewrite/SqliteRewriteRuntime"
	const val HELPER_PACKAGE_PREFIX = "org/appdevforall/sqliterewrite/"
	const val FRAMEWORK_PACKAGE_PREFIX = "androidx/sqlite/db/framework/"

	/**
	 * androidx.core's SQLiteCursorCompat calls SQLiteCursor.setFillWindowForwardOnly, which requery lacks, from a
	 * helper only an app that calls the compat method reaches. Every app that depends on androidx.core has the
	 * class, so failing the build for it would fail them all.
	 */
	const val CORE_DATABASE_PACKAGE_PREFIX = "androidx/core/database/"
	const val REQUERY_PACKAGE_PREFIX = "io/requery/"

	/** The 10 shared classes and the two error handlers requery's copies use. */
	val RENAMES: Map<String, String> =
		buildMap {
			for (name in listOf(
				"SQLiteClosable",
				"SQLiteCursor",
				"SQLiteCursorDriver",
				"SQLiteDatabase",
				"SQLiteDatabase\$CursorFactory",
				"SQLiteOpenHelper",
				"SQLiteProgram",
				"SQLiteQuery",
				"SQLiteQueryBuilder",
				"SQLiteStatement",
			)) {
				put("$SQLITE_PKG$name", "$REQUERY_SQLITE_PKG$name")
			}
			put(ERROR_HANDLER, "${REQUERY_DB_PKG}DatabaseErrorHandler")
			put("android/database/DefaultDatabaseErrorHandler", "${REQUERY_DB_PKG}DefaultDatabaseErrorHandler")
		}

	/** The Android classes that get renamed. */
	val SQLITE_CLASSES: Set<String> = RENAMES.keys.filter { it.startsWith(SQLITE_PKG) }.toSet()

	/** Renamed superclasses of the renamed classes, from the Wave 0 supertypes.tsv. */
	val RENAMED_SUPERS: Map<String, List<String>> =
		mapOf(
			"${SQLITE_PKG}SQLiteDatabase" to listOf("${SQLITE_PKG}SQLiteClosable"),
			"${SQLITE_PKG}SQLiteProgram" to listOf("${SQLITE_PKG}SQLiteClosable"),
			"${SQLITE_PKG}SQLiteQuery" to listOf("${SQLITE_PKG}SQLiteProgram", "${SQLITE_PKG}SQLiteClosable"),
			"${SQLITE_PKG}SQLiteStatement" to listOf("${SQLITE_PKG}SQLiteProgram", "${SQLITE_PKG}SQLiteClosable"),
		)

	/**
	 * Members requery lacks, or has with another meaning. Key: "owner.name" -> the descriptors it lacks;
	 * other overloads are not matched, so every entry names its descriptors.
	 */
	val MISSING_MEMBERS: Map<String, Set<String>> =
		buildMap<String, MutableSet<String>> {
			fun no(
				owner: String,
				name: String,
				desc: String,
			) {
				getOrPut("$SQLITE_PKG$owner.$name") { mutableSetOf() }.add(desc)
			}
			no("SQLiteClosable", "onAllReferencesReleasedFromContainer", "()V")
			no("SQLiteClosable", "releaseReferenceFromContainer", "()V")
			no("SQLiteCursor", "setFillWindowForwardOnly", "(Z)V")
			// requery has only the 3-argument constructor, and its window is its own CursorWindow
			no("SQLiteCursor", "<init>", "(L$DATABASE;L$CURSOR_DRIVER;Ljava/lang/String;L${SQLITE_PKG}SQLiteQuery;)V")
			no("SQLiteCursor", "setWindow", "(L$CURSOR_WINDOW;)V")
			// inherited from android's AbstractCursor and AbstractWindowedCursor, which requery's copies lack
			// those (found with javap over both jars; SqliteRewriteResolutionTest probes the rest)
			for (name in listOf("isBlob", "isFloat", "isLong", "isString", "isFieldUpdated")) {
				no("SQLiteCursor", name, "(I)Z")
			}
			no("SQLiteCursor", "getUpdatedField", "(I)Ljava/lang/Object;")
			// requery's own mWindow has its own CursorWindow type
			no("SQLiteCursor", "mWindow", "L$CURSOR_WINDOW;")
			no("SQLiteCursor", "getNotificationUris", "()Ljava/util/List;")
			no("SQLiteCursor", "setNotificationUris", "(Landroid/content/ContentResolver;Ljava/util/List;)V")
			// The constants of SQLiteDatabase that requery lacks (JOURNAL_MODE_*, SYNC_MODE_*, NO_LOCALIZED_COLLATORS,
			// SQLITE_MAX_LIKE_PATTERN_LENGTH) are not listed: javac copies them into callers, so no field access remains.
			no("SQLiteDatabase", "setLockingEnabled", "(Z)V")
			no("SQLiteDatabase", "isDbLockedByOtherThreads", "()Z")
			no("SQLiteDatabase", "yieldIfContended", "()Z")
			no("SQLiteDatabase", "getSyncedTables", "()Ljava/util/Map;")
			no("SQLiteDatabase", "createInMemory", "(L$OPEN_PARAMS;)L$DATABASE;")
			no("SQLiteDatabase", "setCustomScalarFunction", "(Ljava/lang/String;Ljava/util/function/UnaryOperator;)V")
			no("SQLiteDatabase", "setCustomAggregateFunction", "(Ljava/lang/String;Ljava/util/function/BinaryOperator;)V")
			no("SQLiteDatabase", "execPerConnectionSQL", "(Ljava/lang/String;[Ljava/lang/Object;)V")
			no("SQLiteDatabase", "markTableSyncable", "(Ljava/lang/String;Ljava/lang/String;)V")
			no("SQLiteDatabase", "markTableSyncable", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V")
			no("SQLiteOpenHelper", "setLookasideConfig", "(II)V")
			no("SQLiteOpenHelper", "setOpenParams", "(L$OPEN_PARAMS;)V")
			no("SQLiteOpenHelper", "setIdleConnectionTimeout", "(J)V")
			no("SQLiteProgram", "getUniqueId", "()I")
			no("SQLiteQueryBuilder", "isDistinct", "()Z")
			no("SQLiteQueryBuilder", "appendWhereStandalone", "(Ljava/lang/CharSequence;)V")
			no("SQLiteQueryBuilder", "getProjectionMap", "()Ljava/util/Map;")
			no("SQLiteQueryBuilder", "setProjectionGreylist", "(Ljava/util/Collection;)V")
			no("SQLiteQueryBuilder", "getProjectionGreylist", "()Ljava/util/Collection;")
			no("SQLiteQueryBuilder", "getCursorFactory", "()L$CURSOR_FACTORY;")
			no("SQLiteQueryBuilder", "isStrict", "()Z")
			no("SQLiteQueryBuilder", "setStrictColumns", "(Z)V")
			no("SQLiteQueryBuilder", "isStrictColumns", "()Z")
			no("SQLiteQueryBuilder", "setStrictGrammar", "(Z)V")
			no("SQLiteQueryBuilder", "isStrictGrammar", "()Z")
			no("SQLiteQueryBuilder", "insert", "(L$DATABASE;Landroid/content/ContentValues;)J")
			no("SQLiteQueryBuilder", "update", "(L$DATABASE;Landroid/content/ContentValues;Ljava/lang/String;[Ljava/lang/String;)I")
			no("SQLiteQueryBuilder", "delete", "(L$DATABASE;Ljava/lang/String;[Ljava/lang/String;)I")
			// requery's versions have no selectionArgs parameter
			no(
				"SQLiteQueryBuilder",
				"buildQuery",
				"([Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
			)
			no(
				"SQLiteQueryBuilder",
				"buildUnionSubQuery",
				"(Ljava/lang/String;[Ljava/lang/String;Ljava/util/Set;ILjava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
			)
			// added to android.jar after the Wave 0 members.tsv (API 35/36); requery has none of them
			no("SQLiteDatabase", "beginTransactionReadOnly", "()V")
			no("SQLiteDatabase", "beginTransactionWithListenerReadOnly", "(L${SQLITE_PKG}SQLiteTransactionListener;)V")
			no("SQLiteDatabase", "createRawStatement", "(Ljava/lang/String;)L${SQLITE_PKG}SQLiteRawStatement;")
			no("SQLiteDatabase", "getLastChangedRowCount", "()J")
			no("SQLiteDatabase", "getLastInsertRowId", "()J")
			no("SQLiteDatabase", "getTotalChangedRowCount", "()J")
			// the OpenParams overloads
			no("SQLiteDatabase", "openDatabase", "(Ljava/io/File;L$OPEN_PARAMS;)L$DATABASE;")
			no("SQLiteOpenHelper", "<init>", "(Landroid/content/Context;Ljava/lang/String;IL$OPEN_PARAMS;)V")
		}

	/**
	 * Method names with an entry in [MISSING_MEMBERS], so other calls need no hierarchy lookup. Constructors
	 * are left out: a subclass has no constructor of its parent's, so only the renamed classes themselves match.
	 */
	val MISSING_MEMBER_NAMES: Set<String> =
		MISSING_MEMBERS.keys
			.map { it.substringAfterLast('.') }
			.filter { it != "<init>" }
			.toSet()

	/** Names of the fields the missing-member rule can match: entries with a field descriptor. */
	val MISSING_FIELD_NAMES: Set<String> =
		MISSING_MEMBERS
			.filterValues { descs -> descs.any { !it.startsWith("(") } }
			.keys
			.map { it.substringAfterLast('.') }
			.toSet()

	/** A call whose descriptor changes: selectionArgs-style String[] to Object[], and the signal. */
	class DescriptorRule(
		val owner: String,
		val name: String,
		val desc: String,
		val objectArrayParams: Set<Int>,
	)

	private val S = "Ljava/lang/String;"
	private val SA = "[Ljava/lang/String;"
	private val SIG = "L$ANDROID_SIGNAL;"
	private const val CF = "L$CURSOR_FACTORY;"
	private const val CUR = "Landroid/database/Cursor;"

	val DESCRIPTOR_RULES: List<DescriptorRule> =
		run {
			fun rule(
				name: String,
				params: String,
				ret: String,
				vararg objectArrays: Int,
			) = listOf(
				DescriptorRule(DATABASE, name, "($params)$ret", objectArrays.toSet()),
				DescriptorRule(DATABASE, name, "($params$SIG)$ret", objectArrays.toSet()),
			)
			rule("query", "Z$S$SA$S$SA$S$S$S$S", CUR, 4) +
				rule("query", "$S$SA$S$SA$S$S$S", CUR, 3) +
				rule("query", "$S$SA$S$SA$S$S$S$S", CUR, 3) +
				rule("queryWithFactory", "${CF}Z$S$SA$S$SA$S$S$S$S", CUR, 5) +
				rule("rawQuery", "$S$SA", CUR, 1) +
				rule("rawQueryWithFactory", "$CF$S$SA$S", CUR, 2) +
				// the one signal-only entry; its signal-less form is not an Android method
				DescriptorRule(DATABASE, "validateSql", "(${S}$SIG)V", emptySet()) +
				DescriptorRule(QUERY_BUILDER, "query", "(L$DATABASE;$SA$S$SA$S$S$S$S$SIG)$CUR", emptySet())
		}

	/** Names of the calls whose descriptor changes. */
	val DESCRIPTOR_RULE_NAMES: Set<String> = DESCRIPTOR_RULES.map { it.name }.toSet()

	/** SQLiteCursorDriver.query, matched on any owner: an app class may implement the interface. */
	const val DRIVER_QUERY_NAME = "query"
	const val DRIVER_QUERY_DESC = "($CF[Ljava/lang/String;)Landroid/database/Cursor;"
	const val DRIVER_QUERY_NEW_DESC = "($CF[Ljava/lang/Object;)Landroid/database/Cursor;"

	const val HELPER_OPEN_DESC = "(Ljava/lang/String;${CF}I)L$DATABASE;"
	const val HELPER_OPEN_HANDLER_DESC = "(Ljava/lang/String;${CF}IL$ERROR_HANDLER;)L$DATABASE;"
	const val CONTEXT_OPEN_DESC = "(Ljava/lang/String;I$CF)L$DATABASE;"
	const val CONTEXT_OPEN_HANDLER_DESC = "(Ljava/lang/String;I${CF}L$ERROR_HANDLER;)L$DATABASE;"
	const val CONTEXT_DELETE_DESC = "(Ljava/lang/String;)Z"
	const val HELPER_SIGNAL_DESC = "(L$ANDROID_SIGNAL;)L$CORE_SIGNAL;"

	const val DATABASE_UTILS = "android/database/DatabaseUtils"

	/**
	 * DatabaseUtils methods that take renamed types and that the runtime reimplements, as name plus
	 * Android descriptor. requery has no DatabaseUtils. The rest of DatabaseUtils' and other platform
	 * methods that mention renamed types fail the build.
	 */
	val REDIRECTED_UTILS: Set<String> =
		run {
			val db = "L$DATABASE;"
			val stmt = "L${SQLITE_PKG}SQLiteStatement;"
			setOf(
				"queryNumEntries($db$S)J",
				"queryNumEntries($db$S$S)J",
				"queryNumEntries($db$S$S$SA)J",
				"longForQuery($db$S$SA)J",
				"longForQuery($stmt$SA)J",
				"stringForQuery($db$S$SA)$S",
				"stringForQuery($stmt$SA)$S",
			)
		}

	/** How a renamed class appears in a descriptor. */
	val RENAMED_DESCRIPTOR_TOKENS: List<String> = RENAMES.keys.map { "L$it;" }

	val CONTEXT_FAMILY: Set<String> =
		setOf(
			CONTEXT,
			"android/content/ContextWrapper",
			"android/view/ContextThemeWrapper",
			"android/app/Activity",
			"android/app/Application",
			"android/app/Service",
			"android/app/IntentService",
		)
}
