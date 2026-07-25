package io.github.suterxom.luau.v1.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suterxom.luau.v1.api.runtime.ExecutionResult;
import io.github.suterxom.luau.v1.api.value.LuauBoolean;
import io.github.suterxom.luau.v1.api.value.LuauNil;
import io.github.suterxom.luau.v1.api.value.LuauNumber;
import io.github.suterxom.luau.v1.api.value.LuauString;
import java.util.List;
import org.junit.jupiter.api.Test;

class LuauRuntimeTest {
  private final LuauRuntime runtime = LuauRuntime.getInstance();

  @Test
  void reportsMatchingRuntimeInformation() {
    RuntimeInfo info = runtime.info();

    assertEquals("0.722", info.luauVersion());
    assertEquals("32d52d1b2ceef46fc25d87094a2d7f201c3ea5b8", info.luauCommit());
    assertEquals(1, info.jniAbiVersion());
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
    var compilation = runtime.compile("failure", "error('expected failure')");
    assertTrue(compilation.successful());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow()) {
      var failure = assertInstanceOf(ExecutionResult.RuntimeFailed.class, chunk.execute());
      assertTrue(failure.message().contains("expected failure"));
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
}
