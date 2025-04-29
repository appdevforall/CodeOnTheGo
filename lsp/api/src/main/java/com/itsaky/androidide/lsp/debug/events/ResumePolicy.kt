package com.itsaky.androidide.lsp.debug.events

/**
 * Policy describing how a resume should be processed.
 *
 * @author Akash Yadav
 */
enum class ResumePolicy {

    /**
     * The client should be suspended (or kept suspened if already suspended).
     */
    SUSPEND,

    /**
     * The thread should be resumed.
     */
    RESUME_THREAD,

    /**
     * The client should be resumed.
     */
    RESUME_CLIENT,
}