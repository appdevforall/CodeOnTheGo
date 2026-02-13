package org.appdevforall.codeonthego.lsp.kotlin.parser

/**
 * Represents all possible syntax node types in Kotlin source code.
 *
 * These values correspond to tree-sitter-kotlin grammar node types. The naming follows
 * Kotlin enum conventions (SCREAMING_SNAKE_CASE) while storing the original tree-sitter
 * names (snake_case) for lookup.
 *
 * ## Usage
 *
 * ```kotlin
 * val kind = SyntaxKind.fromTreeSitter("function_declaration")
 * when (kind) {
 *     SyntaxKind.FUNCTION_DECLARATION -> handleFunction(node)
 *     SyntaxKind.CLASS_DECLARATION -> handleClass(node)
 *     else -> {}
 * }
 * ```
 *
 * ## Design Note
 *
 * This enum serves as an abstraction layer between tree-sitter's string-based node types
 * and our type-safe Kotlin code. By centralizing all node type mappings here, we:
 * 1. Get compile-time checking for node type handling
 * 2. Can use exhaustive `when` expressions
 * 3. Isolate tree-sitter grammar changes to a single location
 *
 * @property treeSitterName The exact string used by tree-sitter-kotlin grammar
 * @see <a href="https://github.com/fwcd/tree-sitter-kotlin">tree-sitter-kotlin grammar</a>
 */
enum class SyntaxKind(val treeSitterName: String) {

    // ═══════════════════════════════════════════════════════════════════════════════
    // Source Structure
    // ═══════════════════════════════════════════════════════════════════════════════

    /** Root node of any Kotlin source file */
    SOURCE_FILE("source_file"),

    /** Package declaration: `package com.example` */
    PACKAGE_HEADER("package_header"),

    /** Container for all import statements */
    IMPORT_LIST("import_list"),

    /** Single import statement: `import kotlin.collections.List` */
    IMPORT_HEADER("import_header"),

    /** Alias in import: `import foo.Bar as Baz` */
    IMPORT_ALIAS("import_alias"),

    // ═══════════════════════════════════════════════════════════════════════════════
    // Declarations
    // ═══════════════════════════════════════════════════════════════════════════════

    /** Class declaration: `class Foo { }` */
    CLASS_DECLARATION("class_declaration"),

    /** Object declaration: `object Singleton { }` */
    OBJECT_DECLARATION("object_declaration"),

    /** Companion object: `companion object { }` */
    COMPANION_OBJECT("companion_object"),

    /** Interface declaration: `interface Drawable { }` */
    INTERFACE_DECLARATION("interface_declaration"),

    /** Function declaration: `fun foo() { }` */
    FUNCTION_DECLARATION("function_declaration"),

    /** Function body: `{ statements }` or `= expression` */
    FUNCTION_BODY("function_body"),

    /** Property declaration: `val x = 1` or `var y: Int` */
    PROPERTY_DECLARATION("property_declaration"),

    /** Multi-variable declaration (destructuring): `val (x, y) = pair` */
    MULTI_VARIABLE_DECLARATION("multi_variable_declaration"),

    /** Variable declaration (in destructuring): `x` in `val (x, y)` */
    VARIABLE_DECLARATION("variable_declaration"),

    /** Getter accessor: `get() = field` */
    GETTER("getter"),

    /** Setter accessor: `set(value) { field = value }` */
    SETTER("setter"),

    /** Type alias: `typealias StringList = List<String>` */
    TYPE_ALIAS("type_alias"),

    /** Enum class declaration */
    ENUM_CLASS_BODY("enum_class_body"),

    /** Single enum entry */
    ENUM_ENTRY("enum_entry"),

    /** Anonymous initializer block: `init { }` */
    ANONYMOUS_INITIALIZER("anonymous_initializer"),

    /** Secondary constructor: `constructor(x: Int) : this()` */
    SECONDARY_CONSTRUCTOR("secondary_constructor"),

    // ═══════════════════════════════════════════════════════════════════════════════
    // Class Body & Members
    // ═══════════════════════════════════════════════════════════════════════════════

    /** Body of a class/interface/object */
    CLASS_BODY("class_body"),

