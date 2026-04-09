package org.appdevforall.codeonthego.indexing.jvm

import org.appdevforall.codeonthego.indexing.api.IndexDescriptor
import org.appdevforall.codeonthego.indexing.api.IndexField
import org.appdevforall.codeonthego.indexing.jvm.proto.JvmSymbolProtos
import org.appdevforall.codeonthego.indexing.jvm.proto.JvmSymbolProtos.JvmSymbolData

/**
 * [IndexDescriptor] for [JvmSymbol].
 *
 * Queryable fields:
 * - `name`           : prefix-searchable, for completion
 * - `package`        : exact, for package-scoped queries
 * - `kind`           : exact, for filtering by CLASS/FUNCTION/etc.
 * - `receiverType`   : exact, for extension function matching
 * - `containingClass`: exact, for member lookup
 * - `language`       : exact, for Java-only or Kotlin-only queries
 *
 * Blob serialization uses Protobuf with `oneof` for type-specific data.
 */
object JvmSymbolDescriptor : IndexDescriptor<JvmSymbol> {

	const val KEY_NAME = "name"
	const val KEY_PACKAGE = "package"
	const val KEY_KIND = "kind"
	const val KEY_RECEIVER_TYPE = "receiverType"
	const val KEY_CONTAINING_CLASS = "containingClass"
	const val KEY_LANGUAGE = "language"

    override val name: String = "jvm_symbols"

    override val fields: List<IndexField> = listOf(
        IndexField(name = KEY_NAME, prefixSearchable = true),
        IndexField(name = KEY_PACKAGE),
        IndexField(name = KEY_KIND),
        IndexField(name = KEY_RECEIVER_TYPE),
        IndexField(name = KEY_CONTAINING_CLASS),
        IndexField(name = KEY_LANGUAGE),
    )

    override fun fieldValues(entry: JvmSymbol): Map<String, String?> = mapOf(
        KEY_NAME to entry.shortName,
        KEY_PACKAGE to entry.packageName,
        KEY_KIND to entry.kind.name,
        KEY_RECEIVER_TYPE to entry.receiverTypeName,
        KEY_CONTAINING_CLASS to entry.containingClassName.ifEmpty { null },
        KEY_LANGUAGE to entry.language.name,
    )

    override fun serialize(entry: JvmSymbol): ByteArray =
        toProto(entry).toByteArray()

    override fun deserialize(bytes: ByteArray): JvmSymbol =
        fromProto(JvmSymbolData.parseFrom(bytes))

    private fun toProto(s: JvmSymbol): JvmSymbolData {
        val builder = JvmSymbolData.newBuilder()
            .setName(s.name)
            .setShortName(s.shortName)
            .setPackageName(s.packageName)
            .setSourceId(s.sourceId)
            .setKind(kindToProto(s.kind))
            .setLanguage(languageToProto(s.language))
            .setVisibility(visibilityToProto(s.visibility))
            .setIsDeprecated(s.isDeprecated)

        when (val data = s.data) {
            is JvmClassInfo -> builder.setClassData(classInfoToProto(data))
            is JvmFunctionInfo -> builder.setFunctionData(functionInfoToProto(data))
            is JvmFieldInfo -> builder.setFieldData(fieldInfoToProto(data))
            is JvmEnumEntryInfo -> builder.setEnumEntryData(enumEntryToProto(data))
            is JvmTypeAliasInfo -> builder.setTypeAliasData(typeAliasToProto(data))
        }

        return builder.build()
    }

    private fun classInfoToProto(d: JvmClassInfo): JvmSymbolProtos.ClassData {
        val builder = JvmSymbolProtos.ClassData.newBuilder()
            .setContainingClassName(d.containingClassName)
            .addAllSupertypeNames(d.supertypeNames)
            .addAllTypeParameters(d.typeParameters)
            .setIsAbstract(d.isAbstract)
            .setIsFinal(d.isFinal)
            .setIsInner(d.isInner)
            .setIsStatic(d.isStatic)

        d.kotlin?.let { kd ->
            builder.setKotlin(
                JvmSymbolProtos.KotlinClassData.newBuilder()
                    .setIsData(kd.isData)
                    .setIsValue(kd.isValue)
                    .setIsSealed(kd.isSealed)
                    .setIsFunInterface(kd.isFunInterface)
                    .setIsExpect(kd.isExpect)
                    .setIsActual(kd.isActual)
                    .setIsExternal(kd.isExternal)
                    .addAllSealedSubclasses(kd.sealedSubclasses)
                    .setCompanionObjectName(kd.companionObjectName)
            )
        }

        return builder.build()
    }

