package org.appdevforall.codeonthego.computervision.domain.parser

fun interface ValueCleaner {
    fun clean(rawValue: String): String
}
