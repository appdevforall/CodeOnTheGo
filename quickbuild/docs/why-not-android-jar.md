# Information: why Quick Build does not replace `android.jar`

David proposed two routes to a fast on-device edit loop. The first, **Stubby**, ran the app's
`.class` bytecode directly in a JVM inside a stub app and replaced `android.jar` with an
interceptable copy - a design he wrote up in "Improving User Productivity" (Confluence
`591495169`). The second is what became ADFA-4128, and his own 25-May-2026 note on that same page
points at it as "a less technically-challenging idea". This page records why the cheaper of his two
ideas won, and - because he asked the `android.jar` question again on the discussion doc
(`900038699`, threads `906395719`, `907378691`, `905150475`) - why the hook could not be
`android.jar` even in the parts of Stubby we wanted to keep.

## The two properties worth protecting

David's reason for reaching for `android.jar` was sound, and both properties survive in the
shipped design:

- **User code untouched.** No base-class edits, no annotations, no build-file surgery in the
  user's project. Quick Build interposes at the manifest, not in the user's sources
  ([`component-proxying-design.md`](component-proxying-design.md)).
- **No per-build cost.** Interception is wired **once, at proxy-app setup time**, not on each
  save. The hot loop never rewrites bytecode to install a hook.

## Why `android.jar` cannot be the hook

Three properties, any one of which is fatal:

1. **It is a compile-time stub.** Every method body in `android.jar` throws
   `RuntimeException("Stub!")`; the jar exists to satisfy `javac`/`kotlinc` and is never
   executed `[inferred]` (checkable by decompiling any SDK `android.jar`). It is not even a
   complete view of the platform: hidden members are omitted outright, which is why the
   API 28/29 resource shim has to reach `AssetManager.addAssetPath` reflectively - on the JVM
   that call cannot be made at all, and a unit test pins the resulting `IOException`
   (`runtime/src/test/java/.../LegacyResourceSwapAddAssetPathTest.java`) `[measured on host]`.
2. **It has no runtime existence to modify.** At runtime the framework comes from the device's
   boot image / `framework.jar` on the boot classpath. Shipping a modified `android.jar` in the
   APK changes nothing, because nothing loads it `[inferred]`.
3. **ART's boot classpath cannot be shadowed.** Classloading is parent-first and the boot
   classpath is the root parent, so an app-dex class named `android.content.res.Resources`
   loses to the platform's `[inferred]`.

## What replacing it would actually cost

Akash's point on thread `905871361` is the load-bearing one: **ART executes dex only, never
`.class`.** So Stubby is not "swap a jar" - it is (a) porting or shipping a JVM to Android to run
the `.class` files, and (b) building an `android.jar` marshalling bridge that vectors every
framework call from that JVM across to the real platform. David's own IPC-boundary analysis in
that thread priced the bridge and concluded it was "not trivial work but doable" - which is the
same verdict the spike reached, from the other end: the Mini-Stubby `DESIGN.md` decision D1
rejects the dex-free JVM as "too technically challenging", specifically because it "must intercept
**all** of `android.jar`" (branch `ADFA-4128-prototype`, `spike/mini-stubby/DESIGN.md`).

And the thing that buys is only the dex step:

- In the spike, whole-app `d8` was ~0.3 s against kotlinc's ~2.3 s `[measured on a56]`
  (`DESIGN.md` D1) - a ~0.3 s saving for a JVM port.
- In shipped Quick Build the dex step is larger, 2.2-3.1 s of a ~15 s warm edit on the corpus's
  worst app `[measured on a56]` ([`perf-roadmap.md`](perf-roadmap.md)) - but the cheap fix for
  that is **incremental dexing** (lever 2, 2.1-4.6 s per warm edit `[measured on a56]`), not a
  new runtime.
- Either way **compile remains the dominant term**, and Stubby does not help it: it still has to
  run the same `kotlinc` and `javac` `[inferred]`.

## What the shipped design does instead

- **Resources** go through `ResourcesLoader`/`ResourcesProvider.loadFromApk` on API 30+, with an
  `addAssetPath` shim on 28/29 (`runtime/.../ResourceStore.java`, `ResourceSwapStrategy`). This is
  the platform-sanctioned redirect for exactly what thread `905150475` asked for - replacing
  resource loading - and it needs no framework patching. API 28/29 is unit-tested, device
  verification still pending `[unverified]`.
- **Components** go through generated `Proxy<N><Type>` classes named in the merged manifest, so
  the OS instantiates a class that is in the APK while the code behind the name changes every
  reload (thread `906166273`). Generated at setup, so per-build cost stays zero.
- **Swapping the user's base classes** was the other option in that thread. It works where we own
  the code (CoGo templates) but not for arbitrary apps - `sora-editor` and `StreetComplete` extend
  androidx classes - and it violates "user code untouched" `[inferred]`.
- **The boundary that actually matters is the manifest, not `android.jar`.** From the spike's
  `CAPABILITY-MATRIX.md`: anything the OS reads from the manifest *before your code runs*
  (activities, permissions, icon/label, exported components, custom `Application`) belongs to the
  installed shell; everything the payload's code touches at runtime - views, resources, themes,
  native libs, Compose, Fragments - is hot-loadable. Quick Build draws its line there.

## What would reopen the question

- Compile time falling far enough that dex becomes the dominant cost, *after* incremental dexing
  lands. Nothing in today's numbers points that way `[measured on a56]`.
- Devices below API 28, where neither resource path exists; the candidate there is Akash's
  `Context.getResources()` hook (thread `905609219`), not `android.jar` `[unmeasured]`.

Thread `905674797` is the useful summary of the delta: David's own description of "Son of Stubby"
matches what got built, with two substitutions - deploy is binder + `ParcelFileDescriptor` rather
than reading the project directory, and interception is setup-time proxies rather than
`android.jar`.
