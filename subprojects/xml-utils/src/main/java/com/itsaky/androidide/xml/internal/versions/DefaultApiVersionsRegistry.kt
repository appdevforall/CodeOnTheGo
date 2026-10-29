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

package com.itsaky.androidide.xml.internal.versions

import com.google.auto.service.AutoService
import com.itsaky.androidide.xml.versions.ApiVersion
import com.itsaky.androidide.xml.versions.ApiVersions
import com.itsaky.androidide.xml.versions.ApiVersionsRegistry
import com.itsaky.androidide.xml.versions.ClassInfo
import com.itsaky.androidide.xml.versions.FieldInfo
import com.itsaky.androidide.xml.versions.Info
import com.itsaky.androidide.xml.versions.MethodInfo
import org.slf4j.LoggerFactory
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Default implementation of [ApiVersionsRegistry].
 *
 * @author Akash Yadav
 */
@AutoService(ApiVersionsRegistry::class)
class DefaultApiVersionsRegistry : ApiVersionsRegistry {
	private val versions = ConcurrentHashMap<String, ApiVersions>()

	/*
	 * Platform dirs with no readable table. setupLookupForCompletion asks for the table on every
	 * completion request, so without remembering the failure a dir whose file is missing or
	 * unparseable is re-opened and re-parsed per keystroke. clear() is the way back.
	 */
	private val unreadablePlatforms = ConcurrentHashMap.newKeySet<String>()

	companion object {
		private val log = LoggerFactory.getLogger(DefaultApiVersionsRegistry::class.java)
	}

	override var isLoggingEnabled: Boolean = true

	override fun forPlatformDir(platform: File): ApiVersions? {
		versions[platform.path]?.let { return it }
		if (platform.path in unreadablePlatforms) {
			return null
		}

		/*
		 * This table only feeds API-level hints in the completion popup, and it is read on the
		 * path that opens a project. A platform file we cannot parse must therefore cost the
		 * hints, not the project: every failure below degrades to null. An Error is left to
		 * propagate -- an OutOfMemoryError part-way through a multi-MB parse is not a malformed
		 * file, and continuing on an exhausted heap hides it.
		 */
		val version =
			try {
				readApiVersions(platform)
			} catch (e: Exception) {
				log.warn("Could not read API versions for platform dir: {}", platform, e)
				null
			}

		if (version == null) {
			unreadablePlatforms += platform.path
			return null
		}

		versions[platform.path] = version
		return version
	}

	private fun readApiVersions(platform: File): ApiVersions? {
		val versionsFile = File(platform, "data/api-versions.xml")
		if (!versionsFile.exists() || !versionsFile.isFile) {
			return null
		}

		if (isLoggingEnabled) {
			log.info("Creating API versions table for platform dir: $platform")
		}

		val unreadable = UnreadableAttributes()
		val table =
			versionsFile.bufferedReader().use {
				val parser =
					XmlPullParserFactory.newInstance().run {
						isNamespaceAware = false
						return@run newPullParser().run {
							setInput(it)
							this
						}
					}
				readApiVersions(parser, unreadable)
			}

		if (isLoggingEnabled && unreadable.count > 0) {
			log.warn("Ignored {} unreadable API version attribute(s) in {}, first {}", unreadable.count, versionsFile, unreadable.first)
		}

		return table
	}

	private fun readApiVersions(
		parser: XmlPullParser,
		unreadable: UnreadableAttributes,
	): ApiVersions {
		val versions = DefaultApiVersions()
		var event = parser.eventType
		var apiEncountered = false
		while (event != XmlPullParser.END_DOCUMENT) {
			if (event == XmlPullParser.START_TAG) {
				val tag = parser.name
				if (tag == "api") {
					apiEncountered = true
					event = parser.next()
					continue
				}

				if (!apiEncountered) {
					throw IllegalStateException("<api> tag not found")
				}

				val info = readTag(parser, unreadable)
				if (info != null && info is ClassInfo) {
					versions.putClass(info.name, info)
				}
			}
			event = parser.next()
		}
		return versions
	}

	private fun readTag(
		parser: XmlPullParser,
		unreadable: UnreadableAttributes,
	): Info? =
		when (parser.name) {
			"class" -> readClassInfo(parser, unreadable)
			"method" -> readMethodInfo(parser, unreadable)
			"field" -> readFieldInfo(parser, unreadable)
			else -> null
		}

