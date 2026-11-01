package me.tommy.g1.sprout

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import me.tommy.g1.sprout.config.SproutProperties
import org.springframework.boot.runApplication

private val args = arrayOf("--sprout.operator.enabled=false", "--spring.main.keep-alive=false")

class ApplicationSpec :
    FunSpec({
        test("the context starts without an operator and binds the properties") {
            runApplication<SproutApplication>(*args).use {
                it.getBean(SproutProperties::class.java).ingress.domain shouldBe "localhost"
            }
        }

        test("the entry point starts the application") {
            main(args)
        }
    })
