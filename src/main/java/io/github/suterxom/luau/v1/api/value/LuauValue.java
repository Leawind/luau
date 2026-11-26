package io.github.suterxom.luau.v1.api.value;

/** A value that is safe to transfer across the Java/Luau boundary. */
public sealed interface LuauValue
    permits LuauArray, LuauBoolean, LuauBuffer, LuauNil, LuauNumber, LuauString, LuauTable {}