	private fun readMethodInfo(
		parser: XmlPullParser,
		unreadable: UnreadableAttributes,
	): Info {
		val name = parser.readName()
		return DefaultMethodInfo(
			name = name,
			since = parser.readSince(unreadable),
			removed = parser.readRemoved(unreadable),
			deprecated = parser.readDeprecated(unreadable),
			simpleName = name.substringBefore('('),
		)
	}

	private fun readFieldInfo(
		parser: XmlPullParser,
		unreadable: UnreadableAttributes,
	): Info =
		DefaultFieldInfo(
			name = parser.readName(),
			since = parser.readSince(unreadable),
			removed = parser.readRemoved(unreadable),
			deprecated = parser.readDeprecated(unreadable),
		)

	private fun readClassInfo(
		parser: XmlPullParser,
		unreadable: UnreadableAttributes,
	): ClassInfo =
		DefaultClassInfo(
			name = parser.readName(),
			since = parser.readSince(unreadable),
			removed = parser.readRemoved(unreadable),
			deprecated = parser.readDeprecated(unreadable),
		).apply {
			val depth = parser.depth
			var event = parser.next()
			while (event != XmlPullParser.END_DOCUMENT) {
				if (event == XmlPullParser.END_TAG && parser.depth == depth) {
					break
				}

				if (event != XmlPullParser.START_TAG) {
					event = parser.next()
					continue
				}

				val info = readTag(parser, unreadable)
				if (info == null) {
					event = parser.next()
					continue
				}

				when (info) {
					is FieldInfo -> {
						this.fields[info.name] = info
					}

					is MethodInfo -> {
						var methods = this.methods[info.simpleName]
						if (methods == null) {
							methods = mutableListOf()
						}
						methods.add(info)

						this.methods[info.simpleName] = methods
					}
				}

				event = parser.next()
			}
		}

	private fun XmlPullParser.readName(): String = readString("name")

	private fun XmlPullParser.readSince(unreadable: UnreadableAttributes): ApiVersion = readApiVersion("since", unreadable)

	private fun XmlPullParser.readRemoved(unreadable: UnreadableAttributes): ApiVersion = readApiVersion("removed", unreadable)

	private fun XmlPullParser.readDeprecated(unreadable: UnreadableAttributes): ApiVersion = readApiVersion("deprecated", unreadable)

	private fun XmlPullParser.readApiVersion(
		name: String,
		unreadable: UnreadableAttributes,
	): ApiVersion =
		read(this, name) { raw ->
			if (raw.isNullOrBlank()) {
				return@read ApiVersion.UNKNOWN
			}

			ApiVersion.parse(raw) ?: run {
				unreadable.record(name, raw)
				ApiVersion.UNKNOWN
			}
		}

	private fun XmlPullParser.readString(
		name: String,
		default: String = "",
	): String {
		return read(this, name) {
			if (it.isNullOrBlank()) {
				return@read default
			}

			return@read it
		}
	}

	private fun <T> read(
		parser: XmlPullParser,
		name: String,
		convert: (String?) -> T,
	): T {
		val index = parser.attrIndex(name)
		if (index != -1) {
			return convert(parser.value(index))
		}
		return convert("")
	}

	override fun clear() {
		versions.clear()
		unreadablePlatforms.clear()
	}

	/**
	 * Find the index of the attribute with the given name.
	 *
	 * @param name The name of the attribute to look for.
	 * @return The index of the attribute or `-1`.
	 */
	private fun XmlPullParser.attrIndex(name: String): Int {
		for (i in 0 until this.attributeCount) {
			if (this.getAttributeName(i) == name) {
				return i
			}
		}
		return -1
	}

	/**
	 * Get the value of the attribute at the given index.
	 *
	 * @param index The index of the attribute.
	 * @return The value of the attribute.
	 */
	private fun XmlPullParser.value(index: Int): String? = getAttributeValue(index)

	/*
	 * Attributes ignored while reading one file. api-versions.xml carries ~100k entries, so a
	 * grammar extension we do not understand yet has to cost one warning per file rather than one
	 * per entry.
	 */
	private class UnreadableAttributes {
		var count = 0
			private set

		var first: String? = null
			private set

		fun record(
			name: String,
			raw: String,
		) {
			if (count == 0) {
				first = "$name='$raw'"
			}
			count++
		}
	}
}
