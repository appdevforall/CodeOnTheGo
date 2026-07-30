# Recompressing oversized images in android-sdk.zip

`android-sdk-arm64-v8a.zip` / `android-sdk-armeabi-v7a.zip` are downloaded assets
(see `debugAssets`/`releaseAssets` in `app/build.gradle.kts`), not built in this
repo — they're a packaged copy of Android SDK platform data (`platforms/android-<N>/`)
fetched from appdevforall.org. Some of the bundled platform resources are large,
unoptimized PNGs that bloat the APK for no benefit (ADFA-3771 found two default
wallpaper images at ~2.7MB and ~3.9MB).

## Finding an oversized asset

```sh
unzip -l assets/android-sdk-arm64-v8a.zip | sort -n -k1 | tail -20
```

## Recompressing

Lossless recompression (`zopflipng`) barely helps here — these PNGs are already
well DEFLATE-compressed, and re-running DEFLATE on the same pixel data doesn't
buy much. The real win is `pngquant`'s palette quantization, which is lossy but
usually visually indistinguishable at these quality settings for
photographic/gradient content like a wallpaper:

```sh
pngquant --quality=80-95 --strip --force --output out.png in.png
```

**`pngquant` on this machine is a snap package** and can't read files outside
its allowed roots — a path like `/tmp/.../scratchpad/foo.png` fails with
`cannot open ... for reading` even though the file exists and is readable by
the same user. Snap confinement only grants read/write access under `$HOME`,
`/media`, and a few other allowed roots, so copy the input into `$HOME` first
(the same workaround applies to other snap-packaged CLI tools, e.g. `jira-cli`).
Confirmed savings for
ADFA-3771: `drawable-sw600dp-nodpi/default_wallpaper.png` 2.75MB → 844KB (69%),
`drawable-sw720dp-nodpi/default_wallpaper.png` 3.94MB → 1.43MB (64%).

Always visually diff before/after (open both images) — quantization quality
varies a lot by image content, and this doesn't have a UI test to catch a bad
result.

## Updating the zip in place

`zip` can replace a single entry in an existing archive without rewriting the
whole thing, as long as the working directory mirrors the archive's internal
path structure. Substitute `android-<N>` below with whichever platform
directory (from `unzip -l`) actually contains the target asset — it's
`android-36` for ADFA-3771's SDK level, but will differ for other assets or
future SDK bumps:

```sh
mkdir -p work/platforms/android-<N>/data/res/drawable-sw600dp-nodpi
cp out.png work/platforms/android-<N>/data/res/drawable-sw600dp-nodpi/default_wallpaper.png
cd work
zip ../assets/android-sdk-arm64-v8a.zip platforms/android-<N>/data/res/drawable-sw600dp-nodpi/default_wallpaper.png
```

Repeat for `android-sdk-armeabi-v7a.zip` — SDK platform resources are
architecture-independent, so both zips carry byte-identical copies of these
files (verified via `md5sum` before touching either). Verify afterward:

```sh
unzip -t assets/android-sdk-arm64-v8a.zip     # "No errors detected"
unzip -t assets/android-sdk-armeabi-v7a.zip   # "No errors detected"
```

## Scope

This only rewrites the locally downloaded copies under `assets/` (gitignored —
see `assetsDownloadDebug`/`assetsDownloadRelease` in `app/build.gradle.kts`).
Getting the recompressed images into the real, published `android-sdk.zip` /
`android-sdk.zip.br` on appdevforall.org is a separate, external publishing
step outside this repo.
