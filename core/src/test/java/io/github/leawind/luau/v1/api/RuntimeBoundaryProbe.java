package io.github.leawind.luau.v1.api;

import io.github.leawind.luau.v1.api.runtime.ExecutionBudget;
import java.time.Duration;
import java.util.Map;

/** Child-process entry point for the execution-limit regression test. */
public final class RuntimeBoundaryProbe {
  private RuntimeBoundaryProbe() {}

  public static void main(String[] arguments) {
    String protectedCall = arguments[0];
    String source =
        """
        local function fail()
            while true do end
        end
        local ok = %s
        assert(ok == false)
        while true do end
        """
            .formatted(
                protectedCall.equals("xpcall")
                    ? "xpcall(fail, function(message) return message end)"
                    : "pcall(fail)");
    var compilation = LuauRuntime.compile("main", Map.of("main", source));
    if (!compilation.successful()) {
      throw new IllegalStateException(compilation.diagnostics().toString());
    }
    try (CompiledChunk chunk = compilation.chunk().orElseThrow()) {
      var result = chunk.execute(new ExecutionBudget(Duration.ofMillis(50), 10_000));
      System.out.println("RESULT=" + result.getClass().getSimpleName());
    }
  }
}
