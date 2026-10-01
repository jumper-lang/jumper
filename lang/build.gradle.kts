// `java.security...` cannot be written out in this script: `java` here is the java { } extension
import java.security.MessageDigest

// The language: lexer, parser, interpreter, Tier 1, javax.script, the jmp launcher.
plugins {
    id("java")
    id("application")
}

group = "me.padej"
version = "0.11.0"

// Output encoding of the processes Gradle forks.
//
// Such a process's stdout is not a console but a pipe to the Gradle daemon, and the daemon reads
// it as UTF-8. So the process must write UTF-8. file.encoding alone is not enough: since JDK 19
// System.out takes its encoding from stdout.encoding, which on Windows is the console code page
// (cp866). Then cp866 goes into the pipe, the daemon decodes it as UTF-8, and every non-ASCII
// character becomes U+FFFD - in gradlew output that shows up as mojibake and "x" turns into "?".
//
// file.encoding is set via defaultCharacterEncoding: a -Dfile.encoding of our own in jvmArgs would
// be overridden anyway, because Gradle supplies its own.
fun org.gradle.process.JavaForkOptions.utf8Pipe() {
    defaultCharacterEncoding = "UTF-8"
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}
// The installed distribution (applicationDefaultJvmArgs) does not need this: there stdout is a
// real console, and the JVM should detect its encoding itself.

dependencies {
    // no runtime dependencies: the language jar is self-contained

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The start script cannot substitute its own directory into JVM arguments - do it ourselves.
tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        unixScript.writeText(unixScript.readText().replace("APP_HOME_PLACEHOLDER", "'\"\$APP_HOME\"'"))
        windowsScript.writeText(windowsScript.readText().replace("APP_HOME_PLACEHOLDER", "%APP_HOME%"))
    }
}

application {
    mainClass.set("me.padej.jumper.Main")
    applicationName = "jmp" // ./gradlew installDist -> build/install/jmp/bin/jmp
    // AppCDS: on the first run the JVM itself writes an archive of the loaded classes next to
    // the jar (JDK 19+); on later runs it takes the classes from there. Startup of `jmp hello.jmp`
    // in the container: 112 -> 75 ms vs 40 for a bare JVM; the rest is parsing and Tier 1. The
    // archive is tied to the jar and the JVM version: after a rebuild or a JDK change the JVM
    // recreates it on its own, without errors.
    applicationDefaultJvmArgs = listOf("-Xss16m", "-XX:+AutoCreateSharedArchive",
            "-XX:SharedArchiveFile=APP_HOME_PLACEHOLDER/lib/jmp.jsa", "-Xshare:auto")
}

// CFR (MIT license, https://www.benf.org/other/cfr/), the decompiler the language server uses for
// go-to-definition into a class with no source (lsp.Sources, lsp.Cfr). It is not packed into jmp.jar: the
// server downloads it once into Jumper's data folder (%LOCALAPPDATA%\jumper\cfr), the first time a class needs
// it. What it downloads is pinned here - this version, and the SHA-256 of the jar Gradle resolves for it -
// and travels in jmp.jar as META-INF/jumper/cfr.properties: a jar with another hash is never run.
val decompiler by configurations.creating { isTransitive = false }
dependencies {
    decompiler("org.benf:cfr:0.152")
}

val cfrPin = tasks.register("cfrPin") {
    description = "Pin the CFR the language server downloads: META-INF/jumper/cfr.properties (version, URL, SHA-256)"
    val jars: FileCollection = decompiler
    val out = layout.buildDirectory.dir("generated/cfr-pin")
    inputs.files(jars)
    outputs.dir(out)
    doLast {
        val jar = jars.singleFile
        val version = jar.name.removePrefix("cfr-").removeSuffix(".jar")
        val sha = MessageDigest.getInstance("SHA-256").digest(jar.readBytes())
                .joinToString("") { b -> "%02x".format(b) }
        val file = out.get().file("META-INF/jumper/cfr.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("# The CFR the language server downloads (lsp.Cfr), pinned by lang/build.gradle.kts\n"
                + "version=$version\n"
                + "url=https://repo1.maven.org/maven2/org/benf/cfr/$version/cfr-$version.jar\n"
                + "sha256=$sha\n")
    }
}
sourceSets["main"].resources.srcDir(cfrPin)

// java -jar build/libs/jmp.jar script.jmp - the jar is self-contained
tasks.jar {
    archiveFileName.set("jmp.jar")
    manifest { attributes["Main-Class"] = "me.padej.jumper.Main" }
    from(rootProject.file("lang/THIRD-PARTY.txt")) { into("META-INF") }
}

// The editor extensions (github.com/jumper-lang: jumper-vscode, jumper.nvim) run jmp.jar of a release of this
// repository, pinned by its SHA-256 (.github/workflows/release.yml publishes jmp.jar and jmp.jar.sha256). To try a
// local build in them: JUMPER_SERVER_JAR=lang/build/libs/jmp.jar npm run server (VS Code), opts.jar (Neovim).

// Tests run in two modes: everything through Tier 1 (force) and the pure interpreter (Tier 0)
tasks.test {
    useJUnitPlatform()
    utf8Pipe()
    systemProperty("jmp.tier1", "force")
    testLogging { events("passed", "failed", "skipped") }
}

tasks.register<Test>("testTier0") {
    group = "verification"
    description = "Run tests with Tier 1 disabled (interpreter only)"
    useJUnitPlatform()
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    utf8Pipe()
    systemProperty("jmp.tier1", "0")
    testLogging { events("failed", "skipped") }
}

// An optimization that is switched off must give the same answers as when switched on - otherwise
// it is not an optimization. Both states cannot be checked in one run: the value is read in a
// static initializer and cannot be flipped inside one JVM. Hence a separate run of the whole test
// file with the flag inverted; it costs the same few seconds. While tableprims was off by default
// this task turned it on; now it is the other way round, it turns it off.
tasks.register<Test>("testTablePrimsOff") {
    group = "verification"
    description = "Run tests with typed table slots off (jmp.tableprims=0)"
    useJUnitPlatform()
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    utf8Pipe()
    systemProperty("jmp.tier1", "force")
    systemProperty("jmp.tableprims", "0")
    testLogging { events("failed", "skipped") }
}

tasks.named("check") { dependsOn("testTier0", "testTablePrimsOff") }

// Tests never download CFR into the user's data folder: they expect outlines, or bring a stand-in (CfrTest)
tasks.withType<Test>().configureEach {
    systemProperty("jmp.cfr", "off")
}

// ./gradlew run --args="path/to/script.jmp"
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    utf8Pipe()
    jvmArgs("-Xss16m")
}
