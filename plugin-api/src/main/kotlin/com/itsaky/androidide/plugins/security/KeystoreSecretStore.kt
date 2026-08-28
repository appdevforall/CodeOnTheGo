package com.itsaky.androidide.plugins.security

import android.content.SharedPreferences
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import org.slf4j.LoggerFactory
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES/GCM encryption for a caller's secrets, keyed by a hardware-backed Android Keystore secret.
 * Only ciphertext is written to SharedPreferences, so a copied prefs file (root, `adb backup`,
 * forensic dump) is useless without this device's Keystore.
 *
 * Here rather than in each plugin because every plugin that stores a credential was carrying its
 * own copy of the same cipher, migration and recovery path, and a fix applied to one copy is a fix
 * the others silently do not get.
 */
class KeystoreSecretStore internal constructor(
	private val alias: String,
	private val keys: SecretKeySource,
) {
	/**
	 * @param alias the Keystore alias holding the caller's key. A parameter, and deliberately
	 *   distinct per caller: plugins run in the host's process and UID and therefore share one
	 *   Keystore, so a shared alias would let one caller's invalidated-key recovery destroy another's
	 *   stored secret. It must also stay stable across releases, since a secret encrypted under one
	 *   alias cannot be read under another. It is also what identifies the caller in this class's log
	 *   lines, since every caller shares one logger here.
	 */
	constructor(alias: String) : this(alias, AndroidKeystoreSource(alias))

	/**
	 * What was found under a preference key.
	 *
	 * Three outcomes rather than a nullable String: "nothing stored" and "stored but no longer
	 * readable on this device" lead to opposite advice, and collapsing them is what tells a user
	 * their credential was refused when it was never sent.
	 */
	sealed interface Stored {
		/** Nothing is stored under the key. */
		data object Absent : Stored

		/** The stored value, decrypted. */
		data class Value(
			val plain: String,
		) : Stored

		/** Something is stored, but this device's Keystore can no longer open it. */
		data object Unreadable : Stored
	}

	private companion object {
		/**
		 * Marks a stored value as ciphertext; anything without it is legacy plaintext.
		 *
		 * Deliberately not public: the on-disk format is this class's business, and a caller that
		 * branches on the marker itself is a caller a `enc:v2:` would break. Plugins get both formats
		 * handled for them by [decrypt] and [readAndMigrate].
		 */
		private const val ENC_PREFIX = "enc:v1:"

		private const val TRANSFORM = "AES/GCM/NoPadding"
		private const val IV_LEN = 12
		private const val TAG_BITS = 128

		private val log = LoggerFactory.getLogger(KeystoreSecretStore::class.java)
	}

	/**
	 * Encrypts [plain] into a self-describing string: `enc:v1:` + base64(iv | ciphertext).
	 *
	 * The key is not auth-bound, so a credential change does not invalidate it; an alias an OEM
	 * Keystore drops anyway is regenerated once before retrying.
	 *
	 * @param plain the value to encrypt.
	 * @return the ciphertext to store.
	 * @throws GeneralSecurityException on any other Keystore or cipher failure, so the caller can
	 *   tell the user instead of crashing on Save.
	 */
	@Throws(GeneralSecurityException::class)
	fun encrypt(plain: String): String =
		try {
			encryptWith(keys.getOrCreate(), plain)
		} catch (e: KeyPermanentlyInvalidatedException) {
			log.warn("Keystore key '{}' invalidated; regenerating and retrying encrypt", alias, e)
			keys.delete()
			encryptWith(keys.getOrCreate(), plain)
		}

	/**
	 * Reads a stored value back, handling both formats: an `enc:v1:` value is decrypted and
	 * anything else is returned unchanged as legacy plaintext.
	 *
	 * @param stored the stored string, ciphertext or legacy plaintext.
	 * @return the plaintext, or null when a ciphertext value cannot be decrypted, meaning the
	 *   Keystore key was lost and the user has to enter the secret again.
	 */
	fun decrypt(stored: String?): String? {
		if (stored == null) return null
		if (!stored.startsWith(ENC_PREFIX)) return stored
		return try {
			val combined = Base64.decode(stored.removePrefix(ENC_PREFIX), Base64.NO_WRAP)
			// The split below is at a fixed offset, so say so rather than letting copyOfRange throw:
			// a truncated payload is a malformed value, not an unexpected error.
			require(combined.size >= IV_LEN) { "Ciphertext is too short to hold a $IV_LEN-byte IV" }
			val iv = combined.copyOfRange(0, IV_LEN)
			val ciphertext = combined.copyOfRange(IV_LEN, combined.size)
			val cipher = Cipher.getInstance(TRANSFORM)
			cipher.init(Cipher.DECRYPT_MODE, keys.getOrCreate(), GCMParameterSpec(TAG_BITS, iv))
			// Zeroed once the String is built; see the note in encryptWith about the String.
			val plainBytes = cipher.doFinal(ciphertext)
			try {
				String(plainBytes, Charsets.UTF_8)
			} finally {
				plainBytes.fill(0)
			}
		} catch (e: Exception) {
			log.warn("Failed to decrypt a stored secret under alias '{}'", alias, e)
			null
		}
	}

	/**
	 * Stores [plain] under [key], encrypted; a blank value removes the entry instead.
	 *
	 * Keystore IPC, AES/GCM and a synchronous prefs flush, so call this off the main thread.
	 *
	 * @param prefs where to store it.
	 * @param key the preference key.
	 * @param plain the secret, or blank to forget it.
	 * @return true once the value is on disk, false when encryption or the write itself failed. The
	 *   flush is synchronous (`commit`, not `apply`) precisely so this answer is true at the moment
	 *   it is returned: a caller that shows "Saved" on true must not be told that before the bytes
	 *   have landed, or a process death loses a credential the user believes is stored.
	 */
	fun write(
		prefs: SharedPreferences?,
		key: String,
		plain: String,
	): Boolean {
		val editor = prefs?.edit() ?: return false
		if (plain.isBlank()) {
			return editor.remove(key).commit()
		}
		return try {
			editor.putString(key, encrypt(plain)).commit()
		} catch (e: Exception) {
			log.error("Could not encrypt the secret for '{}' under alias '{}'", key, alias, e)
			false
		}
	}

	/**
	 * Reads [key] from [prefs], upgrading a legacy plaintext value to ciphertext in place.
	 *
	 * Secrets written before this store existed are still plaintext on disk, and [decrypt] alone
	 * hands them back unchanged forever, so an install configured earlier would never actually gain
	 * encryption. Re-encrypting on the first read closes that gap without making the user re-enter
	 * anything. The value migrates verbatim: trimming it here would rewrite a secret whose leading
	 * or trailing whitespace is significant, and since the upgrade overwrites the plaintext it was
	 * read from, that loss is unrecoverable. Trim at the call site if your credential format wants
	 * it. A blank legacy value is purged and reported [Stored.Absent], matching [write], which
	 * forgets a blank secret rather than storing one.
	 *
	 * Keystore IPC plus AES/GCM, so call this off the main thread.
	 *
	 * @param prefs where the value lives.
	 * @param key the preference key.
	 * @return what was found: nothing, the plaintext, or a value that cannot be decrypted here.
	 */
	fun readAndMigrate(
		prefs: SharedPreferences?,
		key: String,
	): Stored {
		val stored = prefs?.getString(key, null) ?: return Stored.Absent
		if (stored.startsWith(ENC_PREFIX)) {
			// A lost Keystore alias (restore onto new hardware, an OEM reset, a re-enrolled screen
			// lock) is not the same as an absent secret, and must not be reported as one.
			return decrypt(stored)?.let(Stored::Value) ?: Stored.Unreadable
		}
		if (stored.isBlank()) {
			// [write] forgets a blank secret rather than storing one; reporting Value("") here would
			// tell a caller a credential is configured when none is. Purge so both paths agree.
			prefs.edit().remove(key).apply()
			return Stored.Absent
		}
		try {
			// `apply`, unlike [write]'s `commit`: the upgrade is best-effort and self-healing. A flush
			// lost to process death leaves the legacy plaintext, which still reads back fine and gets
			// re-upgraded on the next call, so blocking a read on disk I/O would buy nothing.
			prefs.edit().putString(key, encrypt(stored)).apply()
			log.info("Upgraded a legacy plaintext value for '{}' to ciphertext", key)
		} catch (e: Exception) {
			log.warn("Could not upgrade a legacy plaintext value for '{}' to ciphertext", key, e)
		}
		return Stored.Value(stored)
	}

	private fun encryptWith(
		key: SecretKey,
		plain: String,
	): String {
		val cipher = Cipher.getInstance(TRANSFORM)
		cipher.init(Cipher.ENCRYPT_MODE, key)
		val iv = cipher.iv
		// [decrypt] splits the payload at a fixed IV_LEN, so a provider handing back any other IV
		// length would have us write values we could never read back. GCM is 12 bytes on every
		// runtime we ship to; refuse to store rather than store something unreadable.
		if (iv.size != IV_LEN) {
			throw GeneralSecurityException("Expected a $IV_LEN-byte GCM IV, got ${iv.size}")
		}
		// Zeroed straight after the cipher reads it. The String itself cannot be: every API these
		// secrets pass through (SharedPreferences, JSONObject, setRequestProperty) takes one, so a
		// CharArray here would only move the immutable copy one frame away.
		val plainBytes = plain.toByteArray(Charsets.UTF_8)
		val ciphertext =
			try {
				cipher.doFinal(plainBytes)
			} finally {
				plainBytes.fill(0)
			}
		val combined = ByteArray(iv.size + ciphertext.size)
		System.arraycopy(iv, 0, combined, 0, iv.size)
		System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
		return ENC_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
	}
}
