package com.itsaky.androidide.lsp.java.debug.spec

import com.sun.jdi.ReferenceType
import com.sun.jdi.VirtualMachine
import com.sun.jdi.event.ClassPrepareEvent
import com.sun.jdi.request.ClassPrepareRequest
import com.sun.jdi.request.EventRequest
import org.slf4j.LoggerFactory

/**
 * @author Akash Yadav
 */
internal abstract class EventRequestSpec(
	val refSpec: ReferenceTypeSpec,
	var suspendPolicy: Int = EventRequest.SUSPEND_ALL,
) {
	companion object {
		private val logger = LoggerFactory.getLogger(EventRequestSpec::class.java)
	}

	var resolved: EventRequest? = null
	var prepareRequest: ClassPrepareRequest? = null

	val isResolved: Boolean
		get() = resolved != null

	/**
	 * Resolve the [EventRequest] for this spec.
	 */
	abstract fun resolveEventRequest(
		vm: VirtualMachine,
		refType: ReferenceType,
	): EventRequest

	/**
	 * Resolve the [EventRequest] for this spec.
	 *
	 * @return The resolved [EventRequest], or `null` if the spec cannot be resolved.
	 */
	@Synchronized
	fun resolve(
		vm: VirtualMachine,
		event: ClassPrepareEvent,
	): EventRequest? {
		if (resolved == null && prepareRequest != null && prepareRequest == event.request()) {
			// The prepare filter is a suffix match on the file name alone, with no package part, so
			// it admits a same-named file from anywhere on the classpath.
			if (!refSpec.matches(vm, event.referenceType())) {
				return null
			}

			val resolved =
				try {
					resolveEventRequest(vm, event.referenceType())
				} catch (err: LineNotFoundException) {
					logger.debug(
						"class {} shares the source file but not line, waiting for another",
						event.referenceType().name(),
					)
					return null
				}
			this.resolved = resolved

			this.prepareRequest!!.disable()
			vm
				.eventRequestManager()
				.deleteEventRequest(prepareRequest)
			this.prepareRequest = null

			if (((this.refSpec as? PatternReferenceTypeSpec?)?.isPattern == true)) {
				// Class pattern event requests are never considered "resolved", since
				// future class loads might also match.
				// Create and enable a new ClassPrepareRequest to keep trying to resolve.
				this.resolved = null
				this.prepareRequest = refSpec.createPrepareRequest(vm)
				this.prepareRequest!!.enable()
			}
		}
		return resolved
	}

	@Synchronized
	fun remove(vm: VirtualMachine) {
		if (isResolved) {
			vm.eventRequestManager().deleteEventRequest(this.resolved)
			this.resolved = null
		}

		// An unresolved spec still owns an enabled ClassPrepareRequest. Left behind it keeps
		// round-tripping the VM on every matching class load, and resolves later into a breakpoint
		// the user has already deleted.
		prepareRequest?.let { request ->
			request.disable()
			vm.eventRequestManager().deleteEventRequest(request)
			this.prepareRequest = null
		}

		val patternSpec = this.refSpec as? PatternReferenceTypeSpec?
		if (patternSpec?.isPattern == true) {
			// This is a pattern.  Track down and delete
			// all EventRequests matching this spec.
			// Note: Class patterns apply only to ExceptionRequests,
			// so that is all we need to examine.
			val deleteList =
				vm
					.eventRequestManager()
					.exceptionRequests()
					.filter { request -> patternSpec.matches(vm, request.exception()) }

			vm
				.eventRequestManager()
				.deleteEventRequests(deleteList)
		}
	}

	@Throws(Exception::class)
	private fun resolveAgainstPreparedClasses(vm: VirtualMachine): EventRequest? {
		// check if the class is already prepared by first checking the matching reference types
		// if the spec cannot be resolved in matching ref types
		val matchingRefTypes = refSpec.matchingRefTypes(vm)
		logger.debug("matching ref types: {}", matchingRefTypes)
		val resolved = resolveAgainstClasses(vm, matchingRefTypes)

		// we don't traverse over all classes if resolved == null
		// this is because when we use WADB with system's libjdwp.so, the debugger agent
		// is attached even before the application classes are loaded. As a result,
		// a breakpoint spec that was added to the classes which are loaded in the very
		// early stages of the app launch, resolved will always be null and we'll
		// always have to traverse over all classes to resolve the breakpoint. That
		// would be a problem because depending the size of the application,
		// there could be thousands of classes, which in turn may result in
		// longer app launch times when launched in debug mode.

		// also, if we can't resolve the class at this point, then it's probably
		// not loaded, so it's better to lazily create the breakpoint in the VM

		return resolved
	}

	@Throws
	private fun resolveAgainstClasses(
		vm: VirtualMachine,
		classes: List<ReferenceType>,
	): EventRequest? {
		var sourceMatchedWithoutLine = false

		classes.firstOrNull { refType ->
			if (refSpec.matches(vm, refType)) {
				resolved =
					try {
						resolveEventRequest(vm, refType)
					} catch (err: LineNotFoundException) {
						// Another class from the same file may still carry the line: a Kotlin file
						// compiles to many, and its lambdas load lazily. Only the deferred path can
						// tell "not yet" from "never".
						sourceMatchedWithoutLine = true
						null
					}
			}

			resolved != null
		}

		if (resolved == null && sourceMatchedWithoutLine) {
			logger.warn(
				"no prepared class from {} carries this line; waiting for one that does",
				refSpec,
			)
		}

		return resolved
	}

	@Synchronized
	@Throws(Exception::class)
	fun resolveEagerly(vm: VirtualMachine): EventRequest? {
		if (resolved == null) {
			// Not resolved.  Schedule a prepare request so we
			// can resolve later.
			prepareRequest = refSpec.createPrepareRequest(vm)
			prepareRequest!!.enable()

			// Try to resolve in case the class is already loaded.
			resolveAgainstPreparedClasses(vm)

			if (resolved != null) {
				prepareRequest!!.disable()
				vm
					.eventRequestManager()
					.deleteEventRequest(prepareRequest)
				prepareRequest = null
			}
		}

		if (refSpec is PatternReferenceTypeSpec) {
			if (!refSpec.isUnique) {
				// Class pattern event requests are never
				// considered "resolved", since future class loads
				// might also match.  Create a new
				// ClassPrepareRequest if necessary and keep
				// trying to resolve.
				resolved = null
				if (prepareRequest == null) {
					prepareRequest = refSpec.createPrepareRequest(vm)
					prepareRequest!!.enable()
				}
			}
		}
		return resolved
	}
}
