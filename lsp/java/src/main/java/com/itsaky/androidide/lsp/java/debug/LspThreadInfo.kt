package com.itsaky.androidide.lsp.java.debug

import com.itsaky.androidide.lsp.debug.model.PrimitiveKind
import com.itsaky.androidide.lsp.debug.model.PrimitiveValue
import com.itsaky.androidide.lsp.debug.model.StackFrameDescriptor
import com.itsaky.androidide.lsp.debug.model.ThreadDescriptor
import com.itsaky.androidide.lsp.debug.model.ThreadState
import com.itsaky.androidide.lsp.debug.model.Value
import com.itsaky.androidide.lsp.debug.model.Variable
import com.itsaky.androidide.lsp.debug.model.VariableKind
import com.itsaky.androidide.lsp.java.debug.utils.isOpaque
import com.sun.jdi.Location
import com.sun.jdi.Method
import com.sun.jdi.StackFrame
import com.sun.jdi.ThreadReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import com.itsaky.androidide.lsp.debug.model.StackFrame as LspStackFrame
import com.itsaky.androidide.lsp.debug.model.ThreadInfo as LspThreadInfo
import com.itsaky.androidide.lsp.debug.model.ThreadState as LspThreadState
import com.itsaky.androidide.lsp.debug.model.Variable as LspVariable

class JavaStackFrame(
	val thread: ThreadReference,
	val frame: StackFrame,
	val location: Location = frame.location(),
	val method: Method? = location.method(),
	val sourceName: String = location.sourceName(),
	val lineNumber: Long = location.lineNumber().toLong(),
) : LspStackFrame {
	companion object {
		private val logger = LoggerFactory.getLogger(JavaStackFrame::class.java)
	}

	private lateinit var cachedVariables: List<AbstractJavaVariable<*>>

	override suspend fun descriptor() =
		withContext(Dispatchers.IO) {
			val method =
				checkNotNull(method) {
					"Method not found for location: $location"
				}

			StackFrameDescriptor(
				method = method.name(),
				methodSignature = method.signature(),
				sourceFile = sourceName,
				lineNumber = lineNumber,
			)
		}

	override suspend fun getVariables(): List<LspVariable<*>> {
		if (!::cachedVariables.isInitialized) {
			cachedVariables = JavaDebugAdapter.requireInstance().evalContext().evaluate(thread) {
				if (method?.isOpaque == true) {
					// non-concrete method
					// does not have any variables
					return@evaluate emptyList()
				}

				return@evaluate thread
					.frames()
					.firstOrNull { frame ->
						frame.location() == location
					}?.run {
						val variables = mutableListOf<AbstractJavaVariable<*>>()

						try {
							logger.info("QA TEST: Waiting 5s... KILL THE APP!")
							Thread.sleep(5000)
						} catch (e: Exception) {}

						val thisObject = runCatching {
							this.thisObject()
						}.getOrElse { e ->
							when (e) {
								is com.sun.jdi.VMDisconnectedException -> {
									logger.warn("VM disconnected while fetching 'this' object.", e)
									return@evaluate emptyList()
								}
								is com.sun.jdi.ObjectCollectedException -> {
									logger.warn("Object collected by GC during debug", e)
									null
								}
								else -> {
									logger.error("Unexpected error fetching thisObject", e)
									null
								}
							}
						}
						if (thisObject != null) {
							variables.add(
								ThisVariable<Value>(
									thread = thread,
									ref = thisObject,
									refType = thisObject.referenceType(),
								),
							)
						}

						try {
							visibleVariables()
								?.mapNotNull { variable ->
									if (variable.name().isBlank()) {
										// some opaque frames in core Android classes have empty variable names (like in ZygoteInit)
										return@mapNotNull null
									}

									try {
										JavaLocalVariable.forVariable(
											thread = thread,
											stackFrame = this@JavaStackFrame,
											variable = variable,
											value = frame.getValue(variable),
										)
									} catch (e: com.sun.jdi.VMDisconnectedException) {
										throw e
									} catch (err: Throwable) {
										logger.error(
											"Failed to create variable wrapper for {}",
											variable.name(),
											err,
										)
										null
									}
								}?.also { localVariables ->
									variables.addAll(localVariables as List<AbstractJavaVariable<*>>)
								}
						} catch (e: com.sun.jdi.VMDisconnectedException) {
							logger.warn("VM disconnected while reading local variables. Aborting.")
							return@evaluate emptyList()
						} catch (e: Throwable) {
							logger.error("Error reading local variables", e)
						}

						variables
					}
			} ?: emptyList()
		}

		return cachedVariables
	}

	override suspend fun <Val : Value> setValue(
		variable: Variable<Val>,
		value: Val,
	) = withContext(Dispatchers.IO) {
		variable as JavaLocalVariable
		when (variable.kind) {
			VariableKind.PRIMITIVE -> {
				check(value is PrimitiveValue) {
					"Value $value is not a primitive value"
				}

				variable as JavaPrimitiveVariable
				when (variable.primitiveKind) {
					PrimitiveKind.BOOLEAN -> variable.doSetValue(value.asBoolean())
					PrimitiveKind.BYTE -> variable.doSetValue(value.asByte())
					PrimitiveKind.CHAR -> variable.doSetValue(value.asChar())
					PrimitiveKind.SHORT -> variable.doSetValue(value.asShort())
					PrimitiveKind.INT -> variable.doSetValue(value.asInt())
					PrimitiveKind.LONG -> variable.doSetValue(value.asLong())
					PrimitiveKind.FLOAT -> variable.doSetValue(value.asFloat())
					PrimitiveKind.DOUBLE -> variable.doSetValue(value.asDouble())
				}
			}

			// TODO: Support other types of variable values
			else -> throw IllegalStateException("Unsupported variable kind: ${variable.kind}")
		}
	}
}

