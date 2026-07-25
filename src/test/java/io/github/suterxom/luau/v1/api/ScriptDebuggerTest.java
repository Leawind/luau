package io.github.suterxom.luau.v1.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suterxom.luau.v1.api.debug.Breakpoint;
import io.github.suterxom.luau.v1.api.debug.PauseInfo;
import io.github.suterxom.luau.v1.api.debug.SourceLocation;
import io.github.suterxom.luau.v1.api.host.HostCallContext;
import io.github.suterxom.luau.v1.api.host.HostFunction;
import io.github.suterxom.luau.v1.api.host.HostModule;
import io.github.suterxom.luau.v1.api.runtime.ExecutionResult;
import io.github.suterxom.luau.v1.api.value.LuauArray;
import io.github.suterxom.luau.v1.api.value.LuauNumber;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ScriptDebuggerTest {
  private static final String HOST_NAME = "debug/host@1";
  private static final String HOST_DECLARATION =
      """
      return (nil :: any) :: {
          beforePause: () -> number,
          fromContext: () -> number,
      }
      """;

  private final LuauRuntime runtime = LuauRuntime.getInstance();

  @Test
  void debuggerOperationsRequireTheInstanceOwnerThread() throws InterruptedException {
    var compilation = runtime.compile("debug/thread", "return 1");
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance()) {
      AtomicReference<Throwable> failure = new AtomicReference<>();
      Thread otherThread =
          new Thread(
              () -> {
                try {
                  instance.debugger().setBreakpoint("debug/thread", 1);
                } catch (Throwable throwable) {
                  failure.set(throwable);
                }
              });
      otherThread.start();
      otherThread.join();

      assertInstanceOf(IllegalStateException.class, failure.get());
      assertTrue(instance.debugger().breakpoints().isEmpty());
    }
  }

  @Test
  void pausesAndResumesRepeatedExecution() {
    var compilation =
        runtime.compile(
            "debug/execute",
            """
            local value = 40
            value += 2
            return value
            """);
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance()) {
      Breakpoint breakpoint =
          instance.debugger().setBreakpoint("debug/execute", 2);
      assertEquals(2, breakpoint.resolvedLine());

      for (int invocation = 0; invocation < 2; ++invocation) {
        assertInstanceOf(ExecutionResult.Paused.class, instance.execute());
        assertEquals(
            List.of(new LuauNumber(42)),
            assertInstanceOf(
                    ExecutionResult.Completed.class,
                    instance.debugger().resume())
                .values());
      }

      instance.debugger().clearBreakpoints();
      assertEquals(
          List.of(new LuauNumber(42)),
          assertInstanceOf(ExecutionResult.Completed.class, instance.execute())
              .values());
    }
  }

  @Test
  void resolvesBreakpointsAndResumesInitialization() {
    var compilation =
        runtime.compile(
            "debug/main",
            """
            local total = 1

            total += 2
            return {
                value = function(): number
                    return total
                end,
            }
            """);
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance()) {
      ScriptDebugger debugger = instance.debugger();
      Breakpoint fromBlankLine = debugger.setBreakpoint("debug/main", 2);
      Breakpoint exactLine = debugger.setBreakpoint("debug/main", 3);
      Breakpoint unresolved = debugger.setBreakpoint("debug/main", 100);

      assertEquals(3, fromBlankLine.resolvedLine());
      assertEquals(3, exactLine.resolvedLine());
      assertFalse(unresolved.resolved());
      assertEquals(2, debugger.breakpoints().size());

      debugger.removeBreakpoint(fromBlankLine);
      var paused =
          assertInstanceOf(ExecutionResult.Paused.class, instance.initialize());
      assertEquals(PauseInfo.Reason.BREAKPOINT, paused.pauseInfo().reason());
      assertEquals(
          new SourceLocation("debug/main", 3),
          paused.pauseInfo().location());
      assertFalse(paused.pauseInfo().stackFrames().isEmpty());
      assertEquals(
          paused.pauseInfo().location(),
          paused.pauseInfo().stackFrames().get(0).location());
      assertTrue(debugger.isPaused());
      assertEquals(paused.pauseInfo(), debugger.pauseInfo().orElseThrow());
      assertThrows(IllegalStateException.class, instance::initialize);
      assertThrows(
          IllegalStateException.class,
          () -> debugger.setBreakpoint("debug/main", 4));

      var completed =
          assertInstanceOf(ExecutionResult.Completed.class, debugger.resume());
      assertTrue(completed.values().isEmpty());
      assertTrue(
          completed.metrics().runtime().compareTo(paused.metrics().runtime()) >= 0);
      assertFalse(debugger.isPaused());
      assertTrue(debugger.pauseInfo().isEmpty());

      assertEquals(
          List.of(new LuauNumber(3)),
          assertInstanceOf(
                  ExecutionResult.Completed.class,
                  instance.invokeExport("value", List.of()))
              .values());
      debugger.removeBreakpoint(exactLine);
      assertTrue(debugger.breakpoints().isEmpty());
    }
  }

  @Test
  void preservesSourceModuleValuesAcrossPauseAndResume() {
    var compilation =
        runtime.compile(
            "app/main",
            Map.of(
                "app/main",
                """
                local worker = require("lib/worker")
                return {
                    run = function(value: any): any
                        return worker.run(value)
                    end,
                }
                """,
                "lib/worker",
                """
                return {
                    run = function(value: any): any
                        local result = value
                        return result
                    end,
                }
                """));
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance()) {
      assertInstanceOf(ExecutionResult.Completed.class, instance.initialize());
      Breakpoint breakpoint = instance.debugger().setBreakpoint("lib/worker", 3);
      assertEquals(4, breakpoint.resolvedLine());

      LuauArray emptyArray = new LuauArray(List.of());
      var paused =
          assertInstanceOf(
              ExecutionResult.Paused.class,
              instance.invokeExport("run", List.of(emptyArray)));
      assertEquals(
          new SourceLocation("lib/worker", 4),
          paused.pauseInfo().location());
      assertTrue(
          paused.pauseInfo().stackFrames().stream()
              .anyMatch(
                  frame ->
                      frame.location().moduleName().equals("app/main")));

      var completed =
          assertInstanceOf(
              ExecutionResult.Completed.class,
              instance.debugger().resume());
      assertEquals(List.of(emptyArray), completed.values());
    }
  }

  @Test
  void retainsHostContextAndCumulativeMetricsWhilePaused() {
    AtomicInteger calls = new AtomicInteger();
    HostModule host =
        new HostModule(
            HOST_NAME,
            "1",
            HOST_DECLARATION,
            () ->
                Map.of(
                    "beforePause",
                    (HostFunction)
                        (context, arguments) ->
                            List.of(new LuauNumber(calls.incrementAndGet())),
                    "fromContext",
                    (HostFunction)
                        (context, arguments) ->
                            List.of(
                                new LuauNumber(
                                    context.require(DebugCapability.class).value()))));
    var compilation =
        runtime.compile(
            "debug/host-main",
            Map.of(
                "debug/host-main",
                """
                local host = require("debug/host@1")
                return {
                    run = function(): number
                        local before = host.beforePause()
                        local marker = before
                        return marker + host.fromContext()
                    end,
                }
                """),
            List.of(host));
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance()) {
      assertInstanceOf(ExecutionResult.Completed.class, instance.initialize());
      Breakpoint breakpoint =
          instance.debugger().setBreakpoint("debug/host-main", 5);
      assertEquals(6, breakpoint.resolvedLine());
      HostCallContext context =
          HostCallContext.builder()
              .put(DebugCapability.class, new DebugCapability(41))
              .build();

      var paused =
          assertInstanceOf(
              ExecutionResult.Paused.class,
              instance.invokeExport("run", List.of(), context));
      assertEquals(1, paused.metrics().hostCalls());
      assertEquals(1, calls.get());

      var completed =
          assertInstanceOf(
              ExecutionResult.Completed.class,
              instance.debugger().resume());
      assertEquals(List.of(new LuauNumber(42)), completed.values());
      assertEquals(2, completed.metrics().hostCalls());
      assertTrue(
          completed.metrics().runtime().compareTo(paused.metrics().runtime()) >= 0);
    }
  }

  @Test
  void terminatesPausedInvocationAndReappliesBreakpointAfterReset() {
    var compilation =
        runtime.compile(
            "debug/terminate",
            """
            return {
                run = function(): number
                    local value = 42
                    return value
                end,
            }
            """);
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance()) {
      assertInstanceOf(ExecutionResult.Completed.class, instance.initialize());
      Breakpoint breakpoint =
          instance.debugger().setBreakpoint("debug/terminate", 3);
      assertEquals(3, breakpoint.resolvedLine());
      assertInstanceOf(
          ExecutionResult.Paused.class,
          instance.invokeExport("run", List.of()));
      assertThrows(IllegalStateException.class, instance::reset);

      var terminated =
          assertInstanceOf(
              ExecutionResult.Terminated.class,
              instance.debugger().terminate());
      assertTrue(terminated.message().contains("terminated"));
      assertTrue(instance.isFaulted());
      assertFalse(instance.debugger().isPaused());
      assertTrue(instance.debugger().pauseInfo().isEmpty());
      assertEquals(Set.of(breakpoint), instance.debugger().breakpoints());
      assertThrows(
          IllegalStateException.class,
          () -> instance.invokeExport("run", List.of()));

      instance.reset();
      assertFalse(instance.isFaulted());
      assertEquals(Set.of(breakpoint), instance.debugger().breakpoints());
      assertInstanceOf(ExecutionResult.Completed.class, instance.initialize());
      assertInstanceOf(
          ExecutionResult.Paused.class,
          instance.invokeExport("run", List.of()));
    }
  }

  private record DebugCapability(int value) {}
}
