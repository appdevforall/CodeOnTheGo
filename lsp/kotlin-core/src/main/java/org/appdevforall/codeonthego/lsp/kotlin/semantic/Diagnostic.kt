package org.appdevforall.codeonthego.lsp.kotlin.semantic

import org.appdevforall.codeonthego.lsp.kotlin.parser.TextRange

/**
 * Severity level for diagnostics.
 */
enum class DiagnosticSeverity {
    ERROR,
    WARNING,
    INFO,
    HINT;

    val isError: Boolean get() = this == ERROR
    val isWarning: Boolean get() = this == WARNING
}

/**
 * Diagnostic codes for all possible errors and warnings.
 *
 * Each code has a unique identifier and default severity.
 */
enum class DiagnosticCode(
    val id: String,
    val defaultSeverity: DiagnosticSeverity,
    val messageTemplate: String
) {
    UNRESOLVED_REFERENCE(
        "UNRESOLVED_REFERENCE",
        DiagnosticSeverity.ERROR,
        "Unresolved reference: {0}"
    ),

    TYPE_MISMATCH(
        "TYPE_MISMATCH",
        DiagnosticSeverity.ERROR,
        "Type mismatch: expected {0}, found {1}"
    ),

    UNRESOLVED_TYPE(
        "UNRESOLVED_TYPE",
        DiagnosticSeverity.ERROR,
        "Unresolved type: {0}"
    ),

    WRONG_NUMBER_OF_ARGUMENTS(
        "WRONG_NUMBER_OF_ARGUMENTS",
        DiagnosticSeverity.ERROR,
        "Wrong number of arguments: expected {0}, found {1}"
    ),

    NO_VALUE_PASSED_FOR_PARAMETER(
        "NO_VALUE_PASSED_FOR_PARAMETER",
        DiagnosticSeverity.ERROR,
        "No value passed for parameter '{0}'"
    ),

    ARGUMENT_TYPE_MISMATCH(
        "ARGUMENT_TYPE_MISMATCH",
        DiagnosticSeverity.ERROR,
        "Argument type mismatch: expected {0}, found {1}"
    ),

    NONE_APPLICABLE(
        "NONE_APPLICABLE",
        DiagnosticSeverity.ERROR,
        "None of the following functions can be called with the arguments supplied: {0}"
    ),

    OVERLOAD_RESOLUTION_AMBIGUITY(
        "OVERLOAD_RESOLUTION_AMBIGUITY",
        DiagnosticSeverity.ERROR,
        "Overload resolution ambiguity between: {0}"
    ),

    RETURN_TYPE_MISMATCH(
        "RETURN_TYPE_MISMATCH",
        DiagnosticSeverity.ERROR,
        "Return type mismatch: expected {0}, found {1}"
    ),

    RETURN_NOT_ALLOWED(
        "RETURN_NOT_ALLOWED",
        DiagnosticSeverity.ERROR,
        "'return' is not allowed here"
    ),

    UNREACHABLE_CODE(
        "UNREACHABLE_CODE",
        DiagnosticSeverity.WARNING,
        "Unreachable code"
    ),

    VARIABLE_WITH_NO_TYPE_NO_INITIALIZER(
        "VARIABLE_WITH_NO_TYPE_NO_INITIALIZER",
        DiagnosticSeverity.ERROR,
        "This variable must either have a type annotation or be initialized"
    ),

    VAL_REASSIGNMENT(
        "VAL_REASSIGNMENT",
        DiagnosticSeverity.ERROR,
        "Val cannot be reassigned"
    ),

    VAR_INITIALIZATION_REQUIRED(
        "VAR_INITIALIZATION_REQUIRED",
        DiagnosticSeverity.ERROR,
        "Property must be initialized or be abstract"
    ),

    UNINITIALIZED_VARIABLE(
        "UNINITIALIZED_VARIABLE",
        DiagnosticSeverity.ERROR,
        "Variable '{0}' must be initialized"
    ),

    NULLABLE_TYPE_MISMATCH(
        "NULLABLE_TYPE_MISMATCH",
        DiagnosticSeverity.ERROR,
        "Type mismatch: inferred type is {0} but {1} was expected"
    ),

    UNSAFE_CALL(
        "UNSAFE_CALL",
        DiagnosticSeverity.ERROR,
        "Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable receiver of type {0}"
    ),

    UNSAFE_IMPLICIT_INVOKE_CALL(
        "UNSAFE_IMPLICIT_INVOKE_CALL",
        DiagnosticSeverity.ERROR,
        "Reference has a nullable type '{0}', use explicit ?.invoke() to make a function-like call"
    ),

    USELESS_NULLABLE_CHECK(
        "USELESS_NULLABLE_CHECK",
        DiagnosticSeverity.WARNING,
        "Unnecessary safe call on a non-null receiver of type {0}"
    ),

    SENSELESS_NULL_IN_WHEN(
        "SENSELESS_NULL_IN_WHEN",
        DiagnosticSeverity.WARNING,
        "Null can not be a value of a non-null type {0}"
    ),

    ABSTRACT_MEMBER_NOT_IMPLEMENTED(
        "ABSTRACT_MEMBER_NOT_IMPLEMENTED",
        DiagnosticSeverity.ERROR,
        "Class '{0}' is not abstract and does not implement abstract member '{1}'"
    ),

    ABSTRACT_CLASS_MEMBER_NOT_IMPLEMENTED(
        "ABSTRACT_CLASS_MEMBER_NOT_IMPLEMENTED",
        DiagnosticSeverity.ERROR,
        "Class '{0}' must be declared abstract or implement abstract member '{1}'"
    ),

    CANNOT_OVERRIDE_INVISIBLE_MEMBER(
        "CANNOT_OVERRIDE_INVISIBLE_MEMBER",
        DiagnosticSeverity.ERROR,
        "Cannot override invisible member"
    ),

    NOTHING_TO_OVERRIDE(
        "NOTHING_TO_OVERRIDE",
        DiagnosticSeverity.ERROR,
        "'{0}' overrides nothing"
    ),

    MUST_BE_INITIALIZED_OR_BE_ABSTRACT(
        "MUST_BE_INITIALIZED_OR_BE_ABSTRACT",
        DiagnosticSeverity.ERROR,
        "Property '{0}' must be initialized or be abstract"
    ),

    CONFLICTING_OVERLOADS(
        "CONFLICTING_OVERLOADS",
        DiagnosticSeverity.ERROR,
        "Conflicting overloads: {0}"
    ),

    REDECLARATION(
        "REDECLARATION",
        DiagnosticSeverity.ERROR,
        "Conflicting declarations: {0}"
    ),

    NAME_SHADOWING(
        "NAME_SHADOWING",
        DiagnosticSeverity.WARNING,
        "Name '{0}' shadows a declaration from outer scope"
    ),

    UNUSED_VARIABLE(
        "UNUSED_VARIABLE",
        DiagnosticSeverity.WARNING,
        "Variable '{0}' is never used"
    ),

    UNUSED_PARAMETER(
        "UNUSED_PARAMETER",
        DiagnosticSeverity.WARNING,
        "Parameter '{0}' is never used"
    ),

    UNUSED_EXPRESSION(
        "UNUSED_EXPRESSION",
        DiagnosticSeverity.WARNING,
        "The expression is unused"
    ),

    USELESS_CAST(
        "USELESS_CAST",
        DiagnosticSeverity.WARNING,
        "No cast needed"
    ),

    UNCHECKED_CAST(
        "UNCHECKED_CAST",
        DiagnosticSeverity.WARNING,
        "Unchecked cast: {0} to {1}"
    ),

    DEPRECATION(
        "DEPRECATION",
        DiagnosticSeverity.WARNING,
        "'{0}' is deprecated. {1}"
    ),

    MISSING_WHEN_BRANCH(
        "MISSING_WHEN_BRANCH",
        DiagnosticSeverity.ERROR,
        "'when' expression must be exhaustive, add necessary {0} branches"
    ),

    UNREACHABLE_WHEN_BRANCH(
        "UNREACHABLE_WHEN_BRANCH",
        DiagnosticSeverity.WARNING,
        "This branch is unreachable because of previous branches"
    ),

    CONDITION_TYPE_MISMATCH(
        "CONDITION_TYPE_MISMATCH",
        DiagnosticSeverity.ERROR,
        "Condition must be of type Boolean, but is {0}"
    ),

    INCOMPATIBLE_TYPES(
        "INCOMPATIBLE_TYPES",
        DiagnosticSeverity.ERROR,
        "Incompatible types: {0} and {1}"
    ),

    OPERATOR_MODIFIER_REQUIRED(
        "OPERATOR_MODIFIER_REQUIRED",
        DiagnosticSeverity.ERROR,
        "'operator' modifier is required on '{0}'"
    ),

    INFIX_MODIFIER_REQUIRED(
        "INFIX_MODIFIER_REQUIRED",
        DiagnosticSeverity.ERROR,
        "'infix' modifier is required on '{0}'"
    ),

    EXTENSION_FUNCTION_SHADOWED_BY_MEMBER(
        "EXTENSION_FUNCTION_SHADOWED_BY_MEMBER",
        DiagnosticSeverity.WARNING,
        "Extension function '{0}' is shadowed by a member"
    ),

    TOO_MANY_ARGUMENTS(
        "TOO_MANY_ARGUMENTS",
        DiagnosticSeverity.ERROR,
        "Too many arguments for {0}"
    ),

    NAMED_PARAMETER_NOT_FOUND(
        "NAMED_PARAMETER_NOT_FOUND",
        DiagnosticSeverity.ERROR,
        "Cannot find a parameter with this name: {0}"
    ),

    MIXING_NAMED_AND_POSITIONED_ARGUMENTS(
        "MIXING_NAMED_AND_POSITIONED_ARGUMENTS",
        DiagnosticSeverity.ERROR,
        "Mixing named and positioned arguments is not allowed"
    ),

    SUPER_NOT_AVAILABLE(
        "SUPER_NOT_AVAILABLE",
        DiagnosticSeverity.ERROR,
        "'super' is not an expression"
    ),

    THIS_NOT_AVAILABLE(
        "THIS_NOT_AVAILABLE",
        DiagnosticSeverity.ERROR,
        "'this' is not available"
    ),

    SYNTAX_ERROR(
        "SYNTAX_ERROR",
        DiagnosticSeverity.ERROR,
        "Syntax error: {0}"
    );

    fun format(vararg args: Any): String {
        var message = messageTemplate
        args.forEachIndexed { index, arg ->
            message = message.replace("{$index}", arg.toString())
        }
        return message
    }
}

