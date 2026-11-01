package me.tommy.g1.sprout.tools

import io.fabric8.crdv2.generator.CRDGenerator
import me.tommy.g1.sprout.api.PreviewEnvironment
import java.io.File

fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "config/crd").also { it.mkdirs() }
    CRDGenerator().customResourceClasses(PreviewEnvironment::class.java).inOutputDir(out).generate()
}