    /** Primary constructor parameters */
    PRIMARY_CONSTRUCTOR("primary_constructor"),

    /** Delegation specifiers (superclass, interfaces) */
    DELEGATION_SPECIFIER("delegation_specifier"),

    /** Delegation specifiers list */
    DELEGATION_SPECIFIERS("delegation_specifiers"),

    /** Constructor delegation call */
    CONSTRUCTOR_DELEGATION_CALL("constructor_delegation_call"),

    /** Constructor invocation: `Foo()` in inheritance */
    CONSTRUCTOR_INVOCATION("constructor_invocation"),

    /** Explicit delegation: `by delegate` */
    EXPLICIT_DELEGATION("explicit_delegation"),

    /** Annotated delegation specifier */
    ANNOTATED_DELEGATION_SPECIFIER("annotated_delegation_specifier"),

    // ═══════════════════════════════════════════════════════════════════════════════
    // Parameters & Arguments
    // ═══════════════════════════════════════════════════════════════════════════════

    /** Function parameter list: `(a: Int, b: String)` */
    FUNCTION_VALUE_PARAMETERS("function_value_parameters"),

    /** Single function parameter */
    PARAMETER("parameter"),

    /** Parameter with modifiers */
    PARAMETER_WITH_OPTIONAL_TYPE("parameter_with_optional_type"),

    /** Class parameter (in primary constructor) */
    CLASS_PARAMETER("class_parameter"),

    /** Receiver parameter for extension functions */
    FUNCTION_TYPE_PARAMETERS("function_type_parameters"),

    /** Value arguments in function call: `foo(1, "hello")` */
    VALUE_ARGUMENTS("value_arguments"),

    /** Single value argument */
    VALUE_ARGUMENT("value_argument"),

    // ═══════════════════════════════════════════════════════════════════════════════
    // Type Parameters & Arguments (Generics)
    // ═══════════════════════════════════════════════════════════════════════════════

    /** Type parameters: `<T, R>` */
    TYPE_PARAMETERS("type_parameters"),

    /** Single type parameter: `T` or `T : Comparable<T>` */
    TYPE_PARAMETER("type_parameter"),

    /** Type arguments: `<String, Int>` */
    TYPE_ARGUMENTS("type_arguments"),

    /** Type projection: `out T`, `in T`, `*` */
    TYPE_PROJECTION("type_projection"),

    /** Type constraints: `where T : Comparable<T>, T : Serializable` */
    TYPE_CONSTRAINTS("type_constraints"),

    /** Single type constraint */
    TYPE_CONSTRAINT("type_constraint"),

    // ═══════════════════════════════════════════════════════════════════════════════
    // Types
    // ═══════════════════════════════════════════════════════════════════════════════

    /** User-defined type reference: `List<String>` */
    USER_TYPE("user_type"),

    /** Simple user type (single identifier with optional type args) */
    SIMPLE_USER_TYPE("simple_user_type"),

    /** Nullable type: `String?` */
    NULLABLE_TYPE("nullable_type"),

    /** Parenthesized type: `(Int -> String)` */
    PARENTHESIZED_TYPE("parenthesized_type"),

    /** Function type: `(Int, String) -> Boolean` */
    FUNCTION_TYPE("function_type"),

    /** Function type parameters (in function type) */
    FUNCTION_TYPE_PARAMETER("function_type_parameter"),

    /** Dynamic type (for JavaScript interop) */
    DYNAMIC_TYPE("dynamic_type"),

    /** Type modifier (suspend, etc.) */
    TYPE_MODIFIER("type_modifier"),

    /** Receiver type in extension functions */
    RECEIVER_TYPE("receiver_type"),

    // ═══════════════════════════════════════════════════════════════════════════════
    // Expressions - Primary
    // ═══════════════════════════════════════════════════════════════════════════════

    /** Parenthesized expression: `(x + y)` */
    PARENTHESIZED_EXPRESSION("parenthesized_expression"),

    /** Collection literal: `[1, 2, 3]` (annotations only) */
    COLLECTION_LITERAL("collection_literal"),

    /** This expression: `this` or `this@Outer` */
    THIS_EXPRESSION("this_expression"),

