package com.appdevforall.keygen.plugin

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Security
import java.util.Date

/**
 * Data class representing keystore configuration
 */
data class KeystoreConfig(
    val keystoreName: String,
    val keystorePassword: CharArray,
    val keyAlias: String,
    val keyPassword: CharArray,
    val certificateName: String,
    val organizationalUnit: String?,
    val organization: String?,
    val city: String?,
    val state: String?,
    val country: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as KeystoreConfig

        if (keystoreName != other.keystoreName) return false
        if (!keystorePassword.contentEquals(other.keystorePassword)) return false
        if (keyAlias != other.keyAlias) return false
        if (!keyPassword.contentEquals(other.keyPassword)) return false
        if (certificateName != other.certificateName) return false
        if (organizationalUnit != other.organizationalUnit) return false
        if (organization != other.organization) return false
        if (city != other.city) return false
        if (state != other.state) return false
        if (country != other.country) return false

        return true
    }

    override fun hashCode(): Int {
        var result = keystoreName.hashCode()
        result = 31 * result + keystorePassword.contentHashCode()
        result = 31 * result + keyAlias.hashCode()
        result = 31 * result + keyPassword.contentHashCode()
        result = 31 * result + certificateName.hashCode()
        result = 31 * result + (organizationalUnit?.hashCode() ?: 0)
        result = 31 * result + (organization?.hashCode() ?: 0)
        result = 31 * result + (city?.hashCode() ?: 0)
        result = 31 * result + (state?.hashCode() ?: 0)
        result = 31 * result + country.hashCode()
        return result
    }
}

/**
 * Result of keystore generation operation
 */
sealed class KeystoreGenerationResult {
    data class Success(val keystoreFile: File) : KeystoreGenerationResult()
    data class Error(val message: String, val exception: Throwable? = null) : KeystoreGenerationResult()
}

/**
 * Utility class for generating Android keystores using BouncyCastle
 */
object KeystoreGenerator {

    init {
        // Remove the OS provided bouncy castle provider if it exists
        try {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        } catch (e: Exception) {
            // Provider might not be present, ignore
        }
        // Add the bouncy castle provider from the added library
        Security.addProvider(BouncyCastleProvider())
    }

    /**
     * Generates a new Android keystore with the specified configuration
     *
     * @param config The keystore configuration
     * @param outputDirectory The directory where to save the keystore file
     * @return Result of the generation operation
     */
    fun generateKeystore(config: KeystoreConfig, outputDirectory: File): KeystoreGenerationResult {
        return try {
            // Ensure output directory exists
            if (!outputDirectory.exists()) {
                outputDirectory.mkdirs()
            }

            // 1. Generate a key pair
            val keyPairGenerator = KeyPairGenerator.getInstance("RSA", "BC")
            keyPairGenerator.initialize(2048)
            val keyPair = keyPairGenerator.genKeyPair()

            // 2. Create the self-signed certificate using Bouncy Castle
            val issuerName = buildDistinguishedName(config)
            val certificateBuilder = JcaX509v3CertificateBuilder(
                issuerName,
                BigInteger.valueOf(Date().time),
                Date(System.currentTimeMillis()),
                Date(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000 * 25), // 25 years validity
                issuerName,
                keyPair.public
            )

            val signer: ContentSigner = JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC")
                .build(keyPair.private)

            val certificate = certificateBuilder.build(signer)

            // 3. Create a keystore instance and store the key entry
            val keyStore = KeyStore.getInstance("BKS")
            keyStore.load(null, null)

            val javaCertificate = JcaX509CertificateConverter()
                .setProvider("BC")
                .getCertificate(certificate)

            keyStore.setKeyEntry(
                config.keyAlias,
                keyPair.private,
                config.keyPassword,
                arrayOf(javaCertificate)
            )

            // 4. Save the keystore to file
            val keystoreFile = File(outputDirectory, config.keystoreName)
            FileOutputStream(keystoreFile).use { fos ->
                keyStore.store(fos, config.keystorePassword)
            }

            KeystoreGenerationResult.Success(keystoreFile)

        } catch (e: Exception) {
            KeystoreGenerationResult.Error("Failed to generate keystore: ${e.message}", e)
        }
    }

    /**
     * Builds the X.500 distinguished name from the certificate configuration
     */
    private fun buildDistinguishedName(config: KeystoreConfig): X500Name {
        val components = mutableListOf<String>()

        components.add("CN=${config.certificateName}")

        config.organizationalUnit?.takeIf { it.isNotBlank() }?.let {
            components.add("OU=$it")
        }

        config.organization?.takeIf { it.isNotBlank() }?.let {
            components.add("O=$it")
        }

        config.city?.takeIf { it.isNotBlank() }?.let {
            components.add("L=$it")
        }

        config.state?.takeIf { it.isNotBlank() }?.let {
            components.add("ST=$it")
        }

        components.add("C=${config.country}")

        return X500Name(components.joinToString(", "))
    }

    /**
     * Validates the keystore configuration
     *
     * @param config The configuration to validate
     * @return List of validation errors, empty if valid
     */
    fun validateConfig(config: KeystoreConfig): List<String> {
        val errors = mutableListOf<String>()

        if (config.keystoreName.isBlank()) {
            errors.add("Keystore name cannot be empty")
        } else if (!config.keystoreName.endsWith(".jks") && !config.keystoreName.endsWith(".keystore")) {
            errors.add("Keystore name should end with .jks or .keystore")
        }

        if (config.keystorePassword.isEmpty()) {
            errors.add("Keystore password cannot be empty")
        } else if (config.keystorePassword.size < 6) {
            errors.add("Keystore password should be at least 6 characters")
        }

        if (config.keyAlias.isBlank()) {
            errors.add("Key alias cannot be empty")
        }

        if (config.keyPassword.isEmpty()) {
            errors.add("Key password cannot be empty")
        } else if (config.keyPassword.size < 6) {
            errors.add("Key password should be at least 6 characters")
        }

        if (config.certificateName.isBlank()) {
            errors.add("Certificate name cannot be empty")
        }

        if (config.country.length != 2) {
            errors.add("Country code must be exactly 2 characters")
        }

        return errors
    }
}