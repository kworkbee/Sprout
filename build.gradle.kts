plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("dev.detekt") version "2.0.0-alpha.3"
    jacoco
}

group = "me.tommy.g1"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("io.javaoperatorsdk:operator-framework:5.6.3")
    implementation("io.fabric8:crd-generator-api-v2:7.9.0")
    implementation("io.javaoperatorsdk:micrometer-support:5.6.3")
    implementation("io.micrometer:micrometer-registry-prometheus")
    testImplementation("io.fabric8:kube-api-test:7.9.0")
    testImplementation("io.kotest:kotest-runner-junit5:6.2.6")
    testImplementation("io.kotest:kotest-assertions-core:6.2.6")
    testImplementation("io.mockk:mockk:1.14.11")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.register<JavaExec>("generateCrd") {
    group = "sprout"
    description = "Generates config/crd from the API types"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "me.tommy.g1.sprout.tools.GenerateCrdKt"
    args("config/crd")
}

springBoot {
    mainClass = "me.tommy.g1.sprout.SproutApplicationKt"
}

tasks.bootJar {
    archiveFileName = "sprout.jar"
}

detekt {
    buildUponDefaultConfig = true
    parallel = true
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "1.0".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = "1.0".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestReport, tasks.jacocoTestCoverageVerification)
}

jacoco {
    toolVersion = "0.8.16"
}
