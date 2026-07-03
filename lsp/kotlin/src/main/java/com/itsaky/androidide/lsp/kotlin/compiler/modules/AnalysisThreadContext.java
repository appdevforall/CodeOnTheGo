package com.itsaky.androidide.lsp.kotlin.compiler.modules;

import kotlin.coroutines.CoroutineContext;
import kotlinx.coroutines.Job;
import org.jetbrains.kotlin.com.intellij.concurrency.ThreadContext;
import org.jetbrains.kotlin.com.intellij.openapi.application.AccessToken;

/**
 * Bridge to the embeddable IntelliJ {@link ThreadContext} coroutine-context API.
 *
 * <p>{@code currentThreadContext}/{@code installThreadContext} live in a Kotlin file facade whose
 * metadata the Kotlin compiler cannot resolve against from this module (they surface as
 * "unresolved reference" from Kotlin). At the bytecode level, though, they are plain
 * {@code public static} methods, so Java can call them directly.
 *
 * <p>This is used by {@code withAnalysisLock} to install a cancellable {@link Job} into the analysis
 * thread's context: the embeddable {@code CoreProgressManager.checkCanceled()} routes through
 * {@code Cancellation.checkCancelled()}, which throws as soon as that Job is cancelled, aborting the
 * running analysis mid-{@code analyze}.
 */
public final class AnalysisThreadContext {

  private AnalysisThreadContext() {
  }

  /**
   * Installs {@code job} into the current thread's IntelliJ coroutine context (preserving any
   * context already present) and returns a token that restores the previous context when closed.
   *
   * <p>Public because it is referenced from the {@code internal inline} {@code withAnalysisLock};
   * an inline function may only reference declarations at least as accessible as itself.
   */
  public static AccessToken installJob(Job job) {
    CoroutineContext context = ThreadContext.currentThreadContext().plus(job);
    return ThreadContext.installThreadContext(context, true);
  }
}
