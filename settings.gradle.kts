rootProject.name = "JavaJumper"

// One Gradle project, three subprojects. The benchmark used to be a separate build with its own
// wrapper: half of the commands only worked from its own directory, and the harness picked up
// jmp.jar by path and could easily measure the previous change. Now bench and example depend on
// :lang as a project, and Gradle rebuilds the language before any run.
// example/ is three projects itself: the server API, the server, and the Jumper plugin; example/server/
// is not a project but the folder the server runs in (see example/README.md)
include("lang")
// Not in the repository (see .gitignore), only in the author's working copy: included when present
for (p in listOf("bench", "example:api", "example:server-app", "example:jumper-plugin")) {
    if (file(p.replace(':', '/')).isDirectory) include(p)
}
