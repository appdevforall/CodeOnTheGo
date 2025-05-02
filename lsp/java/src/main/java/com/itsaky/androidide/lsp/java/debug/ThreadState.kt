package com.itsaky.androidide.lsp.java.debug

import com.sun.jdi.StackFrame
import com.sun.jdi.ThreadGroupReference
import com.sun.jdi.ThreadReference
import com.sun.jdi.VirtualMachine
import org.slf4j.LoggerFactory
import java.util.Collections
import com.itsaky.androidide.lsp.debug.model.ThreadInfo as LspThreadInfo

/**
 * Information about a thread.
 *
 * @property thread The thread reference.
 */
class ThreadInfo(
    val thread: ThreadReference
) {

    private var _currentFrame = 0

    /**
     * The current frame index.
     */
    var currentFrame: Int
        get() = _currentFrame
        private set(value) {
            ensureSuspended()

            require(value in 0..<thread.frameCount()) {
                "Invalid frame index: $value"
            }

            _currentFrame = value
        }

    /**
     * Get this thread info as [LspThreadInfo].
     */
    fun asLspModel(): LspThreadInfo =
        LspThreadInfo(this)

    /**
     * Ensure that the thread is suspended.
     */
    fun ensureSuspended() {
        check(thread.isSuspended) {
            "Thread is not suspended"
        }
    }

    /**
     * Move up the call stack i.e. away from the current program counter.
     *
     * @param nFrames The number of frames to move up.
     */
    fun up(nFrames: Int = 1) {
        this.currentFrame += nFrames
    }

    /**
     * Move down the call stack i.e. towards the current program counter.
     *
     * @param nFrames The number of frames to move down.
     */
    fun down(nFrames: Int = 1) {
        this.currentFrame -= nFrames
    }

    /**
     * Invalidate the current stack frame index.
     */
    fun invalidate() {
        // update backing field directly since we may not be in a suspended state here
        _currentFrame = 0
    }

    /**
     * Get the stack frame at the given index.
     */
    fun frame(index: Int): StackFrame = thread.frame(index)

    /**
     * Get the stack frames of this thread.
     */
    fun frames(): List<StackFrame> {
        return thread.frames()
    }

    /**
     * Get [count] number of frames starting from [from].
     */
    fun frames(from: Int, count: Int): List<StackFrame> = thread.frames(from, count)
}

/**
 * State of all known threads in a VM.
 *
 * @property _threads The threads in the VM.
 * @property vthreads The virtual threads in the VM.
 */
class ThreadState(
    private val vm: VirtualMachine
) {
    // TODO: Java 21 added support for virtual threads
    //   This should be updated if/when Android has support for the same, or when we want to add
    //   support for those.

    companion object {
        private val logger = LoggerFactory.getLogger(ThreadState::class.java)
    }

    private val _threads = Collections.synchronizedList(mutableListOf<ThreadInfo>())
    private var _current: ThreadInfo? = null
    private var _threadGroup: ThreadGroupReference? = null
    private var gotInitialThreads = false

    /**
     * Get the threads in the VM.
     */
    val threads: List<ThreadInfo>
        get() = _threads

    /**
     * Get the current thread.
     */
    val current: ThreadInfo?
        get() = _current

    /**
     * Get the current [ThreadGroupReference] for the current thread.
     */
    val threadGroup: ThreadGroupReference
        get() {
            if (_threadGroup == null) {
                // Current thread group defaults to the first top level
                // thread group.
                _threadGroup = vm.topLevelThreadGroups()[0]
            }

            return _threadGroup!!
        }

    internal fun initThreads() {
        if (!gotInitialThreads) {
            _threads.addAll(vm.allThreads().map(::ThreadInfo))
            gotInitialThreads = true
        }
    }

    /**
     * Add the given thread to the state.
     *
     * @param thread The thread reference.
     * @return `true` if the thread was added, `false` if it already existed.
     */
    fun addThread(thread: ThreadReference): Boolean {
        synchronized(_threads) {
            initThreads()
            if (getThreadInfo(thread) == null) {
                logger.debug("addThread: {}", thread)
                _threads.add(ThreadInfo(thread))
                return true
            }

            return false
        }
    }

    /**
     * Remove the given thread from the state.
     *
     * @param thread The thread reference.
     */
    fun removeThread(thread: ThreadReference) {
        if (thread == current?.thread) {
            // Current thread has died
            _current = null
        }

        val ti = getThreadInfo(thread)
        if (ti != null) {
            logger.debug("removeThread: {}", thread)
            _threads.remove(ti)
        }
    }

    /**
     * Call [ThreadInfo.invalidate] on all threads.
     */
    fun invalidateAll() {
        _current = null
        _threadGroup = null

        synchronized(threads) {
            for (ti in threads) {
                ti.invalidate()
            }
        }
    }

    /**
     * Set the current thread to the given [ThreadReference].
     *
     * @param thread The thread reference.
     */
    fun setCurrentThread(thread: ThreadReference?) {
        if (thread == null) {
            setCurrentThreadInfo(null)
            return
        }

        setCurrentThreadInfo(getThreadInfo(thread))
    }

    /**
     * Set the current thread to the given [ThreadInfo].
     *
     * @param ti The thread info.
     */
    fun setCurrentThreadInfo(ti: ThreadInfo?) {
        _current = ti
        ti?.invalidate()
    }

    /**
     * Get [ThreadInfo] for the given thread ID.
     *
     * @param id The thread ID.
     * @return The [ThreadInfo] for the given thread, or `null` if it doesn't exist.
     */
    fun getThreadInfo(id: Long): ThreadInfo? {
        synchronized(_threads) {
            var retInfo: ThreadInfo? = null
            for (ti in threads) {
                if (ti.thread.uniqueID() == id) {
                    retInfo = ti
                    break
                }
            }
            return retInfo
        }
    }

    /**
     * Get [ThreadInfo] for the given [ThreadReference].
     *
     * @param tr The thread reference.
     * @return The [ThreadInfo] for the given thread, or `null` if it doesn't exist.
     */
    fun getThreadInfo(tr: ThreadReference): ThreadInfo? {
        return getThreadInfo(tr.uniqueID())
    }

    /**
     * Get [ThreadInfo] for the given ID token of the thread.
     *
     * @param idToken The ID token of the thread.
     * @return The [ThreadInfo] for the given thread, or `null` if it doesn't exist.
     */
    fun getThreadInfo(idToken: String): ThreadInfo? {
        var token = idToken
        if (token.startsWith("t@")) {
            token = token.substring(2)
        }

        val tinfo = try {
            val threadId = java.lang.Long.decode(token)
            getThreadInfo(threadId)
        } catch (e: NumberFormatException) {
            null
        }

        return tinfo
    }
}