internal class LspThreadInfo(
	val thread: ThreadInfo,
	val frames: List<StackFrame> = thread.frames(),
) : LspThreadInfo {
	companion object {
		private val logger = LoggerFactory.getLogger(LspThreadInfo::class.java)
	}

	private lateinit var cachedFrames: List<JavaStackFrame>

	override suspend fun descriptor(): ThreadDescriptor =
		withContext(Dispatchers.IO) {
			val thread = thread.thread
			val group = thread.threadGroup()

			ThreadDescriptor(
				id = thread.uniqueID().toString(),
				name = thread.name(),
				group = group.name(),
				state = threadStateOf(thread.status()),
			)
		}

	override suspend fun getFrames(): List<JavaStackFrame> {
		if (!::cachedFrames.isInitialized) {
			cachedFrames =
				JavaDebugAdapter.requireInstance().evalContext().evaluate(thread.thread) {
					frames.map { frame ->
						JavaStackFrame(
							thread = thread.thread,
							frame = frame,
						)
					}
				} ?: emptyList()
		}

		return cachedFrames
	}
}

private fun threadStateOf(state: Int) =
	when (state) {
		ThreadReference.THREAD_STATUS_UNKNOWN -> LspThreadState.UNKNOWN
		ThreadReference.THREAD_STATUS_ZOMBIE -> LspThreadState.ZOMBIE
		ThreadReference.THREAD_STATUS_RUNNING -> LspThreadState.RUNNING
		ThreadReference.THREAD_STATUS_SLEEPING -> LspThreadState.SLEEPING
		ThreadReference.THREAD_STATUS_MONITOR -> LspThreadState.MONITOR
		ThreadReference.THREAD_STATUS_WAIT -> LspThreadState.WAITING
		ThreadReference.THREAD_STATUS_NOT_STARTED -> LspThreadState.NOT_STARTED
		else -> ThreadState.UNKNOWN
	}
