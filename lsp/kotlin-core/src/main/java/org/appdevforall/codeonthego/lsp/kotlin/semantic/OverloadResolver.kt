package org.appdevforall.codeonthego.lsp.kotlin.semantic

import org.appdevforall.codeonthego.lsp.kotlin.symbol.FunctionSymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.ParameterSymbol
import org.appdevforall.codeonthego.lsp.kotlin.symbol.Scope
import org.appdevforall.codeonthego.lsp.kotlin.types.ClassType
import org.appdevforall.codeonthego.lsp.kotlin.types.KotlinType
import org.appdevforall.codeonthego.lsp.kotlin.types.PrimitiveType
import org.appdevforall.codeonthego.lsp.kotlin.types.TypeChecker

/**
 * Resolves function overloads to select the most specific applicable candidate.
 *
 * OverloadResolver implements Kotlin's overload resolution algorithm:
 * 1. Filter to applicable candidates
 * 2. Rank by specificity
 * 3. Select the most specific candidate
 *
 * ## Applicability
 *
 * A function is applicable if:
 * - Number of arguments matches parameters (considering defaults and varargs)
 * - Each argument type is assignable to the parameter type
 * - Named arguments match parameter names
 *
 * ## Specificity
 *
 * Given two applicable candidates, one is more specific if:
 * - Its parameter types are subtypes of the other's parameter types
 * - It has fewer default parameters used
 * - It's a member (not extension) when both apply
 *
 * ## Usage
 *
 * ```kotlin
 * val resolver = OverloadResolver(context)
 * val result = resolver.resolve(candidates, arguments)
 * when (result) {
 *     is OverloadResolutionResult.Success -> result.selected
 *     is OverloadResolutionResult.Ambiguity -> // handle multiple candidates
 *     is OverloadResolutionResult.NoApplicable -> // handle no match
 * }
 * ```
 */
