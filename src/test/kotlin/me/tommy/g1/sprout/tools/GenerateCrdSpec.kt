package me.tommy.g1.sprout.tools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class GenerateCrdSpec :
    FunSpec({
        test("the checked-in CRD matches what the generator produces") {
            val out = Files.createTempDirectory("crd").toFile()
            main(arrayOf(out.path))
            val name = "previewenvironments.sprout.dev-v1.yml"
            out.resolve(name).readText() shouldBe java.io.File("config/crd/$name").readText()
        }

        test("the default output directory is config/crd") {
            main(emptyArray())
            java.io.File("config/crd").isDirectory shouldBe true
        }
    })