/**
 * A diagnostic message (error, warning, info, or hint).
 *
 * Diagnostics are produced during semantic analysis and represent
 * problems or suggestions in the code.
 *
 * @property code The diagnostic code identifying the issue type
 * @property message Human-readable description of the issue
 * @property severity The severity level
 * @property range The source location where the issue occurs
 * @property filePath Path to the file containing the issue
 * @property relatedInfo Additional related locations/information
 */
data class Diagnostic(
    val code: DiagnosticCode,
    val message: String,
    val severity: DiagnosticSeverity,
    val range: TextRange,
    val filePath: String = "",
    val relatedInfo: List<DiagnosticRelatedInfo> = emptyList()
) {
    val isError: Boolean get() = severity.isError
    val isWarning: Boolean get() = severity.isWarning

    override fun toString(): String = buildString {
        append(severity.name.lowercase())
        append(": ")
        append(message)
        append(" [")
        append(code.id)
        append("]")
        if (filePath.isNotEmpty()) {
            append(" at ")
            append(filePath)
            append(":")
            append(range.start.displayLine)
            append(":")
            append(range.start.displayColumn)
        }
    }

    companion object {
        fun error(
            code: DiagnosticCode,
            range: TextRange,
            vararg args: Any,
            filePath: String = ""
        ): Diagnostic = Diagnostic(
            code = code,
            message = code.format(*args),
            severity = DiagnosticSeverity.ERROR,
            range = range,
            filePath = filePath
        )

        fun warning(
            code: DiagnosticCode,
            range: TextRange,
            vararg args: Any,
            filePath: String = ""
        ): Diagnostic = Diagnostic(
            code = code,
            message = code.format(*args),
            severity = DiagnosticSeverity.WARNING,
            range = range,
            filePath = filePath
        )

        fun info(
            code: DiagnosticCode,
            range: TextRange,
            vararg args: Any,
            filePath: String = ""
        ): Diagnostic = Diagnostic(
            code = code,
            message = code.format(*args),
            severity = DiagnosticSeverity.INFO,
            range = range,
            filePath = filePath
        )

        fun hint(
            code: DiagnosticCode,
            range: TextRange,
            vararg args: Any,
            filePath: String = ""
        ): Diagnostic = Diagnostic(
            code = code,
            message = code.format(*args),
            severity = DiagnosticSeverity.HINT,
            range = range,
            filePath = filePath
        )
    }
}