    private fun functionInfoToProto(d: JvmFunctionInfo): JvmSymbolProtos.FunctionData {
        val builder = JvmSymbolProtos.FunctionData.newBuilder()
            .setContainingClassName(d.containingClassName)
            .setReturnTypeName(d.returnTypeName)
            .setReturnTypeDisplayName(d.returnTypeDisplayName)
            .setParameterCount(d.parameterCount)
            .addAllParameters(d.parameters.map { paramToProto(it) })
            .setSignatureDisplay(d.signatureDisplay)
            .addAllTypeParameters(d.typeParameters)
            .setIsStatic(d.isStatic)
            .setIsAbstract(d.isAbstract)
            .setIsFinal(d.isFinal)

        d.kotlin?.let { kd ->
            builder.setKotlin(
                JvmSymbolProtos.KotlinFunctionData.newBuilder()
                    .setReceiverTypeName(kd.receiverTypeName)
                    .setReceiverTypeDisplayName(kd.receiverTypeDisplayName)
                    .setIsSuspend(kd.isSuspend)
                    .setIsInline(kd.isInline)
                    .setIsInfix(kd.isInfix)
                    .setIsOperator(kd.isOperator)
                    .setIsTailrec(kd.isTailrec)
                    .setIsExternal(kd.isExternal)
                    .setIsExpect(kd.isExpect)
                    .setIsActual(kd.isActual)
                    .setIsReturnTypeNullable(kd.isReturnTypeNullable)
            )
        }

        return builder.build()
    }

    private fun paramToProto(p: JvmParameterInfo): JvmSymbolProtos.ParameterData =
        JvmSymbolProtos.ParameterData.newBuilder()
            .setName(p.name)
            .setTypeName(p.typeName)
            .setTypeDisplayName(p.typeDisplayName)
            .setHasDefaultValue(p.hasDefaultValue)
            .setIsCrossinline(p.isCrossinline)
            .setIsNoinline(p.isNoinline)
            .setIsVararg(p.isVararg)
            .build()

    private fun fieldInfoToProto(d: JvmFieldInfo): JvmSymbolProtos.FieldData {
        val builder = JvmSymbolProtos.FieldData.newBuilder()
            .setContainingClassName(d.containingClassName)
            .setTypeName(d.typeName)
            .setTypeDisplayName(d.typeDisplayName)
            .setIsStatic(d.isStatic)
            .setIsFinal(d.isFinal)
            .setConstantValue(d.constantValue)

        d.kotlin?.let { kd ->
            builder.setKotlin(
                JvmSymbolProtos.KotlinPropertyData.newBuilder()
                    .setReceiverTypeName(kd.receiverTypeName)
                    .setReceiverTypeDisplayName(kd.receiverTypeDisplayName)
                    .setIsConst(kd.isConst)
                    .setIsLateinit(kd.isLateinit)
                    .setHasGetter(kd.hasGetter)
                    .setHasSetter(kd.hasSetter)
                    .setIsDelegated(kd.isDelegated)
                    .setIsExpect(kd.isExpect)
                    .setIsActual(kd.isActual)
                    .setIsExternal(kd.isExternal)
                    .setIsTypeNullable(kd.isTypeNullable)
            )
        }

        return builder.build()
    }

    private fun enumEntryToProto(d: JvmEnumEntryInfo): JvmSymbolProtos.EnumEntryData =
        JvmSymbolProtos.EnumEntryData.newBuilder()
            .setContainingEnumName(d.containingClassName)
            .setOrdinal(d.ordinal)
            .build()

    private fun typeAliasToProto(d: JvmTypeAliasInfo): JvmSymbolProtos.TypeAliasData =
        JvmSymbolProtos.TypeAliasData.newBuilder()
            .setExpandedTypeName(d.expandedTypeName)
            .setExpandedTypeDisplayName(d.expandedTypeDisplayName)
            .addAllTypeParameters(d.typeParameters)
            .build()

