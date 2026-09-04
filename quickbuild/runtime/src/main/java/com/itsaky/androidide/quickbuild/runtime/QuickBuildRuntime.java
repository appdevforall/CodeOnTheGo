package com.itsaky.androidide.quickbuild.runtime;

import android.app.Activity;
import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Coordinates the proxy app runtime: takes payloads from {@link QuickBuildClient}, applies them to {@link PayloadStore} and {@link ResourceStore}, drives the reload, and keeps the {@link StatusOverlay} and the reports to CoGo honest.
 *
 * Installed once per process by {@link QuickBuildAppComponentFactory} at application instantiation; Context work - binding to CoGo, cache dirs - waits for the first activity, since the Application has no base context yet.
 *
 * Failure policy throughout: a reload failure reports the crash, and rolls back when the store adopted the failed generation, so the app keeps running the last working code rather than crash-looping or silently claiming the new generation. Only a failure superseded by a newer live generation stays silent. The rollback is of the code: a resource swap that had already committed stays live, and that case is reported as a mixed state that a restart clears rather than as the last working version.
 */
final class QuickBuildRuntime {

	/**
	 * How long a restart deploy waits for the framework to take the app's state, across both phases of the handoff.
	 *
	 * Only spent when the app is actually in front, which in the normal loop it is not - the user is typing in CoGo, so every activity is already stopped and both phases pass at once. Bounded well under the host's 5 s disconnect wait, since the kill is owed either way.
	 */
	private static final long RESTART_HANDOFF_TIMEOUT_MILLIS = 1500;

	/** The one runtime per process, or null before {@link #install}. */
	private static volatile QuickBuildRuntime instance;

	/**
	 * Routes a resumed activity's completion, to its first drawn frame when one is coming and inline when none is.
	 *
	 * Package-private and free of Activity so a JVM test can pin the branch that matters: a resume with a frame still coming must complete NOTHING, which is what leaves the generation pending in {@link FirstFrameGate} and blamable by {@link BootProbation}. The Activity overload cannot be driven from a unit test - it needs a Window and a live ViewTreeObserver - so without this seam the rule lives only in the gate class and the call site is unpinned.
	 *
	 * @param installDrawCallback
	 *            installs the first-frame callback; false when the activity has no live view tree to hang one on
	 * @param completeNow
	 *            the completion, run only when no frame is coming
	 */
	static void completeOnResume(FrameCallbackInstaller installDrawCallback, Runnable completeNow) {
		if (!installDrawCallback.install()) {
			completeNow.run();
		}
	}

	/**
	 * Creates and starts the one runtime for this process. Idempotent, and never throws.
	 *
	 * @param application
	 *            the app's Application, already instantiated but without a base context yet, so only non-Context setup runs here; null is ignored
	 */
	static void install(Application application) {
		if (instance != null || application == null) {
			return;
		}
		synchronized (QuickBuildRuntime.class) {
			if (instance != null) {
				return;
			}
			try {
				QuickBuildRuntime runtime = new QuickBuildRuntime(application);
				runtime.start();
				instance = runtime;
			} catch (Throwable error) {
				RuntimeLog.e("failed to install quick build runtime", error);
			}
		}
	}

	/**
	 * Runs a reload-failure body on its own thread. Package-private so the JVM test can pin the dispatch off the caller's thread, which is what keeps the quarantine fsync off the frame path.
	 *
	 * @param body
	 *            the failure handling to run
	 * @return the started thread, so a test can join it
	 */
	static Thread startFailReloadThread(Runnable body) {
		Thread thread = new Thread(body, "qb-fail-reload");
		thread.start();
		return thread;
	}

	/**
	 * Runs the boot-time resource restore on its own thread. Package-private so the JVM test can pin the dispatch off the caller's thread, which is the main thread inside the first activity's creation.
	 *
	 * @param body
	 *            the restore to run
	 * @return the started thread, so a test can join it
	 */
	static Thread startBootRestoreThread(Runnable body) {
		Thread thread = new Thread(body, "qb-boot-restore");
		thread.start();
		return thread;
	}

	/**
	 * Opens a persisted store file as a read-only fd, the form the resource paths take.
	 *
	 * @param file
	 *            the store file to open; must exist
	 * @return the fd, which the callee closes
	 * @throws IOException
	 *             when the file cannot be opened
	 */
	private static ParcelFileDescriptor openReadOnly(File file) throws IOException {
		return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
	}

	/**
	 * Parses deploy metadata, falling back to defaults so a bad blob cannot block a code reload.
	 *
	 * @param metadataJson
	 *            the metadata document from the host
	 * @return the parsed metadata, or a recreate-only default with no entry activity when the document is malformed
	 */
	private static DeployMetadata parseMetadata(String metadataJson) {
		try {
			return DeployMetadata.parse(metadataJson);
		} catch (IllegalArgumentException error) {
			// Defaults are recreate-only, with no entry launch.
			RuntimeLog.e("unparseable deploy metadata; using defaults", error);
			return new DeployMetadata(null, false);
		}
	}

