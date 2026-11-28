<div align="center">

<img src="docs/logo.svg" width="160" alt="luau">

</div>

# luau

A sandboxed [Luau](https://luau.org/) runtime for the JVM. Java hosts compile Luau programs ahead of time, run them in isolated VMs, and expose Java code to them only through explicitly granted modules. Every invocation runs under explicit resource limits.

The library targets Java 17 and has no dependencies. Native Luau libraries are bundled inside the JAR for every supported platform; nothing is downloaded at run time. It is also published as a Minecraft mod library for mods that would rather not bundle a native runtime; that packaging adds nothing to the API.

## Quick start

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

The root module must return a table. `initialize()` runs its top-level code once and keeps that table; `invokeExport(name, arguments)` calls a function from it. Globals persist across calls and belong to one instance.

Every invocation returns an `ExecutionResult`. `Completed` carries the returned values; the other variants report a Luau error, a limit, or a debugger stop. After a failure the instance is faulted and rejects every call until `reset()`.

## Concepts

**Modules.** A program is a set of Luau source modules compiled together; `require` resolves within that set and cannot reach outside it. Module caches are per instance.

**Host modules.** Java code is reachable only through a `HostModule`, which carries a Luau type declaration that takes part in the type check, and a factory that creates its exports once per instance:

```java
HostModule counter = new HostModule("host/counter", "1",
    """
    return (nil :: any) :: {
        next: () -> number,
    }
    """,
    () -> Map.of("next", (HostFunction) (context, arguments) ->
        List.of(new LuauNumber(next.incrementAndGet()))));

var compilation = LuauRuntime.compile("main",
    Map.of("main", """
        local counter = require("host/counter")
        return counter.next()
        """),
    List.of(counter));
```

Only the sealed `LuauValue` types cross the boundary. A `HostCallContext` passed to an invocation carries capabilities that its host functions can look up by type; nothing from the JVM reaches a script unless it is granted this way.

**Limits.** Every invocation takes an `ExecutionBudget` of wall-clock time, interrupt checks, host calls and host output. Several calls that form one logical operation share an `ExecutionScope` so they draw from one budget.

**Debugging.** Every instance has a `ScriptDebugger` with breakpoints by module and line, stepping, pause and resume. While paused, the instance rejects every other call.

**Threading.** An instance belongs to the thread that created it and rejects calls from any other thread.

Every type and member is documented in the javadoc that ships with the release.

## Supported platforms

A release produces one `luau-mod-<version>-universal.jar` that contains every supported platform:

| Platform         | Native library      |
| ---------------- | ------------------- |
| `windows-x86_64` | `luau_jni.dll`      |
| `linux-x86_64`   | `libluau_jni.so`    |
| `linux-aarch64`  | `libluau_jni.so`    |
| `macos-x86_64`   | `libluau_jni.dylib` |
| `macos-aarch64`  | `libluau_jni.dylib` |

musl Linux, Windows ARM64, and Android are not supported.

## Using the library

The library is published as `io.github.leawind:luau`. The JAR bundles the native library for the current platform; the `-universal` variant contains every supported platform and picks one at load time.

## Using the mod

Mods that already run inside a loader can depend on the `luau` mod instead of bundling the library. Declare a dependency on it in your loader metadata:

`fabric.mod.json`:

```json
"depends": {
  "luau": ">=0.1.0"
}
```

`META-INF/mods.toml`:

```toml
[[dependencies.examplemod]]
modId = "luau"
mandatory = true
versionRange = "[0.1.0,)"
ordering = "NONE"
side = "BOTH"
```

`META-INF/neoforge.mods.toml`:

```toml
[[dependencies.examplemod]]
modId = "luau"
type = "required"
versionRange = "[0.1.0,)"
ordering = "NONE"
side = "BOTH"
```

## Build

```bash
git submodule update --init --recursive
./gradlew build
```

`core` is the library: the Java API, the JNI binding, and the native build. `mod` wraps it into the loader-visible artifact. On Windows the native build requires Visual Studio Build Tools with the C++ workload. Building requires JDK 21 or newer; the produced classes target Java 17.

## Third-party notices

This project embeds and distributes the [Luau](https://github.com/luau-lang/luau) implementation under the MIT License, which is based on the Lua 5.x implementation, also under the MIT License. See [`native/luau/LICENSE.txt`](native/luau/LICENSE.txt) and [`native/luau/lua_LICENSE.txt`](native/luau/lua_LICENSE.txt).