    /** Super expression: `super` or `super@Outer` */
    SUPER_EXPRESSION("super_expression"),

    /** If expression: `if (cond) a else b` */
    IF_EXPRESSION("if_expression"),

    /** When expression (Kotlin's switch): `when (x) { ... }` */
    WHEN_EXPRESSION("when_expression"),

    /** When subject: `when (val x = expr)` */
    WHEN_SUBJECT("when_subject"),

    /** Single when entry: `is String -> ...` */
    WHEN_ENTRY("when_entry"),

    /** When condition */
    WHEN_CONDITION("when_condition"),

    /** Range test in when: `in 1..10` */
    RANGE_TEST("range_test"),

    /** Type test in when: `is String` */
    TYPE_TEST("type_test"),

    /** Try expression with catch/finally */
    TRY_EXPRESSION("try_expression"),

    /** Catch block */
    CATCH_BLOCK("catch_block"),

    /** Finally block */
    FINALLY_BLOCK("finally_block"),

    /** Jump expression: return, break, continue, throw */
    JUMP_EXPRESSION("jump_expression"),

    /** Object literal: `object : Interface { }` */
    OBJECT_LITERAL("object_literal"),

    /** Lambda literal: `{ x -> x + 1 }` */
    LAMBDA_LITERAL("lambda_literal"),

    /** Lambda parameters */
    LAMBDA_PARAMETERS("lambda_parameters"),

    /** Annotated lambda */
    ANNOTATED_LAMBDA("annotated_lambda"),

    /** Anonymous function: `fun(x: Int): Int = x + 1` */
    ANONYMOUS_FUNCTION("anonymous_function"),

    // ═══════════════════════════════════════════════════════════════════════════════
    // Expressions - Operations
    // ═══════════════════════════════════════════════════════════════════════════════

    /** Function/method call: `foo(args)` */
    CALL_EXPRESSION("call_expression"),

    /** Indexing: `array[index]` */
    INDEXING_EXPRESSION("indexing_expression"),

    /** Navigation/member access: `obj.member` or `obj?.member` */
    NAVIGATION_EXPRESSION("navigation_expression"),

    /** Navigation suffix (the part after the dot) */
    NAVIGATION_SUFFIX("navigation_suffix"),

    /** Call suffix (parentheses and arguments) */
    CALL_SUFFIX("call_suffix"),

    /** Indexing suffix (brackets and indices) */
    INDEXING_SUFFIX("indexing_suffix"),

    /** Prefix unary expression: `!x`, `-y`, `++z` */
    PREFIX_EXPRESSION("prefix_expression"),

    /** Postfix unary expression: `x++`, `y--`, `z!!` */
    POSTFIX_EXPRESSION("postfix_expression"),

    /** As expression (type cast): `x as String` */
    AS_EXPRESSION("as_expression"),

    /** Spread operator: `*array` */
    SPREAD_EXPRESSION("spread_expression"),

    /** Binary expression: `a + b`, `x == y` */
    BINARY_EXPRESSION("binary_expression"),

    /** Infix function call: `a to b` */
    INFIX_EXPRESSION("infix_expression"),

    /** Elvis expression: `x ?: default` */
    ELVIS_EXPRESSION("elvis_expression"),

    /** Check expression (in, !in, is, !is) */
    CHECK_EXPRESSION("check_expression"),

    /** Comparison expression: `a < b` */
    COMPARISON_EXPRESSION("comparison_expression"),

    /** Equality expression: `a == b` */
    EQUALITY_EXPRESSION("equality_expression"),

    /** Conjunction (and): `a && b` */
    CONJUNCTION_EXPRESSION("conjunction_expression"),

    /** Disjunction (or): `a || b` */
    DISJUNCTION_EXPRESSION("disjunction_expression"),

    /** Additive expression: `a + b` */
    ADDITIVE_EXPRESSION("additive_expression"),

    /** Multiplicative expression: `a * b` */
    MULTIPLICATIVE_EXPRESSION("multiplicative_expression"),

    /** Range expression: `1..10` or `1..<10` */
    RANGE_EXPRESSION("range_expression"),

