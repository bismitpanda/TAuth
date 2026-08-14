package com.panda.tauth

// Failure carries a typed error. Project errors are sealed interfaces rather than `Throwable`, which
// `kotlin.Result` cannot hold.
sealed interface Outcome<out T, out E> {
    data class Success<out T>(val value: T) : Outcome<T, Nothing>

    data class Failure<out E>(val error: E) : Outcome<Nothing, E>
}

val <T, E> Outcome<T, E>.valueOrNull: T?
    get() = when (this) {
        is Outcome.Success -> value
        is Outcome.Failure -> null
    }

val <T, E> Outcome<T, E>.errorOrNull: E?
    get() = when (this) {
        is Outcome.Success -> null
        is Outcome.Failure -> error
    }

inline fun <T, R, E> Outcome<T, E>.map(transform: (T) -> R): Outcome<R, E> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

inline fun <T, R, E> Outcome<T, E>.flatMap(transform: (T) -> Outcome<R, E>): Outcome<R, E> = when (this) {
    is Outcome.Success -> transform(value)
    is Outcome.Failure -> this
}
