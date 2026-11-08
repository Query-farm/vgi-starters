// Build for the VGI starter worker.
//
//   ./gradlew shadowJar        -> build/libs/vgi-starter-worker-all.jar (the worker)
//   ./gradlew recipesJar       -> build/recipes/recipes.jar (examples/, one jar, all recipes)
//   ./gradlew test             -> JUnit unit tests
//   ./gradlew spotlessApply    -> format (google-java-format)
//
// `make` wraps all of these; see the Makefile.

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    // Fat jar: one file that DuckDB can run with `java -jar`.
    id("com.gradleup.shadow") version "9.6.1"
    // Formatting gate (`make lint` runs spotlessCheck).
    id("com.diffplug.spotless") version "8.9.0"
}

group = "farm.query"
// The release tag must match this (ci/check-version.sh).
version = "0.1.0"

repositories {
    mavenCentral()
}

val sdkJvmArgs = listOf(
    // Arrow's off-heap memory needs java.nio opened to it.
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    // Arrow allocates through Netty, which disables sun.misc.Unsafe on Java 25+
    // unless told otherwise (apache/arrow-java#728).
    "-Dio.netty.noUnsafe=false",
    // The shared-memory transport makes FFM (mmap/shm_open) downcalls.
    "--enable-native-access=ALL-UNNAMED",
    // Netty still uses sun.misc.Unsafe memory access; allow it without a warning.
    "--sun-misc-unsafe-memory-access=allow",
)

// The SDK is compiled for Java 21, but the worker targets the JDK the SDK is
// built and tested with. Any JDK >= 25 builds and runs it; no toolchain is
// pinned, so a newer JDK on the PATH works as is.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(
        listOf(
            "-Xlint:all,-serial,-processing,-options",
            "-Werror",
            // Required: ScalarFn reads compute() parameter names reflectively.
            "-parameters",
        ),
    )
}

val examples: SourceSet = sourceSets.create("examples") {
    java.srcDir("examples/src")
    compileClasspath += sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().runtimeClasspath
}

configurations.named("examplesImplementation") { extendsFrom(configurations.implementation.get()) }
configurations.named("examplesRuntimeOnly") { extendsFrom(configurations.runtimeOnly.get()) }

// Unit tests can exercise the recipes too (src/test/java/.../recipes).
sourceSets.test {
    compileClasspath += examples.output
    runtimeClasspath += examples.output
}

// Lockfile (gradle.lockfile): `0.46.+` floats within the minor, the lockfile pins
// what CI tested. Refresh with: ./gradlew dependencies --write-locks
dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    // The VGI SDK. Minor bumps can change the wire protocol, which must match the
    // DuckDB extension exactly; take them through template updates.
    implementation("farm.query:vgi:0.46.+")
    // JSON parsing for fetch_items (already on the SDK's runtime classpath).
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.0")
    // SLF4J backend: slf4j-simple logs to stderr, never stdout (the protocol channel).
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")

    // The pushdown recipe reads SQLite through JDBC.
    "examplesRuntimeOnly"("org.xerial:sqlite-jdbc:3.49.1.0")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs(sdkJvmArgs)
    // Surface Arrow allocator leaks immediately.
    systemProperty("arrow.memory.debug.allocator", "true")
}

// Settings shared by the worker jar and the recipes jar.
fun ShadowJar.workerJar(mainClass: String) {
    archiveVersion.set("")
    // Several jars (the SDK, Arrow, Jetty, SLF4J) ship META-INF/services files for
    // the same interfaces; concatenate them instead of keeping only the first.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    manifest {
        attributes(
            "Main-Class" to mainClass,
            "Multi-Release" to "true",
            // Lets a bare `java -jar` work without --add-opens/--enable-native-access.
            "Add-Opens" to "java.base/java.nio",
            "Enable-Native-Access" to "ALL-UNNAMED",
        )
    }
}

tasks.shadowJar {
    archiveBaseName.set("vgi-starter-worker")
    archiveClassifier.set("all")
    workerJar("farm.query.starter.StarterWorker")
}

val recipesJar by tasks.registering(ShadowJar::class) {
    group = "build"
    description = "Builds build/recipes/recipes.jar: every recipe in examples/, one dispatcher."
    from(examples.output)
    configurations.set(listOf(project.configurations.getByName("examplesRuntimeClasspath")))
    destinationDirectory.set(layout.buildDirectory.dir("recipes"))
    archiveBaseName.set("recipes")
    archiveClassifier.set("")
    workerJar("farm.query.starter.recipes.Recipes")
}

tasks.named("assemble") { dependsOn(tasks.shadowJar, recipesJar) }

spotless {
    java {
        target("src/**/*.java", "examples/**/*.java")
        googleJavaFormat("2.0.0")
    }
}
