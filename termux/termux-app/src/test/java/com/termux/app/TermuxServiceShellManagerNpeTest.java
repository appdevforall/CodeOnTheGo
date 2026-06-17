package com.termux.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.termux.shared.termux.shell.TermuxShellManager;

import java.lang.reflect.Field;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;

/**
 * Repro for ADFA-4330: when the OS auto-restarts {@link TermuxService} after process death,
 * {@code TermuxApplication.onCreate()} does NOT run, so the static
 * {@link TermuxShellManager} singleton is still {@code null}.
 *
 * <p>On the pre-fix baseline, {@code TermuxService.onCreate()} assigned
 * {@code mShellManager = TermuxShellManager.getShellManager()} (which returns the null
 * singleton), then immediately called {@code runStartForeground() -> buildNotification() ->
 * getTermuxSessionsSize()}, dereferencing the null {@code mShellManager} and throwing a
 * {@link NullPointerException}.
 *
 * <p>The fix changes that assignment to {@code TermuxShellManager.init(applicationContext)},
 * which lazily creates the singleton, so the service starts cleanly.
 *
 * <p>This test simulates the auto-restart by forcing the static singleton back to {@code null}
 * before creating the service, then asserts the service comes up and
 * {@link TermuxService#getTermuxSessionsSize()} returns 0 instead of NPE-ing.
 */
@RunWith(RobolectricTestRunner.class)
public class TermuxServiceShellManagerNpeTest {

    /**
     * Reset the static singleton to null to mimic a fresh process where
     * TermuxApplication.onCreate() (which would normally call init()) never ran.
     */
    @Before
    public void clearShellManagerSingleton() throws Exception {
        Field f = TermuxShellManager.class.getDeclaredField("shellManager");
        f.setAccessible(true);
        f.set(null, null);
    }

    @Test
    public void onCreateWithNullSingleton_doesNotNpe_andSessionsAreUsable() {
        // Sanity: the auto-restart precondition — singleton is null going in.
        assertEquals(null, TermuxShellManager.getShellManager());

        // Drive the REAL service lifecycle. On stage this throws NullPointerException inside
        // onCreate() -> runStartForeground() -> buildNotification() -> getTermuxSessionsSize().
        ServiceController<TermuxService> controller =
            Robolectric.buildService(TermuxService.class).create();
        TermuxService service = controller.get();

        // After a clean onCreate(), the shell manager must be wired up and queryable.
        assertNotNull("mShellManager must be initialized after onCreate()",
            TermuxShellManager.getShellManager());
        assertEquals("A freshly created service manages zero sessions",
            0, service.getTermuxSessionsSize());

        controller.destroy();
    }
}
