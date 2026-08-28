package com.itsaky.androidide.plugins.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.slf4j.LoggerFactory
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Where [KeystoreSecretStore] gets its AES key.
 *
 * A seam, not an extension point, and deliberately `internal`: the Android Keystore is a binder
 * service with no JVM implementation, so `KeyStore.getInstance("AndroidKeyStore")` fails even under
 * Robolectric. Without this, the cipher, migration and invalidated-key recovery paths -- the parts
 * worth testing -- could only be exercised on a device.
 */
internal interface SecretKeySource {
	/**
	 * The key, minting one on first use.
	 *
	 * Implementations must be safe to call concurrently: two callers that each mint a key under the
	 * same alias leave one of them holding a key the store has already overwritten, and every value
	 * encrypted under it is then unreadable for good.
	 */
	fun getOrCreate(): SecretKey

	/** Drops the key, so the next [getOrCreate] mints a fresh one. Never throws. */
	fun delete()
}

/**
 * The real source: a hardware-backed key held under [alias] in the Android Keystore.
 *
 * @param alias the Keystore alias holding the key.
 */
internal class AndroidKeystoreSource(
	private val alias: String,
) : SecretKeySource {
	override fun getOrCreate(): SecretKey =
		synchronized(KEYSTORE_LOCK) {
			val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
			(store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
			val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
			generator.init(
				KeyGenParameterSpec
					.Builder(
						alias,
						KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
					).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
					.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
					.build(),
			)
			generator.generateKey()
		}

	override fun delete() {
		synchronized(KEYSTORE_LOCK) {
			try {
				KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(alias)
			} catch (e: Exception) {
				// The alias, not the key material: a Keystore secret key is non-exportable, so there is
				// nothing here to leak, and naming the alias is what makes the line diagnosable.
				log.warn("Failed to delete Keystore alias '{}'", alias, e)
			}
		}
	}

	private companion object {
		const val KEYSTORE = "AndroidKeyStore"

		/**
		 * Guards the read-then-generate in [getOrCreate], which is otherwise a lost-update race:
		 * two threads both find the alias empty, both generate, and the second `generateKey` replaces
		 * the first in the Keystore -- so whichever thread encrypted under the losing key wrote a
		 * value nothing can ever decrypt again.
		 *
		 * Process-wide rather than per-instance because the Keystore is a single process-wide
		 * resource and nothing stops a caller from building two stores over one alias. Contention is
		 * irrelevant: these calls are rare and already doing binder IPC.
		 */
		val KEYSTORE_LOCK = Any()

		val log = LoggerFactory.getLogger(AndroidKeystoreSource::class.java)
	}
}
