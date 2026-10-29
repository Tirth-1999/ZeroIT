plugins { kotlin("jvm") version "2.2.21" }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencyLocking { lockAllConfigurations() }
tasks.register<JavaExec>("conformance") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("zeroit.protocol.ConformanceKt")
    args(rootDir.absolutePath, layout.buildDirectory.file("reports/conformance.json").get().asFile.absolutePath)
}
tasks.check { dependsOn("conformance") }
