plugins {
    id("org.jetbrains.kotlin.jvm")
    kotlin("plugin.serialization")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("org.appdevforall.codeonthego.lsp.kotlin.generator.StdlibIndexGeneratorKt")
}

dependencies {
    implementation(kotlin("reflect"))
    implementation(kotlin("stdlib"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.common.kotlin.coroutines.core)
}

tasks.register<JavaExec>("generateStdlibIndex") {
    group = "generation"
    description = "Generates the stdlib-index.json file for the Kotlin standard library"

    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.appdevforall.codeonthego.lsp.kotlin.generator.StdlibIndexGeneratorKt")

    val outputDir = layout.buildDirectory.dir("generated-resources/stdlib")
    outputs.dir(outputDir)
    args = listOf(outputDir.get().asFile.absolutePath)

    doFirst {
        outputDir.get().asFile.mkdirs()
    }
}
