package io.github.leawind.luau.v1.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.leawind.luau.internal.loader.NativePlatform;
import io.github.leawind.luau.v1.api.compile.CompilationDiagnostic;
import io.github.leawind.luau.v1.api.compile.SourceRange;
import io.github.leawind.luau.v1.api.runtime.ExecutionBudget;
import io.github.leawind.luau.v1.api.runtime.ExecutionResult;
import io.github.leawind.luau.v1.api.runtime.InstanceConfig;
import io.github.leawind.luau.v1.api.value.LuauArray;
import io.github.leawind.luau.v1.api.value.LuauBoolean;
import io.github.leawind.luau.v1.api.value.LuauBuffer;
import io.github.leawind.luau.v1.api.value.LuauNil;
import io.github.leawind.luau.v1.api.value.LuauNumber;
import io.github.leawind.luau.v1.api.value.LuauString;
import io.github.leawind.luau.v1.api.value.LuauTable;
import io.github.leawind.luau.v1.api.value.LuauValue;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class LuauRuntimeTest {

  /** Reads what the packaging recorded about the build, so expectations come from the artifact. */
  private static String packagedProperty(String key) {
    try (InputStream stream =
        LuauRuntimeTest.class.getResourceAsStream("/META-INF/luau/runtime.properties")) {
      assertNotNull(stream, "runtime.properties must be on the test classpath");
      Properties properties = new Properties();
      properties.load(stream);
      return properties.getProperty(key);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  @Test
  void reportsMatchingRuntimeInformation() {
    RuntimeInfo info = LuauRuntime.info();

    // Every expected value comes from the packaged properties instead of a literal: a hard coded
    // one breaks on every version bump and says nothing about the built native library.
    assertEquals(packagedProperty("luauVersion"), info.luauVersion());
    assertEquals(packagedProperty("luauCommit"), info.luauCommit());
    assertEquals(packagedProperty("jniAbiVersion"), String.valueOf(info.jniAbiVersion()));
    assertEquals(NativePlatform.current().id(), info.nativePlatform());
  }

  @Test
  void compilesAndExecutesPrimitiveValues() {
    var compilation =
        LuauRuntime.compile(
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
      assertTrue(completed.metrics().peakMemoryBytes() >= completed.metrics().currentMemoryBytes());
    }
  }

  @Test
  void returnsCompilationDiagnosticForInvalidSource() {
    var compilation = LuauRuntime.compile("broken", "local =");

    assertFalse(compilation.successful());
    assertTrue(compilation.chunk().isEmpty());
    assertFalse(compilation.diagnostics().isEmpty());
    assertTrue(
        compilation.diagnostics().stream()
            .allMatch(diagnostic -> diagnostic.moduleName().equals("broken")));
    assertTrue(
        compilation.diagnostics().stream()
            .allMatch(diagnostic -> diagnostic.stage() == CompilationDiagnostic.Stage.PARSE));
  }

  @Test
  void reportsStructuredSourceRangeForTypeErrors() {
    var compilation =
        LuauRuntime.compile(
            "typed-location",
            """
            local value: number = 1
            local text: string = "wrong"
            local wrong: number = text
            return wrong
            """);

    assertFalse(compilation.successful());
    CompilationDiagnostic diagnostic = compilation.diagnostics().get(0);
    assertEquals(CompilationDiagnostic.Stage.TYPE, diagnostic.stage());
    assertEquals(3, diagnostic.sourceRange().startLine());
    assertEquals(1, diagnostic.sourceRange().startColumn());
    assertEquals(3, diagnostic.sourceRange().endLine());
    assertEquals(27, diagnostic.sourceRange().endColumn());
  }

  @Test
  void diagnosticMessagesDoNotEmbedSourceLocations() {
    var compilation =
        LuauRuntime.compile(
            "typed-message",
            """
            local count: number = "not a number"
            return count
            """);

    assertFalse(compilation.successful());
    CompilationDiagnostic diagnostic = compilation.diagnostics().get(0);
    assertFalse(diagnostic.message().matches("(?s)^\\d+:\\d+: .*"), diagnostic::message);
    assertEquals(1, diagnostic.sourceRange().startLine());
  }

  @Test
  void reportsUnknownSourceRangeForCompileStageFailures() {
    // Every local is used and initialized from a call the compiler cannot fold, so the chunk really
    // needs more registers than the 200 the compiler allows; unused locals are eliminated instead.
    StringBuilder source = new StringBuilder();
    for (int index = 0; index < 201; ++index) {
      source.append("local value").append(index).append(" = os.clock()\n");
    }
    source.append("return {");
    for (int index = 0; index < 201; ++index) {
      source.append(index == 0 ? "" : ", ").append("value").append(index);
    }
    source.append("}\n");

    var compilation = LuauRuntime.compile("compile-limit", source.toString());

    assertFalse(compilation.successful());
    CompilationDiagnostic diagnostic = compilation.diagnostics().get(0);
    assertEquals(CompilationDiagnostic.Stage.COMPILE, diagnostic.stage());
    assertEquals(SourceRange.UNKNOWN, diagnostic.sourceRange());
  }

  @Test
  void rejectsStrictTypeErrorsBeforeBytecodeCompilation() {
    var compilation =
        LuauRuntime.compile(
            "typed",
            """
            local count: number = "not a number"
            return count
            """);

    assertFalse(compilation.successful());
    assertEquals(CompilationDiagnostic.Stage.TYPE, compilation.diagnostics().get(0).stage());
    assertTrue(compilation.diagnostics().get(0).message().contains("string"));
    assertTrue(compilation.diagnostics().get(0).message().contains("number"));
  }

  @Test
  void returnsStructuredRuntimeFailure() {
    var compilation = LuauRuntime.compile("failure", "error('预期错误😀')");
    assertTrue(compilation.successful());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow()) {
      var failure = assertInstanceOf(ExecutionResult.RuntimeFailed.class, chunk.execute());
      assertTrue(failure.message().contains("预期错误😀"));
      assertTrue(failure.metrics().peakMemoryBytes() > 0);
    }
  }

  @Test
  void sandboxDoesNotExposeProcessExecution() {
    var compilation = LuauRuntime.compile("sandbox", "return (os :: any).execute");
    assertTrue(compilation.successful());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow()) {
      var completed = assertInstanceOf(ExecutionResult.Completed.class, chunk.execute());
      assertEquals(List.of(LuauNil.INSTANCE), completed.values());
    }
  }

  @Test
  void closedChunkCannotExecute() {
    CompiledChunk chunk = LuauRuntime.compile("main", "return 1").chunk().orElseThrow();
    chunk.close();

    assertEquals(
        "Compiled chunk is closed",
        assertThrows(IllegalStateException.class, chunk::execute).getMessage());
    chunk.close();
  }

  @Test
  void stopsInfiniteLoopAtInterruptCheckLimit() {
    var compilation = LuauRuntime.compile("interrupt-limit", "while true do end");
    assertTrue(compilation.successful());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow()) {
      var exceeded =
          assertInstanceOf(
              ExecutionResult.LimitExceeded.class,
              chunk.execute(new ExecutionBudget(Duration.ofSeconds(5), 1_000)));
      assertEquals(ExecutionResult.LimitExceeded.Kind.INTERRUPT_CHECKS, exceeded.kind());
      assertEquals(1_001, exceeded.metrics().interruptChecks());
    }
  }

  @Test
  void stopsInfiniteLoopAtRuntimeLimit() {
    var compilation = LuauRuntime.compile("runtime-limit", "while true do end");
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
        LuauRuntime.compile(
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
        LuauRuntime.compile(
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
    var compilation = LuauRuntime.compile("faulted", "error('boom')");
    assertTrue(compilation.successful());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance()) {
      assertInstanceOf(ExecutionResult.RuntimeFailed.class, instance.execute());
      assertTrue(instance.isFaulted());
      assertEquals(
          "Script instance is faulted",
          assertThrows(IllegalStateException.class, instance::execute).getMessage());
      instance.reset();
      assertFalse(instance.isFaulted());
      assertInstanceOf(ExecutionResult.RuntimeFailed.class, instance.execute());
    }
  }

  @Test
  void rejectsHeapLimitsOutsideTheSupportedRange() {
    assertEquals(
        "VM memory limit must be between 16777216 and 1073741824 bytes",
        assertThrows(
                IllegalArgumentException.class,
                () -> new InstanceConfig(InstanceConfig.MIN_MEMORY_BYTES - 1))
            .getMessage());
    assertEquals(
        "VM memory limit must be between 16777216 and 1073741824 bytes",
        assertThrows(
                IllegalArgumentException.class,
                () -> new InstanceConfig(InstanceConfig.MAX_MEMORY_BYTES + 1))
            .getMessage());

    assertEquals(
        InstanceConfig.MIN_MEMORY_BYTES,
        new InstanceConfig(InstanceConfig.MIN_MEMORY_BYTES).maxMemoryBytes());
    assertEquals(
        InstanceConfig.MAX_MEMORY_BYTES,
        new InstanceConfig(InstanceConfig.MAX_MEMORY_BYTES).maxMemoryBytes());
  }

  @Test
  void initializesAndInvokesRootExports() {
    var compilation =
        LuauRuntime.compile(
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
      var initialized = assertInstanceOf(ExecutionResult.Completed.class, instance.initialize());
      assertTrue(initialized.values().isEmpty());

      var sum =
          assertInstanceOf(
              ExecutionResult.Completed.class,
              instance.invokeExport("add", List.of(new LuauNumber(20), new LuauNumber(22))));
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
        LuauRuntime.compile(
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
                    List.of(new LuauNumber(1), new LuauBoolean(false), new LuauString(""))),
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
              ExecutionResult.Completed.class, instance.invokeExport("echo", List.of(input)));
      assertEquals(List.of(input), echoed.values());

      var created =
          assertInstanceOf(
              ExecutionResult.Completed.class, instance.invokeExport("create", List.of()));
      assertEquals(
          List.of(
              new LuauTable(
                  Map.of(
                      "键😀",
                      new LuauString("值"),
                      "items",
                      new LuauArray(
                          List.of(new LuauNumber(1), new LuauBoolean(true), new LuauString(""))),
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
        LuauRuntime.compile(
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
              ExecutionResult.LimitExceeded.class, instance.invokeExport("run", List.of()));
      assertEquals(ExecutionResult.LimitExceeded.Kind.VALUE_CODEC, exceeded.kind());
      assertTrue(exceeded.message().contains("depth"));
      assertTrue(instance.isFaulted());
    }
  }

  @Test
  void reportsOversizedReturnBufferAsValueCodecLimit() {
    var compilation =
        LuauRuntime.compile(
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
              ExecutionResult.LimitExceeded.class, instance.invokeExport("run", List.of()));
      assertEquals(ExecutionResult.LimitExceeded.Kind.VALUE_CODEC, exceeded.kind());
      assertTrue(exceeded.message().contains("buffer byte"));
      assertTrue(instance.isFaulted());
    }
  }

  @Test
  void rejectsOversizedJavaValuesBeforeEnteringTheVm() {
    var compilation =
        LuauRuntime.compile(
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
          () -> instance.invokeExport("echo", List.of(new LuauBuffer(new byte[1024 * 1024 + 1]))));
      assertFalse(instance.isFaulted());
    }
  }

  private void assertStructuredValueFailure(
      String chunkName, String source, String expectedMessage) {
    var compilation = LuauRuntime.compile(chunkName, source);
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance()) {
      assertInstanceOf(ExecutionResult.Completed.class, instance.initialize());
      var failure =
          assertInstanceOf(
              ExecutionResult.RuntimeFailed.class, instance.invokeExport("run", List.of()));
      assertTrue(failure.message().contains(expectedMessage), failure::message);
      assertTrue(instance.isFaulted());
    }
  }
}
