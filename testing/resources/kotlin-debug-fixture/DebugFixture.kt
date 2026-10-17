package com.example.debugfixture

inline fun <T> measured(
	label: String,
	body: () -> T,
): T {
	val started = System.nanoTime()
	val result = body()
	println("$label took ${System.nanoTime() - started}")
	return result
}

fun topLevel(n: Int): Int {
	val doubled = n * 2
	return doubled
}

class Greeter(
	private val name: String,
) {
	fun greet(): String {
		val greeting = "hello $name"
		return greeting
	}

	fun greetAll(names: List<String>): List<String> =
		names.map { each ->
			val greeting = "hello $each"
			greeting
		}

	fun timed(): Int =
		measured("greeter") {
			val doubled = topLevel(21)
			doubled
		}

	fun deferred(): Runnable =
		object : Runnable {
			override fun run() {
				val answer = 42
				println("anonymous object ran: $answer")
			}
		}
}

suspend fun suspending(n: Int): Int {
	val doubled = topLevel(n)
	return doubled + 1
}

fun runAll() {
	val greeter = Greeter("world")
	println(greeter.greet())
	println(greeter.greetAll(listOf("first", "second")))
	println(greeter.timed())
	greeter.deferred().run()
}
