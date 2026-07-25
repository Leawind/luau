package io.github.suterxom.luau.v1.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suterxom.luau.internal.loader.NativePlatform;
import io.github.suterxom.luau.v1.api.runtime.ExecutionBudget;
import io.github.suterxom.luau.v1.api.runtime.ExecutionResult;
import io.github.suterxom.luau.v1.api.runtime.InstanceConfig;
import io.github.suterxom.luau.v1.api.value.LuauArray;
import io.github.suterxom.luau.v1.api.value.LuauBoolean;
import io.github.suterxom.luau.v1.api.value.LuauBuffer;
import io.github.suterxom.luau.v1.api.value.LuauNil;
import io.github.suterxom.luau.v1.api.value.LuauNumber;
import io.github.suterxom.luau.v1.api.value.LuauString;
import io.github.suterxom.luau.v1.api.value.LuauTable;
import io.github.suterxom.luau.v1.api.value.LuauValue;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LuauRuntimeTest {
  private final LuauRuntime runtime = LuauRuntime.getInstance();

  @Test
  void reportsMatchingRuntimeInformation() {
    RuntimeInfo info = runtime.info();

    assertEquals("0.722", info.luauVersion());
    assertEquals("32d52d1b2ceef46fc25d87094a2d7f201c3ea5b8", info.luauCommit());
    assertEquals(10, info.jniAbiVersion());
    assertTrue(info.runtimeVersion().startsWith("0.1.0"));
    assertEquals(NativePlatform.current().id(), info.nativePlatform());
  }

  @Test
  void compilesAndExecutesPrimitiveValues() {
    var compilation =
        runtime.compile(
            "模块😀",
            """
            return nil, true, 40 + 2, "你好，Luau"
            """);

    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());
    try (CompiledChunk chunk = compilation.chunk().orElseThrow()) {
      var completed = assertInstanceOf(ExecutionResult.Completed.class, chunk.execute());
      assertEquals(
          List.of(
              LuauNil.INSTANCE,
              new LuauBoolean(true),
              new LuauNumber(42),
              new LuauString("你好，Luau")),
          completed.values());
      assertTrue(completed.metrics().peakMemoryBytes() > 0);
      assertTrue(
          completed.metrics().peakMemoryBytes() >= completed.metrics().currentMemoryBytes());
    }
  }

  @Test
  void returnsCompilationDiagnosticForInvalidSource() {
    var compilation = runtime.compile("broken", "local =");

    assertFalse(compilation.successful());
    assertTrue(compilation.chunk().isEmpty());
    assertFalse(compilation.diagnostics().isEmpty());
    assertTrue(
        compilation.diagnostics().stream()
            .allMatch(diagnostic -> diagnostic.moduleName().equals("broken")));
    assertTrue(
        compilation.diagnostics().stream()
            .allMatch(
                diagnostic ->
                    diagnostic.stage()
                        == io.github.suterxom.luau.v1.api.compile.CompilationDiagnostic.Stage.PARSE));
  }

  @Test
  void rejectsStrictTypeErrorsBeforeBytecodeCompilation() {
    var compilation =
        runtime.compile(
            "typed",
            """
            local count: number = "not a number"
            return count
            """);

    assertFalse(compilation.successful());
    assertEquals(
        io.github.suterxom.luau.v1.api.compile.CompilationDiagnostic.Stage.TYPE,
        compilation.diagnostics().get(0).stage());
    assertTrue(compilation.diagnostics().get(0).message().contains("string"));
    assertTrue(compilation.diagnostics().get(0).message().contains("number"));
  }

  @Test
  void returnsStructuredRuntimeFailure() {
    var compilation = runtime.compile("failure", "error('预期错误😀')");
    assertTrue(compilation.successful());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow()) {
      var failure = assertInstanceOf(ExecutionResult.RuntimeFailed.class, chunk.execute());
      assertTrue(failure.message().contains("预期错误😀"));
      assertTrue(failure.metrics().peakMemoryBytes() > 0);
    }
  }

  @Test
  void sandboxDoesNotExposeProcessExecution() {
    var compilation = runtime.compile("sandbox", "return (os :: any).execute");
    assertTrue(compilation.successful());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow()) {
      var completed = assertInstanceOf(ExecutionResult.Completed.class, chunk.execute());
      assertEquals(List.of(LuauNil.INSTANCE), completed.values());
    }
  }

  @Test
  void closedChunkCannotExecute() {
    CompiledChunk chunk = runtime.compile("main", "return 1").chunk().orElseThrow();
    chunk.close();

    assertThrows(IllegalStateException.class, chunk::execute);
    chunk.close();
  }

  @Test
  void stopsInfiniteLoopAtInterruptCheckLimit() {
    var compilation = runtime.compile("interrupt-limit", "while true do end");
    assertTrue(compilation.successful());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow()) {
      var exceeded =
          assertInstanceOf(
              ExecutionResult.LimitExceeded.class,
              chunk.execute(new ExecutionBudget(Duration.ofSeconds(5), 1_000)));
      assertEquals(
          ExecutionResult.LimitExceeded.Kind.INTERRUPT_CHECKS, exceeded.kind());
      assertEquals(1_001, exceeded.metrics().interruptChecks());
    }
  }

  @Test
  void stopsInfiniteLoopAtRuntimeLimit() {
    var compilation = runtime.compile("runtime-limit", "while true do end");
    assertTrue(compilation.successful());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow()) {
      var exceeded =
          assertInstanceOf(
              ExecutionResult.LimitExceeded.class,
              chunk.execute(new ExecutionBudget(Duration.ofNanos(1), Long.MAX_VALUE)));
      assertEquals(ExecutionResult.LimitExceeded.Kind.RUNTIME, exceeded.kind());
      assertTrue(exceeded.metrics().interruptChecks() > 0);
    }
  }

  @Test
  void stopsAllocationAtVmMemoryLimit() {
    var compilation =
        runtime.compile(
            "memory-limit",
            """
            local values = {}
            while true do
                table.insert(values, buffer.create(4096))
            end
            """);
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow()) {
      var exceeded =
          assertInstanceOf(
              ExecutionResult.LimitExceeded.class,
              chunk.execute(new ExecutionBudget(Duration.ofSeconds(10), Long.MAX_VALUE)));
      assertEquals(ExecutionResult.LimitExceeded.Kind.MEMORY, exceeded.kind());
      assertTrue(exceeded.metrics().peakMemoryBytes() <= 64L * 1024 * 1024);
    }
  }

  @Test
  void persistentInstancesKeepStateWithoutSharingIt() {
    var compilation =
        runtime.compile(
            "persistent",
            """
            --!nocheck
            counter = (counter or 0) + 1
            return counter
            """);
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance first = chunk.createInstance();
        ScriptInstance second = chunk.createInstance()) {
      assertEquals(
          List.of(new LuauNumber(1)),
          assertInstanceOf(ExecutionResult.Completed.class, first.execute()).values());
      assertEquals(
          List.of(new LuauNumber(2)),
          assertInstanceOf(ExecutionResult.Completed.class, first.execute()).values());
      assertEquals(
          List.of(new LuauNumber(1)),
          assertInstanceOf(ExecutionResult.Completed.class, second.execute()).values());
    }
  }

  @Test
  void failedPersistentInstanceBecomesFaulted() {
    var compilation = runtime.compile("faulted", "error('boom')");
    assertTrue(compilation.successful());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance()) {
      assertInstanceOf(ExecutionResult.RuntimeFailed.class, instance.execute());
      assertTrue(instance.isFaulted());
      assertThrows(IllegalStateException.class, instance::execute);
      instance.reset();
      assertFalse(instance.isFaulted());
      assertInstanceOf(ExecutionResult.RuntimeFailed.class, instance.execute());
    }
  }

  @Test
  void validatesInstanceMemoryLimits() {
    assertThrows(IllegalArgumentException.class, () -> new InstanceConfig(1024));
    assertThrows(
        IllegalArgumentException.class,
        () -> new InstanceConfig(1024L * 1024 * 1024 + 1));
  }

  @Test
  void initializesAndInvokesRootExports() {
    var compilation =
        runtime.compile(
            "exports",
            """
            local total = 0
            return {
                add = function(left: number, right: number): number
                    return left + right
                end,
                accumulate = function(value: number): number
                    total += value
                    return total
                end,
                echo = function(value: string): string
                    return value
                end,
            }
            """);
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance()) {
      var initialized =
          assertInstanceOf(ExecutionResult.Completed.class, instance.initialize());
      assertTrue(initialized.values().isEmpty());

      var sum =
          assertInstanceOf(
              ExecutionResult.Completed.class,
              instance.invokeExport(
                  "add", List.of(new LuauNumber(20), new LuauNumber(22))));
      assertEquals(List.of(new LuauNumber(42)), sum.values());

      assertEquals(
          List.of(new LuauNumber(3)),
          assertInstanceOf(
                  ExecutionResult.Completed.class,
                  instance.invokeExport("accumulate", List.of(new LuauNumber(3))))
              .values());
      assertEquals(
          List.of(new LuauNumber(7)),
          assertInstanceOf(
                  ExecutionResult.Completed.class,
                  instance.invokeExport("accumulate", List.of(new LuauNumber(4))))
              .values());
      assertEquals(
          List.of(new LuauString("你好😀")),
          assertInstanceOf(
                  ExecutionResult.Completed.class,
                  instance.invokeExport("echo", List.of(new LuauString("你好😀"))))
              .values());
    }
  }

  @Test
  void roundTripsNestedSafeValuesThroughExportCalls() {
    var compilation =
        runtime.compile(
            "structured-values",
            """
            --!nocheck
            return {
                echo = function(value)
                    return value
                end,
                create = function()
                    return {
                        ["键😀"] = "值",
                        items = {1, true, ""},
                        payload = buffer.fromstring("\\0Luau"),
                    }
                end,
            }
            """);
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    LuauValue input =
        new LuauTable(
            Map.of(
                "键😀\0",
                new LuauString("值😀"),
                "items",
                new LuauArray(
                    List.of(
                        new LuauNumber(1),
                        new LuauBoolean(false),
                        new LuauString(""))),
                "empty",
                new LuauBuffer(new byte[0]),
                "emptyArray",
                new LuauArray(List.of()),
                "emptyTable",
                new LuauTable(Map.of()),
                "arrayWithNil",
                new LuauArray(List.of(LuauNil.INSTANCE, new LuauString("after nil"))),
                "payload",
                new LuauBuffer(new byte[] {0, 1, -1})));

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance()) {
      assertInstanceOf(ExecutionResult.Completed.class, instance.initialize());

      var echoed =
          assertInstanceOf(
              ExecutionResult.Completed.class,
              instance.invokeExport("echo", List.of(input)));
      assertEquals(List.of(input), echoed.values());

      var created =
          assertInstanceOf(
              ExecutionResult.Completed.class,
              instance.invokeExport("create", List.of()));
      assertEquals(
          List.of(
              new LuauTable(
                  Map.of(
                      "键😀",
                      new LuauString("值"),
                      "items",
                      new LuauArray(
                          List.of(
                              new LuauNumber(1),
                              new LuauBoolean(true),
                              new LuauString(""))),
                      "payload",
                      new LuauBuffer(new byte[] {0, 'L', 'u', 'a', 'u'})))),
          created.values());
    }
  }

  @Test
  void rejectsUnsafeAndCyclicReturnValuesAsStructuredFailures() {
    assertStructuredValueFailure(
        "invalid-key",
        """
        --!nocheck
        return {run = function()
            return {[true] = "not allowed"}
        end}
        """,
        "key type");
    assertStructuredValueFailure(
        "cyclic-table",
        """
        --!nocheck
        return {run = function()
            local value = {}
            value.self = value
            return value
        end}
        """,
        "Cyclic");
    assertStructuredValueFailure(
        "invalid-utf8",
        """
        --!nocheck
        return {run = function()
            return string.char(255)
        end}
        """,
        "UTF-8");
  }

  @Test
  void reportsNestedReturnValueBudgetAsLimitExceeded() {
    var compilation =
        runtime.compile(
            "deep-value",
            """
            --!nocheck
            return {run = function()
                local root = {}
                local current = root
                for _ = 1, 34 do
                    local child = {}
                    current.child = child
                    current = child
                end
                return root
            end}
            """);
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance()) {
      assertInstanceOf(ExecutionResult.Completed.class, instance.initialize());
      var exceeded =
          assertInstanceOf(
              ExecutionResult.LimitExceeded.class,
              instance.invokeExport("run", List.of()));
      assertEquals(ExecutionResult.LimitExceeded.Kind.VALUE_CODEC, exceeded.kind());
      assertTrue(exceeded.message().contains("depth"));
      assertTrue(instance.isFaulted());
    }
  }

  @Test
  void reportsOversizedReturnBufferAsValueCodecLimit() {
    var compilation =
        runtime.compile(
            "large-return-value",
            """
            --!nocheck
            return {run = function()
                return buffer.create(1024 * 1024 + 1)
            end}
            """);
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance()) {
      assertInstanceOf(ExecutionResult.Completed.class, instance.initialize());
      var exceeded =
          assertInstanceOf(
              ExecutionResult.LimitExceeded.class,
              instance.invokeExport("run", List.of()));
      assertEquals(ExecutionResult.LimitExceeded.Kind.VALUE_CODEC, exceeded.kind());
      assertTrue(exceeded.message().contains("buffer byte"));
      assertTrue(instance.isFaulted());
    }
  }

  @Test
  void rejectsOversizedJavaValuesBeforeEnteringTheVm() {
    var compilation =
        runtime.compile(
            "input-limit",
            """
            --!nocheck
            return {echo = function(value) return value end}
            """);
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance()) {
      assertInstanceOf(ExecutionResult.Completed.class, instance.initialize());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              instance.invokeExport(
                  "echo",
                  List.of(new LuauBuffer(new byte[1024 * 1024 + 1]))));
      assertFalse(instance.isFaulted());
    }
  }

  private void assertStructuredValueFailure(
      String chunkName, String source, String expectedMessage) {
    var compilation = runtime.compile(chunkName, source);
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance()) {
      assertInstanceOf(ExecutionResult.Completed.class, instance.initialize());
      var failure =
          assertInstanceOf(
              ExecutionResult.RuntimeFailed.class,
              instance.invokeExport("run", List.of()));
      assertTrue(failure.message().contains(expectedMessage), failure::message);
      assertTrue(instance.isFaulted());
    }
  }
}