    /** Assignment: `x = y` */
    ASSIGNMENT("assignment"),

    /** Augmented assignment: `x += 1` */
    AUGMENTED_ASSIGNMENT("augmented_assignment"),

    /** Directly assignable expression: wraps the left side of an assignment */
    DIRECTLY_ASSIGNABLE_EXPRESSION("directly_assignable_expression"),

    // ═══════════════════════════════════════════════════════════════════════════════
    // Expressions - String Templates
    // ═══════════════════════════════════════════════════════════════════════════════

    /** String literal with possible interpolation */
    STRING_LITERAL("string_literal"),

    /** Line string literal: `"hello"` */
    LINE_STRING_LITERAL("line_string_literal"),

    /** Multi-line string literal: `"""..."""` */
    MULTI_LINE_STRING_LITERAL("multi_line_string_literal"),

    /** String content (text between interpolations) */
    STRING_CONTENT("string_content"),

    /** Line string content */
    LINE_STRING_CONTENT("line_string_content"),

    /** Multi-line string content */
    MULTI_LINE_STRING_CONTENT("multi_line_string_content"),

    /** String interpolation entry: `$name` or `${expr}` */
    INTERPOLATION("interpolation"),

    /** Line string expression */
    LINE_STRING_EXPRESSION("line_string_expression"),

    /** Multi-line string expression */
    MULTI_LINE_STRING_EXPRESSION("multi_line_string_expression"),

    // ═══════════════════════════════════════════════════════════════════════════════
    // Literals
    // ═══════════════════════════════════════════════════════════════════════════════

    /** Integer literal: `42`, `0xFF`, `0b1010` */
    INTEGER_LITERAL("integer_literal"),

    /** Long literal: `42L` */
    LONG_LITERAL("long_literal"),

    /** Hex literal: `0xFF` */
    HEX_LITERAL("hex_literal"),

    /** Binary literal: `0b1010` */
    BIN_LITERAL("bin_literal"),

    /** Real/floating-point literal: `3.14`, `1e10` */
    REAL_LITERAL("real_literal"),

    /** Boolean literal: `true` or `false` */
    BOOLEAN_LITERAL("boolean_literal"),

    /** Character literal: `'a'` */
    CHARACTER_LITERAL("character_literal"),

    /** Null literal: `null` */
    NULL_LITERAL("null_literal"),

    // ═══════════════════════════════════════════════════════════════════════════════
    // Identifiers & References
    // ═══════════════════════════════════════════════════════════════════════════════

    /** Simple identifier: `foo`, `bar` */
    SIMPLE_IDENTIFIER("simple_identifier"),

    /** Type identifier (used for type names) */
    TYPE_IDENTIFIER("type_identifier"),

    /** Identifier (general) */
    IDENTIFIER("identifier"),

    /** Label: `loop@` */
    LABEL("label"),

    // ═══════════════════════════════════════════════════════════════════════════════
    // Statements
    // ═══════════════════════════════════════════════════════════════════════════════

    /** Block of statements: `{ ... }` */
    STATEMENTS("statements"),

    /** For loop: `for (x in list) { }` */
    FOR_STATEMENT("for_statement"),

    /** While loop: `while (cond) { }` */
    WHILE_STATEMENT("while_statement"),

    /** Do-while loop: `do { } while (cond)` */
    DO_WHILE_STATEMENT("do_while_statement"),

    /** Control structure body (block or single statement) */
    CONTROL_STRUCTURE_BODY("control_structure_body"),

    // ═══════════════════════════════════════════════════════════════════════════════
    // Modifiers
    // ═══════════════════════════════════════════════════════════════════════════════

    /** Container for modifiers */
    MODIFIERS("modifiers"),

    /** Single modifier */
    MODIFIER("modifier"),

    /** Class modifier: `enum`, `sealed`, `annotation`, `data`, `inner`, `value` */
    CLASS_MODIFIER("class_modifier"),

    /** Member modifier: `override`, `lateinit` */
    MEMBER_MODIFIER("member_modifier"),

    /** Visibility modifier: `public`, `private`, `protected`, `internal` */
    VISIBILITY_MODIFIER("visibility_modifier"),

