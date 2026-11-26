package io.github.suterxom.luau.v1.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.suterxom.luau.v1.api.debug.Breakpoint;
import io.github.suterxom.luau.v1.api.debug.PauseInfo;
import io.github.suterxom.luau.v1.api.debug.SourceLocation;
import io.github.suterxom.luau.v1.api.host.HostCallContext;
import io.github.suterxom.luau.v1.api.host.HostCallException;
import io.github.suterxom.luau.v1.api.host.HostFunction;
import io.github.suterxom.luau.v1.api.host.HostModule;
import io.github.suterxom.luau.v1.api.runtime.ExecutionBudget;
import io.github.suterxom.luau.v1.api.runtime.ExecutionResult;
import io.github.suterxom.luau.v1.api.value.LuauArray;
import io.github.suterxom.luau.v1.api.value.LuauNumber;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
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
  private static final String CONTROL_NAME = "debug/control@1";
  private static final String CONTROL_DECLARATION =
      """
      return (nil :: any) :: {
          synchronize: () -> number,
      }
      """;
  private static final String CONTROL_PROGRAM =
      """
      local control = require("debug/control@1")
      return {
          run = function(): number
              control.synchronize()
              local total = 0
              for value = 1, 100 do
                  total += value
              end
              return total
          end,
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
          new Thread(() -> {
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
      Breakpoint breakpoint = instance.debugger().setBreakpoint("debug/execute", 2);
      assertEquals(2, breakpoint.resolvedLine());

      for (int invocation = 0; invocation < 2; ++invocation) {
        assertInstanceOf(ExecutionResult.Paused.class, instance.execute());
        assertEquals(
            List.of(new LuauNumber(42)),
            assertInstanceOf(ExecutionResult.Completed.class, instance.debugger().resume())
                .values());
      }

      instance.debugger().clearBreakpoints();
      assertEquals(
          List.of(new LuauNumber(42)),
          assertInstanceOf(ExecutionResult.Completed.class, instance.execute()).values());
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
      var paused = assertInstanceOf(ExecutionResult.Paused.class, instance.initialize());
      assertEquals(PauseInfo.Reason.BREAKPOINT, paused.pauseInfo().reason());
      assertEquals(new SourceLocation("debug/main", 3), paused.pauseInfo().location());
      assertFalse(paused.pauseInfo().stackFrames().isEmpty());
      assertEquals(
          paused.pauseInfo().location(), paused.pauseInfo().stackFrames().get(0).location());
      assertTrue(debugger.isPaused());
      assertEquals(paused.pauseInfo(), debugger.pauseInfo().orElseThrow());
      assertThrows(IllegalStateException.class, instance::initialize);
      assertThrows(IllegalStateException.class, () -> debugger.setBreakpoint("debug/main", 4));

      var completed = assertInstanceOf(ExecutionResult.Completed.class, debugger.resume());
      assertTrue(completed.values().isEmpty());
      assertTrue(completed.metrics().runtime().compareTo(paused.metrics().runtime()) >= 0);
      assertFalse(debugger.isPaused());
      assertTrue(debugger.pauseInfo().isEmpty());

      assertEquals(
          List.of(new LuauNumber(3)),
          assertInstanceOf(
                  ExecutionResult.Completed.class, instance.invokeExport("value", List.of()))
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
              ExecutionResult.Paused.class, instance.invokeExport("run", List.of(emptyArray)));
      assertEquals(new SourceLocation("lib/worker", 4), paused.pauseInfo().location());
      assertTrue(
          paused.pauseInfo().stackFrames().stream()
              .anyMatch(frame -> frame.location().moduleName().equals("app/main")));

      var completed =
          assertInstanceOf(ExecutionResult.Completed.class, instance.debugger().resume());
      assertEquals(List.of(emptyArray), completed.values());
    }
  }

  @Test
  void resumesNestedScriptCoroutineAcrossMultipleBreakpoints() {
    var compilation =
        runtime.compile(
            "debug/coroutine",
            """
            return {
                run = function()
                    local thread = coroutine.create(function()
                        local value = 40
                        value += 1
                        value += 1
                        return value
                    end)
                    local ok, result = coroutine.resume(thread)
                    if not ok then
                        error(result)
                    end
                    return result
                end,
            }
            """);
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance()) {
      assertInstanceOf(ExecutionResult.Completed.class, instance.initialize());
      assertEquals(5, instance.debugger().setBreakpoint("debug/coroutine", 5).resolvedLine());
      assertEquals(6, instance.debugger().setBreakpoint("debug/coroutine", 6).resolvedLine());

      var firstPause =
          assertInstanceOf(ExecutionResult.Paused.class, instance.invokeExport("run", List.of()));
      assertEquals(new SourceLocation("debug/coroutine", 5), firstPause.pauseInfo().location());
      assertTrue(firstPause.pauseInfo().stackFrames().size() >= 2);

      var secondPause =
          assertInstanceOf(ExecutionResult.Paused.class, instance.debugger().resume());
      assertEquals(new SourceLocation("debug/coroutine", 6), secondPause.pauseInfo().location());
      assertTrue(secondPause.pauseInfo().stackFrames().size() >= 2);

      var completed =
          assertInstanceOf(ExecutionResult.Completed.class, instance.debugger().resume());
      assertEquals(List.of(new LuauNumber(42)), completed.values());
    }
  }

  @Test
  void pausesAndResumesNestedSourceModuleInitialization() {
    var compilation =
        runtime.compile(
            "app/main",
            Map.of(
                "app/main",
                """
                local middle = require("lib/middle")
                return {
                    read = function(): number
                        return middle.value
                    end,
                }
                """,
                "lib/middle",
                """
                local leaf = require("lib/leaf")
                return {value = leaf.value}
                """,
                "lib/leaf",
                """
                local value = 40
                value += 2
                return {value = value}
                """));
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance()) {
      Breakpoint breakpoint = instance.debugger().setBreakpoint("lib/leaf", 2);
      assertEquals(2, breakpoint.resolvedLine());

      var paused = assertInstanceOf(ExecutionResult.Paused.class, instance.initialize());
      assertEquals(new SourceLocation("lib/leaf", 2), paused.pauseInfo().location());
      Set<String> stackModules =
          paused.pauseInfo().stackFrames().stream()
              .map(frame -> frame.location().moduleName())
              .collect(Collectors.toSet());
      assertTrue(stackModules.containsAll(Set.of("lib/leaf", "lib/middle", "app/main")));

      assertInstanceOf(ExecutionResult.Completed.class, instance.debugger().resume());
      assertEquals(
          List.of(new LuauNumber(42)),
          assertInstanceOf(
                  ExecutionResult.Completed.class, instance.invokeExport("read", List.of()))
              .values());
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
                        (context, arguments) -> List.of(new LuauNumber(calls.incrementAndGet())),
                    "fromContext",
                    (HostFunction)
                        (context, arguments) ->
                            List.of(
                                new LuauNumber(context.require(DebugCapability.class).value()))));
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
      Breakpoint breakpoint = instance.debugger().setBreakpoint("debug/host-main", 5);
      assertEquals(6, breakpoint.resolvedLine());
      HostCallContext context =
          HostCallContext.builder().put(DebugCapability.class, new DebugCapability(41)).build();

      var paused =
          assertInstanceOf(
              ExecutionResult.Paused.class, instance.invokeExport("run", List.of(), context));
      assertEquals(1, paused.metrics().hostCalls());
      assertEquals(1, calls.get());

      var completed =
          assertInstanceOf(ExecutionResult.Completed.class, instance.debugger().resume());
      assertEquals(List.of(new LuauNumber(42)), completed.values());
      assertEquals(2, completed.metrics().hostCalls());
      assertTrue(completed.metrics().runtime().compareTo(paused.metrics().runtime()) >= 0);
    }
  }

  @Test
  void acceptsCrossThreadPauseRequestAtSafepoint() throws InterruptedException {
    var compilation =
        runtime.compile(
            "debug/request-pause",
            Map.of("debug/request-pause", CONTROL_PROGRAM),
            List.of(controlHostModule()));
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance()) {
      assertInstanceOf(ExecutionResult.Completed.class, instance.initialize());
      assertEquals(9, instance.debugger().setBreakpoint("debug/request-pause", 9).resolvedLine());
      RequestControl control = new RequestControl();
      HostCallContext context =
          HostCallContext.builder().put(RequestControl.class, control).build();
      AtomicReference<Throwable> requestFailure = new AtomicReference<>();
      Thread requester =
          requestFromOtherThread(control, instance.debugger()::requestPause, requestFailure);

      var paused =
          assertInstanceOf(
              ExecutionResult.Paused.class,
              instance.invokeExport(
                  "run",
                  List.of(),
                  context,
                  new ExecutionBudget(Duration.ofSeconds(2), 1_000_000)));
      requester.join();
      assertNull(requestFailure.get());
      assertEquals(PauseInfo.Reason.REQUESTED, paused.pauseInfo().reason());
      assertFalse(paused.pauseInfo().stackFrames().isEmpty());

      var breakpointPause =
          assertInstanceOf(ExecutionResult.Paused.class, instance.debugger().resume());
      assertEquals(PauseInfo.Reason.BREAKPOINT, breakpointPause.pauseInfo().reason());
      assertEquals(
          new SourceLocation("debug/request-pause", 9), breakpointPause.pauseInfo().location());

      var completed =
          assertInstanceOf(ExecutionResult.Completed.class, instance.debugger().resume());
      assertEquals(List.of(new LuauNumber(5050)), completed.values());
      assertEquals(1, completed.metrics().hostCalls());
    }
  }

  @Test
  void acceptsCrossThreadTerminationRequestAtSafepoint() throws InterruptedException {
    var compilation =
        runtime.compile(
            "debug/request-terminate",
            Map.of("debug/request-terminate", CONTROL_PROGRAM),
            List.of(controlHostModule()));
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance()) {
      assertInstanceOf(ExecutionResult.Completed.class, instance.initialize());
      RequestControl control = new RequestControl();
      HostCallContext context =
          HostCallContext.builder().put(RequestControl.class, control).build();
      AtomicReference<Throwable> requestFailure = new AtomicReference<>();
      Thread requester =
          requestFromOtherThread(control, instance.debugger()::requestTerminate, requestFailure);

      var terminated =
          assertInstanceOf(
              ExecutionResult.Terminated.class,
              instance.invokeExport(
                  "run",
                  List.of(),
                  context,
                  new ExecutionBudget(Duration.ofSeconds(2), 1_000_000)));
      requester.join();
      assertNull(requestFailure.get());
      assertTrue(terminated.message().contains("debugger request"));
      assertEquals(1, terminated.metrics().hostCalls());
      assertTrue(instance.isFaulted());
      assertFalse(instance.debugger().isPaused());
    }
  }

  @Test
  void stepsIntoOverAndOutOfLuauFunctions() {
    var compilation =
        runtime.compile(
            "debug/step",
            """
            return {
                run = function(): number
                    local value = 1
                    local function add()
                        value += 1
                        value += 1
                    end
                    add()
                    value += 10
                    return value
                end,
            }
            """);
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance intoInstance = chunk.createInstance();
        ScriptInstance overInstance = chunk.createInstance()) {
      assertInstanceOf(ExecutionResult.Completed.class, intoInstance.initialize());
      assertEquals(8, intoInstance.debugger().setBreakpoint("debug/step", 8).resolvedLine());
      var callPause =
          assertInstanceOf(
              ExecutionResult.Paused.class, intoInstance.invokeExport("run", List.of()));
      assertEquals(new SourceLocation("debug/step", 8), callPause.pauseInfo().location());

      var insideFunction =
          assertInstanceOf(ExecutionResult.Paused.class, intoInstance.debugger().stepInto());
      assertEquals(PauseInfo.Reason.STEP, insideFunction.pauseInfo().reason());
      assertEquals(new SourceLocation("debug/step", 5), insideFunction.pauseInfo().location());
      assertTrue(
          insideFunction.pauseInfo().stackFrames().size()
              > callPause.pauseInfo().stackFrames().size());

      var nextLine =
          assertInstanceOf(ExecutionResult.Paused.class, intoInstance.debugger().stepOver());
      assertEquals(PauseInfo.Reason.STEP, nextLine.pauseInfo().reason());
      assertEquals(new SourceLocation("debug/step", 6), nextLine.pauseInfo().location());

      var caller =
          assertInstanceOf(ExecutionResult.Paused.class, intoInstance.debugger().stepOut());
      assertEquals(PauseInfo.Reason.STEP, caller.pauseInfo().reason());
      assertEquals(new SourceLocation("debug/step", 9), caller.pauseInfo().location());
      assertEquals(
          List.of(new LuauNumber(13)),
          assertInstanceOf(ExecutionResult.Completed.class, intoInstance.debugger().resume())
              .values());

      assertInstanceOf(ExecutionResult.Completed.class, overInstance.initialize());
      overInstance.debugger().setBreakpoint("debug/step", 8);
      assertInstanceOf(ExecutionResult.Paused.class, overInstance.invokeExport("run", List.of()));
      var skippedFunction =
          assertInstanceOf(ExecutionResult.Paused.class, overInstance.debugger().stepOver());
      assertEquals(new SourceLocation("debug/step", 9), skippedFunction.pauseInfo().location());
      assertEquals(
          List.of(new LuauNumber(13)),
          assertInstanceOf(ExecutionResult.Completed.class, overInstance.debugger().resume())
              .values());
    }
  }

  @Test
  void stepsAcrossScriptCoroutineBoundary() {
    var compilation =
        runtime.compile(
            "debug/step-coroutine",
            """
            return {
                run = function()
                    local thread = coroutine.create(function()
                        local value = 41
                        value += 1
                        return value
                    end)
                    local ok, result = coroutine.resume(thread)
                    if not ok then
                        error(result)
                    end
                    return result
                end,
            }
            """);
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance intoInstance = chunk.createInstance();
        ScriptInstance overInstance = chunk.createInstance()) {
      assertInstanceOf(ExecutionResult.Completed.class, intoInstance.initialize());
      assertEquals(
          8, intoInstance.debugger().setBreakpoint("debug/step-coroutine", 8).resolvedLine());
      var resumeCall =
          assertInstanceOf(
              ExecutionResult.Paused.class, intoInstance.invokeExport("run", List.of()));

      var child =
          assertInstanceOf(ExecutionResult.Paused.class, intoInstance.debugger().stepInto());
      assertEquals(new SourceLocation("debug/step-coroutine", 4), child.pauseInfo().location());
      assertTrue(
          child.pauseInfo().stackFrames().size() > resumeCall.pauseInfo().stackFrames().size());

      var parent =
          assertInstanceOf(ExecutionResult.Paused.class, intoInstance.debugger().stepOut());
      assertEquals(new SourceLocation("debug/step-coroutine", 9), parent.pauseInfo().location());
      assertEquals(
          List.of(new LuauNumber(42)),
          assertInstanceOf(ExecutionResult.Completed.class, intoInstance.debugger().resume())
              .values());

      assertInstanceOf(ExecutionResult.Completed.class, overInstance.initialize());
      overInstance.debugger().setBreakpoint("debug/step-coroutine", 8);
      assertInstanceOf(ExecutionResult.Paused.class, overInstance.invokeExport("run", List.of()));
      var skippedChild =
          assertInstanceOf(ExecutionResult.Paused.class, overInstance.debugger().stepOver());
      assertEquals(
          new SourceLocation("debug/step-coroutine", 9), skippedChild.pauseInfo().location());
    }
  }

  @Test
  void stepsIntoAndOutOfSourceModuleInitialization() {
    var compilation =
        runtime.compile(
            "debug/step-module",
            Map.of(
                "debug/step-module",
                """
                local value = require("lib/step-value")
                return {read = function(): number return value end}
                """,
                "lib/step-value",
                """
                local value = 41
                value += 1
                return value
                """));
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance()) {
      assertEquals(1, instance.debugger().setBreakpoint("debug/step-module", 1).resolvedLine());
      assertInstanceOf(ExecutionResult.Paused.class, instance.initialize());

      var module = assertInstanceOf(ExecutionResult.Paused.class, instance.debugger().stepInto());
      assertEquals(new SourceLocation("lib/step-value", 1), module.pauseInfo().location());

      var root = assertInstanceOf(ExecutionResult.Paused.class, instance.debugger().stepOut());
      assertEquals(new SourceLocation("debug/step-module", 2), root.pauseInfo().location());
      assertInstanceOf(ExecutionResult.Completed.class, instance.debugger().resume());
      assertEquals(
          List.of(new LuauNumber(42)),
          assertInstanceOf(
                  ExecutionResult.Completed.class, instance.invokeExport("read", List.of()))
              .values());
    }
  }

  @Test
  void terminatesPausedInvocationAndReappliesBreakpointAfterReset() throws InterruptedException {
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
      Breakpoint breakpoint = instance.debugger().setBreakpoint("debug/terminate", 3);
      assertEquals(3, breakpoint.resolvedLine());
      assertInstanceOf(ExecutionResult.Paused.class, instance.invokeExport("run", List.of()));
      assertThrows(IllegalStateException.class, instance::reset);

      var terminated =
          assertInstanceOf(ExecutionResult.Terminated.class, instance.debugger().terminate());
      assertTrue(terminated.message().contains("terminated"));
      assertTrue(instance.isFaulted());
      assertFalse(instance.debugger().isPaused());
      assertTrue(instance.debugger().pauseInfo().isEmpty());
      assertEquals(Set.of(breakpoint), instance.debugger().breakpoints());
      assertThrows(IllegalStateException.class, () -> instance.invokeExport("run", List.of()));

      instance.reset();
      assertFalse(instance.isFaulted());
      assertEquals(Set.of(breakpoint), instance.debugger().breakpoints());
      assertInstanceOf(ExecutionResult.Completed.class, instance.initialize());
      assertInstanceOf(ExecutionResult.Paused.class, instance.invokeExport("run", List.of()));

      AtomicReference<Throwable> requestFailure = new AtomicReference<>();
      Thread requester =
          new Thread(() -> {
            try {
              instance.debugger().requestTerminate();
            } catch (Throwable throwable) {
              requestFailure.set(throwable);
            }
          });
      requester.start();
      requester.join();
      assertNull(requestFailure.get());
      var requestedTermination =
          assertInstanceOf(ExecutionResult.Terminated.class, instance.debugger().resume());
      assertTrue(requestedTermination.message().contains("pending debugger request"));
    }
  }

  private record DebugCapability(int value) {}

  private static HostModule controlHostModule() {
    return new HostModule(
        CONTROL_NAME,
        "1",
        CONTROL_DECLARATION,
        () ->
            Map.of(
                "synchronize",
                (HostFunction)
                    (context, arguments) -> {
                      context.require(RequestControl.class).synchronize();
                      return List.of(new LuauNumber(0));
                    }));
  }

  private static Thread requestFromOtherThread(
      RequestControl control, Runnable request, AtomicReference<Throwable> failure) {
    Thread thread =
        new Thread(() -> {
          try {
            control.awaitExecution();
            request.run();
          } catch (Throwable throwable) {
            failure.set(throwable);
          } finally {
            control.releaseExecution();
          }
        });
    thread.start();
    return thread;
  }

  private static final class RequestControl {
    private final CountDownLatch executionEntered = new CountDownLatch(1);
    private final CountDownLatch requestSent = new CountDownLatch(1);

    void synchronize() throws HostCallException {
      executionEntered.countDown();
      try {
        if (!requestSent.await(5, TimeUnit.SECONDS)) {
          throw new HostCallException("Timed out waiting for debugger request");
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new HostCallException("Interrupted while waiting for debugger request", exception);
      }
    }

    void awaitExecution() throws InterruptedException {
      if (!executionEntered.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for Luau execution");
      }
    }

    void releaseExecution() {
      requestSent.countDown();
    }
  }
}
