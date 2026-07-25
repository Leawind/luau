package io.github.suterxom.luau.v1.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suterxom.luau.v1.api.runtime.ExecutionBudget;
import io.github.suterxom.luau.v1.api.runtime.ExecutionResult;
import io.github.suterxom.luau.v1.api.value.LuauBoolean;
import io.github.suterxom.luau.v1.api.value.LuauNil;
import io.github.suterxom.luau.v1.api.value.LuauNumber;
import io.github.suterxom.luau.v1.api.value.LuauString;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class LuauRuntimeTest {
  private final LuauRuntime runtime = LuauRuntime.getInstance();

  @Test
  void reportsMatchingRuntimeInformation() {
    RuntimeInfo info = runtime.info();

    assertEquals("0.722", info.luauVersion());
    assertEquals("32d52d1b2ceef46fc25d87094a2d7f201c3ea5b8", info.luauCommit());
    assertEquals(2, info.jniAbiVersion());
    assertTrue(info.runtimeVersion().startsWith("0.1.0"));
    assertTrue(info.nativePlatform().startsWith("linux-"));
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
    assertEquals(1, compilation.diagnostics().size());
    assertEquals("broken", compilation.diagnostics().get(0).moduleName());
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
}