	/**
	 * Drains one payload fd into memory and closes it.
	 *
	 * @param fd
	 *            the payload fd, or null when this deploy carried nothing of that kind
	 * @return the bytes, or null when {@code fd} was null
	 * @throws IOException
	 *             on a read failure or when the payload exceeds the size cap; the fd is still closed
	 */
	private static byte[] readBytesAndClose(ParcelFileDescriptor fd) throws IOException {
		if (fd == null) {
			return null;
		}
		// A regular-file fd knows its size, so the buffer is allocated once instead of
		// doubling and copying its way up to a payload-sized array; a pipe reports -1,
		// which readFully takes as unknown.
		long size = fd.getStatSize();
		InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd);
		try {
			return Streams.readFully(in, Streams.MAX_PAYLOAD_BYTES, size);
		} finally {
			in.close();
		}
	}

	/**
	 * Wraps one payload fd as a stream, without reading any of it.
	 *
	 * Only the dex has to become a byte array, because {@code InMemoryDexClassLoader} takes one. Resources and assets are written straight to their store files and then reopened as files, so nothing is gained by holding a whole resource apk in the heap on the way past - and on a low-end device it is what an oversize deploy would die on.
	 *
	 * @param fd
	 *            the payload fd, or null when this deploy carried nothing of that kind
	 * @return a stream that owns {@code fd} and closes it, or null when {@code fd} was null
	 */
	private static InputStream streamOf(ParcelFileDescriptor fd) {
		return fd == null ? null : new ParcelFileDescriptor.AutoCloseInputStream(fd);
	}

	private final Application application;
	private final Handler mainHandler = new Handler(Looper.getMainLooper());

	private final ActivityTracker tracker = new ActivityTracker(this);
	private final QuickBuildClient client = new QuickBuildClient(this);

	private final StatusOverlay overlay = new StatusOverlay();

	/** Whether the generation this process booted from the store has proved itself yet. */
	private final BootProbation bootProbation = new BootProbation();

	/** The restart path's wait for the framework to be told the app's state before the process dies. */
	private final RestartHandoff restartHandoff = new RestartHandoff();

	/** What the overlay should show; written from any thread, rendered on the main one. */
	private volatile OverlayState overlayState = OverlayState.hidden();

	/** Holds a generation's ack and its probation until the frame that rendered it has drawn. */
	private final FirstFrameGate firstFrame = new FirstFrameGate();

	/**
	 * Newest generation whose resource swap failed, or -1 before the first.
	 *
	 * The swap failure arrives on the main thread after the deploy thread has queued - or is about to queue - the recreate for the same generation, and the rollback now runs off-thread, so the recreate can beat it and render the generation the rollback is undoing. Written on main inside the swap guard, read by the posted recreate.
	 */
	private volatile long abandonedReloadGeneration = -1;

	/** Uptime at which the pending reload's payload arrived, the start of the reported duration. */
	private volatile long pendingReloadStartUptime;

	/** Latches the legacy resource-apk cache sweep, which is only safe before the first swap. */
	private boolean sweptLegacyResourceCache;

	/** Newest generation already recorded as good, so the write happens once rather than per resume. */
	private volatile long lastMarkedGoodGeneration = -1;

	/**
	 * True from the moment a persisted generation's resources are taken for restore until their swap has landed or failed.
	 *
	 * The restore runs off the main thread and its swap is posted, so the first activity draws against the baseline table. That frame proves the code half only: {@link #markLiveGenerationGood} waits for this to clear, or a generation whose resources then fail to render would already be recorded as good and be unblamable.
	 */
	private volatile boolean bootRestoreInFlight;

	/**
	 * @param application
	 *            the app's Application; retained for its package name, cache dir and lifecycle callbacks, and safe to hold because the runtime is process-scoped
	 */
	private QuickBuildRuntime(Application application) {
		this.application = application;
	}

	/**
	 * Turns a build-status message from CoGo into overlay state.
	 *
	 * This is the only way the running app learns about a compile error, which never produces a payload. Runs on a binder thread and swallows every throwable, so nothing escapes into the binder.
	 *
	 * @param statusJson
	 *            the status document from the host; an unknown kind or malformed document is dropped, leaving the overlay as it was
	 */
	void handleBuildStatus(String statusJson) {
		try {
			BuildStatus status = BuildStatus.parse(statusJson);
			if (status == null) {
				// Unknown kind from a newer CoGo: the versioning contract says ignore.
				return;
			}
			if (BuildStatus.KIND_BUILD_FAILED.equals(status.kind)) {
				setOverlayState(OverlayState.buildFailed(status));
			} else if (BuildStatus.KIND_BUILDING.equals(status.kind)) {
				// Replaces whatever was showing (a stale failure or nothing) - a new
				// attempt starting is real news either way.
				setOverlayState(OverlayState.building(status.runningGeneration));
			} else if (BuildStatus.KIND_REINSTALL_PENDING.equals(status.kind)) {
				// The update is built but its install confirm can only be shown from
				// CoGo; this banner is the one signal that reaches the user watching
				// the stale app.
				setOverlayState(OverlayState.reinstallPending());
			} else if (overlayState.isError() || overlayState.isBuilding()) {
				// build_ok clears a stale failure or in-flight banner; it never renders
				// anything itself.
				setOverlayState(OverlayState.hidden());
			}
		} catch (Throwable error) {
			RuntimeLog.w("unusable build status; dropped", error);
		}
	}

	/**
	 * Applies one deploy: reads the payload fds, persists them, then swaps in the new generation.
	 *
	 * Runs on a binder thread; only the reload is posted to the main thread. Persisting before applying is what lets a relaunched process boot the newest generation. A restart deploy persists, acks and exits instead, since services, providers and the Application only swap across a process restart; a recreate deploy acks on the first frame it draws, or, when backgrounded, once its resource swaps have committed, because a deferred recreate renders no frame to prove.
	 *
	 * @param generation
	 *            the incoming generation; a stale one is dropped without a report, since acking a refused payload would mislead the host
	 * @param dexPayload
	 *            the dex fd, or null for a resources or assets-only deploy; always closed
	 * @param resourcesPayload
	 *            the relinked resource apk fd, or null; always closed
	 * @param assetsPayload
	 *            the changed-assets zip fd, or null; always closed
	 * @param metadataJson
	 *            the deploy metadata; a malformed document defaults rather than failing
	 */
	void handlePayload(long generation, ParcelFileDescriptor dexPayload,
			ParcelFileDescriptor resourcesPayload, ParcelFileDescriptor assetsPayload,
			String metadataJson) {
		long startUptime = SystemClock.uptimeMillis();
		PayloadStore.Payload previous = null;
		InputStream arscIn = null;
		InputStream assetsIn = null;
		try {
			DeployMetadata metadata = parseMetadata(metadataJson);
			byte[] dexBytes = readBytesAndClose(dexPayload);
			arscIn = streamOf(resourcesPayload);
			assetsIn = streamOf(assetsPayload);
			PayloadPersistence.Persisted persisted;
			// Read here rather than on the way in: draining the dex takes long enough for
			// a newer deploy to land and finish, and a check against a generation read
			// before that would pass. This is the fast path, not the guard - it can still
			// be overtaken between here and the persist below, and what stops the older
			// payload reaching disk is the store refusing a generation it has already
			// published past.
			previous = PayloadStore.INSTANCE.snapshot();
			if (previous == null
					|| !Generations.accepts(previous.generation, generation)) {
				// Deliberately unreported: claiming a reload for a payload we refused
				// would mislead the host.
				RuntimeLog.w("dropping payload gen " + generation + " (running "
						+ (previous == null ? "no baseline" : "gen " + previous.generation) + ")");
				return;
			}
			if (metadata.restart && dexBytes == null) {
				// Without a dex, the relaunch would boot old classes under a new
				// generation label. A CoGo bug if it ever happens.
				throw new IllegalStateException("restart deploy without a dex payload");
			}
			persisted = persistPayload(generation, dexBytes, arscIn, assetsIn);
			if (metadata.restart) {
				// Never applied in-memory: this process is already condemned, and the
				// fresh one boots the persisted generation.
				RuntimeLog.i("restart deploy gen " + generation + " persisted; exiting");
				client.reportReloaded(generation, SystemClock.uptimeMillis() - startUptime);
				exitForRestart();
				return;
			}
			if (!PayloadStore.INSTANCE.apply(generation,
					dexBytes == null ? null : ByteBuffer.wrap(dexBytes))) {
				// Raced by a newer payload between the acceptance check and here.
				return;
			}
			final PayloadStore.Payload rollback = previous;
			// Read BEFORE the swaps, not after: the backgrounded ack now rides on the
			// swap's commit callback, which is free to fire before this method returns,
			// and the callback has to know which branch it is completing.
			final boolean resumed = tracker.hasResumedActivity();
			pendingReloadStartUptime = startUptime;
			// Armed on BOTH branches: the backgrounded ack must also clear any older
			// generation still pending, or the crash guard keeps blaming it for this
			// generation's crashes - and this generation escapes quarantine.
			firstFrame.arm(Generations.pendingAfterApply(resumed, generation));
			// One outcome per apply call, so a backgrounded ack waits for the last of them.
			final SwapAckGate ackGate = new SwapAckGate(
					(resourcesPayload == null ? 0 : 1) + (assetsPayload == null ? 0 : 1));
			final long arrivedUptime = startUptime;
			ResourceStore.SwapOutcome onSwapOutcome = new ResourceStore.SwapOutcome() {

				@Override
				public void onSwapCommitted() {
					if (resumed || !ackGate.committed()) {
						// A foreground deploy acks from its drawn frame instead, and a
						// backgrounded one whose other swap is still in flight is not done.
						return;
					}
					ackBackgroundedReload(generation, arrivedUptime);
				}

				@Override
				public void onSwapFailed(Throwable error) {
					// The swap lands after this method returns, so without this the deploy
					// acks a reload the app is not showing: CoGo reports success while the
					// screen still renders the previous table, and no banner fires.
					//
					// The recreate is abandoned BEFORE the failure is dispatched, because
					// the dispatch is off-thread now: a recreate still queued here would
					// otherwise run against the failed generation while the rollback that
					// undoes it is still on another thread. Marking rather than removing
					// the callback also covers the inline swap, which fails before the
					// recreate has been posted at all.
					ackGate.failed();
					abandonedReloadGeneration = generation;
					// A deploy carrying both payloads has a second swap that may still be
					// queued behind this one; committing it would serve this generation's
					// resources over the dex the rollback is about to restore.
					ResourceStore.INSTANCE.abandon(generation);
					failReload(generation, rollback, error);
				}
			};
			if (resourcesPayload != null) {
				ResourceStore.INSTANCE.applyTable(
						openReadOnly(persisted.arscFile), generation, application, onSwapOutcome);
			}
			if (assetsPayload != null) {
				ResourceStore.INSTANCE.applyAssets(
						openReadOnly(persisted.assetsFile), generation,
						PayloadStore.INSTANCE.baselineFingerprint(),
						application, onSwapOutcome);
			}
			if (!resumed && ackGate.noSwapPosted()) {
				// A dex-only deploy queues no swap, so there is nothing to wait for and
				// nothing that could still fail the reload. Every deploy that does carry a
				// resource or asset payload leaves this false and acks from the callback.
				ackBackgroundedReload(generation, startUptime);
			}
			final long reloadGeneration = generation;
			mainHandler.post(new Runnable() {

				@Override
				public void run() {
					reloadOnMain(reloadGeneration, rollback);
				}
			});
		} catch (PayloadPersistence.StalePayloadException overtaken) {
			// Deliberately unreported, like the acceptance check above: the screen is
			// running the newer payload that overtook this one, so there is nothing wrong
			// to tell the user about and nothing for the host to roll back.
			RuntimeLog.w("dropping payload gen " + generation + ": " + overtaken.getMessage());
		} catch (Throwable error) {
			RuntimeLog.e("payload gen " + generation + " failed to apply", error);
			// A step that already ran may have queued a swap that will still commit on main:
			// applyTable posts before applyAssets can throw. Nothing cancels that swap, so
			// the recreate must not run - it would render this generation's table over the
			// dex failReload is rolling back. Only onSwapFailed used to set this, which is
			// the branch where the swap is the thing that failed. A swap that has ALREADY
			// committed is a different case: it cannot be refused, and failReload reports
			// the mixed state it leaves instead of claiming the last working version.
			abandonedReloadGeneration = generation;
			// The posted swap is refused rather than committed: the store cannot undo a
			// swap that took, so the only place to stop it is before it commits.
			ResourceStore.INSTANCE.abandon(generation);
			Streams.closeQuietly(dexPayload);
			Streams.closeQuietly(resourcesPayload);
			Streams.closeQuietly(assetsPayload);
			failReload(generation, previous, error);
		} finally {
			// Also covers the early returns: an overtaken or restart deploy leaves here
			// without having read these, and an unclosed fd leaks for the process life.
			Streams.closeQuietly(arscIn);
			Streams.closeQuietly(assetsIn);
		}
	}

	/**
	 * Does the Context-dependent setup deferred from install: bind to CoGo, attach persistence, restore boot resources.
	 *
	 * @param activity
	 *            the activity being created, used only for its application context; every step is idempotent, so this runs safely on each activity
	 */
	void onActivityCreated(Activity activity) {
		// First moment a usable Context exists; bind() is idempotent.
		// The sweep runs before bind and before the boot resources apply, because it is
		// only safe while this process has mounted no relinked apk of its own.
		sweepLegacyResourceCache(activity.getApplicationContext());
		client.bind(activity.getApplicationContext());
		PayloadStore.INSTANCE.attachPersistence(activity.getApplicationContext());
		applyPendingBootResources(activity.getApplicationContext());
	}

	/**
	 * Schedules the reload's completion for the resumed activity's first DRAWN frame.
	 *
	 * onResume is not a rendered frame - it precedes the first traversal - so nothing that vouches for a generation may happen here. A payload whose activity resumes and then throws in measure, layout or draw would otherwise have already been acked, cleared from the pending slot and written to good.json, leaving {@link BootProbation} nothing to blame and {@link PayloadPersistence#quarantine} refusing to name it: every relaunch adopted the same generation and died the same way, with no in-app escape.
	 *
	 * So the ack, the pending-clear and the good-marking all move together to {@link #onFirstFrameDrawn}, released only once a frame carrying the generation has been drawn.
	 *
	 * @param activity
	 *            the activity now in the foreground, which hosts the overlay
	 */
	void onActivityResumed(final Activity activity) {
		// Routed through the seam so the rule - a resume with a frame coming completes
		// nothing - is pinned by a test rather than only by this method's shape. The
		// inline fallback is for an activity with no live view tree, which may never
		// draw at all: waiting for a frame that will never arrive would strand the
		// deploy unacked and leave the generation on probation forever.
		completeOnResume(new FrameCallbackInstaller() {

			@Override
			public boolean install() {
				return completeOnFirstFrame(activity);
			}
		}, new Runnable() {

			@Override
			public void run() {
				onFirstFrameDrawn(activity);
			}
		});
	}

	/** Counts an activity into the set a restart deploy waits to empty before killing the process. */
	void onActivityStarted() {
		restartHandoff.onActivityStarted();
	}

	/** Counts an activity out of that set; the last one out is what lets a waiting restart move on. */
	void onActivityStopped() {
		restartHandoff.onActivityStopped();
	}

	/**
	 * The generation the app is running, as reported to CoGo on connect.
	 *
	 * @return the live generation, 0 when only the baseline has ever run
	 */
	long runningGeneration() {
		return PayloadStore.INSTANCE.generation();
	}

	/**
	 * Acks a backgrounded deploy, once everything that could still fail its reload has landed.
	 *
	 * Deliberately not at apply time. A resource swap is queued to the main looper and commits after the deploy method has returned, so an ack written there claimed a reload that could still fail - and when it did, the rollback, the quarantine and the crash report all ran after CoGo had been told the generation reloaded. CoGo's deploy resolves on the first report naming the generation, so the ack won: the build was recorded as a successful reload with a timing number, while the session manager's separate collector still raised a reload crash, and one save produced both signals. The comment on the branch that calls this says the backgrounded case is the normal edit loop, so that was the branch a failing resource swap usually took.
	 *
	 * The foreground branch never had this problem: it acks from a drawn frame, which cannot precede the swap that frame renders.
	 *
	 * @param generation
	 *            the generation that applied, which becomes CoGo's new baseline
	 * @param arrivedUptime
	 *            uptime at which the payload arrived, so the reported duration still spans the whole deploy rather than just the swap
	 */
	private void ackBackgroundedReload(long generation, long arrivedUptime) {
		// Do NOT read the backgrounded branch as "the recreate is deferred until the user
		// returns." Measured on an A56 (Android 16), a stopped-but-not-destroyed activity
		// relaunches immediately - the tracker still holds it, so the relaunch is scheduled
		// before this ack is written. That timing is not guaranteed across versions or
		// states, which is exactly why the ack does not depend on it.
		//
		// Remaining tradeoffs: the metric is swap-time, not render-time, since no frame is
		// coming to measure; a crash in the relaunch still goes unreported, because the
		// crash guard only watches while a reload is pending (ADFA-5466); and a background
		// race after the resumed check falls back to the deploy timeout.
		client.reportReloaded(generation, SystemClock.uptimeMillis() - arrivedUptime);
	}

	/**
	 * Restores the resource payloads a persisted boot left pending, once a Context exists.
	 *
	 * The code half already loaded pre-Context in {@link PayloadStore#ensureBaseline}. The extraction runs on its own thread and only the swap is posted to the main thread: this is dispatched from inside the first activity's creation, and running the asset merge or the API 28/29 apk copy inline there - both bounded only by the payload cap - cost a launch stall or an ANR on exactly the low-end devices the legacy path exists for. The price is that the first activity inflates against the baseline table; it is recreated once the swap lands, so the screen shows the restored resources one frame late rather than never.
	 *
	 * A failure keeps baseline resources under this generation's code, which is the mixed state a deploy-time failure reports, so it is reported the same way: banner and crash report, rather than the one log line it used to leave. The report is best-effort, since CoGo may not have connected yet.
	 *
	 * @param context
	 *            application context, for the Resources to swap and the cache dir to extract assets into
	 */
	private void applyPendingBootResources(final android.content.Context context) {
		final PayloadPersistence.Loaded pending = PayloadStore.INSTANCE.takePendingBootResources();
		if (pending == null) {
			return;
		}
		// Set before the thread starts, so the first frame - which is coming on this
		// thread as soon as the activity finishes creating - finds it.
		bootRestoreInFlight = true;
		startBootRestoreThread(new Runnable() {

			@Override
			public void run() {
				restoreBootResources(pending, context);
			}
		});
	}

	/**
	 * The {@link #applyPendingBootResources} body: extracts, posts the swaps, and settles the restore on their outcome.
	 *
	 * @param pending
	 *            the persisted generation's resource files, at least one of them non-null
	 * @param context
	 *            application context
	 */
	private void restoreBootResources(PayloadPersistence.Loaded pending,
			android.content.Context context) {
		final long generation = pending.generation;
		// One outcome per swap posted, like a backgrounded deploy: a payload carrying both
		// a table and assets lands in two swaps, and the recreate has to wait for the last.
		final SwapAckGate gate = new SwapAckGate(
				(pending.arscFile == null ? 0 : 1) + (pending.assetsFile == null ? 0 : 1));
		ResourceStore.SwapOutcome onOutcome = new ResourceStore.SwapOutcome() {

			@Override
			public void onSwapCommitted() {
				if (gate.committed()) {
					onBootRestoreLanded(generation);
				}
			}

			@Override
			public void onSwapFailed(Throwable error) {
				gate.failed();
				onBootRestoreFailed(generation, error);
			}
		};
		try {
			if (pending.arscFile != null) {
				ResourceStore.INSTANCE.applyTable(
						openReadOnly(pending.arscFile), generation, context, onOutcome);
			}
			if (pending.assetsFile != null) {
				ResourceStore.INSTANCE.applyAssets(
						openReadOnly(pending.assetsFile), generation,
						PayloadStore.INSTANCE.baselineFingerprint(),
						context, onOutcome);
			}
			if (gate.noSwapPosted()) {
				onBootRestoreLanded(generation);
			}
		} catch (Throwable error) {
			// The same hazard as a deploy that throws after applyTable posted: the table
			// swap is still queued and would commit over a merge that failed. Refuse it, so
			// the process stays wholly on the baseline table rather than half on each.
			ResourceStore.INSTANCE.abandon(generation);
			gate.failed();
			onBootRestoreFailed(generation, error);
		}
	}

	/**
	 * Ends the restore once its last swap has committed, and recreates the activity that inflated before it landed.
	 *
	 * Runs on the main thread inside the swap's guard, so the recreate is posted rather than run here; a live Resources now resolves the restored table, but the views the first activity already inflated keep their baseline values until it is recreated.
	 *
	 * @param generation
	 *            the generation whose resources are now live
	 */
	private void onBootRestoreLanded(long generation) {
		bootRestoreInFlight = false;
		RuntimeLog.i("restored persisted resources for gen " + generation);
		mainHandler.post(new Runnable() {

			@Override
			public void run() {
				Activity top = tracker.topActivity();
				if (top == null) {
					// Nothing inflated against the baseline yet; the next activity created
					// picks the restored table up through attachTo.
					return;
				}
				try {
					top.recreate();
				} catch (Throwable error) {
					// The table is live for every later inflate; only this activity's views
					// are stale, and a crash here would cost the user the app for it.
					RuntimeLog.w("could not recreate the activity after the boot restore", error);
				}
			}
		});
	}

	/**
	 * Ends the restore on a failure: baseline resources stay live under this generation's code, and the user and CoGo are told so.
	 *
	 * Mixed rather than "last working version", because that is what the process is: the persisted dex is this generation's and the table is the installed APK's. There is no rollback here - no snapshot precedes a boot - so the banner offers the one remedy the user has, a restart, which re-runs the restore.
	 *
	 * @param generation
	 *            the generation whose resources could not be restored
	 * @param error
	 *            the extraction or swap failure
	 */
	private void onBootRestoreFailed(long generation, Throwable error) {
		bootRestoreInFlight = false;
		RuntimeLog.e("could not restore persisted resources for gen " + generation, error);
		setOverlayState(OverlayState.mixed());
		client.reportCrash(generation, CrashSummary.forBootRestoreReport(error));
	}

	/**
	 * Asks Android to background the app and waits until the framework has been told the app's state, so the relaunch can put the user back where they were.
	 *
	 * Killing a process the server still believes has no saved state for its top activity gets that record force-removed; when it was the task's only entry the task goes too, and the relaunch has nothing to resume. Waiting for the in-process onSaveInstanceState callback, as this did before, ends about one main-thread message too early - the app has written its bundle and the server has not been told, which measured on an A56 as a force-removal 102 ms later and a task collapsed to a single launcher entry.
	 *
	 * The gate is any STARTED activity rather than a resumed one, because the record at risk is any the server holds no state for, split screen and a dialog from another app included.
	 *
	 * A no-op in the normal loop: the user saves by typing in CoGo, so every activity is already stopped and the framework has what it needs. Never fails the restart - a handoff that does not complete costs the user their place, not their app.
	 */
	private void backgroundForRestart() {
		final Activity top = tracker.topActivity();
		if (top == null || !restartHandoff.anyActivityStarted()) {
			return;
		}
		// Arm before asking, so nothing from an earlier handoff can answer this one.
		restartHandoff.arm();
		mainHandler.post(new Runnable() {

			@Override
			public void run() {
				try {
					// nonRoot, so this works from any activity in the task rather than only
					// the one that started it.
					top.moveTaskToBack(true);
				} catch (Throwable error) {
					RuntimeLog.w("could not background the task before restarting", error);
				}
			}
		});
		boolean handedOff = restartHandoff.awaitHandoff(RESTART_HANDOFF_TIMEOUT_MILLIS, new Runnable() {

			@Override
			public void run() {
				drainMainLooper();
			}
		});
		if (!handedOff) {
			RuntimeLog.w("the framework was not told the app's state within "
					+ RESTART_HANDOFF_TIMEOUT_MILLIS
					+ " ms; restarting anyway, so the screen and back stack may not come back");
		}
	}

	/**
	 * Runs the completion once the resumed activity's first frame has finished drawing.
	 *
	 * The listener fires at the START of each draw pass, so the completion is posted rather than run inline: the posted message runs after the traversal that drew the frame returns, which is what makes measure, layout and draw failures land BEFORE the generation is vouched for rather than after. Posting is also what makes removing the listener legal, since a {@link ViewTreeObserver} rejects a removal made from inside its own dispatch.
	 *
	 * @param activity
	 *            the resumed activity whose first frame gates the completion
	 * @return true when a draw callback was installed, false when the activity has no live view tree and the caller must complete without one
	 */
	private boolean completeOnFirstFrame(final Activity activity) {
		Window window = activity.getWindow();
		final View decor = window == null ? null : window.peekDecorView();
		if (decor == null) {
			return false;
		}
		final ViewTreeObserver observer = decor.getViewTreeObserver();
		if (observer == null || !observer.isAlive()) {
			return false;
		}
		final ViewTreeObserver.OnDrawListener[] listener = new ViewTreeObserver.OnDrawListener[1];
		final boolean[] scheduled = new boolean[1];
		listener[0] = new ViewTreeObserver.OnDrawListener() {

			@Override
			public void onDraw() {
				if (scheduled[0]) {
					return;
				}
				scheduled[0] = true;
				mainHandler.post(new Runnable() {

					@Override
					public void run() {
						ViewTreeObserver live = decor.getViewTreeObserver();
						if (live != null && live.isAlive()) {
							live.removeOnDrawListener(listener[0]);
						}
						onFirstFrameDrawn(activity);
					}
				});
			}
		};
		observer.addOnDrawListener(listener[0]);
		return true;
	}

	/**
	 * Ends the handoff once the main looper has run everything the last activity's stop queued behind it.
	 *
	 * ActivityThread posts its {@code activityStopped} report - the message carrying the saved state to the server - to the main looper from inside the stop it has just dispatched. A message queued from here can land either side of that post, so it proves nothing; an idle callback cannot, because the looper only looks for one when no message is ready, which is necessarily after the report has run. The empty post is the nudge that makes it look, since adding an idle handler does not wake a looper that is already parked.
	 *
	 * A failure here ends the wait rather than stranding it: the kill is owed either way, and a full timeout would cost the user the same place this is protecting.
	 */
	private void drainMainLooper() {
		try {
			Looper.getMainLooper().getQueue().addIdleHandler(new MessageQueue.IdleHandler() {

				@Override
				public boolean queueIdle() {
					restartHandoff.onDrained();
					return false;
				}
			});
			mainHandler.post(new Runnable() {

				@Override
				public void run() {}
			});
		} catch (Throwable error) {
			RuntimeLog.w("could not wait for the main looper before restarting", error);
			restartHandoff.onDrained();
		}
	}

	/**
	 * Backgrounds the app so Android saves its state, then kills the process, because a restart deploy's ack promises a fresh boot.
	 *
	 * The kill has to come from inside the app: CoGo binds this app's keep-alive service to keep it out of the cached-app freezer, which also holds it out of the killable bucket, so {@code am kill} reports success and leaves the process running (measured on an A56, 3 of 3).
	 */
	private void exitForRestart() {
		backgroundForRestart();
		android.os.Process.killProcess(android.os.Process.myPid());
	}

	/**
	 * Reports the failure to CoGo and shows the banner; rolls back only when the store adopted the failed generation, so the app stays on the old one either way.
	 *
	 * The body runs off the caller's thread, like {@link #markLiveGenerationGood}'s write: the rollback path fsyncs the quarantine marker to disk, and two of the three entry points - a rejected resource swap and a recreate that throws - land on main. Everything in the body is already safe off-main: the pending-reload field is volatile, the store calls are synchronized, the crash report is oneway, and the banner re-posts to main itself.
	 *
	 * @param generation
	 *            the generation that failed, which CoGo marks bad
	 * @param rollback
	 *            the snapshot taken before the apply; may be null, which restores the inert state the store was already in
	 * @param error
	 *            the failure, summarized into both the report and the banner
	 */
	private void failReload(long generation, PayloadStore.Payload rollback, Throwable error) {
		startFailReloadThread(new Runnable() {

			@Override
			public void run() {
				failReloadNow(generation, rollback, error);
			}
		});
	}

	/**
	 * The {@link #failReload} body: decides the failure action against the store's live generation and rolls back in the same lock, then reports and renders.
	 *
	 * The rollback covers the dex; a resource swap that already committed stays live. When that is the failed generation's own swap the app is on mixed versions, and the banner and the report say restart instead of claiming the last working version - which the process is no longer running.
	 *
	 * A failure before the apply took - an oversize payload, a persist failure, a restart deploy missing its dex - leaves the store on the previous generation, so there is nothing to restore or quarantine; the report and banner still fire, or the host's only signal would be its deploy timeout. Only a failure superseded by a newer live generation stays silent, since that generation owns the store, the pending ack and the screen.
	 */
	private void failReloadNow(long generation, PayloadStore.Payload rollback, Throwable error) {
		// Decided and restored under one lock: reading the live generation and then
		// restoring took the monitor twice, and a deploy applying in the gap was rolled
		// back by a decision taken before it existed.
		Generations.FailureAction action = PayloadStore.INSTANCE.restoreIfCurrent(generation, rollback);
		if (action == Generations.FailureAction.LEAVE_ALONE) {
			// A newer payload landed while this one was failing, so it owns the store, the
			// pending ack and the screen. Rolling back here would undo a deploy that worked.
			RuntimeLog.w("gen " + generation + " failed but gen "
					+ PayloadStore.INSTANCE.generation() + " is live; leaving it alone", error);
			return;
		}
		if (action == Generations.FailureAction.ROLLBACK_AND_REPORT) {
			quarantine(generation);
			firstFrame.disarm();
		}
		// The rollback above restored the dex only. A table swap that committed before the
		// failure - the usual order, since applyTable posts and returns while applyAssets
		// merges on the binder thread - stays live, and the store cannot take it down. Both
		// callers abandon the generation before dispatching here, so a swap still queued is
		// refused and this read cannot be overtaken by it.
		boolean mixed = Generations.leavesMixedState(
				ResourceStore.INSTANCE.swappedGeneration(), generation);
		// The banner gets no summary at all: it is a few unscrollable lines over the user's
		// own app, so a stack put there is clipped mid-frame and the frames naming the fault
		// are the half nobody sees. It names Build Output instead, and the report below is
		// what actually puts the text there - including, for the mixed case, the restart
		// instruction in full.
		setOverlayState(mixed ? OverlayState.mixed() : OverlayState.crashed());
		client.reportCrash(generation,
				mixed ? CrashSummary.forMixedReport(error) : CrashSummary.forReport(error));
	}

	/**
	 * Chains a handler that quarantines and reports the generation a crash belongs to, before the app dies.
	 *
	 * A payload crash during render happens outside our call stack - the recreated activity throws in its own lifecycle - so the default uncaught handler is the only interception point. It delegates afterwards, so the process still dies; on relaunch the app reconnects with whatever the store then serves and CoGo decides what to redeploy.
	 *
	 * Which generation a crash belongs to is {@link BootProbation}'s question, not this handler's, because a restart deploy's crash lands in the process AFTER the one that deployed it, where no reload is pending.
	 */
	private void installCrashGuard() {
		final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {

			/**
			 * @param thread
			 *            the thread that died; forwarded untouched to the previous handler
			 * @param error
			 *            the uncaught failure, reported to CoGo only when a generation this process adopted is to blame
			 */
			@Override
			public void uncaughtException(Thread thread, Throwable error) {
				try {
					long doomed = bootProbation.generationToBlame(firstFrame.pending(),
							PayloadStore.INSTANCE.generation());
					if (doomed >= 0) {
						// The store already claims this generation, so a relaunch would
						// adopt it and die the same way again - and the marker is what
						// sends that relaunch to the last generation that ran instead.
						quarantine(doomed);
						client.reportCrash(doomed, CrashSummary.forReport(error));
					}
				} catch (Throwable ignored) {
					// The crash guard itself must never throw.
				}
				if (previous != null) {
					previous.uncaughtException(thread, error);
				}
			}
		});
	}

	/**
	 * Records the running generation as the one a later quarantine should fall back to, and ends its probation.
	 *
	 * Called from a DRAWN frame, which is the bar that matters: the failure a fallback has to survive is a payload that throws on the way to the screen, so a generation whose frame reached it is one a fresh process can boot. A resume is not that bar - it precedes the first traversal, so a payload that resumes and then throws in measure, layout or draw has proved nothing. Without this a quarantine drops the app to install-time code and discards every save since.
	 *
	 * The probation ends on the recorded write rather than on the frame that prompted it, so the two facts stay simultaneous: the moment this generation stops being blamed for a crash is the moment there is something to fall back to instead. A write that fails leaves it on probation, which is the expensive direction, not a safe one: nothing recorded it, so a later crash anywhere in the app blames a generation that demonstrably reached the screen and quarantines it. That is why a failed write releases the latch and the next resume tries again.
	 *
	 * Written off the main thread, because the write is fsynced and this runs on the frame path; latched per generation, so it costs one short-lived thread per generation rather than one per resume. Losing the write to a process death only makes the fallback one generation older.
	 *
	 * Deferred while a boot restore is in flight: a frame drawn against the baseline table has not shown this generation's resources, and recording it good from that frame would leave a table that fails to render unblamable.
	 */
	private void markLiveGenerationGood() {
		if (bootRestoreInFlight) {
			// This frame drew against the baseline table, so it proves the code half only;
			// the frame after the restore's recreate is the one that vouches for the
			// generation. Not latched, so that frame comes back here.
			return;
		}
		final long generation = PayloadStore.INSTANCE.generation();
		final PayloadPersistence store = PayloadStore.INSTANCE.persistence();
		if (generation <= 0 || generation == lastMarkedGoodGeneration || store == null) {
			return;
		}
		lastMarkedGoodGeneration = generation;
		new Thread(new Runnable() {

			@Override
			public void run() {
				if (store.markGood(generation)) {
					bootProbation.proved(generation);
					return;
				}
				// markGood answers three situations with one false, and only a failed
				// write is worth coming back to. The other two - the store has moved on,
				// or has quarantined this generation - can never succeed, and releasing
				// the latch for them would start a write thread on every resume. The
				// store-moved-on case is ordinary, not exotic: persist runs before apply,
				// so meta.json is briefly ahead of the live generation on every deploy.
				if (lastMarkedGoodGeneration == generation && store.markGoodCanSucceed(generation)) {
					lastMarkedGoodGeneration = -1;
				}
			}
		}, "qb-mark-good").start();
	}

	/**
	 * Completes a pending reload now that its generation has been drawn, and renders the overlay and return button.
	 *
	 * This is where reportReloaded fires for a foreground deploy, and the reported time is now true time-to-pixels rather than time-to-resume. A backgrounded deploy was already acked at apply time and left no pending generation, so it cannot double-report here.
	 *
	 * @param activity
	 *            the activity that drew the frame, which hosts the overlay
	 */
	private void onFirstFrameDrawn(Activity activity) {
		long acked = firstFrame.drawn(PayloadStore.INSTANCE.generation());
		if (acked >= 0) {
			long reloadMillis = SystemClock.uptimeMillis() - pendingReloadStartUptime;
			client.reportReloaded(acked, reloadMillis);
			// Success renders nothing; it only clears a shown error or in-flight
			// banner, since a landed reload means the build finished even if the
			// build_ok message is still in flight behind it.
			if (overlayState.isError() || overlayState.isBuilding()) {
				setOverlayState(OverlayState.hidden());
			} else {
				overlay.render(activity, overlayState);
			}
		} else {
			overlay.render(activity, overlayState);
		}
		// Unconditional, because the point is that a drawn frame of this generation
		// reached the screen - which is true whether it arrived by hot swap or by a
		// fresh process booting it, and only the first of those leaves a pending
		// generation behind.
		markLiveGenerationGood();
	}

	/**
	 * Writes the payload to the persisted store before anything applies it.
	 *
	 * @param generation
	 *            the generation the store will claim after this write
	 * @param dex
	 *            the dex bytes, or null to keep whatever is persisted
	 * @param arsc
	 *            the relinked resource apk, streamed to the store, or null to keep whatever is persisted
	 * @param assetsZip
	 *            the changed-assets zip, streamed to the store, or null to keep whatever is persisted
	 * @return the store's payload files, which the resource paths then open read-only
	 * @throws IOException
	 *             when the store is unavailable or the write fails, so the deploy fails loudly instead of leaving the boot path behind the running generation
	 */
	private PayloadPersistence.Persisted persistPayload(long generation, byte[] dex,
			InputStream arsc, InputStream assetsZip) throws IOException {
		PayloadPersistence store = PayloadStore.INSTANCE.persistence();
		String fingerprint = PayloadStore.INSTANCE.baselineFingerprint();
		if (store == null || fingerprint == null) {
			throw new IOException("payload persistence unavailable");
		}
		return store.persist(generation, fingerprint, dex, arsc, assetsZip);
	}

	/**
	 * Marks {@code generation} as one a fresh process must not boot.
	 *
	 * The payload was persisted before it was applied, so without this the generation that just failed is what the next cold start adopts - and it fails again during startup, where no reload is pending and so nothing reports it. Refusing it boots the baseline instead, which is the code the installed APK carries.
	 *
	 * @param generation
	 *            the generation that failed to apply or render; nothing happens when persistence never came up, which already means no cold start can adopt it
	 */
	private void quarantine(long generation) {
		PayloadPersistence store = PayloadStore.INSTANCE.persistence();
		if (store != null) {
			store.quarantine(generation);
		}
	}

	/**
	 * Recreates the top activity so it re-instantiates from the new generation's classloader.
	 *
	 * With no live activity there is nothing to recreate and deliberately nothing to launch: the payload is applied, persisted and acked, so the next launch boots this generation. Launching here would take the screen on a plain save, which a save must never do; the user-asked-for launch paths live in CoGo.
	 *
	 * @param generation
	 *            the generation being reloaded, used only for logging and the failure path
	 * @param rollback
	 *            the pre-apply snapshot to restore if the recreate throws
	 */
	private void reloadOnMain(long generation, PayloadStore.Payload rollback) {
		if (generation <= abandonedReloadGeneration) {
			// This generation's resource swap failed, so failReload owns it: recreating
			// here would render a generation whose rollback is already in flight.
			RuntimeLog.w("skipping recreate for gen " + generation + ": its resource swap failed");
			return;
		}
		try {
			Activity top = tracker.topActivity();
			if (top != null) {
				top.recreate();
			} else {
				RuntimeLog.i("no live activity; gen " + generation + " applies on next launch");
				if (firstFrame.pending() == generation) {
					// The activity that was resumed when this deploy was accepted is gone,
					// so there is no frame left to ack on - the same situation the
					// backgrounded branch already acks at apply time. Without this the
					// host learns only from its deploy timeout, and the crash guard goes
					// on blaming this generation for anything the app throws later.
					firstFrame.disarm();
					client.reportReloaded(generation,
							SystemClock.uptimeMillis() - pendingReloadStartUptime);
				}
			}
			// A foreground deploy's reportReloaded fires from onActivityResumed, after
			// the reload rendered; a backgrounded one was acked at apply time.
		} catch (Throwable error) {
			RuntimeLog.e("reload for gen " + generation + " failed", error);
			failReload(generation, rollback, error);
		}
	}

	/**
	 * Installs the new overlay state and re-renders it on the main thread; callable from any thread.
	 *
	 * @param state
	 *            the state to become current; the render reads the field rather than this argument, so a state superseded before the post lands is never drawn
	 */
	private void setOverlayState(OverlayState state) {
		overlayState = state;
		mainHandler.post(new Runnable() {

			@Override
			public void run() {
				overlay.render(tracker.topActivity(), overlayState);
			}
		});
	}

	/**
	 * Wires up the pieces that need no Context: activity tracking, the boot probation and the crash guard.
	 *
	 * The store has already run - {@link QuickBuildAppComponentFactory} calls {@link PayloadStore#ensureBaseline} before it instantiates the Application - so the generation this process booted is known here, which is early enough for the guard to cover the Application's own onCreate.
	 */
	private void start() {
		application.registerActivityLifecycleCallbacks(tracker);
		bootProbation.bootedFromStore(PayloadStore.INSTANCE.bootedPersistedGeneration());
		installCrashGuard();
	}

	/**
	 * Deletes the relinked apks a previous process left in the API 28/29 resource cache, once.
	 *
	 * Those files can only be unmounted by the process dying, so the process that wrote them cannot clean them up and the cache would otherwise grow by one apk per deploy. Latched and run before this process mounts any of its own, since a mounted path deleted underneath the AssetManager cannot be recovered.
	 *
	 * @param context
	 *            application context, for the cache directory
	 */
	private void sweepLegacyResourceCache(android.content.Context context) {
		if (sweptLegacyResourceCache) {
			return;
		}
		sweptLegacyResourceCache = true;
		try {
			int deleted = LegacyResourceSwap.deleteStaleApks(
					new File(context.getCacheDir(), LegacyResourceSwap.TABLE_DIR));
			if (deleted > 0) {
				RuntimeLog.i("swept " + deleted + " stale relinked apk(s) from a previous process");
			}
		} catch (Throwable error) {
			RuntimeLog.w("could not sweep the legacy resource cache", error);
		}
	}

	/**
	 * Installs the callback that runs a reload's completion on the resumed activity's first drawn frame.
	 *
	 * Exists so {@link #completeOnResume} can be driven without an Activity; the production implementation is {@link #completeOnFirstFrame}.
	 */
	interface FrameCallbackInstaller {

		/**
		 * @return true when a first-frame callback was installed, false when the activity has no live view tree and the caller must complete without a frame
		 */
		boolean install();
	}
}
