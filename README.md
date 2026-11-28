<div align="center">

<img src="docs/logo.svg" width="160" alt="luau">

</div>

# Luau

A sandboxed [Luau](https://luau.org/) runtime for the JVM. Every invocation runs under explicit resource limits.

It targets Java 17 and has no dependencies. Native Luau libraries are bundled inside the jar for every supported platform; nothing is downloaded at run time. It is also published as a Minecraft mod.

## Installation

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.leawind:luau:<version>")
}
```

`<version>` is a [release](https://github.com/Leawind/luau/releases) number. The jar carries the native library for Windows x86_64, Linux x86_64 and aarch64, and macOS x86_64 and aarch64; musl Linux, Windows ARM64 and Android are not supported.

Inside Minecraft, the same jar goes into the `mods` folder and other mods can depend on it by the mod id `luau`.

## Example

```java
var compilation = LuauRuntime.compile("main", """
    local total = 0

    return {
        add = function(value: number): number
            total += value
            return total
        end,
    }
    """);

try (var chunk = compilation.chunk().orElseThrow();
     var instance = chunk.createInstance()) {
    instance.initialize();
    var result = instance.invokeExport("add", List.of(new LuauNumber(2)));
}
```

The root module must return a table. `initialize()` runs its top-level code once and keeps that table; `invokeExport(name, arguments)` calls a function from it.

Scripts reach Java code only through a `HostModule` the host grants. Every type and member is documented in the javadoc that ships with the release.

## Third-party notices

This project embeds and distributes the [Luau](https://github.com/luau-lang/luau) implementation under the MIT License, which is based on the Lua 5.x implementation, also under the MIT License. See [`native/luau/LICENSE.txt`](native/luau/LICENSE.txt) and [`native/luau/lua_LICENSE.txt`](native/luau/lua_LICENSE.txt).
