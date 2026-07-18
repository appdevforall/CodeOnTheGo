package org.appdevforall.cotg.corpus.medium.data

class UserRepository {
	private val users = mutableListOf<User>()

	fun add(user: User) {
		users.add(user)
	}

	fun byId(id: Int): User? = users.firstOrNull { it.id == id }

	fun all(): List<User> = users.toList()
}
