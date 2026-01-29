package com.itsaky.androidide.app.strictmode

import com.itsaky.androidide.tasks.runOnUiThread
import org.slf4j.LoggerFactory

/**
 * A violation handler for strict mode.
 *
 * @author Akash Yadav
 */
object ViolationHandler {
    private val logger = LoggerFactory.getLogger(ViolationHandler::class.java)

    private const val ALLOW_VIOLATION_MESSAGE = "StrictMode violation '{}' allowed because: {}"
    private const val WARN_VIOLATION_MESSAGE = "StrictMode violation detected"

    /**
     * Allow the violation.
     *
     * @param violation The violation that was detected.
     */
    fun allow(
        violation: ViolationDispatcher.Violation,
        reason: String,
    ) {
        logger.info(ALLOW_VIOLATION_MESSAGE, violation.violation.javaClass.simpleName, reason)
    }

    /**
     * Log the violation.
     *
     * @param violation The violation that was detected.
     */
    fun log(violation: ViolationDispatcher.Violation) {
        logger.warn(WARN_VIOLATION_MESSAGE, violation.violation)
    }

    /**
     * Crash the app (throw an exception on the main thread).
     *
     * @param violation The violation that was detected.
     */
    fun crash(violation: ViolationDispatcher.Violation) {
        runOnUiThread {
            throw RuntimeException(WARN_VIOLATION_MESSAGE, violation.violation)
        }
    }
}