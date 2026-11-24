package org.appdevforall.codeonthego.indexing.jvm

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.appdevforall.codeonthego.indexing.InMemoryIndex
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.time.Instant

@RunWith(JUnit4::class)
class KtFileMetadataIndexWriteListenerTest {
	private val index = KtFileMetadataIndex(InMemoryIndex(KtFileMetadataDescriptor))
	private val writes = mutableListOf<KtFileMetadataWrite>()

	private fun metadata(
		path: String,
		pkg: String,
	) = KtFileMetadata(path, pkg, Instant.EPOCH, 1L)

	@Test
	fun upsertReportsPathAndPackage() =
		runTest {
			index.addWriteListener { writes += it }

			index.upsert(metadata("/a/A.kt", "com.a"))

			assertThat(writes).containsExactly(KtFileMetadataWrite.Upserted("/a/A.kt", "com.a"))
		}

	@Test
	fun removeAndRemoveAllReportPaths() =
		runTest {
			index.addWriteListener { writes += it }

			index.remove("/a/A.kt")
			index.removeAll(listOf("/a/B.kt", "/a/C.kt"))

			assertThat(writes)
				.containsExactly(
					KtFileMetadataWrite.Removed(listOf("/a/A.kt")),
					KtFileMetadataWrite.Removed(listOf("/a/B.kt", "/a/C.kt")),
				).inOrder()
		}

	@Test
	fun clearReportsCleared() =
		runTest {
			index.addWriteListener { writes += it }

			index.clear()

			assertThat(writes).containsExactly(KtFileMetadataWrite.Cleared)
		}

	@Test
	fun listenerSeesTheWriteAlreadyApplied() =
		runTest {
			var pathsSeenInListener: List<String>? = null
			index.addWriteListener {
				pathsSeenInListener = index.getFilePathsForPackage("com.a").toList()
			}

			index.upsert(metadata("/a/A.kt", "com.a"))

			assertThat(pathsSeenInListener).containsExactly("/a/A.kt")
		}

	@Test
	fun closedRegistrationIsNotCalled() =
		runTest {
			index.addWriteListener { writes += it }.close()

			index.upsert(metadata("/a/A.kt", "com.a"))

			assertThat(writes).isEmpty()
		}
}
