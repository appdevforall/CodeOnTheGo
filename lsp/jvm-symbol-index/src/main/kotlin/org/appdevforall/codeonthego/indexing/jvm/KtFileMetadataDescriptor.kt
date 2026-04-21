package org.appdevforall.codeonthego.indexing.jvm

import org.appdevforall.codeonthego.indexing.api.IndexDescriptor
import org.appdevforall.codeonthego.indexing.api.IndexField
import org.appdevforall.codeonthego.indexing.jvm.proto.JvmSymbolProtos
import java.time.Instant

/**
 * [IndexDescriptor] for [KtFileMetadata].
 *
 * Queryable fields:
 * - `package`   : exact match, for package → file path lookups and package
 *                 existence checks used by the Kotlin LSP declaration/package
 *                 providers.
 * - `isIndexed` : exact match ("true"/"false"), to enumerate files that still
 *                 need their declaration keys populated.
 *
 * Non-queryable data (`lastModified`, `modificationStamp`, `declarationKeys`)
 * is stored opaquely in the protobuf payload blob.
 *
 * Serialization uses the `KtFileData` message from `jvm_symbol.proto`.
 * `lastModified` is stored as epoch-milliseconds; `modificationStamp` is stored
 * as-is (a raw long).
 */
object KtFileMetadataDescriptor : IndexDescriptor<KtFileMetadata> {

    const val KEY_PACKAGE = "package"
    const val KEY_IS_INDEXED = "isIndexed"

    override val name: String = "kt_file_metadata"

    override val fields: List<IndexField> = listOf(
        IndexField(name = KEY_PACKAGE),
        IndexField(name = KEY_IS_INDEXED),
    )

    override fun fieldValues(entry: KtFileMetadata): Map<String, String?> = mapOf(
        KEY_PACKAGE to entry.packageFqName,
        KEY_IS_INDEXED to entry.isIndexed.toString(),
    )

    override fun serialize(entry: KtFileMetadata): ByteArray =
        JvmSymbolProtos.KtFileData.newBuilder()
            .setPath(entry.filePath)
            .setPackageFqName(entry.packageFqName)
            .setLastModified(entry.lastModified.toEpochMilli())
            .setModificationStamp(entry.modificationStamp)
            .setIndexed(entry.isIndexed)
			.addAllSymbolKeys(entry.symbolKeys)
            .build()
            .toByteArray()

    override fun deserialize(bytes: ByteArray): KtFileMetadata {
        val proto = JvmSymbolProtos.KtFileData.parseFrom(bytes)
        return KtFileMetadata(
            filePath = proto.path,
            packageFqName = proto.packageFqName,
            lastModified = Instant.ofEpochMilli(proto.lastModified),
            modificationStamp = proto.modificationStamp,
            isIndexed = proto.indexed,
            symbolKeys = proto.symbolKeysList.toList(),
        )
    }
}
