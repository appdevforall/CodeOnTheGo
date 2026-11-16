package com.itsaky.androidide.gradle.sqlite

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassVisitor

/** Moves classes from android.database.sqlite to requery; see [SqliteRewriter]. */
abstract class SqliteRewriteVisitorFactory : AsmClassVisitorFactory<InstrumentationParameters.None> {
	override fun createClassVisitor(
		classContext: ClassContext,
		nextClassVisitor: ClassVisitor,
	): ClassVisitor = SqliteRewriter.createVisitor(nextClassVisitor, AgpHierarchy(classContext))

	override fun isInstrumentable(classData: ClassData): Boolean = !RewritePolicy.isExcluded(classData.className.replace('.', '/'))

	private class AgpHierarchy(
		private val context: ClassContext,
	) : ClassHierarchy {
		override fun superTypes(internalName: String): Collection<String>? {
			val data = context.loadClassData(internalName.replace('/', '.')) ?: return null
			val result = linkedSetOf<String>()
			val pending = ArrayDeque(data.superClasses + data.interfaces)
			while (pending.isNotEmpty()) {
				val name = pending.removeFirst()
				if (result.add(name.replace('.', '/'))) {
					context.loadClassData(name)?.let { pending.addAll(it.interfaces) }
				}
			}
			return result
		}
	}
}
