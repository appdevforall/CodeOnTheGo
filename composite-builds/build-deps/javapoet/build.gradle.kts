/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

plugins {
	kotlin("jvm")
}

dependencies {
	// javapoet itself stays fully resident (see lsp/java-compiler-impl/build.gradle.kts) --
	// templates-api/templates-impl (the "New Project" wizard) need it unconditionally, unlike
	// javac. So unlike jdk-compiler's identical-looking dependency, this one stays `api`: there's
	// no isolated consumer to duplicate java-compiler's classes into.
	api(projects.buildDeps.javaCompiler)
}
