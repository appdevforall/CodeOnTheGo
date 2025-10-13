package com.itsaky.androidide.utils

data class Either<L, R>(
	private val _left: L? = null,
	private val _right: R? = null,
) {
	val isLeft: Boolean
		get() = _left != null && _right == null

	val left: L
		get() =
			checkNotNull(_left) {
				"Either.left is null"
			}

	val isRight: Boolean
		get() = _left == null && _right != null

	val right: R
		get() =
			checkNotNull(_right) {
				"Either.right is null"
			}

	companion object {
		fun <L, R> left(value: L) = Either<L, R>(_left = value)

		fun <L, R> right(value: R) = Either<L, R>(_right = value)
	}
}
