// Root: shared settings and the iteration-cycle tasks. The language itself is lang/, the
// benchmark is bench/, the embedding example is example/. All commands run from the root:
//
//   gradlew iterate                       tests (Tier 1, Tier 0, tableprims off) + correctness gate
//   gradlew guard  --args="--opts unbox"  the gate only, on the chosen slice
//   gradlew benchAB --args="--opt unbox"  effect of one optimization, both branches in one session
//   gradlew benchDelta                    delta over java + both Jumper engines, ~10 minutes
//   gradlew bench                         full table of the suite, ~1 hour
//   gradlew :lang:test                    language tests only
//   gradlew :example:run                  the embedding example (a server in example/server/; Jumper is one of its plugins)
//
// Benchmark tasks live in :bench, but Gradle also finds them by short name from the root:
// `gradlew benchDelta` runs :bench:benchDelta. The old way of passing harness arguments,
// -Pargs="...", is still understood (see bench/build.gradle.kts), but --args="..." now works directly.
//
// On Windows: jj.cmd is the same gradlew but with chcp 65001 (UTF-8 output); `jj` with no arguments prints task help.

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    // Compile for Java 21 with any installed JDK 21+ (the author uses JDK 25), no toolchain provisioning
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
        options.encoding = "UTF-8"
    }
}

// One command after every change. Further along the cycle come benchAB on a questionable change
// and benchDelta for the numbers; they are separate because they cost minutes, while this step
// is always mandatory.
tasks.register("iterate") {
    group = "benchmark"
    description = "Iteration step: language tests in all modes and the benchmark correctness gate"
    dependsOn(":lang:test", ":lang:testTier0", ":lang:testTablePrimsOff")
    if (findProject(":bench") != null) dependsOn(":bench:guard")
}
