package org.appdevforall.codeonthego.indexing.jvm

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.time.Instant

@RunWith(JUnit4::class)
class KtFileMetadataTest {

    @Test
    fun `key equals filePath`() {
        val meta = KtFileMetadata("/project/src/Main.kt", "com.example", Instant.now(), 1L)
        assertThat(meta.key).isEqualTo("/project/src/Main.kt")
    }

    @Test
    fun `sourceId equals filePath`() {
        val meta = KtFileMetadata("/project/src/Main.kt", "com.example", Instant.now(), 1L)
        assertThat(meta.sourceId).isEqualTo("/project/src/Main.kt")
    }

    @Test
    fun `shouldBeSkipped is false when existing is null`() {
        val meta = KtFileMetadata("/f.kt", "p", Instant.now(), 1L)
        assertThat(KtFileMetadata.shouldBeSkipped(null, meta)).isFalse()
    }

    @Test
    fun `shouldBeSkipped is true when existing is same timestamp and stamp`() {
        val ts = Instant.ofEpochMilli(500L)
        val a = KtFileMetadata("/f.kt", "p", ts, 5L)
        val b = KtFileMetadata("/f.kt", "p", ts, 5L)
        assertThat(KtFileMetadata.shouldBeSkipped(a, b)).isTrue()
    }

    @Test
    fun `shouldBeSkipped is true when existing is newer by wall clock`() {
        val older = KtFileMetadata("/f.kt", "p", Instant.ofEpochMilli(100L), 1L)
        val newer = KtFileMetadata("/f.kt", "p", Instant.ofEpochMilli(200L), 2L)
        // Existing is newer → skip
        assertThat(KtFileMetadata.shouldBeSkipped(newer, older)).isTrue()
    }

    @Test
    fun `shouldBeSkipped is false when new file has newer wall clock`() {
        val old = KtFileMetadata("/f.kt", "p", Instant.ofEpochMilli(100L), 1L)
        val new = KtFileMetadata("/f.kt", "p", Instant.ofEpochMilli(200L), 2L)
        assertThat(KtFileMetadata.shouldBeSkipped(old, new)).isFalse()
    }

    @Test
    fun `shouldBeSkipped is false when new has higher modificationStamp (same wall clock)`() {
        val ts = Instant.ofEpochMilli(500L)
        val existing = KtFileMetadata("/f.kt", "p", ts, 1L)
        val new = KtFileMetadata("/f.kt", "p", ts, 2L)
        // same wallclock but new has higher stamp
        assertThat(KtFileMetadata.shouldBeSkipped(existing, new)).isFalse()
    }

    @Test
    fun `shouldBeSkipped is true when existing has higher modificationStamp (same wall clock)`() {
        val ts = Instant.ofEpochMilli(500L)
        val existing = KtFileMetadata("/f.kt", "p", ts, 3L)
        val new = KtFileMetadata("/f.kt", "p", ts, 2L)
        assertThat(KtFileMetadata.shouldBeSkipped(existing, new)).isTrue()
    }

    @Test
    fun `shouldBeSkipped handles zero modificationStamp symmetrically`() {
        val ts = Instant.ofEpochMilli(100L)
        val a = KtFileMetadata("/f.kt", "p", ts, 0L)
        val b = KtFileMetadata("/f.kt", "p", ts, 0L)
        // Both have stamp 0 — treated as "no stamp info available"
        assertThat(KtFileMetadata.shouldBeSkipped(a, b)).isTrue()
    }
}

@RunWith(JUnit4::class)
class KtFileMetadataDescriptorTest {

    private fun roundtrip(meta: KtFileMetadata): KtFileMetadata =
        KtFileMetadataDescriptor.deserialize(KtFileMetadataDescriptor.serialize(meta))

    @Test
    fun `serialize and deserialize preserves all fields`() {
        val meta = KtFileMetadata(
            filePath = "/project/src/Main.kt",
            packageFqName = "com.example.main",
            lastModified = Instant.ofEpochMilli(1_234_567_890L),
            modificationStamp = 42L,
            isIndexed = true,
            symbolKeys = listOf("com.example.main.Foo", "com.example.main.Bar"),
        )
        val restored = roundtrip(meta)
        assertThat(restored.filePath).isEqualTo(meta.filePath)
        assertThat(restored.packageFqName).isEqualTo(meta.packageFqName)
        assertThat(restored.lastModified).isEqualTo(meta.lastModified)
        assertThat(restored.modificationStamp).isEqualTo(meta.modificationStamp)
        assertThat(restored.isIndexed).isTrue()
        assertThat(restored.symbolKeys).containsExactlyElementsIn(meta.symbolKeys)
    }

    @Test
    fun `serialize with empty symbolKeys and isIndexed=false`() {
        val meta = KtFileMetadata(
            filePath = "/f.kt",
            packageFqName = "",
            lastModified = Instant.EPOCH,
            modificationStamp = 0L,
            isIndexed = false,
            symbolKeys = emptyList(),
        )
        val restored = roundtrip(meta)
        assertThat(restored.symbolKeys).isEmpty()
        assertThat(restored.isIndexed).isFalse()
        assertThat(restored.packageFqName).isEmpty()
        assertThat(restored.lastModified).isEqualTo(Instant.EPOCH)
    }

    @Test
    fun `serialize preserves large symbolKeys list`() {
        val keys = (1..100).map { "com.example.Symbol$it" }
        val meta = KtFileMetadata("/big.kt", "com.example", Instant.now(), 1L,
            isIndexed = true, symbolKeys = keys)
        assertThat(roundtrip(meta).symbolKeys).hasSize(100)
    }

    @Test
    fun `fieldValues returns package and isIndexed`() {
        val meta = KtFileMetadata("/f.kt", "org.example", Instant.now(), 1L, isIndexed = true)
        val fields = KtFileMetadataDescriptor.fieldValues(meta)
        assertThat(fields[KtFileMetadataDescriptor.KEY_PACKAGE]).isEqualTo("org.example")
        assertThat(fields[KtFileMetadataDescriptor.KEY_IS_INDEXED]).isEqualTo("true")
    }

    @Test
    fun `fieldValues isIndexed false`() {
        val meta = KtFileMetadata("/f.kt", "p", Instant.now(), 1L, isIndexed = false)
        assertThat(KtFileMetadataDescriptor.fieldValues(meta)[KtFileMetadataDescriptor.KEY_IS_INDEXED])
            .isEqualTo("false")
    }

    @Test
    fun `descriptor name is kt_file_metadata`() {
        assertThat(KtFileMetadataDescriptor.name).isEqualTo("kt_file_metadata")
    }

    @Test
    fun `descriptor has exactly two fields`() {
        assertThat(KtFileMetadataDescriptor.fields).hasSize(2)
        assertThat(KtFileMetadataDescriptor.fields.map { it.name })
            .containsExactly(
                KtFileMetadataDescriptor.KEY_PACKAGE,
                KtFileMetadataDescriptor.KEY_IS_INDEXED,
            )
    }

    @Test
    fun `neither field is prefix-searchable`() {
        assertThat(KtFileMetadataDescriptor.fields.none { it.prefixSearchable }).isTrue()
    }

    @Test
    fun `roundtrip with special characters in path`() {
        val meta = KtFileMetadata(
            filePath = "/Users/test user/my project/src/Main.kt",
            packageFqName = "com.example",
            lastModified = Instant.now(),
            modificationStamp = 7L,
        )
        assertThat(roundtrip(meta).filePath).isEqualTo(meta.filePath)
    }
}
