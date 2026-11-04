package com.itsaky.androidide.utils

import java.io.File

object TermuxProcessEnvironment {
	fun applyTo(
		env: MutableMap<String, String>,
		termuxRoot: File,
	) {
		val termuxBase = termuxRoot.absolutePath
		val termuxBin = "$termuxBase/usr/bin"
		val termuxLib = "$termuxBase/usr/lib"

		val existingPath = env["PATH"] ?: ""
		if (!existingPath.contains(termuxBin)) {
			env["PATH"] = "$termuxBin:$existingPath"
		}

		val existingLdPath = env["LD_LIBRARY_PATH"] ?: ""
		if (!existingLdPath.contains(termuxLib)) {
			env["LD_LIBRARY_PATH"] = "$termuxLib:$existingLdPath"
		}

		env.putIfAbsent("HOME", "$termuxBase/home")
		env.putIfAbsent("TMPDIR", "$termuxBase/usr/tmp")
		env.putIfAbsent("LANG", "en_US.UTF-8")
		env.putIfAbsent("PREFIX", "$termuxBase/usr")
	}
}
