package com.itsaky.androidide.plugins.security

import android.content.SharedPreferences
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import org.slf4j.LoggerFactory
import java.security.GeneralSecurityException
import java.security.UnrecoverableEntryException
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
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
	 * Serializes every key acquisition, cipher operation and prefs write under this alias.
	 *
	 * Two compounds here are unsafe without it. [encrypt]'s invalidated-key recovery is
	 * delete-then-create-then-encrypt: interleaved, one caller deletes the *valid* key another just
	 * minted, and that other caller's returned ciphertext can never be decrypted. [readAndMigrate]
	 * is a read-modify-write against SharedPreferences, so a [write] committing in the middle of an
	 * upgrade is silently reverted to the old secret.
	 *
	 * Alias-scoped and process-wide rather than per-instance, for the same reason
	 * [AndroidKeystoreSource]'s own lock is: the Keystore entry is a process-wide resource and
	 * nothing stops a caller from building two stores over one alias. Coarser than strictly needed
	 * (it covers every preference key a store touches, not just the one), which costs nothing here -
	 * these calls are rare, already doing binder IPC, and documented as off-main-thread.
	 */
	private val aliasLock: Any = lockFor(alias)

	/**
	 * What was found under a preference key.
	 *
	 * Four outcomes rather than a nullable String: "nothing stored", "stored but no longer readable
	 * on this device" and "readable, but the Keystore would not answer just now" lead to three
	 * different pieces of advice, and collapsing them is what tells a user their credential was
	 * refused when it was never sent, or asks them to retype one that is perfectly intact.
	 */
	sealed interface Stored {
		/** Nothing is stored under the key. */
		data object Absent : Stored

		/** The stored value, decrypted. */
		data class Value(
			val plain: String,
		) : Stored {
			// Never print the secret: a Stored can reach a log line or a crash breadcrumb.
			override fun toString(): String = "Value(plain=<redacted>)"
		}

		/**
		 * Something is stored, but this device's Keystore can no longer open it: the key is gone
		 * (restore onto new hardware, an OEM reset, a re-enrolled screen lock) or the value does not
		 * authenticate. Permanent - ask the user for the secret again.
		 */
		data object Unreadable : Stored

		/**
		 * Something is stored and is very likely fine, but the Keystore could not be reached to open
		 * it - not ready this early in boot, a dead binder, a passing keymaster error. Transient:
		 * retry rather than telling the user their credential is lost.
		 */
		data object Unavailable : Stored
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

		/**
		 * One monitor per alias, shared by every store over that alias. Entries are never evicted;
		 * an alias is a per-plugin constant, so the map holds a handful of them for the process's
		 * life rather than growing.
		 */
		private val ALIAS_LOCKS = ConcurrentHashMap<String, Any>()

		private fun lockFor(alias: String): Any = ALIAS_LOCKS.computeIfAbsent(alias) { Any() }
	}

	/**
	 * Encrypts [plain] into a self-describing string: `enc:v1:` + base64(iv | ciphertext).
	 *
	 * The key is not auth-bound, so a credential change does not invalidate it; an alias an OEM
	 * Keystore drops anyway is regenerated once before retrying.
	 *
	 * Keystore IPC (a binder round trip per call, and a key generation on first use) plus AES/GCM,
	 * so call this off the main thread.
	 *
	 * @param plain the value to encrypt.
	 * @return the ciphertext to store.
	 * @throws GeneralSecurityException on any Keystore or cipher failure, so the caller can tell the
	 *   user instead of crashing on Save. This is the only throwable a caller has to handle: a
	 *   failure that is not already a `GeneralSecurityException` is wrapped in one, with the original
	 *   as its cause.
	 */
	@Throws(GeneralSecurityException::class)
	fun encrypt(plain: String): String =
		synchronized(aliasLock) {
			try {
				try {
					encryptWith(keys.getOrCreate(), plain)
				} catch (e: KeyPermanentlyInvalidatedException) {
					log.warn("Keystore key '{}' invalidated; regenerating and retrying encrypt", alias, e)
					keys.delete()
					encryptWith(keys.getOrCreate(), plain)
				}
			} catch (e: GeneralSecurityException) {
				throw e
			} catch (e: Exception) {
				// Not every way this fails is a GeneralSecurityException, and the KDoc above promises
				// one: KeyStore.load(null) declares IOException, and AndroidKeyStore keygen raises the
				// unchecked ProviderException for keymaster failures. Unwrapped, a plugin catching
				// exactly what is documented still takes an uncaught throwable - in the host's process,
				// so it surfaces as an IDE crash.
				throw GeneralSecurityException("Could not encrypt a secret under alias '$alias'", e)
			}
		}

	/**
	 * Reads a stored value back, handling both formats: an `enc:v1:` value is decrypted and
	 * anything else is returned unchanged as legacy plaintext.
	 *
	 * Keystore IPC plus AES/GCM for a ciphertext value, so call this off the main thread. It also
	 * takes the same alias lock [write] holds across its synchronous flush, so a main-thread call
	 * here can park on a background write's disk I/O.
	 *
	 * @param stored the stored string, ciphertext or legacy plaintext.
	 * @return the plaintext, or null when nothing is stored (null or blank, matching [write], which
	 *   forgets a blank secret rather than storing one) or a ciphertext value could not be
	 *   decrypted. Null collapses "the key is lost" and "the Keystore would not answer"; use
	 *   [readAndMigrate] where that difference decides what the user is told.
	 */
	fun decrypt(stored: String?): String? = (readStored(stored) as? Stored.Value)?.plain

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
		return synchronized(aliasLock) {
			val editor = prefs?.edit() ?: return false
			if (plain.isBlank()) {
				return editor.remove(key).commit()
			}
			try {
				editor.putString(key, encrypt(plain)).commit()
			} catch (e: Exception) {
				log.error("Could not encrypt the secret for '{}' under alias '{}'", key, alias, e)
				false
			}
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
	 * @return what was found: nothing, the plaintext, a value that cannot be decrypted here, or a
	 *   value the Keystore would not open just now.
	 */
	fun readAndMigrate(
		prefs: SharedPreferences?,
		key: String,
	): Stored {
		return synchronized(aliasLock) {
			val stored = prefs?.getString(key, null) ?: return Stored.Absent
			if (stored.startsWith(ENC_PREFIX)) {
				// A lost Keystore alias (restore onto new hardware, an OEM reset, a re-enrolled screen
				// lock) is not the same as an absent secret, and must not be reported as one.
				return readStored(stored)
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
			Stored.Value(stored)
		}
	}

	/**
	 * Classifies a stored string, keeping a value that is really gone apart from one the Keystore
	 * merely would not open just now.
	 *
	 * Both the key-acquisition and the cipher step make that split, and for the same reason:
	 * reporting a momentarily unavailable Keystore as [Stored.Unreadable] asks a user to retype a
	 * credential that is still perfectly readable, and reporting a permanent loss as
	 * [Stored.Unavailable] has a conforming caller retry forever and never re-prompt.
	 */
	private fun readStored(stored: String?): Stored {
		if (stored == null) return Stored.Absent
		// The rule [write] and [readAndMigrate] already apply, applied here too so all three agree: a
		// blank secret is no secret. Value("") would have [decrypt] call a credential configured over
		// the exact bytes [readAndMigrate] purges and reports Absent for.
		if (stored.isBlank()) return Stored.Absent
		if (!stored.startsWith(ENC_PREFIX)) return Stored.Value(stored)
		return synchronized(aliasLock) {
			val key =
				try {
					keys.getOrCreate()
				} catch (e: KeyPermanentlyInvalidatedException) {
					log.warn("Keystore key '{}' is invalidated; a value stored under it is lost", alias, e)
					return@synchronized Stored.Unreadable
				} catch (e: UnrecoverableEntryException) {
					// The alias is there but its key material cannot be recovered: permanent, exactly as an
					// invalidated key is, and not something a retry fixes. One arm covers both shapes -
					// KeyStore.getEntry declares this, AndroidKeyStoreSpi.engineGetKey raises the
					// UnrecoverableKeyException subclass of it.
					log.warn("Keystore key '{}' cannot be recovered; a value stored under it is lost", alias, e)
					return@synchronized Stored.Unreadable
				} catch (e: Exception) {
					log.warn("Could not obtain the Keystore key for alias '{}'", alias, e)
					return@synchronized Stored.Unavailable
				}
			try {
				Stored.Value(decryptWith(key, stored))
			} catch (e: Exception) {
				val outcome = classifyCipherFailure(e)
				log.warn("Could not decrypt a stored secret under alias '{}'; reporting {}", alias, outcome, e)
				outcome
			}
		}
	}

	/**
	 * Whether a cipher-step failure means the stored bytes are gone, or only that the Keystore would
	 * not answer just now.
	 *
	 * Enumerates the *permanent* failures rather than the transient ones, because that is the short
	 * closed list. Everything else a Keystore-backed `Cipher.init`/`doFinal` can surface -- a dead
	 * binder, a `BackendBusyException` under load, a passing keymaster error -- is worth a retry, and
	 * a value that fails one of those is intact: clear the failure and the same ciphertext reads back.
	 * Asking the platform instead is not available in this module: `BackendBusyException` is API 31+
	 * and `KeyStoreException.isTransientFailure()` API 33+, against `minSdk 28`.
	 */
	private fun classifyCipherFailure(e: Exception): Stored =
		when (e) {
			// GCM authentication failed -- a wrong key, or a payload altered since it was written. The
			// subclass AEADBadTagException is the usual one; a provider may raise the plain type instead.
			is BadPaddingException,
			// The payload is not a whole number of blocks; it is not base64; or it is too short to hold
			// an IV (the `require` in [decryptWith]). Malformed, and no retry makes it well-formed.
			is IllegalBlockSizeException,
			is IllegalArgumentException,
			// Cipher.init raises this as readily as key acquisition does, and it is permanent in both.
			is KeyPermanentlyInvalidatedException,
			-> Stored.Unreadable

			else -> Stored.Unavailable
		}

	private fun decryptWith(
		key: SecretKey,
		stored: String,
	): String {
		val combined = Base64.decode(stored.removePrefix(ENC_PREFIX), Base64.NO_WRAP)
		// The split below is at a fixed offset, so say so rather than letting copyOfRange throw:
		// a truncated payload is a malformed value, not an unexpected error.
		require(combined.size >= IV_LEN) { "Ciphertext is too short to hold a $IV_LEN-byte IV" }
		val iv = combined.copyOfRange(0, IV_LEN)
		val ciphertext = combined.copyOfRange(IV_LEN, combined.size)
		val cipher = Cipher.getInstance(TRANSFORM)
		cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
		// Zeroed once the String is built; see the note in encryptWith about the String.
		val plainBytes = cipher.doFinal(ciphertext)
		return try {
			String(plainBytes, Charsets.UTF_8)
		} finally {
			plainBytes.fill(0)
		}
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
