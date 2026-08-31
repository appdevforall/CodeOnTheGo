/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.lsp.java.loader

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertNull
import org.junit.Test

// getOrCreateSession() extracts a real carrier APK and DexClassLoader-loads it, neither of
// which works in this JVM unit-test environment (no carrier APK asset is present, and there's
// no on-device ART to load it into -- see JavaCompletionProviderTest's own comment for the same
// constraint). So this only covers close()/currentSession()'s contract when no session was ever
// created, not the getOrCreateSession()-vs-close() race ADFA-5053's review fixed by synchronizing
// close() -- that needs either a DI seam for the classloader construction or an on-device test.
class JavaCompilerLoaderTest {
	private fun newLoader() = JavaCompilerLoader(mockk<Context>(relaxed = true))

	@Test
	fun `currentSession is null before any session is created`() {
		assertNull(newLoader().currentSession())
	}

	@Test
	fun `close before any session is created is a safe no-op`() {
		val loader = newLoader()
		loader.close()
		assertNull(loader.currentSession())
	}

	@Test
	fun `close is idempotent`() {
		val loader = newLoader()
		loader.close()
		loader.close()
		assertNull(loader.currentSession())
	}
}
