package me.tommy.g1.sprout

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class SproutApplication

fun main(args: Array<String>) {
    runApplication<SproutApplication>(*args)
}