    private fun fromProto(p: JvmSymbolData): JvmSymbol {
        val kind = kindFromProto(p.kind)
        val data = dataFromProto(p)

        val key = when {
            kind.isCallable && kind != JvmSymbolKind.PROPERTY
                    && kind != JvmSymbolKind.EXTENSION_PROPERTY
                    && kind != JvmSymbolKind.FIELD -> {
                val params = (data as? JvmFunctionInfo)
                    ?.parameters
                    ?.joinToString(",") { it.typeName }
                    ?: ""
                "${p.name}($params)"
            }
            else -> p.name
        }

        return JvmSymbol(
            key = key,
            sourceId = p.sourceId,
            name = p.name,
            shortName = p.shortName,
            packageName = p.packageName,
            kind = kind,
            language = languageFromProto(p.language),
            visibility = visibilityFromProto(p.visibility),
            isDeprecated = p.isDeprecated,
            data = data,
        )
    }

    private fun dataFromProto(p: JvmSymbolData): JvmSymbolInfo = when (p.dataCase) {
        JvmSymbolData.DataCase.CLASS_DATA -> classInfoFromProto(p.classData)
        JvmSymbolData.DataCase.FUNCTION_DATA -> functionInfoFromProto(p.functionData)
        JvmSymbolData.DataCase.FIELD_DATA -> fieldInfoFromProto(p.fieldData)
        JvmSymbolData.DataCase.ENUM_ENTRY_DATA -> enumEntryFromProto(p.enumEntryData)
        JvmSymbolData.DataCase.TYPE_ALIAS_DATA -> typeAliasFromProto(p.typeAliasData)
        else -> JvmClassInfo() // fallback
    }

    private fun classInfoFromProto(p: JvmSymbolProtos.ClassData): JvmClassInfo {
        val kotlin = if (p.hasKotlin()) {
            val kd = p.kotlin
            KotlinClassInfo(
                isData = kd.isData,
                isValue = kd.isValue,
                isSealed = kd.isSealed,
                isFunInterface = kd.isFunInterface,
                isExpect = kd.isExpect,
                isActual = kd.isActual,
                isExternal = kd.isExternal,
                sealedSubclasses = kd.sealedSubclassesList.toList(),
                companionObjectName = kd.companionObjectName,
            )
        } else null

        return JvmClassInfo(
            containingClassName = p.containingClassName,
            supertypeNames = p.supertypeNamesList.toList(),
            typeParameters = p.typeParametersList.toList(),
            isAbstract = p.isAbstract,
            isFinal = p.isFinal,
            isInner = p.isInner,
            isStatic = p.isStatic,
            kotlin = kotlin,
        )
    }

    private fun functionInfoFromProto(p: JvmSymbolProtos.FunctionData): JvmFunctionInfo {
        val kotlin = if (p.hasKotlin()) {
            val kd = p.kotlin
            KotlinFunctionInfo(
                receiverTypeName = kd.receiverTypeName,
                receiverTypeDisplayName = kd.receiverTypeDisplayName,
                isSuspend = kd.isSuspend,
                isInline = kd.isInline,
                isInfix = kd.isInfix,
                isOperator = kd.isOperator,
                isTailrec = kd.isTailrec,
                isExternal = kd.isExternal,
                isExpect = kd.isExpect,
                isActual = kd.isActual,
                isReturnTypeNullable = kd.isReturnTypeNullable,
            )
        } else null

        return JvmFunctionInfo(
            containingClassName = p.containingClassName,
            returnTypeName = p.returnTypeName,
            returnTypeDisplayName = p.returnTypeDisplayName,
            parameterCount = p.parameterCount,
            parameters = p.parametersList.map { paramFromProto(it) },
            signatureDisplay = p.signatureDisplay,
            typeParameters = p.typeParametersList.toList(),
            isStatic = p.isStatic,
            isAbstract = p.isAbstract,
            isFinal = p.isFinal,
            kotlin = kotlin,
        )
    }

    private fun paramFromProto(p: JvmSymbolProtos.ParameterData): JvmParameterInfo =
        JvmParameterInfo(
            name = p.name,
            typeName = p.typeName,
            typeDisplayName = p.typeDisplayName,
            hasDefaultValue = p.hasDefaultValue,
            isCrossinline = p.isCrossinline,
            isNoinline = p.isNoinline,
            isVararg = p.isVararg,
        )

