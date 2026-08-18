# well-known Worker

Serves `https://appdevforall.org/.well-known/assetlinks.json` (and the `www`
host) out of the private `well-known` R2 bucket, for Android App Link
verification (ADFA-5067).

## Why a Worker and not an Origin Rule

R2 picks a bucket from the `Host` header, so pointing a path at it with an
Origin Rule needs a host header override plus a DNS record override. Both are
Enterprise-only; the Free plan exposes just the destination-port override.

A Worker replaces the origin fetch instead of retargeting it. `env.WELL_KNOWN`
is an in-network binding rather than a URL, so no DNS, TLS or `Host` header is
involved and the bucket needs no public hostname at all. **Leave the bucket's
public access disabled** - it is reachable only through this Worker.

A Redirect Rule to an R2 custom domain is not an alternative: Android's App Link
verifier does not follow redirects.

## Shape

- Routes match **exact paths**, not `/.well-known/*`. A wildcard would also
  capture `/.well-known/acme-challenge/`, which the origin needs for certificate
  renewal.
- A request whose object is missing falls through to the site origin, so the
  Worker can never black-hole a path it does not own.
- `Content-Type` is replayed from the object's stored metadata. The uploader
  sets `application/json`; re-uploading by hand without `--content-type` yields
  `binary/octet-stream` and fails verification.
- No `Cache-Control`. `signing-fingerprint.yml` re-fetches the URL within seconds
  of writing it and compares bytes, so a cached copy would fail that check on a
  legitimate update.

## Deploying

CI only, via `.github/workflows/deploy-well-known-worker.yml` - it runs on any
push touching this directory, and on manual dispatch.

Prerequisites:

1. The `well-known` R2 bucket exists in the account. `wrangler deploy` does not
   create it.
2. A `CLOUDFLARE_WORKERS_DEPLOY_TOKEN` repository secret, non-expiring, with:
   - **Account -> Workers Scripts -> Edit**
   - **Account -> Workers R2 Storage -> Read** - wrangler resolves the bucket
     named in the binding via `GET /accounts/<id>/r2/buckets/well-known`, and
     fails with `Authentication error [code: 10000]` without it
   - **Zone -> Workers Routes -> Edit** on `appdevforall.org`

   The existing `CLOUDFLARE_KEY_ID` / `CLOUDFLARE_SECRET_ACCESS_KEY` pair is an
   R2 S3-compatible credential and cannot deploy a Worker.

## Verifying

The object is published by the **Print release signing certificate fingerprint**
workflow with `deploy` enabled, which also verifies the served result. By hand:

```bash
curl -sSI https://www.appdevforall.org/.well-known/assetlinks.json   # 200, application/json, no 3xx
adb shell pm verify-app-links --re-verify com.itsaky.androidide
adb shell pm get-app-links com.itsaky.androidide
```
