package com.itsaky.androidide.lsp.java.debug.spec

import com.itsaky.androidide.lsp.debug.model.Source
import com.itsaky.androidide.lsp.java.debug.utils.matchesSourcePath
import com.itsaky.androidide.lsp.java.debug.utils.sourcePathOrNull
import com.sun.jdi.ReferenceType
import com.sun.jdi.VirtualMachine
import com.sun.jdi.request.ClassPrepareRequest

/**
 * @author Akash Yadav
 */
class SourceReferenceTypeSpec(
	internal val source: Source,
	internal val qualifiedNames: List<String>,
) : ReferenceTypeSpec {
	override fun matchingRefTypes(vm: VirtualMachine): List<ReferenceType> =
		qualifiedNames.flatMap { name ->
			vm.classesByName(name)
		}

	override fun matches(
		vm: VirtualMachine,
		refType: ReferenceType,
	): Boolean = matchesSourcePath(this.source.path, refType.sourcePathOrNull())

	override fun createPrepareRequest(vm: VirtualMachine): ClassPrepareRequest {
		val request =
			vm
				.eventRequestManager()
				.createClassPrepareRequest()
		request.addSourceNameFilter("*${source.name}")
		return request
	}
}