    /** Variance modifier: `in`, `out` */
    VARIANCE_MODIFIER("variance_modifier"),

    /** Type parameter modifier: `reified` */
    TYPE_PARAMETER_MODIFIER("type_parameter_modifier"),

    /** Function modifier: `tailrec`, `operator`, `infix`, `inline`, `external`, `suspend` */
    FUNCTION_MODIFIER("function_modifier"),

    /** Property modifier: `const` */
    PROPERTY_MODIFIER("property_modifier"),

    /** Inheritance modifier: `abstract`, `final`, `open` */
    INHERITANCE_MODIFIER("inheritance_modifier"),

    /** Parameter modifier: `vararg`, `noinline`, `crossinline` */
    PARAMETER_MODIFIER("parameter_modifier"),

    /** Platform modifier: `expect`, `actual` */
    PLATFORM_MODIFIER("platform_modifier"),

    // ═══════════════════════════════════════════════════════════════════════════════
    // Annotations
    // ═══════════════════════════════════════════════════════════════════════════════

    /** Annotation: `@Deprecated` */
    ANNOTATION("annotation"),

    /** Single annotation entry */
    SINGLE_ANNOTATION("single_annotation"),

    /** Multiple annotations: `@A @B` */
    MULTI_ANNOTATION("multi_annotation"),

    /** Annotation use-site target: `@field:Inject` */
    ANNOTATION_USE_SITE_TARGET("annotation_use_site_target"),

    /** Unescaped annotation */
    UNESCAPED_ANNOTATION("unescaped_annotation"),

    /** File annotation: `@file:JvmName("Utils")` */
    FILE_ANNOTATION("file_annotation"),

    // ═══════════════════════════════════════════════════════════════════════════════
    // Operators
    // ═══════════════════════════════════════════════════════════════════════════════

    /** Prefix operator: `!`, `-`, `+`, `++`, `--` */
    PREFIX_UNARY_OPERATOR("prefix_unary_operator"),

    /** Postfix operator: `++`, `--`, `!!` */
    POSTFIX_UNARY_OPERATOR("postfix_unary_operator"),

    /** Multiplicative operator: `*`, `/`, `%` */
    MULTIPLICATIVE_OPERATOR("multiplicative_operator"),

    /** Additive operator: `+`, `-` */
    ADDITIVE_OPERATOR("additive_operator"),

    /** Comparison operator: `<`, `>`, `<=`, `>=` */
    COMPARISON_OPERATOR("comparison_operator"),

    /** Equality operator: `==`, `!=`, `===`, `!==` */
    EQUALITY_OPERATOR("equality_operator"),

    /** Assignment operator: `=` */
    ASSIGNMENT_OPERATOR("assignment_operator"),

    /** Augmented assignment operator: `+=`, `-=`, etc. */
    AUGMENTED_ASSIGNMENT_OPERATOR("augmented_assignment_operator"),

    /** Member access operator: `.`, `?.`, `::` */
    MEMBER_ACCESS_OPERATOR("member_access_operator"),

    /** In operator: `in`, `!in` */
    IN_OPERATOR("in_operator"),

    /** Is operator: `is`, `!is` */
    IS_OPERATOR("is_operator"),

    /** As operator: `as`, `as?` */
    AS_OPERATOR("as_operator"),

    // ═══════════════════════════════════════════════════════════════════════════════
    // Punctuation & Keywords
    // ═══════════════════════════════════════════════════════════════════════════════

    /** Semicolon */
    SEMI("semi"),

    /** Newline (semis) */
    SEMIS("semis"),

    /** Colon */
    COLON("colon"),

    /** Comma */
    COMMA("comma"),

    /** Left parenthesis */
    LPAREN("("),

    /** Right parenthesis */
    RPAREN(")"),

    /** Left brace */
    LBRACE("{"),

    /** Right brace */
    RBRACE("}"),

    /** Left bracket */
    LBRACKET("["),

    /** Right bracket */
    RBRACKET("]"),

    /** Left angle bracket */
    LANGLE("<"),

    /** Right angle bracket */
    RANGLE(">"),

