package me.tommy.g1.sprout.domain

import me.tommy.g1.sprout.api.PreviewEnvironment
import java.time.Duration
import java.time.Instant

private val TTL = Regex("^([1-9][0-9]*)([smhd])$")

fun parseTtl(raw: String): Duration? =
    TTL.matchEntire(raw)?.destructured?.let { (amount, unit) ->
        amount.toLongOrNull()?.let { n ->
            runCatching {
                when (unit) {
                    "s" -> Duration.ofSeconds(n)
                    "m" -> Duration.ofMinutes(n)
                    "h" -> Duration.ofHours(n)
                    else -> Duration.ofDays(n)
                }
            }.getOrNull()
        }
    }

fun expiresAt(
    createdAt: Instant,
    ttl: Duration,
): Instant = createdAt.plus(ttl)

sealed interface TtlDecision {
    object NoExpiry : TtlDecision

    class Invalid(
        val raw: String,
    ) : TtlDecision

    class Running(
        val expiresAt: Instant,
        val remaining: Duration,
    ) : TtlDecision

    class Expired(
        val raw: String,
    ) : TtlDecision
}

fun PreviewEnvironment.decideTtl(now: Instant): TtlDecision {
    val raw = spec.ttl
    val expiry = raw?.let(::parseTtl)?.let { expiresAt(Instant.parse(metadata.creationTimestamp), it) }
    return when {
        raw == null -> TtlDecision.NoExpiry
        expiry == null -> TtlDecision.Invalid(raw)
        now.isBefore(expiry) -> TtlDecision.Running(expiry, Duration.between(now, expiry))
        else -> TtlDecision.Expired(raw)
    }
}
