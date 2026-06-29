package org.appdevforall.codeonthego.indexing

import com.google.common.truth.Truth.assertThat
import org.appdevforall.codeonthego.indexing.api.IndexQuery
import org.appdevforall.codeonthego.indexing.api.IndexQueryBuilder
import org.appdevforall.codeonthego.indexing.api.indexQuery
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class IndexQueryBuilderTest {

    // --- Default query ---

    @Test
    fun `empty builder produces ALL-equivalent query`() {
        val q = indexQuery { }
        assertThat(q.exactMatch).isEmpty()
        assertThat(q.prefixMatch).isEmpty()
        assertThat(q.presence).isEmpty()
        assertThat(q.sourceId).isNull()
        assertThat(q.key).isNull()
        assertThat(q.limit).isEqualTo(200)
    }

    // --- eq ---

    @Test
    fun `eq adds exact match predicate`() {
        val q = indexQuery { eq("kind", "class") }
        assertThat(q.exactMatch).containsEntry("kind", "class")
    }

    @Test
    fun `multiple eq calls accumulate`() {
        val q = indexQuery {
            eq("kind", "class")
            eq("pkg", "com.example")
        }
        assertThat(q.exactMatch).containsEntry("kind", "class")
        assertThat(q.exactMatch).containsEntry("pkg", "com.example")
    }

    // --- prefix ---

    @Test
    fun `prefix adds prefix match predicate`() {
        val q = indexQuery { prefix("name", "Array") }
        assertThat(q.prefixMatch).containsEntry("name", "Array")
    }

    @Test
    fun `multiple prefix calls accumulate`() {
        val q = indexQuery {
            prefix("name", "Array")
            prefix("pkg", "java.util")
        }
        assertThat(q.prefixMatch).containsEntry("name", "Array")
        assertThat(q.prefixMatch).containsEntry("pkg", "java.util")
    }

    // --- exists / notExists ---

    @Test
    fun `exists sets presence predicate to true`() {
        val q = indexQuery { exists("receiverType") }
        assertThat(q.presence).containsEntry("receiverType", true)
    }

    @Test
    fun `notExists sets presence predicate to false`() {
        val q = indexQuery { notExists("receiverType") }
        assertThat(q.presence).containsEntry("receiverType", false)
    }

    // --- sourceId and key ---

    @Test
    fun `sourceId is set via builder property`() {
        val q = indexQuery { sourceId = "my-jar.jar" }
        assertThat(q.sourceId).isEqualTo("my-jar.jar")
    }

    @Test
    fun `key is set via builder property`() {
        val q = indexQuery { key = "com.example.Foo" }
        assertThat(q.key).isEqualTo("com.example.Foo")
    }

    // --- limit ---

    @Test
    fun `limit can be overridden`() {
        val q = indexQuery { limit = 50 }
        assertThat(q.limit).isEqualTo(50)
    }

    @Test
    fun `limit zero means unlimited`() {
        val q = indexQuery { limit = 0 }
        assertThat(q.limit).isEqualTo(0)
    }

    // --- combined predicates ---

    @Test
    fun `combined predicates all appear in built query`() {
        val q = indexQuery {
            eq("kind", "function")
            prefix("name", "get")
            exists("receiverType")
            sourceId = "rt.jar"
            limit = 10
        }
        assertThat(q.exactMatch).containsEntry("kind", "function")
        assertThat(q.prefixMatch).containsEntry("name", "get")
        assertThat(q.presence).containsEntry("receiverType", true)
        assertThat(q.sourceId).isEqualTo("rt.jar")
        assertThat(q.limit).isEqualTo(10)
    }

    // --- static factory methods ---

    @Test
    fun `IndexQuery ALL has no predicates and default limit`() {
        assertThat(IndexQuery.ALL.exactMatch).isEmpty()
        assertThat(IndexQuery.ALL.prefixMatch).isEmpty()
        assertThat(IndexQuery.ALL.sourceId).isNull()
        assertThat(IndexQuery.ALL.key).isNull()
    }

    @Test
    fun `IndexQuery byKey sets key and limit 1`() {
        val q = IndexQuery.byKey("com.example.Bar")
        assertThat(q.key).isEqualTo("com.example.Bar")
        assertThat(q.limit).isEqualTo(1)
    }

    @Test
    fun `IndexQuery bySource sets sourceId and unlimited limit`() {
        val q = IndexQuery.bySource("classes.jar")
        assertThat(q.sourceId).isEqualTo("classes.jar")
        assertThat(q.limit).isEqualTo(0)
    }

    // --- immutability of built query ---

    @Test
    fun `modifying builder after build does not affect built query`() {
        val builder = IndexQueryBuilder()
        builder.eq("kind", "class")
        val q = builder.build()

        builder.eq("kind", "function") // mutate after build
        assertThat(q.exactMatch["kind"]).isEqualTo("class")
    }

    // --- IndexQuery data class equality ---

    @Test
    fun `two queries with identical predicates are equal`() {
        val q1 = indexQuery { eq("k", "v"); limit = 5 }
        val q2 = indexQuery { eq("k", "v"); limit = 5 }
        assertThat(q1).isEqualTo(q2)
    }

    @Test
    fun `queries with different predicates are not equal`() {
        val q1 = indexQuery { eq("k", "v1") }
        val q2 = indexQuery { eq("k", "v2") }
        assertThat(q1).isNotEqualTo(q2)
    }
}
