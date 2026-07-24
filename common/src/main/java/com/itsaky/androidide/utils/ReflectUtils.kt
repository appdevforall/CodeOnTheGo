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

package com.itsaky.androidide.utils

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * A small fluent reflection helper, covering the subset of behavior this codebase relies on:
 * wrapping an object or class, getting/setting a field by name, invoking a method by name, and
 * instantiating via a matching constructor. Field/method/constructor resolution walks up the
 * class hierarchy and accepts any assignable argument type (auto-boxing primitives).
 */
class ReflectUtils private constructor(
	private val type: Class<*>,
	private val target: Any?,
) {
	class ReflectException(
		cause: Throwable,
	) : RuntimeException(cause)

	companion object {
		@JvmStatic
		fun reflect(clazz: Class<*>): ReflectUtils = ReflectUtils(clazz, null)

		@JvmStatic
		fun reflect(obj: Any): ReflectUtils = ReflectUtils(obj.javaClass, obj)

		private fun wrapper(cls: Class<*>): Class<*> =
			when (cls) {
				Int::class.javaPrimitiveType -> Int::class.javaObjectType
				Long::class.javaPrimitiveType -> Long::class.javaObjectType
				Double::class.javaPrimitiveType -> Double::class.javaObjectType
				Float::class.javaPrimitiveType -> Float::class.javaObjectType
				Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
				Short::class.javaPrimitiveType -> Short::class.javaObjectType
				Byte::class.javaPrimitiveType -> Byte::class.javaObjectType
				Char::class.javaPrimitiveType -> Char::class.javaObjectType
				else -> cls
			}

		private fun paramsMatch(
			paramTypes: Array<Class<*>>,
			argTypes: List<Class<*>?>,
		): Boolean {
			if (paramTypes.size != argTypes.size) return false
			for (i in paramTypes.indices) {
				val argType = argTypes[i] ?: continue
				if (!wrapper(paramTypes[i]).isAssignableFrom(wrapper(argType))) return false
			}
			return true
		}
	}

	private fun findField(name: String): Field {
		var current: Class<*>? = type
		while (current != null) {
			try {
				return current.getDeclaredField(name)
			} catch (e: NoSuchFieldException) {
				current = current.superclass
			}
		}
		throw ReflectException(NoSuchFieldException("No field '$name' found on $type or its superclasses"))
	}

	private fun findMethod(
		name: String,
		argTypes: List<Class<*>?>,
	): Method {
		var current: Class<*>? = type
		while (current != null) {
			for (m in current.declaredMethods) {
				if (m.name == name && paramsMatch(m.parameterTypes, argTypes)) {
					return m
				}
			}
			current = current.superclass
		}
		throw ReflectException(NoSuchMethodException("No method '$name' found on $type matching $argTypes"))
	}

	private fun findConstructor(argTypes: List<Class<*>?>): Constructor<*> {
		for (c in type.declaredConstructors) {
			if (paramsMatch(c.parameterTypes, argTypes)) return c
		}
		throw ReflectException(NoSuchMethodException("No constructor found on $type matching $argTypes"))
	}

	fun newInstance(vararg args: Any?): ReflectUtils =
		try {
			val constructor = findConstructor(args.map { it?.javaClass })
			constructor.isAccessible = true
			reflect(constructor.newInstance(*args) ?: return ReflectUtils(Any::class.java, null))
		} catch (e: ReflectException) {
			throw e
		} catch (e: Exception) {
			throw ReflectException(e)
		}

	fun field(name: String): ReflectUtils =
		try {
			val f = findField(name)
			f.isAccessible = true
			ReflectUtils(f.type, f.get(target))
		} catch (e: ReflectException) {
			throw e
		} catch (e: Exception) {
			throw ReflectException(e)
		}

	fun field(
		name: String,
		value: Any?,
	): ReflectUtils =
		try {
			val f = findField(name)
			f.isAccessible = true
			f.set(target, value)
			this
		} catch (e: ReflectException) {
			throw e
		} catch (e: Exception) {
			throw ReflectException(e)
		}

	fun method(
		name: String,
		vararg args: Any?,
	): ReflectUtils =
		try {
			val m = findMethod(name, args.map { it?.javaClass })
			m.isAccessible = true
			val result = m.invoke(target, *args)
			if (m.returnType == Void.TYPE || result == null) {
				ReflectUtils(type, target)
			} else {
				reflect(result)
			}
		} catch (e: ReflectException) {
			throw e
		} catch (e: Exception) {
			throw ReflectException(e)
		}

	@Suppress("UNCHECKED_CAST")
	fun <T> get(): T = target as T
}
