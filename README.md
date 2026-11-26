<div align="center">

<img src="src/main/resources/logo.png" width="160" alt="Luau Runtime">

</div>

# Luau Runtime

Luau Runtime is a Minecraft mod library that lets other mods compile and run sandboxed [Luau](https://luau.org/) scripts. Native Luau libraries are bundled inside the mod JAR, so no native code is downloaded at runtime.

## Features

- Strict type checking and compilation of in-memory Luau source files.
- Isolated, persistent `ScriptInstance`s with explicit lifecycle management.
- Controlled source and host modules, including a sandboxed `require`.
- Safe conversion between Java values and Luau values.
- Limits for execution time, interrupt checks, VM memory, and host calls.
- Source breakpoints, stepping, pause, resume, and termination support.

## Supported platforms

A release produces one `luau-<version>-universal.jar` that contains every supported platform:

| Platform         | Native library      |
| ---------------- | ------------------- |
| `windows-x86_64` | `luau_jni.dll`      |
| `linux-x86_64`   | `libluau_jni.so`    |
| `linux-aarch64`  | `libluau_jni.so`    |
| `macos-x86_64`   | `libluau_jni.dylib` |
| `macos-aarch64`  | `libluau_jni.dylib` |

musl Linux, Windows ARM64, and Android are not supported.

## Build

Initialize the Luau submodule, then build the project:

```bash
git submodule update --init --recursive
./gradlew build
```

A normal build compiles the JNI library for the current platform and embeds it in the produced JAR. On Windows the native build requires Visual Studio Build Tools with the C++ workload; the build script imports the MSVC environment itself, so no developer command prompt is needed.

Building requires JDK 21 or newer. The produced classes target Java 17.

## Quick start

Compile a root module, create an instance, initialize it once, and invoke an exported function:

```java
import io.github.suterxom.luau.v1.api.LuauRuntime;
import io.github.suterxom.luau.v1.api.runtime.ExecutionResult;
import io.github.suterxom.luau.v1.api.value.LuauNumber;

import java.util.List;

var runtime = LuauRuntime.getInstance();
var compilation = runtime.compile("example/main", """
        local total = 0

        return {
            add = function(value: number): number
                total += value
                return total
            end,
        }
        """);

if (!compilation.successful()) {
    throw new IllegalStateException(compilation.diagnostics().toString());
}

try (var chunk = compilation.chunk().orElseThrow();
     var instance = chunk.createInstance()) {
    if (!(instance.initialize() instanceof ExecutionResult.Completed)) {
        throw new IllegalStateException("Script initialization failed");
    }

    var result = instance.invokeExport("add", List.of(new LuauNumber(2)));
    if (result instanceof ExecutionResult.Completed completed) {
        System.out.println(completed.values()); // [LuauNumber[value=2.0]]
    }
}
```

The root module must return an export table. `initialize()` runs its top-level code once and keeps that table, and `invokeExport(name, arguments)` calls a function from it. Handle the other `ExecutionResult` variants when scripts may pause, exceed limits, or fail at runtime.

## Using it from another mod

No Maven artifacts are published. Consume the released universal JAR as a mod dependency and declare a dependency on the `luau` mod in your loader metadata.

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

The native runtime loads on the first call to the public API.

## Third-party notices

This project embeds and distributes the [Luau](https://github.com/luau-lang/luau) implementation. Luau is distributed under the MIT License and is based on the Lua 5.x implementation, which is also distributed under the MIT License.

Please refer to [`native/luau/LICENSE.txt`](native/luau/LICENSE.txt) and [`native/luau/lua_LICENSE.txt`](native/luau/lua_LICENSE.txt) for the applicable license texts.