    private fun fieldInfoFromProto(p: JvmSymbolProtos.FieldData): JvmFieldInfo {
        val kotlin = if (p.hasKotlin()) {
            val kd = p.kotlin
            KotlinPropertyInfo(
                receiverTypeName = kd.receiverTypeName,
                receiverTypeDisplayName = kd.receiverTypeDisplayName,
                isConst = kd.isConst,
                isLateinit = kd.isLateinit,
                hasGetter = kd.hasGetter,
                hasSetter = kd.hasSetter,
                isDelegated = kd.isDelegated,
                isExpect = kd.isExpect,
                isActual = kd.isActual,
                isExternal = kd.isExternal,
                isTypeNullable = kd.isTypeNullable,
            )
        } else null

        return JvmFieldInfo(
            containingClassName = p.containingClassName,
            typeName = p.typeName,
            typeDisplayName = p.typeDisplayName,
            isStatic = p.isStatic,
            isFinal = p.isFinal,
            constantValue = p.constantValue,
            kotlin = kotlin,
        )
    }

    private fun enumEntryFromProto(p: JvmSymbolProtos.EnumEntryData): JvmEnumEntryInfo =
        JvmEnumEntryInfo(
            containingClassName = p.containingEnumName,
            ordinal = p.ordinal,
        )

    private fun typeAliasFromProto(p: JvmSymbolProtos.TypeAliasData): JvmTypeAliasInfo =
        JvmTypeAliasInfo(
            expandedTypeName = p.expandedTypeName,
            expandedTypeDisplayName = p.expandedTypeDisplayName,
            typeParameters = p.typeParametersList.toList(),
        )

    private fun kindToProto(k: JvmSymbolKind) = when (k) {
        JvmSymbolKind.CLASS -> JvmSymbolProtos.JvmSymbolKind.KIND_CLASS
        JvmSymbolKind.INTERFACE -> JvmSymbolProtos.JvmSymbolKind.KIND_INTERFACE
        JvmSymbolKind.ENUM -> JvmSymbolProtos.JvmSymbolKind.KIND_ENUM
        JvmSymbolKind.ENUM_ENTRY -> JvmSymbolProtos.JvmSymbolKind.KIND_ENUM_ENTRY
        JvmSymbolKind.ANNOTATION_CLASS -> JvmSymbolProtos.JvmSymbolKind.KIND_ANNOTATION_CLASS
        JvmSymbolKind.OBJECT -> JvmSymbolProtos.JvmSymbolKind.KIND_OBJECT
        JvmSymbolKind.COMPANION_OBJECT -> JvmSymbolProtos.JvmSymbolKind.KIND_COMPANION_OBJECT
        JvmSymbolKind.DATA_CLASS -> JvmSymbolProtos.JvmSymbolKind.KIND_DATA_CLASS
        JvmSymbolKind.VALUE_CLASS -> JvmSymbolProtos.JvmSymbolKind.KIND_VALUE_CLASS
        JvmSymbolKind.SEALED_CLASS -> JvmSymbolProtos.JvmSymbolKind.KIND_SEALED_CLASS
        JvmSymbolKind.SEALED_INTERFACE -> JvmSymbolProtos.JvmSymbolKind.KIND_SEALED_INTERFACE
        JvmSymbolKind.FUNCTION -> JvmSymbolProtos.JvmSymbolKind.KIND_FUNCTION
        JvmSymbolKind.EXTENSION_FUNCTION -> JvmSymbolProtos.JvmSymbolKind.KIND_EXTENSION_FUNCTION
        JvmSymbolKind.CONSTRUCTOR -> JvmSymbolProtos.JvmSymbolKind.KIND_CONSTRUCTOR
        JvmSymbolKind.PROPERTY -> JvmSymbolProtos.JvmSymbolKind.KIND_PROPERTY
        JvmSymbolKind.EXTENSION_PROPERTY -> JvmSymbolProtos.JvmSymbolKind.KIND_EXTENSION_PROPERTY
        JvmSymbolKind.FIELD -> JvmSymbolProtos.JvmSymbolKind.KIND_FIELD
        JvmSymbolKind.TYPE_ALIAS -> JvmSymbolProtos.JvmSymbolKind.KIND_TYPE_ALIAS
    }

