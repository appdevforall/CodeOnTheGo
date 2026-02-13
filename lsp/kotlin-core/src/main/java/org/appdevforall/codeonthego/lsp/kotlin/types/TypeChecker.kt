package org.appdevforall.codeonthego.lsp.kotlin.types

/**
 * Checks subtype relationships and type compatibility.
 *
 * Implements Kotlin's subtyping rules:
 * - Nothing is a subtype of all types
 * - All types are subtypes of Any?
 * - T <: T? (non-nullable is subtype of nullable)
 * - Variance affects generic subtyping
 *
 * ## Subtyping Rules
 *
 * ```
 * Nothing <: T <: Any?
 * T <: T?
 * List<Cat> <: List<out Animal>  (covariance)
 * (Animal) -> Unit <: (Cat) -> Unit  (contravariance in params)
 * () -> Cat <: () -> Animal  (covariance in return)
 * ```
 *
 * @see KotlinType
 * @see TypeHierarchy
 */
class TypeChecker(
    private val hierarchy: TypeHierarchy = TypeHierarchy.DEFAULT
) {
    /**
     * Checks if [subtype] is a subtype of [supertype].
     *
     * @param subtype The potential subtype
     * @param supertype The potential supertype
     * @return true if subtype <: supertype
     */
    fun isSubtypeOf(subtype: KotlinType, supertype: KotlinType): Boolean {
        if (subtype == supertype) return true

        if (subtype.hasError || supertype.hasError) return true

        if (subtype.isNothing) return true

        if (supertype is ClassType && supertype.fqName == "kotlin.Any") {
            return supertype.isNullable || !subtype.isNullable
        }

        if (!supertype.isNullable && subtype.isNullable) {
            return false
        }

        return when {
            subtype is ClassType && supertype is ClassType ->
                isClassSubtype(subtype, supertype)

            subtype is PrimitiveType && supertype is PrimitiveType ->
                isPrimitiveSubtype(subtype, supertype)

            subtype is PrimitiveType && supertype is ClassType ->
                isPrimitiveToClassSubtype(subtype, supertype)

            subtype is FunctionType && supertype is FunctionType ->
                isFunctionSubtype(subtype, supertype)

            subtype is TypeParameter && supertype is TypeParameter ->
                isTypeParameterSubtype(subtype, supertype)

            subtype is TypeParameter ->
                isTypeParameterSubtypeOf(subtype, supertype)

            supertype is TypeParameter ->
                isSubtypeOfTypeParameter(subtype, supertype)

            else -> false
        }
    }

    /**
     * Checks if two types are equivalent (mutual subtypes).
     */
    fun areEquivalent(type1: KotlinType, type2: KotlinType): Boolean {
        return isSubtypeOf(type1, type2) && isSubtypeOf(type2, type1)
    }

    /**
     * Checks if a type is assignable to a target type.
     *
     * This is slightly more permissive than subtyping for practical use.
     */
    fun isAssignableTo(source: KotlinType, target: KotlinType): Boolean {
        return isSubtypeOf(source, target)
    }

    /**
     * Finds the common supertype of two types.
     *
     * Used for inferring result types of if/when expressions.
     */
    fun commonSupertype(type1: KotlinType, type2: KotlinType): KotlinType {
        if (isSubtypeOf(type1, type2)) return type2
        if (isSubtypeOf(type2, type1)) return type1

        if (type1.isNothing) return type2
        if (type2.isNothing) return type1

        val nullable = type1.isNullable || type2.isNullable

        return when {
            type1 is ClassType && type2 is ClassType -> {
                val common = findCommonSuperclass(type1, type2)
                if (nullable) common.nullable() else common
            }
            type1 is PrimitiveType && type2 is PrimitiveType -> {
                val common = commonPrimitiveSupertype(type1, type2)
                if (nullable) common.nullable() else common
            }
            else -> {
                if (nullable) ClassType.ANY_NULLABLE else ClassType.ANY
            }
        }
    }

    /**
     * Finds the common supertype of multiple types.
     */
    fun commonSupertype(types: List<KotlinType>): KotlinType {
        if (types.isEmpty()) return ClassType.NOTHING
        return types.reduce { acc, type -> commonSupertype(acc, type) }
    }

    private fun isClassSubtype(subtype: ClassType, supertype: ClassType): Boolean {
        if (subtype.fqName == supertype.fqName) {
            return checkTypeArguments(subtype.typeArguments, supertype.typeArguments)
        }

        val supertypes = hierarchy.getSupertypes(subtype.fqName)
        for (st in supertypes) {
            if (st.fqName == supertype.fqName) {
                return checkTypeArguments(st.typeArguments, supertype.typeArguments)
            }
            if (isClassSubtype(st, supertype)) {
                return true
            }
        }

        return false
    }

    private fun checkTypeArguments(
        subArgs: List<TypeArgument>,
        superArgs: List<TypeArgument>
    ): Boolean {
        if (subArgs.size != superArgs.size) return false

        return subArgs.zip(superArgs).all { (subArg, superArg) ->
            checkTypeArgument(subArg, superArg)
        }
    }

    private fun checkTypeArgument(subArg: TypeArgument, superArg: TypeArgument): Boolean {
        if (superArg.isStarProjection) return true
        if (subArg.isStarProjection) return false

        val subType = subArg.type!!
        val superType = superArg.type!!

        return when (superArg.variance) {
            TypeVariance.INVARIANT -> when (subArg.variance) {
                TypeVariance.INVARIANT -> areEquivalent(subType, superType)
                else -> false
            }
            TypeVariance.OUT -> isSubtypeOf(subType, superType)
            TypeVariance.IN -> isSubtypeOf(superType, subType)
        }
    }

    private fun isPrimitiveSubtype(subtype: PrimitiveType, supertype: PrimitiveType): Boolean {
        if (subtype.kind == supertype.kind) return true

        return when (subtype.kind) {
            PrimitiveKind.BYTE -> supertype.kind in listOf(
                PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG,
                PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE
            )
            PrimitiveKind.SHORT -> supertype.kind in listOf(
                PrimitiveKind.INT, PrimitiveKind.LONG,
                PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE
            )
            PrimitiveKind.INT -> supertype.kind in listOf(
                PrimitiveKind.LONG, PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE
            )
            PrimitiveKind.LONG -> supertype.kind in listOf(
                PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE
            )
            PrimitiveKind.FLOAT -> supertype.kind == PrimitiveKind.DOUBLE
            else -> false
        }
    }

    private fun isPrimitiveToClassSubtype(subtype: PrimitiveType, supertype: ClassType): Boolean {
        if (supertype.fqName == "kotlin.Any") return true

        val boxedFqName = when (subtype.kind) {
            PrimitiveKind.INT -> "kotlin.Int"
            PrimitiveKind.LONG -> "kotlin.Long"
            PrimitiveKind.FLOAT -> "kotlin.Float"
            PrimitiveKind.DOUBLE -> "kotlin.Double"
            PrimitiveKind.BOOLEAN -> "kotlin.Boolean"
            PrimitiveKind.CHAR -> "kotlin.Char"
            PrimitiveKind.BYTE -> "kotlin.Byte"
            PrimitiveKind.SHORT -> "kotlin.Short"
        }

        if (supertype.fqName == boxedFqName) return true

        val boxedType = ClassType(boxedFqName, isNullable = subtype.isNullable)
        return isClassSubtype(boxedType, supertype)
    }

    private fun isFunctionSubtype(subtype: FunctionType, supertype: FunctionType): Boolean {
        if (subtype.arity != supertype.arity) return false

        if (subtype.isSuspend && !supertype.isSuspend) return false

        if (!isSubtypeOf(subtype.returnType, supertype.returnType)) return false

        for ((subParam, superParam) in subtype.parameterTypes.zip(supertype.parameterTypes)) {
            if (!isSubtypeOf(superParam, subParam)) return false
        }

        if (supertype.receiverType != null) {
            if (subtype.receiverType == null) return false
            if (!isSubtypeOf(supertype.receiverType, subtype.receiverType)) return false
        }

        return true
    }

    private fun isTypeParameterSubtype(
        subtype: TypeParameter,
        supertype: TypeParameter
    ): Boolean {
        if (subtype.name == supertype.name && subtype.symbol == supertype.symbol) {
            return true
        }

        for (bound in subtype.bounds) {
            if (bound is TypeParameter && isTypeParameterSubtype(bound, supertype)) {
                return true
            }
        }

        return false
    }

    private fun isTypeParameterSubtypeOf(
        subtype: TypeParameter,
        supertype: KotlinType
    ): Boolean {
        for (bound in subtype.bounds) {
            if (isSubtypeOf(bound, supertype)) {
                return true
            }
        }

        return isSubtypeOf(ClassType.ANY_NULLABLE, supertype)
    }

    private fun isSubtypeOfTypeParameter(
        subtype: KotlinType,
        supertype: TypeParameter
    ): Boolean {
        if (subtype.isNothing) return true

        for (bound in supertype.bounds) {
            if (!isSubtypeOf(subtype, bound)) {
                return false
            }
        }
        return true
    }

    private fun findCommonSuperclass(type1: ClassType, type2: ClassType): ClassType {
        val supertypes1 = collectAllSupertypes(type1)
        val supertypes2 = collectAllSupertypes(type2)

        for (st in supertypes1) {
            if (st.fqName in supertypes2.map { it.fqName }) {
                return st
            }
        }

        return ClassType.ANY
    }

    private fun collectAllSupertypes(type: ClassType): List<ClassType> {
        val result = mutableListOf(type)
        val toProcess = ArrayDeque<ClassType>()
        toProcess.add(type)

        while (toProcess.isNotEmpty()) {
            val current = toProcess.removeFirst()
            val supertypes = hierarchy.getSupertypes(current.fqName)
            for (st in supertypes) {
                if (st !in result) {
                    result.add(st)
                    toProcess.add(st)
                }
            }
        }

        return result
    }

    private fun commonPrimitiveSupertype(
        type1: PrimitiveType,
        type2: PrimitiveType
    ): PrimitiveType {
        if (type1.kind == type2.kind) return type1

        if (type1.kind.isNumeric && type2.kind.isNumeric) {
            val wider = if (type1.kind.bitWidth >= type2.kind.bitWidth) type1.kind else type2.kind
            val isFloating = type1.kind.isFloatingPoint || type2.kind.isFloatingPoint

            return when {
                isFloating && wider.bitWidth <= 32 -> PrimitiveType.FLOAT
                isFloating -> PrimitiveType.DOUBLE
                else -> PrimitiveType(wider)
            }
        }

        return PrimitiveType.INT
    }

    companion object {
        val DEFAULT = TypeChecker()
    }
}

