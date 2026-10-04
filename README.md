# Jumper

[![JitPack](https://jitpack.io/v/jumper-lang/jumper.svg)](https://jitpack.io/#jumper-lang/jumper)

Jumper is a scripting language for the JVM with the syntax of Java and the lightness of Lua.

```java
import java.util.ArrayList;

dyn greet(name) { return "Hello, " + name; }

dyn names = new ArrayList();
names.add("Jumper");
names.add("Java");

dyn point = { x: 1, y: 2 };
for (dyn n : names) println(greet(n) + " " + (point.x + point.y));
```

- `dyn` for dynamic values, `int`, `long`, `double`, `boolean`, `String` where you want types checked
- functions, classes, lambdas, tables (`{ x: 1 }`), modules (`import "utils.jmp";`)
- Java classes used directly: `import`, `new`, method calls, fields
- an interpreter and a compiler to JVM bytecode for hot code

## What it is for

Scripts inside a Java application: plugins and mods of a server, user automation, configs with logic.

- **Embedding** - a standard `javax.script` engine, the host passes its objects to scripts.
- **Safety** - every script runs under an access policy (`.jma`): which Java packages, classes and methods it may touch. Everything else is closed.
- **Configs** - `.jmc` files are a safe subset of the language: data, conditions, no loops or Java.
- **Editors** - [IntelliJ IDEA](https://github.com/jumper-lang/jumper-intellij), [VS Code](https://github.com/jumper-lang/jumper-vscode), [Neovim](https://github.com/jumper-lang/jumper.nvim).

## How to add it

Requires Java 21+. Jumper is published through [JitPack](https://jitpack.io/#jumper-lang/jumper): `Tag` below is a [release](https://github.com/jumper-lang/jumper/releases) tag, the latest one is on the badge above.

### Gradle (Kotlin)

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.jumper-lang:jumper:Tag")
}
```

### Gradle (Groovy)

```groovy
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.jumper-lang:jumper:Tag'
}
```

### Maven

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.jumper-lang</groupId>
    <artifactId>jumper</artifactId>
    <version>Tag</version>
</dependency>
```

### Run a script from Java

```java
ScriptEngine jumper = new ScriptEngineManager().getEngineByName("jumper");
jumper.put("name", "world");
jumper.eval("println(\"Hello, \" + name);");
```

### Command line

Download `jmp.jar` from [Releases](https://github.com/jumper-lang/jumper/releases):

```
java -jar jmp.jar script.jmp
```
