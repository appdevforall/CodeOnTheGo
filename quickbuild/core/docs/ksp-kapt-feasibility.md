# Can the quick-build daemon run kapt/KSP itself?

Research only - nothing here is implemented. Today an edit touching annotation-processor input (a
`@Dao`, `@Entity`, `@Module`) falls off the live reload path and takes a full proxy app rebuild,
though the shipped classifier
([`domain/annotations/`](../src/main/java/org/appdevforall/cotg/quickbuild/domain/annotations))
already keeps every *other* edit in the same project on live reload.

**Finding:** both mechanisms are reachable from the daemon. kapt rides the existing `-Xplugin`
route (475 KB jar); KSP2 works too but needs an 83 MB standalone jar bundling its own Analysis API
session - on the low-end offline devices this product exists for. The prize is narrow.

**Recommended next step: one ~1-hour A56 experiment.** Everything else hangs on it. Run a plain JVM
as the app's uid (`adb shell run-as com.itsaky.androidide`, CoGo's bundled OpenJDK), open
`jdbc:sqlite::memory:` through xerial sqlite-jdbc with `org.sqlite.lib.path` pointed at an
extracted `Linux-Android/aarch64/libsqlitejdbc.so`, and see whether the connection opens. If it
does, Room's verifier is not a wall and the choice becomes a plain cost comparison - kapt first. If
it doesn't, the fallback is unchanged: processor-touching edits keep going to Gradle, already a
small minority.

| Mechanism | Verdict | Cost `[assumed]` | Why |
|---|---|---|---|
| Java `annotationProcessor` (JSR-269) | cheap | days | rides the daemon's in-process javac; needs `-processorpath` + generated-source routing. Covers only Java sources, not a Kotlin-declared `@Entity`, so it doesn't move the needle |
| kapt via `-Xplugin=` | possible | 1-2 weeks | reuses the plugin-jar route, but needs a second kotlinc pass orchestrated alongside the incremental compile |
| KSP2 standalone | possible, heavy | weeks | incremental-aware, but ships 83 MB and does not reuse the daemon's compile session |
| KSP1 as a compiler plugin | gone | n/a | KSP 2.3.6 dropped `symbol-processing-cmdline` |

## Room's wall is not what it looks like

`DatabaseVerifier$Companion.create` catches only `java.lang.Exception`, but a failed native load
throws `UnsatisfiedLinkError` - an `Error`. It escapes the catch and kills the processing round
instead of degrading to Room's documented "verification disabled" warning.

The native itself is fine: `sqlite-jdbc-3.41.2.2.jar` ships a real bionic build at
`org/sqlite/native/Linux-Android/aarch64/libsqlitejdbc.so` (`DT_NEEDED`: `libm.so`, `libc.so`,
`libandroid.so` - not the `libm.so.6` glibc build that failed in a prior on-device attempt).
Selection depends on `OSInfo.isAndroid()`, which the daemon's bundled OpenJDK likely fails
`[unverified, needs a device]` - but `SQLiteJDBCLoader` exposes `org.sqlite.lib.path` /
`org.sqlite.lib.name`, so the daemon can force-load the right native without patching Room or
sqlite-jdbc.

There is no `room.verifySchema=false` opt-out (only source-level `@SkipQueryVerification`), and KSP
vs kapt makes no difference - both share the same `DatabaseProcessor` path.

## Known gaps

- **Version provenance unverified**: 3.41.2.2 is what was in this workspace's cache, not confirmed
  as what room-compiler 2.8.4 resolves.
- **Whether kapt's stub pass can share the daemon's incremental caches is unknown** - it is a
  separate kotlinc invocation, not a plugin riding the normal compile the way Compose does.
- **The delivery half is untested**: whether newly-generated classes the baseline dex has never
  seen load through live reload at all. Confirm alongside the A56 experiment.
- **How often processor-input edits happen in practice is `[unmeasured]`** - the corpus e2e data
  can answer this, and should, before investing in either mechanism.