/**
 * Type hierarchy for determining supertypes of classes.
 */
class TypeHierarchy {
    private val supertypes = mutableMapOf<String, List<ClassType>>()

    init {
        registerBuiltins()
    }

    fun getSupertypes(fqName: String): List<ClassType> {
        return supertypes[fqName] ?: emptyList()
    }

    fun registerSupertypes(fqName: String, types: List<ClassType>) {
        supertypes[fqName] = types
    }

    private fun registerBuiltins() {
        supertypes["kotlin.String"] = listOf(
            ClassType.COMPARABLE.withArguments(ClassType.STRING),
            ClassType.CHAR_SEQUENCE
        )

        supertypes["kotlin.Int"] = listOf(
            ClassType.COMPARABLE.withArguments(PrimitiveType.INT.toClassType()),
            ClassType("kotlin.Number")
        )
        supertypes["kotlin.Long"] = listOf(
            ClassType.COMPARABLE.withArguments(PrimitiveType.LONG.toClassType()),
            ClassType("kotlin.Number")
        )
        supertypes["kotlin.Float"] = listOf(
            ClassType.COMPARABLE.withArguments(PrimitiveType.FLOAT.toClassType()),
            ClassType("kotlin.Number")
        )
        supertypes["kotlin.Double"] = listOf(
            ClassType.COMPARABLE.withArguments(PrimitiveType.DOUBLE.toClassType()),
            ClassType("kotlin.Number")
        )
        supertypes["kotlin.Short"] = listOf(
            ClassType.COMPARABLE.withArguments(PrimitiveType.SHORT.toClassType()),
            ClassType("kotlin.Number")
        )
        supertypes["kotlin.Byte"] = listOf(
            ClassType.COMPARABLE.withArguments(PrimitiveType.BYTE.toClassType()),
            ClassType("kotlin.Number")
        )
        supertypes["kotlin.Char"] = listOf(
            ClassType.COMPARABLE.withArguments(PrimitiveType.CHAR.toClassType())
        )
        supertypes["kotlin.Boolean"] = listOf(
            ClassType.COMPARABLE.withArguments(PrimitiveType.BOOLEAN.toClassType())
        )

        supertypes["kotlin.Number"] = listOf(ClassType.ANY)

        supertypes["kotlin.collections.List"] = listOf(ClassType.COLLECTION)
        supertypes["kotlin.collections.Set"] = listOf(ClassType.COLLECTION)
        supertypes["kotlin.collections.Collection"] = listOf(ClassType.ITERABLE)
        supertypes["kotlin.collections.Iterable"] = listOf(ClassType.ANY)
        supertypes["kotlin.collections.Map"] = listOf(ClassType.ANY)

        supertypes["kotlin.collections.MutableList"] = listOf(
            ClassType.LIST,
            ClassType("kotlin.collections.MutableCollection")
        )
        supertypes["kotlin.collections.MutableSet"] = listOf(
            ClassType.SET,
            ClassType("kotlin.collections.MutableCollection")
        )
        supertypes["kotlin.collections.MutableCollection"] = listOf(
            ClassType.COLLECTION,
            ClassType("kotlin.collections.MutableIterable")
        )
        supertypes["kotlin.collections.MutableIterable"] = listOf(ClassType.ITERABLE)

        supertypes["kotlin.Throwable"] = listOf(ClassType.ANY)
        supertypes["kotlin.Exception"] = listOf(ClassType.THROWABLE)
        supertypes["kotlin.RuntimeException"] = listOf(ClassType.EXCEPTION)
        supertypes["kotlin.IllegalArgumentException"] = listOf(ClassType("kotlin.RuntimeException"))
        supertypes["kotlin.IllegalStateException"] = listOf(ClassType("kotlin.RuntimeException"))
        supertypes["kotlin.NullPointerException"] = listOf(ClassType("kotlin.RuntimeException"))
    }

    companion object {
        val DEFAULT = TypeHierarchy()
    }
}

/**
 * Extension to convert PrimitiveType to its boxed ClassType.
 */
fun PrimitiveType.toClassType(): ClassType {
    return ClassType(fqName, isNullable = isNullable)
}