    /** Dot */
    DOT("."),

    /** Safe call operator */
    SAFE_NAV("?."),

    /** Double colon (callable reference) */
    COLONCOLON("::"),

    /** Range operator */
    RANGE(".."),

    /** Range until operator */
    RANGE_UNTIL("..<"),

    /** Spread operator */
    SPREAD("*"),

    /** Elvis operator */
    ELVIS("?:"),

    /** Arrow */
    ARROW("->"),

    /** Double arrow */
    DOUBLE_ARROW("=>"),

    /** At sign */
    AT("@"),

    /** Question mark */
    QUEST("?"),

    /** Exclamation mark */
    EXCL("!"),

    /** Double exclamation (non-null assertion) */
    EXCL_EXCL("!!"),

    /** Underscore (unused parameter) */
    UNDERSCORE("_"),

    // ═══════════════════════════════════════════════════════════════════════════════
    // Keywords
    // ═══════════════════════════════════════════════════════════════════════════════

    /** `package` keyword */
    PACKAGE("package"),

    /** `import` keyword */
    IMPORT("import"),

    /** `class` keyword */
    CLASS("class"),

    /** `interface` keyword */
    INTERFACE("interface"),

    /** `fun` keyword */
    FUN("fun"),

    /** `object` keyword */
    OBJECT("object"),

    /** `val` keyword */
    VAL("val"),

    /** `var` keyword */
    VAR("var"),

    /** `typealias` keyword */
    TYPEALIAS("typealias"),

    /** `constructor` keyword */
    CONSTRUCTOR("constructor"),

    /** `by` keyword (delegation) */
    BY("by"),

    /** `companion` keyword */
    COMPANION("companion"),

    /** `init` keyword */
    INIT("init"),

    /** `this` keyword */
    THIS("this"),

    /** `super` keyword */
    SUPER("super"),

    /** `typeof` keyword (reserved) */
    TYPEOF("typeof"),

    /** `where` keyword */
    WHERE("where"),

    /** `if` keyword */
    IF("if"),

    /** `else` keyword */
    ELSE("else"),

    /** `when` keyword */
    WHEN("when"),

    /** `try` keyword */
    TRY("try"),

    /** `catch` keyword */
    CATCH("catch"),

    /** `finally` keyword */
    FINALLY("finally"),

    /** `for` keyword */
    FOR("for"),

    /** `do` keyword */
    DO("do"),

    /** `while` keyword */
    WHILE("while"),

    /** `throw` keyword */
    THROW("throw"),

    /** `return` keyword */
    RETURN("return"),

    /** `continue` keyword */
    CONTINUE("continue"),

    /** `break` keyword */
    BREAK("break"),

    /** `as` keyword */
    AS("as"),

    /** `is` keyword */
    IS("is"),

    /** `in` keyword */
    IN("in"),

    /** `out` keyword */
    OUT("out"),

    /** `get` keyword */
    GET("get"),

    /** `set` keyword */
    SET("set"),

    /** `dynamic` keyword */
    DYNAMIC("dynamic"),

    /** `file` keyword (annotation target) */
    FILE("file"),

    /** `field` keyword (annotation target/backing field) */
    FIELD("field"),

    /** `property` keyword (annotation target) */
    PROPERTY("property"),

    /** `receiver` keyword (annotation target) */
    RECEIVER("receiver"),

    /** `param` keyword (annotation target) */
    PARAM("param"),

    /** `setparam` keyword (annotation target) */
    SETPARAM("setparam"),

    /** `delegate` keyword (annotation target) */
    DELEGATE("delegate"),

    /** `public` keyword */
    PUBLIC("public"),

    /** `private` keyword */
    PRIVATE("private"),

    /** `protected` keyword */
    PROTECTED("protected"),

    /** `internal` keyword */
    INTERNAL("internal"),

    /** `enum` keyword */
    ENUM("enum"),

    /** `sealed` keyword */
    SEALED("sealed"),

    /** `data` keyword */
    DATA("data"),

    /** `inner` keyword */
    INNER("inner"),

    /** `value` keyword (value class) */
    VALUE("value"),

    /** `tailrec` keyword */
    TAILREC("tailrec"),

