package io.github.suterxom.luau.v1.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suterxom.luau.v1.api.host.HostCallContext;
import io.github.suterxom.luau.v1.api.host.HostFunction;
import io.github.suterxom.luau.v1.api.host.HostModule;
import io.github.suterxom.luau.v1.api.runtime.ExecutionBudget;
import io.github.suterxom.luau.v1.api.runtime.ExecutionResult;
import io.github.suterxom.luau.v1.api.runtime.InstanceConfig;
import io.github.suterxom.luau.v1.api.value.LuauArray;
import io.github.suterxom.luau.v1.api.value.LuauBuffer;
import io.github.suterxom.luau.v1.api.value.LuauNil;
import io.github.suterxom.luau.v1.api.value.LuauNumber;
import io.github.suterxom.luau.v1.api.value.LuauString;
import io.github.suterxom.luau.v1.api.value.LuauTable;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RuntimeBoundariesTest {
  private static final int ERROR_MESSAGE_LIMIT = 64 * 1024;
  private final LuauRuntime runtime = LuauRuntime.getInstance();

  @Test
  void protectedCallCannotSwallowExecutionLimit() throws Exception {
    assertChildStopsAtLimit("pcall");
    assertChildStopsAtLimit("xpcall");
  }

  @Test
  void initializationAndExportShareOneBudget() {
    HostModule host =
        new HostModule(
            "boundary/host@1",
            "1",
            "return (nil :: any) :: { next: () -> number }",
            () ->
                Map.of("next", (HostFunction) (context, arguments) -> List.of(new LuauNumber(1))));
    var compilation =
        runtime.compile(
            "main",
            Map.of(
                "main",
                """
                local host = require("boundary/host@1")
                host.next()
                host.next()
                return {
                    cast = function()
                        host.next()
                        return 1
                    end,
                }
                """),
            List.of(host));
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance()) {
      var budget = new ExecutionBudget(Duration.ofSeconds(1), 1_000_000, 2, 1024);
      ExecutionScope scope = new ExecutionScope(budget);
      assertInstanceOf(
          ExecutionResult.Completed.class, instance.initialize(HostCallContext.empty(), scope));
      var result = instance.invokeExport("cast", List.of(), HostCallContext.empty(), scope);
      var exceeded = assertInstanceOf(ExecutionResult.LimitExceeded.class, result);
      assertEquals(ExecutionResult.LimitExceeded.Kind.HOST_CALLS, exceeded.kind());
      assertEquals(3, exceeded.metrics().hostCalls());
    }
  }

  @Test
  void hostReturnPayloadCountsTowardOutputBudget() {
    HostModule host =
        new HostModule(
            "boundary/host@1",
            "1",
            "return (nil :: any) :: { large: () -> string }",
            () ->
                Map.of(
                    "large",
                    (HostFunction)
                        (context, arguments) -> List.of(new LuauString("x".repeat(1024)))));
    var compilation =
        runtime.compile(
            "main",
            Map.of(
                "main",
                """
                local host = require("boundary/host@1")
                return { run = function() return host.large() end }
                """),
            List.of(host));
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance()) {
      assertInstanceOf(ExecutionResult.Completed.class, instance.initialize());
      var result =
          instance.invokeExport(
              "run",
              List.of(),
              HostCallContext.empty(),
              new ExecutionBudget(Duration.ofSeconds(1), 1_000_000, 10, 1));
      var exceeded = assertInstanceOf(ExecutionResult.LimitExceeded.class, result);
      assertEquals(ExecutionResult.LimitExceeded.Kind.HOST_OUTPUT, exceeded.kind());
      assertEquals(1024, exceeded.metrics().hostOutputBytes());
    }
  }

  @Test
  void savedArraysKeepTheirShapeAcrossExportCalls() {
    var compilation =
        runtime.compile(
            "main",
            Map.of(
                "main",
                """
                --!nocheck
                local saved
                return {
                    save = function(value) saved = value end,
                    get = function() return saved end,
                }
                """));
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance()) {
      assertInstanceOf(ExecutionResult.Completed.class, instance.initialize());
      instance.invokeExport("save", List.of(new LuauArray(List.of())));
      var empty =
          assertInstanceOf(
              ExecutionResult.Completed.class, instance.invokeExport("get", List.of()));
      assertEquals(List.of(new LuauArray(List.of())), empty.values());

      instance.reset();
      assertInstanceOf(ExecutionResult.Completed.class, instance.initialize());
      instance.invokeExport(
          "save", List.of(new LuauArray(List.of(LuauNil.INSTANCE, new LuauString("after nil")))));
      var withHole =
          assertInstanceOf(
              ExecutionResult.Completed.class, instance.invokeExport("get", List.of()));
      assertEquals(
          List.of(new LuauArray(List.of(LuauNil.INSTANCE, new LuauString("after nil")))),
          withHole.values());
    }
  }

  @Test
  void idleDebuggerRequestsDoNotLeakIntoTheNextInvocation() {
    var compilation = runtime.compile("main", "return { run = function() return 1 end }");
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance()) {
      instance.debugger().requestPause();
      instance.debugger().requestTerminate();
      assertInstanceOf(ExecutionResult.Completed.class, instance.initialize());
      assertInstanceOf(ExecutionResult.Completed.class, instance.invokeExport("run", List.of()));
    }
  }

  @Test
  void sharedBudgetSurvivesDebuggerPauseWithoutDoubleCounting() {
    HostModule host =
        new HostModule(
            "boundary/host@1",
            "1",
            "return (nil :: any) :: { next: () -> number }",
            () ->
                Map.of("next", (HostFunction) (context, arguments) -> List.of(new LuauNumber(1))));
    var compilation =
        runtime.compile(
            "main",
            Map.of(
                "main",
                """
                local host = require("boundary/host@1")
                host.next()
                return {
                    run = function()
                        host.next()
                        local marker = 1
                        return marker + host.next()
                    end,
                }
                """),
            List.of(host));
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance()) {
      ExecutionScope scope =
          new ExecutionScope(new ExecutionBudget(Duration.ofSeconds(1), 1_000_000, 2, 1024));
      assertInstanceOf(
          ExecutionResult.Completed.class, instance.initialize(HostCallContext.empty(), scope));
      assertEquals(7, instance.debugger().setBreakpoint("main", 7).resolvedLine());

      var paused =
          assertInstanceOf(
              ExecutionResult.Paused.class,
              instance.invokeExport("run", List.of(), HostCallContext.empty(), scope));
      assertEquals(2, paused.metrics().hostCalls());

      var exceeded =
          assertInstanceOf(ExecutionResult.LimitExceeded.class, instance.debugger().resume());
      assertEquals(ExecutionResult.LimitExceeded.Kind.HOST_CALLS, exceeded.kind());
      assertEquals(3, exceeded.metrics().hostCalls());
    }
  }

  @Test
  void runtimeErrorMessagesAreBounded() {
    var compilation = runtime.compile("main", "error(string.rep('界', 2 * 1024 * 1024))");
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow()) {
      var failure = assertInstanceOf(ExecutionResult.RuntimeFailed.class, chunk.execute());
      assertTrue(failure.message().endsWith("[truncated]"), failure::message);
      assertTrue(!failure.message().contains("\uFFFD"), failure::message);
      assertTrue(
          failure.message().getBytes(StandardCharsets.UTF_8).length <= ERROR_MESSAGE_LIMIT + 32,
          () -> "error message was " + failure.message().length() + " characters");
    }
  }

  @Test
  void protectedCallsCannotSwallowHostCallLimit() {
    HostModule host =
        new HostModule(
            "boundary/host@1",
            "1",
            "return (nil :: any) :: { next: () -> number }",
            () ->
                Map.of("next", (HostFunction) (context, arguments) -> List.of(new LuauNumber(1))));
    var compilation =
        runtime.compile(
            "main",
            Map.of(
                "main",
                """
                local host = require("boundary/host@1")
                return {
                    run = function()
                        local ok = pcall(function()
                            while true do
                                host.next()
                            end
                        end)
                        return ok
                    end,
                }
                """),
            List.of(host));
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance()) {
      assertInstanceOf(ExecutionResult.Completed.class, instance.initialize());
      var exceeded =
          assertInstanceOf(
              ExecutionResult.LimitExceeded.class,
              instance.invokeExport(
                  "run",
                  List.of(),
                  HostCallContext.empty(),
                  new ExecutionBudget(Duration.ofSeconds(5), 10_000_000, 3, 4096)));
      assertEquals(ExecutionResult.LimitExceeded.Kind.HOST_CALLS, exceeded.kind());
    }
  }

  @Test
  void protectedCallsCannotSwallowHostOutputLimit() {
    HostModule host =
        new HostModule(
            "boundary/host@1",
            "1",
            "return (nil :: any) :: { large: () -> string }",
            () ->
                Map.of(
                    "large",
                    (HostFunction)
                        (context, arguments) -> List.of(new LuauString("x".repeat(256)))));
    var compilation =
        runtime.compile(
            "main",
            Map.of(
                "main",
                """
                local host = require("boundary/host@1")
                return {
                    run = function()
                        local ok = pcall(function()
                            while true do
                                host.large()
                            end
                        end)
                        return ok
                    end,
                }
                """),
            List.of(host));
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance()) {
      assertInstanceOf(ExecutionResult.Completed.class, instance.initialize());
      var exceeded =
          assertInstanceOf(
              ExecutionResult.LimitExceeded.class,
              instance.invokeExport(
                  "run",
                  List.of(),
                  HostCallContext.empty(),
                  new ExecutionBudget(Duration.ofSeconds(5), 10_000_000, 1000, 512)));
      assertEquals(ExecutionResult.LimitExceeded.Kind.HOST_OUTPUT, exceeded.kind());
    }
  }

  @Test
  void protectedCallsCannotSwallowMemoryLimit() {
    var compilation =
        runtime.compile(
            "main",
            Map.of(
                "main",
                """
                local kept = {}
                return {
                    run = function()
                        local ok = pcall(function()
                            for index = 1, 200000 do
                                kept[index] = { index }
                            end
                        end)
                        return ok
                    end,
                }
                """));
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance(new InstanceConfig(16L * 1024 * 1024))) {
      assertInstanceOf(ExecutionResult.Completed.class, instance.initialize());
      var exceeded =
          assertInstanceOf(
              ExecutionResult.LimitExceeded.class,
              instance.invokeExport(
                  "run",
                  List.of(),
                  HostCallContext.empty(),
                  new ExecutionBudget(Duration.ofSeconds(20), 100_000_000, 100, 0)));
      assertEquals(ExecutionResult.LimitExceeded.Kind.MEMORY, exceeded.kind(), exceeded::toString);
    }
  }

  @Test
  void terminateBreaksOutOfProtectedCall() {
    var compilation =
        runtime.compile(
            "main",
            Map.of(
                "main",
                """
                return {
                    run = function()
                        local ok = pcall(function()
                            local marker = 1
                            return marker
                        end)
                        return ok
                    end,
                }
                """));
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance()) {
      assertInstanceOf(ExecutionResult.Completed.class, instance.initialize());
      assertEquals(5, instance.debugger().setBreakpoint("main", 5).resolvedLine());
      assertInstanceOf(ExecutionResult.Paused.class, instance.invokeExport("run", List.of()));
      assertInstanceOf(ExecutionResult.Terminated.class, instance.debugger().terminate());
    }
  }

  @Test
  void hostOutputCountsBuffersTablesAndManualCosts() {
    HostModule host =
        new HostModule(
            "boundary/out@1",
            "1",
            "return (nil :: any) :: { multi: () -> (buffer, {[string]: string}) }",
            () ->
                Map.of(
                    "multi",
                    (HostFunction)
                        (context, arguments) -> {
                          context.recordOutputBytes(7);
                          return List.of(
                              new LuauBuffer(new byte[100]),
                              new LuauTable(Map.of("k", new LuauString("v"))));
                        }));
    var compilation =
        runtime.compile(
            "main",
            Map.of(
                "main",
                """
                local host = require("boundary/out@1")
                return {
                    run = function()
                        local payload, table = host.multi()
                        return buffer.len(payload) + #table.k
                    end,
                }
                """),
            List.of(host));
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance()) {
      assertInstanceOf(ExecutionResult.Completed.class, instance.initialize());
      var completed =
          assertInstanceOf(
              ExecutionResult.Completed.class,
              instance.invokeExport(
                  "run",
                  List.of(),
                  HostCallContext.empty(),
                  new ExecutionBudget(Duration.ofSeconds(5), 10_000_000, 10, 4096)));
      assertEquals(List.of(new LuauNumber(101)), completed.values());
      assertEquals(100 + 1 + 1 + 7, completed.metrics().hostOutputBytes());
    }
  }

  @Test
  void nestedArraysKeepTheirShapeAcrossExportCalls() {
    var compilation =
        runtime.compile(
            "main",
            Map.of(
                "main",
                """
                --!nocheck
                local saved
                return {
                    save = function(value) saved = value end,
                    get = function() return saved end,
                }
                """));
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance()) {
      assertInstanceOf(ExecutionResult.Completed.class, instance.initialize());
      var nested =
          new LuauArray(
              List.of(
                  new LuauArray(List.of(new LuauNumber(1), new LuauNumber(2))),
                  new LuauArray(List.of()),
                  new LuauTable(Map.of("k", new LuauArray(List.of())))));
      instance.invokeExport("save", List.of(nested));
      var read =
          assertInstanceOf(
              ExecutionResult.Completed.class, instance.invokeExport("get", List.of()));
      assertEquals(List.of(nested), read.values());
    }
  }

  @Test
  void arrayShapeSurvivesHeavyTableChurn() {
    var compilation =
        runtime.compile(
            "main",
            Map.of(
                "main",
                """
                --!nocheck
                local saved
                return {
                    churn = function()
                        for index = 1, 50 do
                            saved = { index }
                        end
                        return saved
                    end,
                    keep = function(value) saved = value end,
                    get = function() return saved end,
                }
                """));
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance()) {
      assertInstanceOf(ExecutionResult.Completed.class, instance.initialize());
      for (int round = 0; round < 40; round++) {
        var churned =
            assertInstanceOf(
                ExecutionResult.Completed.class, instance.invokeExport("churn", List.of()));
        assertEquals(
            List.of(new LuauArray(List.of(new LuauNumber(50)))),
            churned.values(),
            "round " + round);
      }
      instance.invokeExport("keep", List.of(new LuauArray(List.of())));
      var empty =
          assertInstanceOf(
              ExecutionResult.Completed.class, instance.invokeExport("get", List.of()));
      assertEquals(List.of(new LuauArray(List.of())), empty.values());
    }
  }

  private static void assertChildStopsAtLimit(String protectedCall) throws Exception {
    String classpath = childClasspath();
    Process child =
        new ProcessBuilder(
                ProcessHandle.current().info().command().orElseThrow(),
                "-cp",
                classpath,
                RuntimeBoundaryProbe.class.getName(),
                protectedCall)
            .redirectErrorStream(true)
            .start();
    if (!child.waitFor(10, TimeUnit.SECONDS)) {
      child.destroyForcibly();
      child.waitFor(5, TimeUnit.SECONDS);
      throw new AssertionError(protectedCall + " swallowed the execution limit and the child hung");
    }
    String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, child.exitValue(), output);
    assertTrue(output.contains("RESULT=LimitExceeded"), output);
  }

  private static String childClasspath() {
    ClassLoader loader = RuntimeBoundariesTest.class.getClassLoader();
    if (loader instanceof URLClassLoader urlLoader) {
      return Arrays.stream(urlLoader.getURLs())
          .map(url -> {
            try {
              return url.toURI().getPath();
            } catch (Exception exception) {
              throw new IllegalStateException(exception);
            }
          })
          .collect(Collectors.joining(java.io.File.pathSeparator));
    }
    return System.getProperty("java.class.path");
  }
}
