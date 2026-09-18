package org.appdevforall.codeonthego.indexing.jvm

import org.appdevforall.codeonthego.indexing.service.IndexKey

val KT_SOURCE_FILE_INDEX_KEY = IndexKey<JvmSymbolIndex>("kt-source-file-index")
val KT_SOURCE_FILE_META_INDEX_KEY = IndexKey<KtFileMetadataIndex>("kt-source-file-meta-index")