    /** `operator` keyword */
    OPERATOR("operator"),

    /** `inline` keyword */
    INLINE("inline"),

    /** `infix` keyword */
    INFIX("infix"),

    /** `external` keyword */
    EXTERNAL("external"),

    /** `suspend` keyword */
    SUSPEND("suspend"),

    /** `override` keyword */
    OVERRIDE("override"),

    /** `abstract` keyword */
    ABSTRACT("abstract"),

    /** `final` keyword */
    FINAL("final"),

    /** `open` keyword */
    OPEN("open"),

    /** `const` keyword */
    CONST("const"),

    /** `lateinit` keyword */
    LATEINIT("lateinit"),

    /** `vararg` keyword */
    VARARG("vararg"),

    /** `noinline` keyword */
    NOINLINE("noinline"),

    /** `crossinline` keyword */
    CROSSINLINE("crossinline"),

    /** `reified` keyword */
    REIFIED("reified"),

    /** `expect` keyword (multiplatform) */
    EXPECT("expect"),

    /** `actual` keyword (multiplatform) */
    ACTUAL("actual"),

    // ═══════════════════════════════════════════════════════════════════════════════
    // Special
    // ═══════════════════════════════════════════════════════════════════════════════

    /** Comment (line or block) */
    COMMENT("comment"),

    /** Line comment: `// ...` */
    LINE_COMMENT("line_comment"),

    /** Multiline comment: `/* ... */` */
    MULTILINE_COMMENT("multiline_comment"),

    /** Shebang line: `#!/usr/bin/env kotlin` */
    SHEBANG_LINE("shebang_line"),

    /** Error node (for parse errors) */
    ERROR("ERROR"),

    /** Unknown/unrecognized node type */
    UNKNOWN("unknown");