    private fun kindFromProto(k: JvmSymbolProtos.JvmSymbolKind) = when (k) {
        JvmSymbolProtos.JvmSymbolKind.KIND_CLASS -> JvmSymbolKind.CLASS
        JvmSymbolProtos.JvmSymbolKind.KIND_INTERFACE -> JvmSymbolKind.INTERFACE
        JvmSymbolProtos.JvmSymbolKind.KIND_ENUM -> JvmSymbolKind.ENUM
        JvmSymbolProtos.JvmSymbolKind.KIND_ENUM_ENTRY -> JvmSymbolKind.ENUM_ENTRY
        JvmSymbolProtos.JvmSymbolKind.KIND_ANNOTATION_CLASS -> JvmSymbolKind.ANNOTATION_CLASS
        JvmSymbolProtos.JvmSymbolKind.KIND_OBJECT -> JvmSymbolKind.OBJECT
        JvmSymbolProtos.JvmSymbolKind.KIND_COMPANION_OBJECT -> JvmSymbolKind.COMPANION_OBJECT
        JvmSymbolProtos.JvmSymbolKind.KIND_DATA_CLASS -> JvmSymbolKind.DATA_CLASS
        JvmSymbolProtos.JvmSymbolKind.KIND_VALUE_CLASS -> JvmSymbolKind.VALUE_CLASS
        JvmSymbolProtos.JvmSymbolKind.KIND_SEALED_CLASS -> JvmSymbolKind.SEALED_CLASS
        JvmSymbolProtos.JvmSymbolKind.KIND_SEALED_INTERFACE -> JvmSymbolKind.SEALED_INTERFACE
        JvmSymbolProtos.JvmSymbolKind.KIND_FUNCTION -> JvmSymbolKind.FUNCTION
        JvmSymbolProtos.JvmSymbolKind.KIND_EXTENSION_FUNCTION -> JvmSymbolKind.EXTENSION_FUNCTION
        JvmSymbolProtos.JvmSymbolKind.KIND_CONSTRUCTOR -> JvmSymbolKind.CONSTRUCTOR
        JvmSymbolProtos.JvmSymbolKind.KIND_PROPERTY -> JvmSymbolKind.PROPERTY
        JvmSymbolProtos.JvmSymbolKind.KIND_EXTENSION_PROPERTY -> JvmSymbolKind.EXTENSION_PROPERTY
        JvmSymbolProtos.JvmSymbolKind.KIND_FIELD -> JvmSymbolKind.FIELD
        JvmSymbolProtos.JvmSymbolKind.KIND_TYPE_ALIAS -> JvmSymbolKind.TYPE_ALIAS
        else -> JvmSymbolKind.CLASS
    }

    private fun languageToProto(l: JvmSourceLanguage) = when (l) {
        JvmSourceLanguage.JAVA -> JvmSymbolProtos.JvmSourceLanguage.LANGUAGE_JAVA
        JvmSourceLanguage.KOTLIN -> JvmSymbolProtos.JvmSourceLanguage.LANGUAGE_KOTLIN
    }

    private fun languageFromProto(l: JvmSymbolProtos.JvmSourceLanguage) = when (l) {
        JvmSymbolProtos.JvmSourceLanguage.LANGUAGE_JAVA -> JvmSourceLanguage.JAVA
        JvmSymbolProtos.JvmSourceLanguage.LANGUAGE_KOTLIN -> JvmSourceLanguage.KOTLIN
        else -> JvmSourceLanguage.JAVA
    }

    private fun visibilityToProto(v: JvmVisibility) = when (v) {
        JvmVisibility.PUBLIC -> JvmSymbolProtos.JvmVisibility.VISIBILITY_PUBLIC
        JvmVisibility.PROTECTED -> JvmSymbolProtos.JvmVisibility.VISIBILITY_PROTECTED
        JvmVisibility.INTERNAL -> JvmSymbolProtos.JvmVisibility.VISIBILITY_INTERNAL
        JvmVisibility.PRIVATE -> JvmSymbolProtos.JvmVisibility.VISIBILITY_PRIVATE
        JvmVisibility.PACKAGE_PRIVATE -> JvmSymbolProtos.JvmVisibility.VISIBILITY_PACKAGE_PRIVATE
    }

    private fun visibilityFromProto(v: JvmSymbolProtos.JvmVisibility) = when (v) {
        JvmSymbolProtos.JvmVisibility.VISIBILITY_PUBLIC -> JvmVisibility.PUBLIC
        JvmSymbolProtos.JvmVisibility.VISIBILITY_PROTECTED -> JvmVisibility.PROTECTED
        JvmSymbolProtos.JvmVisibility.VISIBILITY_INTERNAL -> JvmVisibility.INTERNAL
        JvmSymbolProtos.JvmVisibility.VISIBILITY_PRIVATE -> JvmVisibility.PRIVATE
        JvmSymbolProtos.JvmVisibility.VISIBILITY_PACKAGE_PRIVATE -> JvmVisibility.PACKAGE_PRIVATE
        else -> JvmVisibility.PUBLIC
    }
}