class OverloadResolver(
    private val context: AnalysisContext
) {
    private val typeChecker: TypeChecker = context.typeChecker

    /**
     * Result of overload resolution.
     */
    sealed class OverloadResolutionResult {
        /**
         * Successfully resolved to a single function.
         */
        data class Success(
            val selected: FunctionSymbol,
            val parameterMapping: ParameterMapping
        ) : OverloadResolutionResult()

        /**
         * Multiple candidates are equally specific.
         */
        data class Ambiguity(
            val candidates: List<FunctionSymbol>
        ) : OverloadResolutionResult()

        /**
         * No candidates are applicable.
         */
        data class NoApplicable(
            val candidates: List<FunctionSymbol>,
            val diagnostics: List<ApplicabilityDiagnostic>
        ) : OverloadResolutionResult()

        val isSuccess: Boolean get() = this is Success
    }

    /**
     * Mapping of arguments to parameters after resolution.
     */
    data class ParameterMapping(
        val positionalMappings: Map<Int, Int>,
        val namedMappings: Map<String, Int>,
        val defaultsUsed: Set<Int>,
        val varargElements: List<Int>
    )

    /**
     * Diagnostic for why a candidate is not applicable.
     */
    data class ApplicabilityDiagnostic(
        val candidate: FunctionSymbol,
        val reason: ApplicabilityFailure
    )

    /**
     * Reason for applicability failure.
     */
    sealed class ApplicabilityFailure {
        data class TooFewArguments(val required: Int, val provided: Int) : ApplicabilityFailure()
        data class TooManyArguments(val max: Int, val provided: Int) : ApplicabilityFailure()
        data class TypeMismatch(val paramIndex: Int, val expected: KotlinType, val actual: KotlinType) : ApplicabilityFailure()
        data class NamedParameterNotFound(val name: String) : ApplicabilityFailure()
        data class DuplicateNamedArgument(val name: String) : ApplicabilityFailure()
        data class PositionalAfterNamed(val argIndex: Int) : ApplicabilityFailure()
    }

    /**
     * Argument in a function call.
     */
    data class CallArgument(
        val type: KotlinType,
        val name: String? = null,
        val isSpread: Boolean = false
    )

    /**
     * Resolves overloads for a function call.
     *
     * @param candidates List of candidate functions with the same name
     * @param arguments The call arguments with types
     * @param expectedReturnType Optional expected return type for disambiguation
     * @param scope The current scope
     * @return Resolution result
     */
    fun resolve(
        candidates: List<FunctionSymbol>,
        arguments: List<CallArgument>,
        expectedReturnType: KotlinType? = null,
        scope: Scope? = null
    ): OverloadResolutionResult {
        if (candidates.isEmpty()) {
            return OverloadResolutionResult.NoApplicable(emptyList(), emptyList())
        }

        if (candidates.size == 1) {
            val candidate = candidates.first()
            val applicability = checkApplicability(candidate, arguments, scope)
            return when (applicability) {
                is ApplicabilityResult.Applicable -> {
                    OverloadResolutionResult.Success(candidate, applicability.mapping)
                }
                is ApplicabilityResult.NotApplicable -> {
                    OverloadResolutionResult.NoApplicable(
                        candidates,
                        listOf(ApplicabilityDiagnostic(candidate, applicability.reason))
                    )
                }
            }
        }

        val applicableCandidates = mutableListOf<Pair<FunctionSymbol, ParameterMapping>>()
        val diagnostics = mutableListOf<ApplicabilityDiagnostic>()

        for (candidate in candidates) {
            val applicability = checkApplicability(candidate, arguments, scope)
            when (applicability) {
                is ApplicabilityResult.Applicable -> {
                    applicableCandidates.add(candidate to applicability.mapping)
                }
                is ApplicabilityResult.NotApplicable -> {
                    diagnostics.add(ApplicabilityDiagnostic(candidate, applicability.reason))
                }
            }
        }

        if (applicableCandidates.isEmpty()) {
            return OverloadResolutionResult.NoApplicable(candidates, diagnostics)
        }

        if (applicableCandidates.size == 1) {
            val (selected, mapping) = applicableCandidates.first()
            return OverloadResolutionResult.Success(selected, mapping)
        }

        val ranked = rankCandidates(applicableCandidates, arguments, expectedReturnType, scope)

        return when {
            ranked.size == 1 -> {
                val (selected, mapping) = ranked.first()
                OverloadResolutionResult.Success(selected, mapping)
            }
            ranked.all { (f, _) -> isMoreSpecific(ranked.first().first, f, arguments, scope) } -> {
                val (selected, mapping) = ranked.first()
                OverloadResolutionResult.Success(selected, mapping)
            }
            else -> {
                OverloadResolutionResult.Ambiguity(ranked.map { it.first })
            }
        }
    }

    private sealed class ApplicabilityResult {
        data class Applicable(val mapping: ParameterMapping) : ApplicabilityResult()
        data class NotApplicable(val reason: ApplicabilityFailure) : ApplicabilityResult()
    }

    private fun checkApplicability(
        function: FunctionSymbol,
        arguments: List<CallArgument>,
        scope: Scope?
    ): ApplicabilityResult {
        val parameters = function.parameters
        val positionalMappings = mutableMapOf<Int, Int>()
        val namedMappings = mutableMapOf<String, Int>()
        val usedParams = mutableSetOf<Int>()
        val varargElements = mutableListOf<Int>()

        var positionalIndex = 0
        var seenNamed = false

        for ((argIndex, argument) in arguments.withIndex()) {
            if (argument.name != null) {
                seenNamed = true
                val paramIndex = parameters.indexOfFirst { it.name == argument.name }
                if (paramIndex == -1) {
                    return ApplicabilityResult.NotApplicable(
                        ApplicabilityFailure.NamedParameterNotFound(argument.name)
                    )
                }
                if (paramIndex in usedParams) {
                    return ApplicabilityResult.NotApplicable(
                        ApplicabilityFailure.DuplicateNamedArgument(argument.name)
                    )
                }

                val param = parameters[paramIndex]
                val paramType = resolveParamType(param, function, scope)
                if (!isArgumentCompatible(argument, paramType)) {
                    return ApplicabilityResult.NotApplicable(
                        ApplicabilityFailure.TypeMismatch(paramIndex, paramType, argument.type)
                    )
                }

                namedMappings[argument.name] = argIndex
                usedParams.add(paramIndex)
            } else {
                if (seenNamed) {
                    return ApplicabilityResult.NotApplicable(
                        ApplicabilityFailure.PositionalAfterNamed(argIndex)
                    )
                }

                while (positionalIndex in usedParams) {
                    positionalIndex++
                }

                val param = parameters.getOrNull(positionalIndex)

                if (param == null) {
                    val varargParam = parameters.find { it.isVararg }
                    if (varargParam != null) {
                        val varargIndex = parameters.indexOf(varargParam)
                        val elementType = resolveVarargElementType(varargParam, function, scope)
                        if (!isArgumentCompatible(argument, elementType)) {
                            return ApplicabilityResult.NotApplicable(
                                ApplicabilityFailure.TypeMismatch(varargIndex, elementType, argument.type)
                            )
                        }
                        varargElements.add(argIndex)
                        continue
                    }

                    return ApplicabilityResult.NotApplicable(
                        ApplicabilityFailure.TooManyArguments(parameters.size, arguments.size)
                    )
                }

                val paramType = if (param.isVararg && !argument.isSpread) {
                    resolveVarargElementType(param, function, scope)
                } else {
                    resolveParamType(param, function, scope)
                }

                if (!isArgumentCompatible(argument, paramType)) {
                    return ApplicabilityResult.NotApplicable(
                        ApplicabilityFailure.TypeMismatch(positionalIndex, paramType, argument.type)
                    )
                }

                if (param.isVararg) {
                    varargElements.add(argIndex)
                } else {
                    positionalMappings[positionalIndex] = argIndex
                    usedParams.add(positionalIndex)
                    positionalIndex++
                }
            }
        }

        val defaultsUsed = mutableSetOf<Int>()
        for ((paramIndex, param) in parameters.withIndex()) {
            if (paramIndex !in usedParams && !param.isVararg) {
                if (!param.hasDefaultValue) {
                    return ApplicabilityResult.NotApplicable(
                        ApplicabilityFailure.TooFewArguments(
                            function.requiredParameterCount,
                            arguments.size
                        )
                    )
                }
                defaultsUsed.add(paramIndex)
            }
        }

        return ApplicabilityResult.Applicable(
            ParameterMapping(
                positionalMappings = positionalMappings,
                namedMappings = namedMappings,
                defaultsUsed = defaultsUsed,
                varargElements = varargElements
            )
        )
    }

    private fun resolveParamType(
        param: ParameterSymbol,
        function: FunctionSymbol,
        scope: Scope?
    ): KotlinType {
        return param.type?.let {
            context.typeResolver.resolve(it, function.containingScope ?: scope)
        } ?: ClassType.ANY
    }

    private fun resolveVarargElementType(
        param: ParameterSymbol,
        function: FunctionSymbol,
        scope: Scope?
    ): KotlinType {
        val arrayType = resolveParamType(param, function, scope)
        return when {
            arrayType is ClassType && arrayType.fqName == "kotlin.Array" -> {
                arrayType.typeArguments.firstOrNull()?.type ?: ClassType.ANY
            }
            arrayType is ClassType && arrayType.fqName == "kotlin.IntArray" -> {
                PrimitiveType.INT
            }
            arrayType is ClassType && arrayType.fqName == "kotlin.LongArray" -> {
                PrimitiveType.LONG
            }
            else -> ClassType.ANY
        }
    }

    private fun isArgumentCompatible(argument: CallArgument, paramType: KotlinType): Boolean {
        return typeChecker.isSubtypeOf(argument.type, paramType)
    }

    private fun rankCandidates(
        candidates: List<Pair<FunctionSymbol, ParameterMapping>>,
        arguments: List<CallArgument>,
        expectedReturnType: KotlinType?,
        scope: Scope?
    ): List<Pair<FunctionSymbol, ParameterMapping>> {
        return candidates.sortedWith { (f1, m1), (f2, m2) ->
            val specificityCompare = compareSpecificity(f1, f2, arguments, scope)
            if (specificityCompare != 0) {
                return@sortedWith -specificityCompare
            }

            val defaultsCompare = m1.defaultsUsed.size.compareTo(m2.defaultsUsed.size)
            if (defaultsCompare != 0) {
                return@sortedWith defaultsCompare
            }

            if (expectedReturnType != null) {
                val r1 = f1.returnType?.let { context.typeResolver.resolve(it, f1.containingScope ?: scope) }
                val r2 = f2.returnType?.let { context.typeResolver.resolve(it, f2.containingScope ?: scope) }

                val r1Match = r1?.let { typeChecker.isSubtypeOf(it, expectedReturnType) } ?: false
                val r2Match = r2?.let { typeChecker.isSubtypeOf(it, expectedReturnType) } ?: false

                if (r1Match && !r2Match) return@sortedWith -1
                if (r2Match && !r1Match) return@sortedWith 1
            }

            val isExtension1 = f1.isExtension
            val isExtension2 = f2.isExtension
            if (!isExtension1 && isExtension2) return@sortedWith -1
            if (isExtension1 && !isExtension2) return@sortedWith 1

            0
        }
    }

    private fun compareSpecificity(
        f1: FunctionSymbol,
        f2: FunctionSymbol,
        arguments: List<CallArgument>,
        scope: Scope?
    ): Int {
        var f1MoreSpecific = 0
        var f2MoreSpecific = 0

        val minParams = minOf(f1.parameters.size, f2.parameters.size)
        for (i in 0 until minParams) {
            val p1Type = resolveParamType(f1.parameters[i], f1, scope)
            val p2Type = resolveParamType(f2.parameters[i], f2, scope)

            if (typeChecker.isSubtypeOf(p1Type, p2Type) && !typeChecker.isSubtypeOf(p2Type, p1Type)) {
                f1MoreSpecific++
            } else if (typeChecker.isSubtypeOf(p2Type, p1Type) && !typeChecker.isSubtypeOf(p1Type, p2Type)) {
                f2MoreSpecific++
            }
        }

        return when {
            f1MoreSpecific > 0 && f2MoreSpecific == 0 -> 1
            f2MoreSpecific > 0 && f1MoreSpecific == 0 -> -1
            else -> 0
        }
    }

    private fun isMoreSpecific(
        f1: FunctionSymbol,
        f2: FunctionSymbol,
        arguments: List<CallArgument>,
        scope: Scope?
    ): Boolean {
        if (f1 === f2) return true
        return compareSpecificity(f1, f2, arguments, scope) >= 0
    }

    /**
     * Generates a descriptive error message for failed overload resolution.
     */
    fun formatError(result: OverloadResolutionResult): String {
        return when (result) {
            is OverloadResolutionResult.Success -> "Resolution successful"
            is OverloadResolutionResult.Ambiguity -> {
                val candidateList = result.candidates.joinToString("\n  ") { it.toString() }
                "Overload resolution ambiguity between:\n  $candidateList"
            }
            is OverloadResolutionResult.NoApplicable -> {
                if (result.diagnostics.isEmpty()) {
                    "No applicable candidates found"
                } else {
                    val details = result.diagnostics.joinToString("\n  ") { diag ->
                        "${diag.candidate.name}: ${formatFailure(diag.reason)}"
                    }
                    "None of the following candidates are applicable:\n  $details"
                }
            }
        }
    }

    private fun formatFailure(failure: ApplicabilityFailure): String {
        return when (failure) {
            is ApplicabilityFailure.TooFewArguments -> {
                "Too few arguments: expected ${failure.required}, got ${failure.provided}"
            }
            is ApplicabilityFailure.TooManyArguments -> {
                "Too many arguments: expected at most ${failure.max}, got ${failure.provided}"
            }
            is ApplicabilityFailure.TypeMismatch -> {
                "Type mismatch at parameter ${failure.paramIndex}: expected ${failure.expected.render()}, got ${failure.actual.render()}"
            }
            is ApplicabilityFailure.NamedParameterNotFound -> {
                "No parameter named '${failure.name}'"
            }
            is ApplicabilityFailure.DuplicateNamedArgument -> {
                "Duplicate named argument '${failure.name}'"
            }
            is ApplicabilityFailure.PositionalAfterNamed -> {
                "Positional argument at index ${failure.argIndex} after named arguments"
            }
        }
    }

    companion object {
        /**
         * Creates arguments from a list of types (all positional, no names).
         */
        fun argumentsFromTypes(types: List<KotlinType>): List<CallArgument> {
            return types.map { CallArgument(it) }
        }

        /**
         * Creates a single named argument.
         */
        fun namedArgument(name: String, type: KotlinType): CallArgument {
            return CallArgument(type, name)
        }

        /**
         * Creates a spread argument.
         */
        fun spreadArgument(type: KotlinType): CallArgument {
            return CallArgument(type, isSpread = true)
        }
    }
}