    companion object {
        /** Lookup table for O(1) conversion from tree-sitter names */
        private val byTreeSitterName: Map<String, SyntaxKind> = buildMap {
            SyntaxKind.entries.forEach { kind -> put(kind.treeSitterName, kind) }
            put("null", NULL_LITERAL)
            put("true", BOOLEAN_LITERAL)
            put("false", BOOLEAN_LITERAL)
            put(":", COLON)
            put("=", ASSIGNMENT_OPERATOR)
            put("==", EQUALITY_OPERATOR)
            put("!=", EQUALITY_OPERATOR)
            put("===", EQUALITY_OPERATOR)
            put("!==", EQUALITY_OPERATOR)
            put("+", ADDITIVE_OPERATOR)
            put("-", ADDITIVE_OPERATOR)
            put("/", MULTIPLICATIVE_OPERATOR)
            put("%", MULTIPLICATIVE_OPERATOR)
            put("<=", COMPARISON_OPERATOR)
            put(">=", COMPARISON_OPERATOR)
        }

        /**
         * Converts a tree-sitter node type string to a [SyntaxKind].
         *
         * @param name The tree-sitter node type string (e.g., "function_declaration")
         * @return The corresponding [SyntaxKind], or [UNKNOWN] if not recognized
         */
        fun fromTreeSitter(name: String): SyntaxKind = byTreeSitterName[name] ?: UNKNOWN

        /**
         * Checks if a node kind represents a declaration.
         *
         * Declarations introduce named entities into scopes: classes, functions, properties, etc.
         */
        fun isDeclaration(kind: SyntaxKind): Boolean = kind in DECLARATIONS

        /**
         * Checks if a node kind represents an expression.
         *
         * Expressions are code that evaluates to a value.
         */
        fun isExpression(kind: SyntaxKind): Boolean = kind in EXPRESSIONS

        /**
         * Checks if a node kind represents a statement.
         *
         * Statements are executable code that may have side effects.
         */
        fun isStatement(kind: SyntaxKind): Boolean = kind in STATEMENTS

        /**
         * Checks if a node kind represents a type reference.
         */
        fun isType(kind: SyntaxKind): Boolean = kind in TYPES

        /**
         * Checks if a node kind represents a literal value.
         */
        fun isLiteral(kind: SyntaxKind): Boolean = kind in LITERALS

        /**
         * Checks if a node kind represents a modifier.
         */
        fun isModifier(kind: SyntaxKind): Boolean = kind in MODIFIER_KINDS

        /** All declaration node kinds */
        private val DECLARATIONS = setOf(
            CLASS_DECLARATION,
            OBJECT_DECLARATION,
            INTERFACE_DECLARATION,
            FUNCTION_DECLARATION,
            PROPERTY_DECLARATION,
            TYPE_ALIAS,
            ENUM_ENTRY,
            SECONDARY_CONSTRUCTOR,
            PARAMETER,
            CLASS_PARAMETER,
            TYPE_PARAMETER,
            COMPANION_OBJECT,
            ANONYMOUS_INITIALIZER
        )

        /** All expression node kinds */
        private val EXPRESSIONS = setOf(
            PARENTHESIZED_EXPRESSION,
            COLLECTION_LITERAL,
            THIS_EXPRESSION,
            SUPER_EXPRESSION,
            IF_EXPRESSION,
            WHEN_EXPRESSION,
            TRY_EXPRESSION,
            JUMP_EXPRESSION,
            OBJECT_LITERAL,
            LAMBDA_LITERAL,
            ANONYMOUS_FUNCTION,
            CALL_EXPRESSION,
            INDEXING_EXPRESSION,
            NAVIGATION_EXPRESSION,
            PREFIX_EXPRESSION,
            POSTFIX_EXPRESSION,
            AS_EXPRESSION,
            SPREAD_EXPRESSION,
            BINARY_EXPRESSION,
            INFIX_EXPRESSION,
            ELVIS_EXPRESSION,
            CHECK_EXPRESSION,
            COMPARISON_EXPRESSION,
            EQUALITY_EXPRESSION,
            CONJUNCTION_EXPRESSION,
            DISJUNCTION_EXPRESSION,
            ADDITIVE_EXPRESSION,
            MULTIPLICATIVE_EXPRESSION,
            RANGE_EXPRESSION,
            ASSIGNMENT,
            AUGMENTED_ASSIGNMENT,
            STRING_LITERAL,
            LINE_STRING_LITERAL,
            MULTI_LINE_STRING_LITERAL,
            INTEGER_LITERAL,
            LONG_LITERAL,
            HEX_LITERAL,
            BIN_LITERAL,
            REAL_LITERAL,
            BOOLEAN_LITERAL,
            CHARACTER_LITERAL,
            NULL_LITERAL,
            SIMPLE_IDENTIFIER
        )

        /** All statement node kinds */
        private val STATEMENTS = setOf(
            FOR_STATEMENT,
            WHILE_STATEMENT,
            DO_WHILE_STATEMENT,
            ASSIGNMENT,
            AUGMENTED_ASSIGNMENT
        )

        /** All type node kinds */
        private val TYPES = setOf(
            USER_TYPE,
            SIMPLE_USER_TYPE,
            NULLABLE_TYPE,
            PARENTHESIZED_TYPE,
            FUNCTION_TYPE,
            DYNAMIC_TYPE,
            RECEIVER_TYPE
        )

        /** All literal node kinds */
        private val LITERALS = setOf(
            INTEGER_LITERAL,
            LONG_LITERAL,
            HEX_LITERAL,
            BIN_LITERAL,
            REAL_LITERAL,
            BOOLEAN_LITERAL,
            CHARACTER_LITERAL,
            NULL_LITERAL,
            STRING_LITERAL,
            LINE_STRING_LITERAL,
            MULTI_LINE_STRING_LITERAL
        )

        /** All modifier node kinds */
        private val MODIFIER_KINDS: Set<SyntaxKind> = setOf(
            SyntaxKind.MODIFIERS,
            MODIFIER,
            CLASS_MODIFIER,
            MEMBER_MODIFIER,
            VISIBILITY_MODIFIER,
            VARIANCE_MODIFIER,
            TYPE_PARAMETER_MODIFIER,
            FUNCTION_MODIFIER,
            PROPERTY_MODIFIER,
            INHERITANCE_MODIFIER,
            PARAMETER_MODIFIER,
            PLATFORM_MODIFIER
        )
    }
}
