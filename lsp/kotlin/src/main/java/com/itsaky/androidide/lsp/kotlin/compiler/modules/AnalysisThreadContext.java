package com.itsaky.androidide.lsp.kotlin.compiler.modules;

import kotlin.coroutines.CoroutineContext;
import kotlinx.coroutines.Job;
import org.jetbrains.kotlin.com.intellij.concurrency.ThreadContext;
import org.jetbrains.kotlin.com.intellij.openapi.application.AccessToken;

/**
 * Java bridge to the embeddable IntelliJ {@link ThreadContext} coroutine-context API.
 *
 * <p>Exists in Java because {@code currentThreadContext}/{@code installThreadContext} live in a
 * Kotlin file-facade whose metadata this module's Kotlin compiler cannot resolve ("unresolved
 * reference"), yet at the bytecode level they are plain {@code public static} methods Java can call.
 */
public final class AnalysisThreadContext {

  private AnalysisThreadContext() {
  }

  /**
   * Installs {@code job} into the current thread's IntelliJ coroutine context (preserving any
   * existing context) and returns a token that restores the previous context when closed. Cancelling
   * {@code job} then aborts the running analysis mid-{@code analyze}, since the embeddable
   * {@code CoreProgressManager.checkCanceled()} throws once the installed Job is cancelled.
   *
   * <p>Public (not package-private) because the {@code internal inline} {@code withAnalysisLock}
   * references it: an inline function may only reference declarations at least as accessible as itself.
   */
  public static AccessToken installJob(Job job) {
    CoroutineContext context = ThreadContext.currentThreadContext().plus(job);
    return ThreadContext.installThreadContext(context, true);
  }
}
