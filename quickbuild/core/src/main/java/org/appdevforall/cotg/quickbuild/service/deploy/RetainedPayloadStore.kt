package org.appdevforall.cotg.quickbuild.service.deploy

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Keeps the bytes of the last successfully deployed payload, so a proxy app reconnecting
 * below the deployed generation can be answered by re-sending them at their original
 * generation (concurrency.md rules 3-4) instead of by a forced blind rebuild.
 *
 * Payloads are cumulative over their baseline, so the last-deployed set alone brings a
 * same-baseline app fully current. The bytes are copied because the executor's own artifacts
 * (the daemon's dex, the staged assets zip) are overwritten by the next build.
 *
 * Everything here is best-effort by contract: a failed [retain] or an unreadable [load] only
 * costs the caller its fallback - the forced catch-up build - never a build result. Call only
 * on the session dispatcher.
 *
 * @property dir the retention directory, replaced wholesale by every [retain]
 */
internal class RetainedPayloadStore(
	private val dir: File,
) {
	/**
	 * One retained payload, exactly as it was deployed.
	 *
	 * @property generation the generation the deploy claimed; a re-send replays it unchanged,
	 *   and the runtime's strictly-newer gate accepts it because the reconnected app runs
	 *   something older
	 * @property metadataJson metadata for the re-send; always the hot-swap variant, since a
	 *   reconnect catch-up must not ask the just-relaunched app to persist and exit again
	 * @property dexFile the retained classes, or null when the deploy carried none
	 * @property arscFile the retained resource APK, or null when the deploy carried none
	 * @property assetsZip the retained changed-assets zip, or null when the deploy carried none
	 */
	data class RetainedPayload(
		val generation: Long,
		val metadataJson: String,
		val dexFile: File?,
		val arscFile: File?,
		val assetsZip: File?,
	)

	/**
	 * Replaces the retained set with this deploy's artifacts. Call only after the proxy app
	 * confirmed the payload, so what is retained is always something known to have run.
	 *
	 * The swap goes through a staging dir: a crash at any point leaves either the previous
	 * set, or nothing - never a half-written mix that [load] could hand to a re-send.
	 *
	 * @param generation the generation the confirmed deploy claimed
	 * @param dexFile the deployed classes, or null when the build moved no code
	 * @param arscFile the deployed resource APK, or null when resources did not move
	 * @param assetsZip the deployed changed-assets zip, or null when no asset changed
	 * @param metadataJson the metadata a re-send should use (the hot-swap variant)
	 */
	fun retain(
		generation: Long,
		dexFile: File?,
		arscFile: File?,
		assetsZip: File?,
		metadataJson: String,
	) {
		val staging = stagingDir()
		try {
			staging.deleteRecursively()
			check(staging.mkdirs()) { "could not create ${staging.absolutePath}" }
			dexFile?.copyTo(File(staging, DEX_NAME))
			arscFile?.copyTo(File(staging, ARSC_NAME))
			assetsZip?.copyTo(File(staging, ASSETS_NAME))
			File(staging, META_NAME).writeText(
				JsonObject()
					.apply {
						addProperty("generation", generation)
						addProperty("metadata", metadataJson)
						addProperty("hasDex", dexFile != null)
						addProperty("hasArsc", arscFile != null)
						addProperty("hasAssets", assetsZip != null)
					}.toString(),
			)
			dir.deleteRecursively()
			check(staging.renameTo(dir)) { "could not move staging into ${dir.absolutePath}" }
		} catch (e: Exception) {
			staging.deleteRecursively()
			log.warn(
				"Could not retain the deployed payload of generation {}; a reconnect catch-up will rebuild instead",
				generation,
				e,
			)
		}
	}

	/**
	 * Reads the retained set back for a re-send.
	 *
	 * @return the retained payload, or null when nothing is retained or the set is unreadable
	 *   (missing part, corrupt metadata) - either way the caller falls back to rebuilding
	 */
	fun load(): RetainedPayload? {
		val meta = File(dir, META_NAME)
		if (!meta.isFile) return null
		return try {
			val json = JsonParser.parseString(meta.readText()).asJsonObject
			RetainedPayload(
				generation = json.get("generation").asLong,
				metadataJson = json.get("metadata").asString,
				dexFile = part(json, "hasDex", DEX_NAME),
				arscFile = part(json, "hasArsc", ARSC_NAME),
				assetsZip = part(json, "hasAssets", ASSETS_NAME),
			)
		} catch (e: Exception) {
			log.warn("Retained payload under {} is unreadable; a reconnect catch-up will rebuild instead", dir, e)
			null
		}
	}

	/**
	 * Drops the retained set. Call whenever the baseline changes: the old baseline's bytes
	 * must never be replayed onto a new one.
	 */
	fun clear() {
		dir.deleteRecursively()
		stagingDir().deleteRecursively()
	}

	/**
	 * One payload part of the retained set.
	 *
	 * @param json the parsed metadata
	 * @param flag the presence key written by [retain]
	 * @param name the part's file name inside [dir]
	 * @return the part, or null when the deploy carried none
	 * @throws IllegalStateException when the metadata claims a part the directory lacks -
	 *   re-sending a payload missing its classes would advance the app past them
	 */
	private fun part(
		json: JsonObject,
		flag: String,
		name: String,
	): File? {
		if (!json.get(flag).asBoolean) return null
		val file = File(dir, name)
		check(file.isFile) { "retained $name is missing" }
		return file
	}

	private fun stagingDir(): File = File(dir.parentFile, dir.name + ".staging")

	companion object {
		private val log = LoggerFactory.getLogger("QB-RetainedPayloads")

		private const val DEX_NAME = "payload.dex"
		private const val ARSC_NAME = "payload.arsc"
		private const val ASSETS_NAME = "assets.zip"
		private const val META_NAME = "meta.json"

		/**
		 * The store for one executor work dir. A fixed relative path, so the executor writing
		 * retention and the session reading it agree across proxy app rebuilds, which rebuild
		 * the executor but keep the work dir.
		 *
		 * @param workDir the executor's payload-staging dir
		 * @return a store over `workDir/last-deployed`
		 */
		fun forWorkDir(workDir: File): RetainedPayloadStore = RetainedPayloadStore(File(workDir, "last-deployed"))
	}
}
