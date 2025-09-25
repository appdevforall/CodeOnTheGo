# Debug Keystore Generator

This directory contains scripts to generate a debug keystore for Android development.

## Usage

### Generate Debug Keystore

Run the shell script to generate a new debug keystore:

```bash
./generate_debug_keystore.sh
```

This script will:
- Generate a secure 16-character password using `make_password.py`
- Create a new keystore file (`adfa-keystore.jks`) with RSA 2048-bit key
- Generate a base64-encoded version (`adfa-keystore.jks.base64`)
- Display the keystore password and file information

## Requirements

- `uv` (Python package manager)
- `keytool` (Java keytool utility)
- `openssl` (for base64 encoding)

## Files Generated

- `adfa-keystore.jks` - The keystore file
- `adfa-keystore.jks.base64` - Base64-encoded version for storage/transmission

## Keystore Details

- **Alias**: `cogo-debug-key`
- **Algorithm**: RSA 2048-bit
- **Validity**: 10000 days
- **Subject**: CN=Hal Eisen, OU=Engineering, O=App Dev For All, L=San Francisco, ST=CA, C=US

## GitHub Actions Notes

After the data is generated, it needs to be made available to our CI/CD pipeline. See the `env` section of "Assemble Universal APK" in debug.yml

### Secrets

- **KEYSTORE_BASE64**: Contains the encoded keystore
- **KEYSTORE_PASSWORD**: Required for Gradle to access the keystore AND the key

### Variables

- **KEY_ALIAS**: The name of the key