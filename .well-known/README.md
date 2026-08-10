# `.well-known` (ADFA-5067)

`assetlinks.json` in this directory is the [RFC 5785](https://www.rfc-editor.org/rfc/rfc5785) /
[Digital Asset Links](https://developers.google.com/digital-asset-links) file required for Android
App Links to `https://www.appdevforall.org/device/open/project/...` to auto-verify.

This directory lives in the repo only until the actual website exists. To activate it:

1. Copy this directory verbatim to the web server root, so it serves at
   `https://www.appdevforall.org/.well-known/assetlinks.json` with `Content-Type: application/json`.
2. Replace the `TODO_REPLACE_WITH_RELEASE_SIGNING_SHA256_FINGERPRINT` placeholder with the SHA-256
   fingerprint of the certificate that actually signs the released APK/AAB — get it via
   `keytool -list -v -keystore <release.jks>` (whoever holds the release keystore), or from the Play
   Console under **App integrity > App signing key certificate** if Play App Signing is used. This
   cannot be filled in from source; it's a secret held by release engineering, not derivable from this
   repository.

Until both steps are done, `android:autoVerify="true"` on `DeepLinkActivity`'s intent-filter will fail
Digital Asset Links verification, and Android may show a disambiguation chooser instead of opening the
app directly when a link is tapped. This is expected for now.
