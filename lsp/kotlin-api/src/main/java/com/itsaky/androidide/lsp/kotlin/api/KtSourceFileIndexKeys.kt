package com.itsaky.androidide.lsp.kotlin.api

import org.appdevforall.codeonthego.indexing.jvm.JvmSymbolIndex
import org.appdevforall.codeonthego.indexing.jvm.KtFileMetadataIndex
import org.appdevforall.codeonthego.indexing.service.IndexKey

/**
 * Resident-safe: registered by `KotlinLanguageServer.setupWithProject` (which must stay
 * resident) but keyed against index implementations that live in the isolated
 * `lsp:kotlin-compiler-impl` module. Deliberately not defined alongside `KtSymbolIndex` in
 * that module, even though it originally was -- these two constants themselves have zero
 * Analysis API dependencies and are needed on both sides of the DexClassLoader boundary.
 */
val KT_SOURCE_FILE_INDEX_KEY = IndexKey<JvmSymbolIndex>("kt-source-file-index")
val KT_SOURCE_FILE_META_INDEX_KEY = IndexKey<KtFileMetadataIndex>("kt-source-file-meta-index")
