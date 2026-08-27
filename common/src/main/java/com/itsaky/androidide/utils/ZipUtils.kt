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

package com.itsaky.androidide.utils

import com.itsaky.androidide.utils.ContainedPathResolver.Resolution
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipFile

object ZipUtils {
	private val log = LoggerFactory.getLogger(ZipUtils::class.java)

	/**
	 * What [unzipFile] did with each entry: [extracted] holds the files it wrote, [skipped] the
	 * names of entries it did not extract because a symlink already sits at their target. A caller
	 * that needs specific entries on disk must check [skipped] (or the files themselves) rather
	 * than trusting a normal return.
	 */
	data class UnzipResult(
		val extracted: List<File>,
		val skipped: List<String>,
	)

	/**
	 * Writes [input] to [outFile], refusing to write *through* a symlink at that path.
	 *
	 * The check above this is a check: it stats the path, then the write happens as a separate step,
	 * so a symlink appearing in between is followed -- `FileOutputStream` resolves links, and Kotlin's
	 * `File.outputStream()` is a thin inline wrapper over it. [StandardOpenOption] plus
	 * [LinkOption.NOFOLLOW_LINKS] moves the refusal into the `open(2)` call itself (`O_NOFOLLOW`), so
	 * there is no window between deciding and doing.
	 *
	 * This closes the *final component* only. A symlink substituted for one of the parent directories
	 * is still followed, by `mkdirs()` above and by the open here, because resolving a path relative
	 * to an already-open directory needs `openat(2)`, which `java.nio` does not expose. Narrowing that
	 * further would mean JNI or a different extraction strategy; it is recorded rather than implied
	 * away (ADFA-5257 review).
	 */
	internal fun writeNoFollow(
		outFile: File,
		input: InputStream,
	) {
		Files
			.newOutputStream(
				outFile.toPath(),
				StandardOpenOption.WRITE,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING,
				LinkOption.NOFOLLOW_LINKS,
			).use { output -> input.copyTo(output) }
	}

	/**
	 * Extracts [zipFile] into [destDir], preserving directory structure, and returns an
	 * [UnzipResult] reporting the files it wrote and the entries it skipped.
	 *
	 * An entry whose target is an existing symlink that *stays inside* [destDir] -- live with an
	 * in-base real target, or dangling with an in-base lexical one -- is skipped and extraction
	 * continues: nothing is written at or through the link, which keeps a user's own symlink (a
	 * `gradlew`, an SDK link) from being overwritten by an archive. A symlink leading outside
	 * [destDir] gets no such courtesy: like an entry that would land outside [destDir] (zip-slip),
	 * one that names its target through a `..` segment, or one that is not a representable path,
	 * it fails the whole call with an [IOException]. Containment that cannot be *verified* (a
	 * filesystem error, not an escape) also fails the call, saying so rather than accusing the
	 * archive. A `.`/`./` root directory entry names [destDir] itself and is ignored as a no-op.
	 */
	@JvmStatic
	@Throws(IOException::class)
	fun unzipFile(
		zipFile: File,
		destDir: File,
	): UnzipResult {
		destDir.mkdirs()
		val contained = ContainedPathResolver(destDir)
		val extracted = mutableListOf<File>()
		val skipped = mutableListOf<String>()

		ZipFile(zipFile).use { zip ->
			val entries = zip.entries()
			while (entries.hasMoreElements()) {
				val entry = entries.nextElement()

				// A "." or "./" root directory entry names destDir itself, which already exists.
				// The resolver stays strict about it (a Contained result must be *inside* the
				// base), so the extract-it-as-a-no-op tolerance lives here (ADFA-5257 review).
				if (entry.isDirectory && ContainedPathResolver.namesBase(entry.name)) {
					continue
				}

				// Containment first, then link policy: the link check must only ever see a path
				// already proven contained. Checking File(destDir, entry.name) before containment
				// would stat outside destDir for a ../ entry and could quietly skip a zip-slip
				// attempt (ADFA-5257).
				val outFile =
					when (val resolution = contained.resolve(entry.name)) {
						is Resolution.Contained -> resolution.file
						is Resolution.Rejected -> {
							val link = resolution.lexicalTarget
							if (link != null && isContainedSymlink(contained.base, link)) {
								// The resolver refuses a symlink it cannot vouch for, but a link that
								// stays inside destDir is the user's own, and skipping writes nothing
								// at or through it. Same policy as below.
								log.info(
									"Leaving the existing symlink at {}/{} alone; that zip entry was not extracted",
									destDir,
									entry.name,
								)
								skipped.add(entry.name)
								continue
							}
							throw IOException("Zip entry does not resolve to a safe path inside the target directory: ${entry.name}")
						}
						is Resolution.Unverifiable ->
							// Not an escape: refused because unproven. Distinct wording so a
							// filesystem error is not reported as a hostile archive.
							throw IOException(
								"Cannot verify that a zip entry resolves inside the target directory: ${entry.name} (${resolution.cause})",
								resolution.cause,
							)
					}

				// Policy, not containment: a user's own symlink inside their own project -- gradlew, or
				// gradle/wrapper pointed at a shared location -- is legitimate, so the entry is skipped
				// and their symlink left alone rather than written through. Reported via the result,
				// because the caller is otherwise told the archive extracted.
				if (Files.isSymbolicLink(outFile.toPath())) {
					log.info("Leaving the existing symlink at {} alone; that zip entry was not extracted", outFile)
					skipped.add(entry.name)
					continue
				}

				if (entry.isDirectory) {
					outFile.mkdirs()
				} else {
					outFile.parentFile?.mkdirs()
					zip.getInputStream(entry).use { input -> writeNoFollow(outFile, input) }
				}

				extracted.add(outFile)
			}
		}

		return UnzipResult(extracted, skipped)
	}

