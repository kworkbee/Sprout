package me.tommy.g1.sprout.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import me.tommy.g1.sprout.previewEnvironment
import java.time.Duration
import java.time.Instant

class TtlSpec :
    FunSpec({
        test("units are parsed") {
            parseTtl("30s") shouldBe Duration.ofSeconds(30)
            parseTtl("30m") shouldBe Duration.ofMinutes(30)
            parseTtl("4h") shouldBe Duration.ofHours(4)
            parseTtl("2d") shouldBe Duration.ofDays(2)
        }

        test("malformed values are rejected") {
            listOf("4", "0h", "1w", "", "h", "-1h").forEach { parseTtl(it).shouldBeNull() }
        }

        test("numbers that overflow are rejected") {
            parseTtl("99999999999999999999h").shouldBeNull()
            parseTtl("${Long.MAX_VALUE}d").shouldBeNull()
        }

        test("expiry is creation plus ttl") {
            val created = Instant.parse("2026-01-01T00:00:00Z")
            expiresAt(created, Duration.ofHours(1)) shouldBe Instant.parse("2026-01-01T01:00:00Z")
        }

        context("decisions") {
            val now = Instant.parse("2026-01-01T00:30:00Z")

            fun env(ttl: String?) =
                previewEnvironment("a", ttl = ttl).apply {
                    metadata.creationTimestamp = "2026-01-01T00:00:00Z"
                }

            test("no ttl means no expiry") {
                env(null).decideTtl(now) shouldBe TtlDecision.NoExpiry
            }

            test("a malformed ttl is invalid") {
                env("forever").decideTtl(now).shouldBeInstanceOf<TtlDecision.Invalid>().raw shouldBe "forever"
            }

            test("a ttl in the future is running with the time left") {
                val running = env("1h").decideTtl(now).shouldBeInstanceOf<TtlDecision.Running>()
                running.expiresAt shouldBe Instant.parse("2026-01-01T01:00:00Z")
                running.remaining shouldBe Duration.ofMinutes(30)
            }

            test("a ttl in the past is expired") {
                env("10m").decideTtl(now).shouldBeInstanceOf<TtlDecision.Expired>().raw shouldBe "10m"
            }
        }
    })