/**
 * Related information for a diagnostic.
 */
data class DiagnosticRelatedInfo(
    val message: String,
    val range: TextRange,
    val filePath: String
)

/**
 * Collector for accumulating diagnostics during analysis.
 */
class DiagnosticCollector {
    private val _diagnostics = mutableListOf<Diagnostic>()

    val diagnostics: List<Diagnostic> get() = _diagnostics.toList()

    val errors: List<Diagnostic> get() = _diagnostics.filter { it.isError }

    val warnings: List<Diagnostic> get() = _diagnostics.filter { it.isWarning }

    val hasErrors: Boolean get() = _diagnostics.any { it.isError }

    val errorCount: Int get() = _diagnostics.count { it.isError }

    val warningCount: Int get() = _diagnostics.count { it.isWarning }

    fun report(diagnostic: Diagnostic) {
        _diagnostics.add(diagnostic)
    }

    fun error(code: DiagnosticCode, range: TextRange, vararg args: Any, filePath: String = "") {
        report(Diagnostic.error(code, range, *args, filePath = filePath))
    }

    fun warning(code: DiagnosticCode, range: TextRange, vararg args: Any, filePath: String = "") {
        report(Diagnostic.warning(code, range, *args, filePath = filePath))
    }

    fun info(code: DiagnosticCode, range: TextRange, vararg args: Any, filePath: String = "") {
        report(Diagnostic.info(code, range, *args, filePath = filePath))
    }

    fun hint(code: DiagnosticCode, range: TextRange, vararg args: Any, filePath: String = "") {
        report(Diagnostic.hint(code, range, *args, filePath = filePath))
    }

    fun unresolvedReference(name: String, range: TextRange, filePath: String = "") {
        error(DiagnosticCode.UNRESOLVED_REFERENCE, range, name, filePath = filePath)
    }

    fun typeMismatch(expected: String, actual: String, range: TextRange, filePath: String = "") {
        error(DiagnosticCode.TYPE_MISMATCH, range, expected, actual, filePath = filePath)
    }

    fun unresolvedType(name: String, range: TextRange, filePath: String = "") {
        error(DiagnosticCode.UNRESOLVED_TYPE, range, name, filePath = filePath)
    }

    fun clear() {
        _diagnostics.clear()
    }

    fun merge(other: DiagnosticCollector) {
        _diagnostics.addAll(other._diagnostics)
    }

    fun diagnosticsInRange(range: TextRange): List<Diagnostic> {
        return _diagnostics.filter { it.range.overlaps(range) }
    }

    fun diagnosticsAtLine(line: Int): List<Diagnostic> {
        return _diagnostics.filter { it.range.startLine == line || it.range.endLine == line }
    }
}
