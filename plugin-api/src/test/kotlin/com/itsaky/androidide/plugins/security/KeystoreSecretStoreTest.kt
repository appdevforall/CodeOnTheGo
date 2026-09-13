package com.itsaky.androidide.plugins.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Robolectric rather than a plain JVM test because the store reaches for `android.util.Base64` and
 * stores through a real [SharedPreferences]. The Keystore itself is swapped out via
 * [SecretKeySource] -- there is no AndroidKeyStore JCA provider off-device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeystoreSecretStoreTest {
	private lateinit var prefs: SharedPreferences
	private lateinit var keys: FakeKeySource
	private lateinit var store: KeystoreSecretStore

	@Before
	fun setUp() {
		prefs =
			RuntimeEnvironment
				.getApplication()
				.getSharedPreferences("secrets", Context.MODE_PRIVATE)
		prefs.edit().clear().apply()
		keys = FakeKeySource()
		store = KeystoreSecretStore(ALIAS, keys)
	}

	@Test
	fun `Given_a_plaintext_secret_When_encrypt_Then_the_output_is_marked_ciphertext_and_omits_the_plaintext`() {
		val out = store.encrypt("hunter2")

		assertThat(out).startsWith(ENC_PREFIX)
		assertThat(out).doesNotContain("hunter2")
	}

	@Test
	fun `Given_the_same_secret_encrypted_twice_When_the_results_are_compared_Then_the_ciphertexts_differ`() {
		assertThat(store.encrypt("same")).isNotEqualTo(store.encrypt("same"))
	}

	@Test
	fun `Given_an_encrypted_secret_When_decrypt_Then_the_original_plaintext_is_returned`() {
		assertThat(store.decrypt(store.encrypt("hunter2"))).isEqualTo("hunter2")
	}

	@Test
	fun `Given_non_ASCII_emoji_and_multi_byte_secrets_When_round_tripped_Then_each_is_preserved_exactly`() {
		// Both directions name Charsets.UTF_8 explicitly; this pins that they agree.
		listOf(
			"paßwort-äöü",
			"密码令牌",
			"🔐🚀",
			"مفتاح",
			// Escapes, not raw 0x00: two literal NUL bytes trip git's binary heuristic, and this
			// whole suite then has no reviewable diff on GitHub. Identical bytes once compiled.
			"key\u0000with\u0000nuls",
		).forEach { secret ->
			assertThat(store.decrypt(store.encrypt(secret))).isEqualTo(secret)
		}
	}

	@Test
	fun `Given_an_empty_and_a_64KB_secret_When_round_tripped_Then_each_is_preserved_exactly`() {
		assertThat(store.decrypt(store.encrypt(""))).isEqualTo("")
		val long = "x".repeat(64 * 1024)
		assertThat(store.decrypt(store.encrypt(long))).isEqualTo(long)
	}

	@Test
	fun `Given_an_invalidated_Keystore_key_When_encrypt_Then_the_key_is_regenerated_once_and_the_retry_succeeds`() {
		keys.invalidated = true

		val out = store.encrypt("hunter2")

		assertThat(store.decrypt(out)).isEqualTo("hunter2")
		assertThat(keys.deleted).isEqualTo(1)
		// The fake mints a fresh key on delete, so this pins that the retry used the *new* key
		// rather than passing because the old one was handed back unchanged.
		assertThat(keys.minted).hasSize(2)
	}

	@Test
	fun `Given_concurrent_recovery_from_an_invalidated_key_When_they_encrypt_Then_one_regeneration_serves_every_caller`() {
		// encrypt's recovery is a delete-then-create-then-encrypt compound. Interleaved, the second
		// caller's delete drops the *valid* key the first just minted, and the first caller walks away
		// with a ciphertext nothing can ever decrypt. Serialized, whoever gets there second finds the
		// fresh key and never deletes at all.
		val threads = 4
		val ready = CyclicBarrier(threads)
		val out = arrayOfNulls<String>(threads)
		keys.invalidated = true

		val workers =
			(0 until threads).map { t ->
				Thread {
					ready.await()
					out[t] = store.encrypt("secret-$t")
				}.apply { start() }
			}
		workers.forEach { it.join() }

		assertThat(keys.deleted).isEqualTo(1)
		(0 until threads).forEach { t ->
			assertThat(store.decrypt(out[t])).isEqualTo("secret-$t")
		}
	}

	@Test
	fun `Given_a_Keystore_failure_that_is_not_a_GeneralSecurityException_When_encrypt_Then_it_is_wrapped_in_one`() {
		// encrypt documents GeneralSecurityException, but KeyStore.load(null) declares IOException and
		// AndroidKeyStore keygen raises the unchecked ProviderException. Unwrapped, a plugin catching
		// exactly what is documented still takes an uncaught throwable - in the host's process.
		listOf(IOException("keystore file"), ProviderException("keymaster")).forEach { cause ->
			keys.getFailure = cause

			val thrown =
				try {
					store.encrypt("hunter2")
					null
				} catch (e: GeneralSecurityException) {
					e
				}

			assertThat(thrown).isNotNull()
			assertThat(thrown!!.cause).isSameInstanceAs(cause)
		}
	}

	@Test(expected = KeyStoreException::class)
	fun `Given_an_unavailable_Keystore_When_encrypt_Then_the_failure_propagates_so_the_caller_can_tell_the_user`() {
		keys.getFailure = KeyStoreException("Keystore unavailable")
		store.encrypt("hunter2")
	}

	@Test
	fun `Given_a_null_stored_value_When_decrypt_Then_null_is_returned`() {
		assertThat(store.decrypt(null)).isNull()
	}

	@Test
	fun `Given_an_unprefixed_legacy_plaintext_value_When_decrypt_Then_it_is_returned_unchanged`() {
		assertThat(store.decrypt("legacy-plaintext")).isEqualTo("legacy-plaintext")
		assertThat(store.decrypt("  padded  ")).isEqualTo("  padded  ")
	}

	@Test
	fun `Given_a_blank_stored_value_When_decrypt_and_readAndMigrate_Then_both_report_no_credential`() {
		listOf("", "   ", "\t\n").forEach { blank ->
			// decrypt returning "" while readAndMigrate returns Absent over the identical bytes lets one
			// plugin send an empty API key on `decrypt(...) != null` and the same plugin, migrated to
			// readAndMigrate, correctly find nothing - with nothing on disk having changed.
			assertThat(store.decrypt(blank)).isNull()

			prefs.edit().putString(KEY, blank).apply()
			assertThat(store.readAndMigrate(prefs, KEY)).isEqualTo(KeystoreSecretStore.Stored.Absent)
		}
	}

	@Test
	fun `Given_ciphertext_tampered_with_in_place_When_decrypt_Then_null_is_returned_rather_than_garbage`() {
		val body = store.encrypt("hunter2").removePrefix(ENC_PREFIX)
		val raw = Base64.decode(body, Base64.NO_WRAP)
		// Flip a bit past the IV: GCM authenticates, so this must fail rather than yield garbage.
		raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 0x01).toByte()
		val tampered = ENC_PREFIX + Base64.encodeToString(raw, Base64.NO_WRAP)

		assertThat(store.decrypt(tampered)).isNull()
	}

	@Test
	fun `Given_a_truncated_malformed_or_non_base64_payload_When_decrypt_Then_null_is_returned`() {
		listOf(
			ENC_PREFIX,
			ENC_PREFIX + Base64.encodeToString(ByteArray(4), Base64.NO_WRAP),
			ENC_PREFIX + Base64.encodeToString(ByteArray(12), Base64.NO_WRAP),
			ENC_PREFIX + "!!!not-base64!!!",
		).forEach { assertThat(store.decrypt(it)).isNull() }
	}

	@Test
	fun `Given_a_key_other_than_the_one_that_encrypted_the_value_When_decrypt_Then_null_is_returned`() {
		val out = store.encrypt("hunter2")

		val onNewHardware = KeystoreSecretStore(ALIAS, FakeKeySource())
		assertThat(onNewHardware.decrypt(out)).isNull()
	}

	@Test
	fun `Given_a_secret_When_write_Then_only_ciphertext_reaches_SharedPreferences`() {
		assertThat(store.write(prefs, KEY, "hunter2")).isTrue()

		val raw = prefs.getString(KEY, null)
		assertThat(raw).startsWith(ENC_PREFIX)
		assertThat(raw).doesNotContain("hunter2")
		assertThat(store.decrypt(raw)).isEqualTo("hunter2")
	}

	@Test
	fun `Given_null_prefs_When_write_Then_false_is_returned_and_nothing_is_stored`() {
		assertThat(store.write(null, KEY, "hunter2")).isFalse()
	}

	@Test
	fun `Given_a_blank_value_When_write_Then_the_entry_is_forgotten_rather_than_stored_empty`() {
		store.write(prefs, KEY, "hunter2")

		listOf("", "   ", "\t\n").forEach { blank ->
			store.write(prefs, KEY, "hunter2")
			assertThat(store.write(prefs, KEY, blank)).isTrue()
			assertThat(prefs.contains(KEY)).isFalse()
		}
	}

	@Test
	fun `Given_encryption_fails_When_write_Then_false_is_returned_and_the_previous_value_survives`() {
		store.write(prefs, KEY, "old")
		val before = prefs.getString(KEY, null)
		keys.getFailure = KeyStoreException("Keystore unavailable")

		assertThat(store.write(prefs, KEY, "new")).isFalse()
		assertThat(prefs.getString(KEY, null)).isEqualTo(before)
	}

	@Test
	fun `Given_null_prefs_or_an_unset_key_When_readAndMigrate_Then_Absent_is_reported`() {
		assertThat(store.readAndMigrate(null, KEY)).isEqualTo(KeystoreSecretStore.Stored.Absent)
		assertThat(store.readAndMigrate(prefs, KEY)).isEqualTo(KeystoreSecretStore.Stored.Absent)
	}

	@Test
	fun `Given_a_value_the_store_wrote_When_readAndMigrate_Then_the_decrypted_value_is_returned`() {
		store.write(prefs, KEY, "hunter2")

		assertThat(store.readAndMigrate(prefs, KEY))
			.isEqualTo(KeystoreSecretStore.Stored.Value("hunter2"))
	}

	@Test
	fun `Given_a_stored_secret_and_a_lost_Keystore_key_When_readAndMigrate_Then_Unreadable_not_Absent_is_reported`() {
		store.write(prefs, KEY, "hunter2")

		// The distinction the tri-state exists for: reporting this as Absent is what tells a user
		// their credential was refused when it was never sent.
		val onNewHardware = KeystoreSecretStore(ALIAS, FakeKeySource())
		assertThat(onNewHardware.readAndMigrate(prefs, KEY))
			.isEqualTo(KeystoreSecretStore.Stored.Unreadable)
	}

	@Test
	fun `Given_a_stored_secret_and_an_unreachable_Keystore_When_readAndMigrate_Then_Unavailable_not_Unreadable_is_reported`() {
		store.write(prefs, KEY, "hunter2")
		keys.getFailure = KeyStoreException("Keystore unavailable")

		// Unreadable is the caller's cue to make the user type the credential again. The ciphertext
		// here is intact and the very next call succeeds, so saying Unreadable would be a lie that
		// costs the user their secret.
		assertThat(store.readAndMigrate(prefs, KEY))
			.isEqualTo(KeystoreSecretStore.Stored.Unavailable)

		keys.getFailure = null
		assertThat(store.readAndMigrate(prefs, KEY))
			.isEqualTo(KeystoreSecretStore.Stored.Value("hunter2"))
	}

	@Test
	fun `Given_a_stored_secret_and_an_invalidated_key_When_readAndMigrate_Then_Unreadable_is_reported`() {
		store.write(prefs, KEY, "hunter2")
		keys.invalidated = true

		// The other side of the split above: this one really is gone, and the read path does not
		// regenerate, because regenerating cannot bring the value back.
		assertThat(store.readAndMigrate(prefs, KEY))
			.isEqualTo(KeystoreSecretStore.Stored.Unreadable)
	}

	@Test
	fun `Given_a_stored_secret_and_a_key_whose_material_cannot_be_recovered_When_readAndMigrate_Then_Unreadable_is_reported`() {
		store.write(prefs, KEY, "hunter2")
		// KeyStore.getEntry declares UnrecoverableEntryException and engineGetKey raises the
		// UnrecoverableKeyException subclass; either means the entry is there but its key material is
		// not - permanent, so Unavailable here would have a conforming caller retry forever.
		keys.getFailure = UnrecoverableKeyException("Key material is gone")

		assertThat(store.readAndMigrate(prefs, KEY))
			.isEqualTo(KeystoreSecretStore.Stored.Unreadable)
	}

	@Test
	fun `Given_the_cipher_fails_transiently_When_readAndMigrate_Then_Unavailable_is_reported_and_the_ciphertext_survives`() {
		store.write(prefs, KEY, "hunter2")
		// Key acquisition succeeds and only the cipher fails, which is the dead-binder / busy-backend
		// shape. Reporting Unreadable here is the exact lie Stored.Unavailable was added to prevent.
		keys.cipherUnreachable = true

		assertThat(store.readAndMigrate(prefs, KEY))
			.isEqualTo(KeystoreSecretStore.Stored.Unavailable)
		assertThat(store.decrypt(prefs.getString(KEY, null))).isNull()

		// The proof that Unavailable was the honest answer: the stored bytes were never the problem.
		keys.cipherUnreachable = false
		assertThat(store.readAndMigrate(prefs, KEY))
			.isEqualTo(KeystoreSecretStore.Stored.Value("hunter2"))
	}

	@Test
	fun `Given_a_permanently_lost_value_When_readAndMigrate_Then_Unreadable_survives_the_transient_default`() {
		// The other side of the inversion: with everything unrecognised now defaulting to Unavailable,
		// the failures that really do mean the value is gone must still be recognised.
		store.write(prefs, KEY, "hunter2")
		val body = prefs.getString(KEY, null)!!.removePrefix(ENC_PREFIX)
		val raw = Base64.decode(body, Base64.NO_WRAP)
		raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 0x01).toByte()

		listOf(
			// GCM authentication failure: AEADBadTagException.
			ENC_PREFIX + Base64.encodeToString(raw, Base64.NO_WRAP),
			// Not base64, and too short to hold an IV: IllegalArgumentException.
			ENC_PREFIX + "!!!not-base64!!!",
			ENC_PREFIX + Base64.encodeToString(ByteArray(4), Base64.NO_WRAP),
		).forEach { stored ->
			prefs.edit().putString(KEY, stored).apply()
			assertThat(store.readAndMigrate(prefs, KEY))
				.isEqualTo(KeystoreSecretStore.Stored.Unreadable)
		}
	}

	@Test
	fun `Given_a_Stored_Value_When_it_is_printed_Then_the_secret_is_redacted`() {
		// The store zeroes its plaintext buffers so plaintext does not linger; a generated toString
		// would undo that the first time a caller logs a Stored or one lands in a crash breadcrumb.
		val value = KeystoreSecretStore.Stored.Value("hunter2")

		assertThat(value.toString()).doesNotContain("hunter2")
		assertThat("$value").doesNotContain("hunter2")
		assertThat(listOf(value).toString()).doesNotContain("hunter2")
		// Redacting the printout must not cost the equality the tri-state is compared by.
		assertThat(value.plain).isEqualTo("hunter2")
	}

	@Test
	fun `Given_a_legacy_plaintext_value_When_readAndMigrate_Then_it_is_upgraded_to_ciphertext_in_place`() {
		prefs.edit().putString(KEY, "legacy-secret").apply()

		assertThat(store.readAndMigrate(prefs, KEY))
			.isEqualTo(KeystoreSecretStore.Stored.Value("legacy-secret"))

		val raw = prefs.getString(KEY, null)
		assertThat(raw).startsWith(ENC_PREFIX)
		assertThat(raw).doesNotContain("legacy-secret")
		// Still readable after the upgrade, and stable across a second read.
		assertThat(store.readAndMigrate(prefs, KEY))
			.isEqualTo(KeystoreSecretStore.Stored.Value("legacy-secret"))
	}

	@Test
	fun `Given_a_legacy_value_padded_with_whitespace_When_readAndMigrate_Then_the_padding_is_preserved`() {
		prefs.edit().putString(KEY, "  legacy-secret\n").apply()

		// Trimming would rewrite a secret whose padding is significant, and unrecoverably: the
		// upgrade overwrites the very plaintext it was read from. write does not trim either.
		assertThat(store.readAndMigrate(prefs, KEY))
			.isEqualTo(KeystoreSecretStore.Stored.Value("  legacy-secret\n"))
		assertThat(store.decrypt(prefs.getString(KEY, null))).isEqualTo("  legacy-secret\n")
	}

	@Test
	fun `Given_a_secret_padded_with_whitespace_When_written_and_read_back_Then_both_paths_agree`() {
		val padded = "  padded-secret\n"
		assertThat(store.write(prefs, KEY, padded)).isTrue()

		// The encrypted path and the legacy-migration path must not disagree about the same secret.
		assertThat(store.readAndMigrate(prefs, KEY)).isEqualTo(KeystoreSecretStore.Stored.Value(padded))
	}

	@Test
	fun `Given_the_upgrade_cannot_encrypt_When_readAndMigrate_Then_the_plaintext_is_still_returned`() {
		prefs.edit().putString(KEY, "legacy-secret").apply()
		keys.getFailure = KeyStoreException("Keystore unavailable")

		// A Keystore that cannot encrypt must not cost the user a credential that already works.
		assertThat(store.readAndMigrate(prefs, KEY))
			.isEqualTo(KeystoreSecretStore.Stored.Value("legacy-secret"))
		assertThat(prefs.getString(KEY, null)).isEqualTo("legacy-secret")
	}

	@Test
	fun `Given_a_write_racing_a_legacy_upgrade_When_both_run_Then_the_newer_secret_survives`() {
		prefs.edit().putString(KEY, "legacy-secret").apply()
		val writer = Thread { store.write(prefs, KEY, "typed-by-the-user") }
		// readAndMigrate reads the legacy value, then re-encrypts it back over the same key. Let the
		// write run in exactly that window: unserialized, the upgrade silently reverts the credential
		// the user just typed, and nothing is surfaced to say so.
		val hooked =
			ReadHookedPrefs(prefs) {
				writer.start()
				awaitBlockedOrDone(writer)
			}

		val read = store.readAndMigrate(hooked, KEY)
		writer.join()

		assertThat(read).isEqualTo(KeystoreSecretStore.Stored.Value("legacy-secret"))
		assertThat(store.decrypt(prefs.getString(KEY, null))).isEqualTo("typed-by-the-user")
	}

	@Test
	fun `Given_a_blank_legacy_value_When_readAndMigrate_Then_it_is_purged_and_reported_Absent`() {
		listOf("", "   ", "\t\n").forEach { blank ->
			prefs.edit().putString(KEY, blank).apply()

			// Same rule write applies: a blank secret is no secret. Value("") would tell a caller a
			// credential is configured when none is.
			assertThat(store.readAndMigrate(prefs, KEY)).isEqualTo(KeystoreSecretStore.Stored.Absent)
			assertThat(prefs.contains(KEY)).isFalse()
		}
	}

	@Test
	fun `Given_two_stores_with_different_keys_When_each_reads_the_other_s_entry_Then_it_is_Unreadable`() {
		val other = KeystoreSecretStore(ALIAS, FakeKeySource())
		store.write(prefs, KEY, "mine")
		other.write(prefs, "other_key", "theirs")

		assertThat(store.readAndMigrate(prefs, KEY))
			.isEqualTo(KeystoreSecretStore.Stored.Value("mine"))
		assertThat(store.readAndMigrate(prefs, "other_key"))
			.isEqualTo(KeystoreSecretStore.Stored.Unreadable)
	}

	@Test
	fun `Given_the_public_alias_constructor_When_a_store_is_constructed_Then_the_Keystore_is_not_touched`() {
		// Plugins construct this on whatever thread builds their settings screen, so construction
		// must stay cheap: no Keystore IPC until a secret is actually read or written. Robolectric
		// has no AndroidKeyStore provider, so this only passes while that stays true.
		val real = KeystoreSecretStore(alias = "com.example.plugin.key")

		// Format handling needs no key at all, so it still works here.
		assertThat(real.decrypt(null)).isNull()
		assertThat(real.decrypt("legacy-plaintext")).isEqualTo("legacy-plaintext")
	}

	@Test
	fun `Given_a_payload_too_short_to_hold_an_IV_When_decrypt_Then_it_is_rejected_on_a_length_check`() {
		// The IV/ciphertext split is at a fixed offset, so a payload shorter than the IV is rejected
		// outright rather than by letting copyOfRange throw and catching that.
		(0 until 12).forEach { size ->
			val short =
				ENC_PREFIX + Base64.encodeToString(ByteArray(size), Base64.NO_WRAP)
			assertThat(store.decrypt(short)).isNull()
		}
	}

	@Test
	fun `Given_a_secret_When_write_Then_the_plaintext_never_reaches_the_backing_file`() {
		// The class's core claim is that a copied prefs file is useless without this device's
		// Keystore, so assert it against the real file rather than an in-memory read.
		// (This cannot also prove write's commit-not-apply flush: Robolectric drains apply()
		// synchronously, so the two are indistinguishable here.)
		val file =
			java.io.File(RuntimeEnvironment.getApplication().dataDir, "shared_prefs/secrets.xml")

		assertThat(store.write(prefs, KEY, "hunter2")).isTrue()

		assertThat(file.exists()).isTrue()
		val onDisk = file.readText()
		assertThat(onDisk).contains(KEY)
		assertThat(onDisk).doesNotContain("hunter2")
	}

	@Test
	fun `Given_many_threads_sharing_one_store_When_they_encrypt_and_decrypt_at_once_Then_every_secret_round_trips`() {
		// The store holds no mutable state beyond its alias lock, so concurrent use must be safe.
		// (The recovery compound is covered by the barrier test above; AndroidKeystoreSource's own
		// read-then-generate race is guarded inside it, and has no JVM stand-in.)
		val threads = 8
		val perThread = 40
		val failures = java.util.concurrent.ConcurrentLinkedQueue<String>()
		val start = java.util.concurrent.CountDownLatch(1)
		val workers =
			(0 until threads).map { t ->
				Thread {
					start.await()
					repeat(perThread) { i ->
						val secret = "secret-$t-$i"
						val back = store.decrypt(store.encrypt(secret))
						if (back != secret) failures += "$secret -> $back"
					}
				}.apply { start() }
			}

		start.countDown()
		workers.forEach { it.join() }

		assertThat(failures).isEmpty()
	}

	@Test
	fun `Given_the_four_Stored_cases_When_they_are_compared_Then_each_stays_distinct`() {
		val value = KeystoreSecretStore.Stored.Value("a")

		assertThat(value).isEqualTo(KeystoreSecretStore.Stored.Value("a"))
		assertThat(value).isNotEqualTo(KeystoreSecretStore.Stored.Value("b"))
		assertThat(value.plain).isEqualTo("a")
		assertThat(value.copy(plain = "b")).isEqualTo(KeystoreSecretStore.Stored.Value("b"))

		val cases =
			listOf(
				KeystoreSecretStore.Stored.Absent,
				value,
				KeystoreSecretStore.Stored.Unreadable,
				KeystoreSecretStore.Stored.Unavailable,
			)
		// Unavailable collapsing into any of the others is the bug the case exists to prevent.
		assertThat(cases.toSet()).hasSize(4)
	}

	/**
	 * Waits until [t] is parked on a monitor or has finished, so a race test can pin the interleaving
	 * instead of hoping for it. Serialized, the thread blocks; unserialized, it runs to completion -
	 * either way the caller may proceed, and the assertions are what tell the two apart.
	 */
	private fun awaitBlockedOrDone(t: Thread) {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
		while (System.nanoTime() < deadline) {
			if (t.state == Thread.State.BLOCKED || t.state == Thread.State.TERMINATED) return
			Thread.yield()
		}
		throw AssertionError("The racing thread never reached the store")
	}

	/**
	 * Delegating prefs that run [onFirstRead] once, right after the first `getString` returns - the
	 * one point inside `readAndMigrate`'s read-modify-write window a test can reach without the store
	 * exposing a seam for it. Hooking the key acquisition instead would not do: that happens inside
	 * `encrypt`, which takes the alias lock itself, so the racing thread would block either way and
	 * the test would pass against the unserialized code.
	 */
	private class ReadHookedPrefs(
		private val delegate: SharedPreferences,
		private val onFirstRead: () -> Unit,
	) : SharedPreferences by delegate {
		private val fired = AtomicBoolean(false)

		override fun getString(
			key: String?,
			defValue: String?,
		): String? =
			delegate.getString(key, defValue).also {
				if (fired.compareAndSet(false, true)) onFirstRead()
			}
	}

	/**
	 * A key whose material the provider cannot fetch, which is the shape a dead binder or a busy
	 * keymaster takes: `Cipher.init` asks a Keystore key for its encoding, the IPC fails, and an
	 * unchecked [ProviderException] comes out of the cipher rather than out of key acquisition.
	 */
	private class UnreachableKey(
		private val delegate: SecretKey,
	) : SecretKey by delegate {
		override fun getEncoded(): ByteArray = throw ProviderException("Keystore backend is busy")
	}

	/** An in-memory stand-in for the Android Keystore, which has no JVM provider. */
	private class FakeKeySource : SecretKeySource {
		@Volatile
		private var key: SecretKey = newKey()

		/** Every key minted so far, oldest first, so a test can prove a regeneration really happened. */
		val minted = CopyOnWriteArrayList(listOf(key))

		@Volatile
		var deleted = 0
			private set

		/**
		 * Makes [getOrCreate] fail the way an alias an OEM Keystore has dropped does.
		 *
		 * A property of the key rather than of the next call, which is what the real thing is: once
		 * one caller recovers by regenerating, every other caller sees the fresh valid key instead.
		 * A next-call-only flag would model a failure that cannot happen and would make the recovery
		 * race untestable. Cleared by [delete].
		 */
		@Volatile
		var invalidated = false

		/** Fails every [getOrCreate] with this, standing in for a Keystore that will not answer. */
		@Volatile
		var getFailure: Exception? = null

		/**
		 * Hands back a key the *cipher* cannot use, so key acquisition succeeds and only `Cipher.init`
		 * fails - the one interleaving that tells a transient cipher failure apart from a lost key.
		 */
		@Volatile
		var cipherUnreachable = false

		override fun getOrCreate(): SecretKey {
			if (invalidated) throw KeyPermanentlyInvalidatedException()
			getFailure?.let { throw it }
			return if (cipherUnreachable) UnreachableKey(key) else key
		}

		override fun delete() {
			// Minting a fresh key, not just counting: handing back the very key delete claims to have
			// dropped makes the round-trip assertions pass against broken regeneration too.
			synchronized(this) {
				deleted++
				invalidated = false
				key = newKey()
				minted += key
			}
		}

		private companion object {
			fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
		}
	}

	private companion object {
		const val ALIAS = "KeystoreSecretStoreTest"
		const val KEY = "api_key"

		/**
		 * Pinned here as a literal rather than read off the class: the marker is the on-disk format,
		 * so changing it must break a test loudly instead of silently agreeing with itself.
		 */
		const val ENC_PREFIX = "enc:v1:"
	}
}
