package io.github.leawind.luau.v1.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.leawind.luau.v1.api.host.HostCallContext;
import io.github.leawind.luau.v1.api.host.HostCallException;
import io.github.leawind.luau.v1.api.host.HostFunction;
import io.github.leawind.luau.v1.api.host.HostModule;
import io.github.leawind.luau.v1.api.runtime.ExecutionBudget;
import io.github.leawind.luau.v1.api.runtime.ExecutionResult;
import io.github.leawind.luau.v1.api.value.LuauArray;
import io.github.leawind.luau.v1.api.value.LuauBoolean;
import io.github.leawind.luau.v1.api.value.LuauBuffer;
import io.github.leawind.luau.v1.api.value.LuauNil;
import io.github.leawind.luau.v1.api.value.LuauNumber;
import io.github.leawind.luau.v1.api.value.LuauString;
import io.github.leawind.luau.v1.api.value.LuauTable;
import io.github.leawind.luau.v1.api.value.LuauValue;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HostModulesTest {
  private static final String HOST_NAME = "test/host@1";
  private static final String HOST_DECLARATION =
      """
      return (nil :: any) :: {
          echo: (any) -> any,
          next: () -> number,
          contextName: () -> string,
          expectedFailure: () -> any,
          unexpectedFailure: () -> any,
          emit: (number) -> number,
      }
      """;

  private final LuauRuntime runtime = LuauRuntime.getInstance();

  @Test
  void statelessExecutionCreatesFreshHostExports() {
    AtomicInteger factories = new AtomicInteger();
    var compilation =
        runtime.compile(
            "main",
            Map.of(
                "main",
                """
                local host = require("test/host@1")
                return host.next()
                """),
            List.of(statefulHostModule(factories)));
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow()) {
      ExecutionResult.Completed first = completed(chunk.execute());
      ExecutionResult.Completed second = completed(chunk.execute());
      assertEquals(List.of(new LuauNumber(1)), first.values());
      assertEquals(List.of(new LuauNumber(1)), second.values());
      assertEquals(1, first.metrics().hostCalls());
      assertEquals(2, factories.get());
    }
  }

  @Test
  void invokesTypedHostModulesWithPerInstanceStateAndContext() {
    AtomicInteger factories = new AtomicInteger();
    HostModule host = statefulHostModule(factories);
    var compilation =
        runtime.compile(
            "main",
            Map.of(
                "main",
                """
                local host = require("test/host@1")
                local same = require("test/host@1")
                return {
                    sameModule = function(): boolean
                        return host == same
                    end,
                    next = function(): number
                        return host.next()
                    end,
                    contextName = function(): string
                        return host.contextName()
                    end,
                    echo = function(): any
                        return host.echo({
                            message = "你好",
                            values = ({true, 42} :: {any}),
                        })
                    end,
                    roundTrip = function(value: any): any
                        return host.echo(value)
                    end,
                }
                """),
            List.of(host));

    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());
    assertEquals(0, factories.get(), "compilation must not instantiate host modules");
    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance first = chunk.createInstance();
        ScriptInstance second = chunk.createInstance()) {
      assertEquals(2, factories.get());
      assertEquals(java.util.Set.of(HOST_NAME), chunk.hostModuleNames());
      assertEquals(Map.of(HOST_NAME, "1"), chunk.hostModuleVersions());
      assertInstanceOf(ExecutionResult.Completed.class, first.initialize());
      assertInstanceOf(ExecutionResult.Completed.class, second.initialize());

      assertEquals(
          List.of(new LuauBoolean(true)),
          completed(first.invokeExport("sameModule", List.of())).values());
      assertEquals(
          List.of(new LuauNumber(1)), completed(first.invokeExport("next", List.of())).values());
      assertEquals(
          List.of(new LuauNumber(2)), completed(first.invokeExport("next", List.of())).values());
      assertEquals(
          List.of(new LuauNumber(1)), completed(second.invokeExport("next", List.of())).values());

      HostCallContext context =
          HostCallContext.builder().put(Device.class, new Device("router-7")).build();
      ExecutionResult.Completed contextResult =
          completed(first.invokeExport("contextName", List.of(), context));
      assertEquals(List.of(new LuauString("router-7")), contextResult.values());
      assertEquals(1, contextResult.metrics().hostCalls());

      assertEquals(
          List.of(
              new LuauTable(
                  Map.of(
                      "message",
                      new LuauString("你好"),
                      "values",
                      new LuauArray(List.of(new LuauBoolean(true), new LuauNumber(42)))))),
          completed(first.invokeExport("echo", List.of())).values());

      List<LuauValue> safeValues =
          List.of(
              LuauNil.INSTANCE,
              new LuauBoolean(false),
              new LuauNumber(3.5),
              new LuauString("值😀"),
              new LuauBuffer(new byte[] {0, 1, -1}),
              new LuauArray(List.of(new LuauNumber(1), new LuauString("two"))),
              new LuauTable(Map.of("key", new LuauString("value"))));
      for (var value : safeValues) {
        assertEquals(
            List.of(value), completed(first.invokeExport("roundTrip", List.of(value))).values());
      }
    }
  }

  @Test
  void convertsExpectedAndUnexpectedJavaFailuresWithoutEscapingJni() {
    AtomicInteger factories = new AtomicInteger();
    var compilation =
        runtime.compile("main", failureProgram(), List.of(statefulHostModule(factories)));
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance()) {
      assertInstanceOf(ExecutionResult.Completed.class, instance.initialize());
      var expected =
          assertInstanceOf(
              ExecutionResult.RuntimeFailed.class, instance.invokeExport("expected", List.of()));
      assertTrue(expected.message().contains("safe failure"), expected::message);

      instance.reset();
      assertInstanceOf(ExecutionResult.Completed.class, instance.initialize());
      var unexpectedResult = instance.invokeExport("unexpected", List.of());
      // The message carries the actual result: this assertion once failed on a CI machine
      // without saying which kind of result came back instead.
      assertTrue(
          unexpectedResult instanceof ExecutionResult.RuntimeFailed,
          () -> "expected a runtime failure but got " + unexpectedResult);
      var unexpected = (ExecutionResult.RuntimeFailed) unexpectedResult;
      assertTrue(unexpected.message().contains("failed internally"), unexpected::message);
      assertFalse(unexpected.message().contains("private implementation detail"));
    }
    assertEquals(2, factories.get(), "reset must create fresh host module state");
  }

  @Test
  void enforcesHostCallAndRegisteredOutputBudgets() {
    var compilation =
        runtime.compile(
            "main",
            Map.of(
                "main",
                """
                local host = require("test/host@1")
                return {
                    twice = function(): number
                        return host.next() + host.next()
                    end,
                    emit = function(bytes: number): number
                        return host.emit(bytes)
                    end,
                }
                """),
            List.of(statefulHostModule(new AtomicInteger())));
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance callLimited = chunk.createInstance();
        ScriptInstance outputLimited = chunk.createInstance()) {
      assertInstanceOf(ExecutionResult.Completed.class, callLimited.initialize());
      var calls =
          assertInstanceOf(
              ExecutionResult.LimitExceeded.class,
              callLimited.invokeExport(
                  "twice",
                  List.of(),
                  HostCallContext.empty(),
                  new ExecutionBudget(Duration.ofSeconds(1), 1_000_000, 1, 1024)));
      assertEquals(ExecutionResult.LimitExceeded.Kind.HOST_CALLS, calls.kind());
      assertEquals(2, calls.metrics().hostCalls());

      assertInstanceOf(ExecutionResult.Completed.class, outputLimited.initialize());
      HostCallContext outputContext = HostCallContext.empty();
      var output =
          assertInstanceOf(
              ExecutionResult.LimitExceeded.class,
              outputLimited.invokeExport(
                  "emit",
                  List.of(new LuauNumber(8)),
                  outputContext,
                  new ExecutionBudget(Duration.ofSeconds(1), 1_000_000, 10, 4)));
      assertEquals(ExecutionResult.LimitExceeded.Kind.HOST_OUTPUT, output.kind());
      assertEquals(1, output.metrics().hostCalls());
      assertEquals(8, output.metrics().hostOutputBytes());
      assertEquals(8, outputContext.outputBytes());
    }
  }

  @Test
  void hostDeclarationsAreTypeCheckedAndGrantsAreExplicit() {
    HostModule host = statefulHostModule(new AtomicInteger());
    var wrongType =
        runtime.compile(
            "main",
            Map.of(
                "main",
                """
                local host = require("test/host@1")
                local value: string = host.next()
                return value
                """),
            List.of(host));
    assertFalse(wrongType.successful());
    assertTrue(
        wrongType.diagnostics().stream()
            .anyMatch(diagnostic -> diagnostic.message().contains("string")));

    var missingGrant = runtime.compile("main", Map.of("main", "return require(\"test/host@1\")"));
    assertFalse(missingGrant.successful());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            runtime.compile(
                "main", Map.of("main", "return 1", HOST_NAME, "return 2"), List.of(host)));
  }

  @Test
  void hostExportTablesAreReadOnly() {
    var compilation =
        runtime.compile(
            "main",
            Map.of(
                "main",
                """
                local host = require("test/host@1")
                return {
                    mutate = function()
                        (host :: any).next = nil
                    end,
                }
                """),
            List.of(statefulHostModule(new AtomicInteger())));
    assertTrue(compilation.successful(), () -> compilation.diagnostics().toString());

    try (CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance instance = chunk.createInstance()) {
      assertInstanceOf(ExecutionResult.Completed.class, instance.initialize());
      var failure =
          assertInstanceOf(
              ExecutionResult.RuntimeFailed.class, instance.invokeExport("mutate", List.of()));
      assertTrue(failure.message().toLowerCase().contains("readonly"), failure::message);
    }
  }

  private static Map<String, String> failureProgram() {
    return Map.of(
        "main",
        """
        local host = require("test/host@1")
        return {
            expected = function()
                return host.expectedFailure()
            end,
            unexpected = function()
                return host.unexpectedFailure()
            end,
        }
        """);
  }

  private static HostModule statefulHostModule(AtomicInteger factories) {
    return new HostModule(
        HOST_NAME,
        "1",
        HOST_DECLARATION,
        () -> {
          factories.incrementAndGet();
          AtomicInteger counter = new AtomicInteger();
          return Map.of(
              "echo",
              (HostFunction) (context, arguments) -> arguments,
              "next",
              (HostFunction)
                  (context, arguments) -> List.of(new LuauNumber(counter.incrementAndGet())),
              "contextName",
              (HostFunction)
                  (context, arguments) ->
                      List.of(new LuauString(context.require(Device.class).name())),
              "expectedFailure",
              (HostFunction)
                  (context, arguments) -> {
                    throw new HostCallException("safe failure");
                  },
              "unexpectedFailure",
              (HostFunction)
                  (context, arguments) -> {
                    throw new IllegalStateException("private implementation detail");
                  },
              "emit",
              (HostFunction)
                  (context, arguments) -> {
                    long bytes = (long) ((LuauNumber) arguments.get(0)).value();
                    context.recordOutputBytes(bytes);
                    return List.of(new LuauNumber(bytes));
                  });
        });
  }

  private static ExecutionResult.Completed completed(ExecutionResult result) {
    return assertInstanceOf(ExecutionResult.Completed.class, result);
  }

  private record Device(String name) {}
}
