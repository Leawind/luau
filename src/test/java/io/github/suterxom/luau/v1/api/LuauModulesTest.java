package io.github.suterxom.luau.v1.api;

import io.github.suterxom.luau.v1.api.compile.CompilationDiagnostic;
import io.github.suterxom.luau.v1.api.runtime.ExecutionBudget;
import io.github.suterxom.luau.v1.api.runtime.ExecutionResult;
import io.github.suterxom.luau.v1.api.value.LuauBoolean;
import io.github.suterxom.luau.v1.api.value.LuauNumber;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuauModulesTest {
  private final LuauRuntime runtime = LuauRuntime.getInstance();
  
  @Test
  void executesTransitiveModulesWithPerInstanceCaching() {
    var compilation = runtime.compile("app/main", Map.of("app/main", """
      local counter = require("lib/counter")
      local service = require("app/service")
      return {
          sameModule = function(): boolean
              return counter == service.counter
          end,
          next = function(): number
              return service.counter.next()
          end,
      }
      """, "app/service", """
                                                           local counter = require("lib/counter")
                                                           return {counter = counter}
                                                           """, "lib/counter", """
                                                           local value = 0
                                                           return {
                                                               next = function(): number
                                                                   value += 1
                                                                   return value
                                                               end,
                                                           }
                                                           """));
    assertTrue(compilation.successful(), ()->compilation.diagnostics().toString());
    
    try(CompiledChunk chunk = compilation.chunk().orElseThrow();
        ScriptInstance first = chunk.createInstance();
        ScriptInstance second = chunk.createInstance()){
      assertEquals(Set.of("app/main", "app/service", "lib/counter"), chunk.moduleNames());
      assertInstanceOf(ExecutionResult.Completed.class, first.initialize());
      assertInstanceOf(ExecutionResult.Completed.class, second.initialize());
      
      assertEquals(List.of(new LuauBoolean(true)), completed(first.invokeExport("sameModule", List.of())).values());
      assertEquals(List.of(new LuauNumber(1)), completed(first.invokeExport("next", List.of())).values());
      assertEquals(List.of(new LuauNumber(2)), completed(first.invokeExport("next", List.of())).values());
      assertEquals(List.of(new LuauNumber(1)), completed(second.invokeExport("next", List.of())).values());
    }
  }
  
  @Test
  void statelessExecutionCanRequireRegisteredModules() {
    var compilation = runtime.compile("main",
                                      Map.of("main", "return require(\"answer\").value", "answer", "return {value = 42}"));
    assertTrue(compilation.successful(), ()->compilation.diagnostics().toString());
    
    try(CompiledChunk chunk = compilation.chunk().orElseThrow()){
      assertEquals(List.of(new LuauNumber(42)), completed(chunk.execute()).values());
    }
  }
  
  @Test
  void attributesDependencyTypeErrorsToTheirModule() {
    var compilation = runtime.compile("main", Map.of("main", "return require(\"broken\")", "broken", """
      local value: number = "wrong"
      return value
      """));
    
    assertFalse(compilation.successful());
    assertTrue(compilation.diagnostics()
                          .stream()
                          .anyMatch(diagnostic->diagnostic.stage() == CompilationDiagnostic.Stage.TYPE
                                                && diagnostic.moduleName().equals("broken") && diagnostic.message()
                                                                                                         .contains("number")));
  }
  
  @Test
  void rejectsUnknownAndStaticallyCyclicDependencies() {
    var missing = runtime.compile("main", Map.of("main", "return require(\"not-registered\")"));
    assertFalse(missing.successful());
    assertTrue(missing.diagnostics().stream().anyMatch(diagnostic->diagnostic.message().contains("not-registered")));
    
    var cyclic = runtime.compile("A", Map.of("A", "return require(\"B\")", "B", "return require(\"A\")"));
    assertFalse(cyclic.successful());
    assertTrue(cyclic.diagnostics().stream().anyMatch(diagnostic->diagnostic.message().toLowerCase().contains("cyclic")));
  }
  
  @Test
  void runtimeRejectsDynamicUnknownAndCyclicDependencies() {
    var missing = runtime.compile("main", Map.of("main", """
      local name = string.lower("NOT-REGISTERED")
      return require(name) :: any
      """));
    assertTrue(missing.successful(), ()->missing.diagnostics().toString());
    try(CompiledChunk chunk = missing.chunk().orElseThrow()){
      var failure = assertInstanceOf(ExecutionResult.RuntimeFailed.class, chunk.execute());
      assertTrue(failure.message().contains("not-registered"), failure::message);
    }
    
    var cyclic = runtime.compile("A", Map.of("A", """
      local name = string.upper("b")
      return require(name) :: any
      """, "B", """
                                               local name = string.upper("a")
                                               return require(name) :: any
                                               """));
    assertTrue(cyclic.successful(), ()->cyclic.diagnostics().toString());
    try(CompiledChunk chunk = cyclic.chunk().orElseThrow()){
      var failure = assertInstanceOf(ExecutionResult.RuntimeFailed.class, chunk.execute());
      assertTrue(failure.message().contains("Circular module dependency"), failure::message);
    }
  }
  
  @Test
  void requireValidatesArgumentsAndModuleReturnCountAtRuntime() {
    var invalidArgument = runtime.compile("main", Map.of("main", "return require(42 :: any) :: any"));
    assertTrue(invalidArgument.successful(), ()->invalidArgument.diagnostics().toString());
    try(CompiledChunk chunk = invalidArgument.chunk().orElseThrow()){
      var failure = assertInstanceOf(ExecutionResult.RuntimeFailed.class, chunk.execute());
      assertTrue(failure.message().contains("one string module name"), failure::message);
    }
    
    var multipleReturns = runtime.compile("main", Map.of("main", "return require(\"multiple\")", "multiple", "return 1, 2"));
    assertTrue(multipleReturns.successful(), ()->multipleReturns.diagnostics().toString());
    try(CompiledChunk chunk = multipleReturns.chunk().orElseThrow()){
      var failure = assertInstanceOf(ExecutionResult.RuntimeFailed.class, chunk.execute());
      assertTrue(failure.message().contains("exactly one value"), failure::message);
    }
  }
  
  @Test
  void requiredModulesShareTheCallingExecutionBudget() {
    var compilation = runtime.compile("main", Map.of("main", "return require(\"loop\")", "loop", """
      local function wait(value: boolean): boolean
          while not value do end
          return true
      end
      return wait(false)
      """));
    assertTrue(compilation.successful(), ()->compilation.diagnostics().toString());
    
    try(CompiledChunk chunk = compilation.chunk().orElseThrow()){
      var exceeded = assertInstanceOf(ExecutionResult.LimitExceeded.class,
                                      chunk.execute(new ExecutionBudget(Duration.ofSeconds(5), 1_000)));
      assertEquals(ExecutionResult.LimitExceeded.Kind.INTERRUPT_CHECKS, exceeded.kind());
    }
  }
  
  @Test
  void validatesClosedModuleSetsAndLogicalNames() {
    assertThrows(IllegalArgumentException.class, ()->runtime.compile("main", Map.of("other", "return 1")));
    assertThrows(IllegalArgumentException.class, ()->runtime.compile("../main", Map.of("../main", "return 1")));
    assertThrows(IllegalArgumentException.class, ()->runtime.compile("app//main", Map.of("app//main", "return 1")));
    assertThrows(IllegalArgumentException.class, ()->runtime.compile("app\\main", Map.of("app\\main", "return 1")));
    
    Map<String, String> tooMany = new LinkedHashMap<>();
    for(int index = 0; index < 65; ++index){
      tooMany.put("module/" + index, "return " + index);
    }
    assertThrows(IllegalArgumentException.class, ()->runtime.compile("module/0", tooMany));
  }
  
  private static ExecutionResult.Completed completed(ExecutionResult result) {
    return assertInstanceOf(ExecutionResult.Completed.class, result);
  }
}