	/**
	 * Whether [candidate] -- an entry's target the resolver already proved lexically inside [base]
	 * and then refused, via [Resolution.Rejected.lexicalTarget], so no containment logic is
	 * re-derived here -- is a pre-existing symlink that stays inside [base], the one refusal
	 * [unzipFile] downgrades to a skip. An entry the resolver refused for its *syntax* (a `..`
	 * segment, an absolute path) never gets here: it carries no lexical target, so `a/../link.txt`
	 * fails rather than riding the skip meant for `link.txt`.
	 *
	 * Every ancestor between [base] and the candidate must be a non-link: stat'ing the candidate
	 * *follows* an ancestor symlink, so with `a -> /outside`, `a/link.txt` would stat
	 * `/outside/link.txt` and a link found there would ride the skip -- an escaping archive
	 * tolerated instead of rejected. The link's own target must stay inside [base] too: a live
	 * link is judged by its real path, a dangling one by where its text leads lexically (there is
	 * no real target to resolve). A link leading outside [base] is failed like any other escape --
	 * the pre-resolver canonical-path behavior, kept on purpose -- never skipped (ADFA-5257
	 * review).
	 */
	private fun isContainedSymlink(
		base: Path,
		candidate: Path,
	): Boolean {
		var ancestor = candidate.parent
		while (ancestor != null && ancestor != base) {
			if (Files.isSymbolicLink(ancestor)) {
				return false
			}
			ancestor = ancestor.parent
		}
		if (!Files.isSymbolicLink(candidate)) {
			return false
		}
		return try {
			candidate.toRealPath().startsWith(base.toRealPath())
			// Fully qualified: Kotlin auto-imports kotlin.io.NoSuchFileException, which
			// toRealPath() never throws.
		} catch (_: java.nio.file.NoSuchFileException) {
			// Dangling link. In-base is decided on its lexical target instead; failing to read
			// the link at all falls through to "not skippable".
			val parent = candidate.parent ?: return false
			try {
				parent.resolve(Files.readSymbolicLink(candidate)).normalize().startsWith(base)
			} catch (_: IOException) {
				false
			}
		} catch (_: IOException) {
			false
		}
	}
}
