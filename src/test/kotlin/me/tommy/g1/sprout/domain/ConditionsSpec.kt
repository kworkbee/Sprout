package me.tommy.g1.sprout.domain

import io.fabric8.kubernetes.api.model.Condition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.time.Instant

class ConditionsSpec :
    FunSpec({
        val t0 = Instant.parse("2026-01-01T00:00:00Z")

        test("transition time is kept while the status is unchanged and moves when it flips") {
            val conditions = mutableListOf<Condition>()
            conditions.setCondition(READY, false, Reason.Progressing, "m", 1, t0)
            conditions.setCondition(READY, false, Reason.Progressing, "m", 1, t0.plusSeconds(60))
            conditions.single().lastTransitionTime shouldBe t0.toString()

            conditions.setCondition(READY, true, Reason.WorkloadsAvailable, "m", 1, t0.plusSeconds(120))
            conditions shouldHaveSize 1
            conditions.single().lastTransitionTime shouldBe t0.plusSeconds(120).toString()
            conditions.single().status shouldBe "True"
        }

        test("other condition types are untouched") {
            val conditions = mutableListOf<Condition>()
            conditions.setCondition("Other", true, Reason.Progressing, "m", null, t0)
            conditions.setCondition(READY, true, Reason.Progressing, "m", 2, t0)
            conditions shouldHaveSize 2
        }

        test("default timestamp is used when none is given") {
            val conditions = mutableListOf<Condition>()
            conditions.setCondition(READY, true, Reason.Progressing, "m", 1)
            conditions.single().lastTransitionTime shouldBe
                Instant.parse(conditions.single().lastTransitionTime).toString()
        }
    })
