package com.itsaky.androidide.lsp.debug.model

/**
 * Policy describing how a resume should be processed.
 *
 * @author Akash Yadav
 */
enum class ResumePolicy {

    /**
     * The thread should be suspended (or kept suspended if already suspended).
     */
    SUSPEND_THREAD,

    /**
     * The client should be suspended (or kept suspened if already suspended).
     */
    SUSPEND_CLIENT,

    /**
     * The thread should be resumed.
     */
    RESUME_THREAD,

    /**
     * The client should be resumed.
     */
    RESUME_CLIENT,
}