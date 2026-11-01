package me.tommy.g1.sprout.reconcile

import java.time.Duration

class Requeue {
    private var after: Duration? = null

    fun earliest(candidate: Duration): Duration = (after?.let { minOf(it, candidate) } ?: candidate).also { after = it }

    fun orNull(): Duration? = after
}
