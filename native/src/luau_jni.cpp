#include <jni.h>

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <exception>
#include <memory>
#include <string>
#include <string_view>
#include <vector>

#include "Luau/BuiltinDefinitions.h"
#include "Luau/Config.h"
#include "Luau/ConfigResolver.h"
#include "Luau/Error.h"
#include "Luau/FileResolver.h"
#include "Luau/Frontend.h"
#include "Luau/TypeArena.h"
#include "lua.h"
#include "lualib.h"
#include "luacode.h"

namespace suterxom::luau
{
namespace
{
constexpr int VALUE_NIL = 0;
constexpr int VALUE_BOOLEAN = 1;
constexpr int VALUE_NUMBER = 2;
constexpr int VALUE_STRING = 3;

constexpr int EXECUTION_COMPLETED = 0;
constexpr int EXECUTION_RUNTIME_FAILED = 1;
constexpr int EXECUTION_RUNTIME_LIMIT = 2;
constexpr int EXECUTION_INTERRUPT_LIMIT = 3;
constexpr int EXECUTION_MEMORY_LIMIT = 4;

struct ExecutionContext
{
    size_t currentMemory = 0;
    size_t peakMemory = 0;
    size_t maxMemory = 0;
    uint64_t interruptChecks = 0;
    uint64_t maxInterruptChecks = 0;
    std::chrono::steady_clock::time_point startedAt;
    std::chrono::steady_clock::time_point deadline;
    int exceededLimit = EXECUTION_COMPLETED;
};

void* limitedAllocate(void* userdata, void* pointer, size_t oldSize, size_t newSize)
{
    auto* context = static_cast<ExecutionContext*>(userdata);
    if (newSize == 0)
    {
        context->currentMemory =
            oldSize <= context->currentMemory ? context->currentMemory - oldSize : 0;
        std::free(pointer);
        return nullptr;
    }

    const size_t memoryWithoutOld =
        oldSize <= context->currentMemory ? context->currentMemory - oldSize : 0;
    if (newSize > context->maxMemory - std::min(memoryWithoutOld, context->maxMemory))
    {
        context->exceededLimit = EXECUTION_MEMORY_LIMIT;
        return nullptr;
    }

    void* result = std::realloc(pointer, newSize);
    if (result != nullptr)
    {
        context->currentMemory = memoryWithoutOld + newSize;
        context->peakMemory = std::max(context->peakMemory, context->currentMemory);
    }
    return result;
}

void executionInterrupt(lua_State* state, int gcState)
{
    if (gcState >= 0)
        return;

    auto* context = static_cast<ExecutionContext*>(lua_callbacks(state)->userdata);
    ++context->interruptChecks;

    if (context->interruptChecks > context->maxInterruptChecks)
        context->exceededLimit = EXECUTION_INTERRUPT_LIMIT;
    else if (std::chrono::steady_clock::now() >= context->deadline)
        context->exceededLimit = EXECUTION_RUNTIME_LIMIT;
    else
        return;

    lua_callbacks(state)->interrupt = nullptr;
    lua_rawcheckstack(state, 1);
    luaL_error(state, "%s", "Luau execution resource limit exceeded");
}

struct LuaStateCloser
{
    void operator()(lua_State* state) const
    {
        if (state != nullptr)
            lua_close(state);
    }
};

using LuaStatePtr = std::unique_ptr<lua_State, LuaStateCloser>;
using BytecodePtr = std::unique_ptr<char, decltype(&std::free)>;

class SingleSourceResolver final : public Luau::FileResolver
{
public:
    SingleSourceResolver(std::string moduleName, std::string source)
        : moduleName(std::move(moduleName))
        , source(std::move(source))
    {
    }

    std::optional<Luau::SourceCode> readSource(const Luau::ModuleName& requestedName) override
    {
        if (requestedName != moduleName)
            return std::nullopt;
        return Luau::SourceCode{source, Luau::SourceCode::Module};
    }

private:
    std::string moduleName;
    std::string source;
};

class StrictConfigResolver final : public Luau::ConfigResolver
{
public:
    StrictConfigResolver()
    {
        config.mode = Luau::Mode::Strict;
    }

    const Luau::Config& getConfig(const Luau::ModuleName&, const Luau::TypeCheckLimits&) const override
    {
        return config;
    }

private:
    Luau::Config config;
};

jbyteArray newByteArray(JNIEnv* env, std::string_view bytes)
{
    jbyteArray result = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (result == nullptr)
        return nullptr;
    if (!bytes.empty())
    {
        env->SetByteArrayRegion(
            result,
            0,
            static_cast<jsize>(bytes.size()),
            reinterpret_cast<const jbyte*>(bytes.data()));
    }
    return result;
}

jstring newJavaUtf8String(JNIEnv* env, std::string_view value)
{
    jbyteArray bytes = newByteArray(env, value);
    if (bytes == nullptr)
        return nullptr;

    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr)
        return nullptr;
    jmethodID constructor =
        env->GetMethodID(stringClass, "<init>", "([BLjava/lang/String;)V");
    if (constructor == nullptr)
        return nullptr;
    jstring charsetName = env->NewStringUTF("UTF-8");
    if (charsetName == nullptr)
        return nullptr;

    jstring result =
        static_cast<jstring>(env->NewObject(stringClass, constructor, bytes, charsetName));
    env->DeleteLocalRef(charsetName);
    env->DeleteLocalRef(bytes);
    return result;
}

void throwNativeException(JNIEnv* env, const std::string& message)
{
    jclass exceptionClass =
        env->FindClass("io/github/suterxom/luau/internal/jni/NativeLuauException");
    if (exceptionClass == nullptr)
        return;
    jmethodID constructor =
        env->GetMethodID(exceptionClass, "<init>", "(Ljava/lang/String;)V");
    if (constructor == nullptr)
        return;
    jstring javaMessage = newJavaUtf8String(env, message);
    if (javaMessage == nullptr)
        return;
    jthrowable exception =
        static_cast<jthrowable>(env->NewObject(exceptionClass, constructor, javaMessage));
    env->DeleteLocalRef(javaMessage);
    if (exception != nullptr)
    {
        env->Throw(exception);
        env->DeleteLocalRef(exception);
    }
}

template<typename Result, typename Function>
Result guardNativeCall(JNIEnv* env, Function&& function) noexcept
{
    try
    {
        return function();
    }
    catch (const std::exception& exception)
    {
        if (!env->ExceptionCheck())
            throwNativeException(env, std::string("Native Luau failure: ") + exception.what());
    }
    catch (...)
    {
        if (!env->ExceptionCheck())
            throwNativeException(env, "Unknown native Luau failure");
    }
    return nullptr;
}

std::string getUtf8Bytes(JNIEnv* env, jbyteArray value)
{
    if (value == nullptr)
        return {};

    const jsize size = env->GetArrayLength(value);
    std::string result(static_cast<size_t>(size), '\0');
    if (size > 0)
        env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte*>(result.data()));
    return result;
}

std::string luaError(lua_State* state)
{
    size_t length = 0;
    const char* message = lua_tolstring(state, -1, &length);
    return message == nullptr ? "Unknown Luau error" : std::string(message, length);
}

jobject newNativeValue(
    JNIEnv* env,
    int kind,
    bool booleanValue = false,
    double numberValue = 0.0,
    std::string_view bytes = {})
{
    jclass valueClass = env->FindClass("io/github/suterxom/luau/internal/jni/NativeValue");
    if (valueClass == nullptr)
        return nullptr;

    jmethodID constructor = env->GetMethodID(valueClass, "<init>", "(IZD[B)V");
    if (constructor == nullptr)
        return nullptr;

    jbyteArray byteValue = nullptr;
    if (!bytes.empty())
    {
        byteValue = env->NewByteArray(static_cast<jsize>(bytes.size()));
        if (byteValue == nullptr)
            return nullptr;
        env->SetByteArrayRegion(
            byteValue,
            0,
            static_cast<jsize>(bytes.size()),
            reinterpret_cast<const jbyte*>(bytes.data()));
    }

    jobject result = env->NewObject(
        valueClass,
        constructor,
        static_cast<jint>(kind),
        static_cast<jboolean>(booleanValue),
        static_cast<jdouble>(numberValue),
        byteValue);
    if (byteValue != nullptr)
        env->DeleteLocalRef(byteValue);
    return result;
}

jobject convertValue(JNIEnv* env, lua_State* state, int index)
{
    switch (lua_type(state, index))
    {
    case LUA_TNIL:
        return newNativeValue(env, VALUE_NIL);
    case LUA_TBOOLEAN:
        return newNativeValue(env, VALUE_BOOLEAN, lua_toboolean(state, index) != 0);
    case LUA_TNUMBER:
        return newNativeValue(env, VALUE_NUMBER, false, lua_tonumber(state, index));
    case LUA_TSTRING:
    {
        size_t length = 0;
        const char* value = lua_tolstring(state, index, &length);
        return newNativeValue(env, VALUE_STRING, false, 0.0, std::string_view(value, length));
    }
    default:
        throwNativeException(
            env,
            std::string("Unsupported Luau return type: ") + lua_typename(state, lua_type(state, index)));
        return nullptr;
    }
}

jobjectArray newNativeValueArray(JNIEnv* env, lua_State* state, int resultCount)
{
    jclass valueClass = env->FindClass("io/github/suterxom/luau/internal/jni/NativeValue");
    if (valueClass == nullptr)
        return nullptr;

    jobjectArray result = env->NewObjectArray(resultCount, valueClass, nullptr);
    if (result == nullptr)
        return nullptr;

    for (int index = 1; index <= resultCount; ++index)
    {
        jobject value = convertValue(env, state, index);
        if (env->ExceptionCheck() || value == nullptr)
            return nullptr;
        env->SetObjectArrayElement(result, index - 1, value);
        env->DeleteLocalRef(value);
    }
    return result;
}

jobject newNativeExecutionResult(
    JNIEnv* env,
    int status,
    jobjectArray values,
    std::string_view message,
    uint64_t elapsedNanos,
    const ExecutionContext& context)
{
    jclass resultClass =
        env->FindClass("io/github/suterxom/luau/internal/jni/NativeExecutionResult");
    if (resultClass == nullptr)
        return nullptr;
    jmethodID constructor = env->GetMethodID(
        resultClass,
        "<init>",
        "(I[Lio/github/suterxom/luau/internal/jni/NativeValue;[BJJJJ)V");
    if (constructor == nullptr)
        return nullptr;

    jbyteArray messageBytes = message.empty() ? nullptr : newByteArray(env, message);
    if (!message.empty() && messageBytes == nullptr)
        return nullptr;

    jobject result = env->NewObject(
        resultClass,
        constructor,
        static_cast<jint>(status),
        values,
        messageBytes,
        static_cast<jlong>(elapsedNanos),
        static_cast<jlong>(context.interruptChecks),
        static_cast<jlong>(context.currentMemory),
        static_cast<jlong>(context.peakMemory));
    if (messageBytes != nullptr)
        env->DeleteLocalRef(messageBytes);
    return result;
}
} // namespace
} // namespace suterxom::luau

extern "C"
{
JNIEXPORT jint JNICALL
Java_io_github_suterxom_luau_internal_jni_NativeBindings_abiVersion(JNIEnv*, jclass)
{
    return LUAU_JNI_ABI_VERSION;
}

JNIEXPORT jstring JNICALL
Java_io_github_suterxom_luau_internal_jni_NativeBindings_luauVersion(JNIEnv* env, jclass)
{
    return env->NewStringUTF(LUAU_UPSTREAM_VERSION);
}

JNIEXPORT jstring JNICALL
Java_io_github_suterxom_luau_internal_jni_NativeBindings_luauCommit(JNIEnv* env, jclass)
{
    return env->NewStringUTF(LUAU_UPSTREAM_COMMIT);
}

JNIEXPORT jstring JNICALL
Java_io_github_suterxom_luau_internal_jni_NativeBindings_runtimeVersion(JNIEnv* env, jclass)
{
    return env->NewStringUTF(LUAU_RUNTIME_VERSION);
}

jbyteArray compileImpl(
    JNIEnv* env,
    jclass,
    jbyteArray chunkNameUtf8,
    jbyteArray sourceUtf8)
{
    using namespace suterxom::luau;

    const std::string chunk = getUtf8Bytes(env, chunkNameUtf8);
    const std::string source = getUtf8Bytes(env, sourceUtf8);
    if (env->ExceptionCheck())
        return nullptr;

    lua_CompileOptions options{};
    options.optimizationLevel = 1;
    options.debugLevel = 2;
    options.typeInfoLevel = 0;
    options.coverageLevel = 0;

    size_t bytecodeSize = 0;
    BytecodePtr bytecode(
        luau_compile(source.data(), source.size(), &options, &bytecodeSize),
        &std::free);
    if (bytecode == nullptr)
    {
        throwNativeException(env, "Luau compiler returned no bytecode");
        return nullptr;
    }

    LuaStatePtr validationState(luaL_newstate());
    if (validationState == nullptr)
    {
        throwNativeException(env, "Failed to create a Luau validation state");
        return nullptr;
    }

    const int loadStatus =
        luau_load(validationState.get(), chunk.c_str(), bytecode.get(), bytecodeSize, 0);
    if (loadStatus != LUA_OK)
    {
        throwNativeException(env, luaError(validationState.get()));
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(bytecodeSize));
    if (result == nullptr)
        return nullptr;
    env->SetByteArrayRegion(
        result,
        0,
        static_cast<jsize>(bytecodeSize),
        reinterpret_cast<const jbyte*>(bytecode.get()));
    return result;
}

jobjectArray typecheckImpl(
    JNIEnv* env,
    jclass,
    jbyteArray chunkNameUtf8,
    jbyteArray sourceUtf8)
{
    using namespace suterxom::luau;

    const std::string chunk = getUtf8Bytes(env, chunkNameUtf8);
    const std::string source = getUtf8Bytes(env, sourceUtf8);
    if (env->ExceptionCheck())
        return nullptr;

    SingleSourceResolver fileResolver(chunk, source);
    StrictConfigResolver configResolver;
    Luau::FrontendOptions options;
    options.retainFullTypeGraphs = false;
    options.runLintChecks = false;
    options.moduleTimeLimitSec = 2.0;
    options.applyInternalLimitScaling = true;

    Luau::Frontend frontend(&fileResolver, &configResolver, options);
    Luau::registerBuiltinGlobals(frontend, frontend.globals);
    Luau::freeze(frontend.globals.globalTypes);

    Luau::CheckResult checkResult = frontend.check(chunk);
    std::vector<std::string> messages;
    messages.reserve(checkResult.errors.size() + checkResult.timeoutHits.size());

    for (const Luau::TypeError& error : checkResult.errors)
    {
        const Luau::Location& location = error.location;
        messages.push_back(
            std::to_string(location.begin.line + 1) + ":" +
            std::to_string(location.begin.column + 1) + ": " +
            Luau::toString(error, Luau::TypeErrorToStringOptions{&fileResolver}));
    }
    for (const Luau::ModuleName& timedOutModule : checkResult.timeoutHits)
        messages.push_back("Type checking timed out for module " + timedOutModule);

    jclass byteArrayClass = env->FindClass("[B");
    if (byteArrayClass == nullptr)
        return nullptr;
    jobjectArray result =
        env->NewObjectArray(static_cast<jsize>(messages.size()), byteArrayClass, nullptr);
    if (result == nullptr)
        return nullptr;

    for (size_t index = 0; index < messages.size(); ++index)
    {
        jbyteArray message = newByteArray(env, messages[index]);
        if (message == nullptr)
            return nullptr;
        env->SetObjectArrayElement(result, static_cast<jsize>(index), message);
        env->DeleteLocalRef(message);
    }
    return result;
}

jobject executeImpl(
    JNIEnv* env,
    jclass,
    jbyteArray chunkNameUtf8,
    jbyteArray bytecodeBytes,
    jlong maxRuntimeNanos,
    jlong maxInterruptChecks,
    jlong maxMemoryBytes)
{
    using namespace suterxom::luau;

    const std::string chunk = getUtf8Bytes(env, chunkNameUtf8);
    const std::string bytecode = getUtf8Bytes(env, bytecodeBytes);
    if (env->ExceptionCheck())
        return nullptr;
    if (maxRuntimeNanos <= 0 || maxInterruptChecks <= 0 || maxMemoryBytes <= 0)
    {
        throwNativeException(env, "Native execution limits must be positive");
        return nullptr;
    }

    ExecutionContext context;
    context.maxMemory = static_cast<size_t>(maxMemoryBytes);
    context.maxInterruptChecks = static_cast<uint64_t>(maxInterruptChecks);

    LuaStatePtr globalState(lua_newstate(limitedAllocate, &context));
    if (globalState == nullptr)
    {
        context.exceededLimit = EXECUTION_MEMORY_LIMIT;
        return newNativeExecutionResult(
            env,
            EXECUTION_MEMORY_LIMIT,
            nullptr,
            "Luau VM memory limit exceeded while creating the state",
            0,
            context);
    }

    luaL_openlibs(globalState.get());
    luaL_sandbox(globalState.get());
    lua_Callbacks* callbacks = lua_callbacks(globalState.get());
    callbacks->userdata = &context;
    callbacks->interrupt = executionInterrupt;

    lua_State* thread = lua_newthread(globalState.get());
    if (thread == nullptr)
    {
        context.exceededLimit = EXECUTION_MEMORY_LIMIT;
        return newNativeExecutionResult(
            env,
            EXECUTION_MEMORY_LIMIT,
            nullptr,
            "Luau VM memory limit exceeded while creating the execution thread",
            0,
            context);
    }
    luaL_sandboxthread(thread);

    const int loadStatus =
        luau_load(thread, chunk.c_str(), bytecode.data(), bytecode.size(), 0);
    if (loadStatus != LUA_OK)
    {
        const int status =
            loadStatus == LUA_ERRMEM ? EXECUTION_MEMORY_LIMIT : EXECUTION_RUNTIME_FAILED;
        return newNativeExecutionResult(
            env,
            status,
            nullptr,
            luaError(thread),
            0,
            context);
    }

    context.startedAt = std::chrono::steady_clock::now();
    context.deadline =
        context.startedAt + std::chrono::nanoseconds(static_cast<int64_t>(maxRuntimeNanos));
    const int executionStatus = lua_resume(thread, nullptr, 0);
    const auto finishedAt = std::chrono::steady_clock::now();
    const uint64_t elapsedNanos = static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(finishedAt - context.startedAt).count());

    if (executionStatus != LUA_OK)
    {
        int status = EXECUTION_RUNTIME_FAILED;
        if (context.exceededLimit != EXECUTION_COMPLETED)
            status = context.exceededLimit;
        else if (executionStatus == LUA_ERRMEM)
            status = EXECUTION_MEMORY_LIMIT;

        std::string message = luaError(thread);
        if (status == EXECUTION_RUNTIME_LIMIT)
            message = "Luau execution exceeded its maximum runtime";
        else if (status == EXECUTION_INTERRUPT_LIMIT)
            message = "Luau execution exceeded its interrupt-check budget";
        else if (status == EXECUTION_MEMORY_LIMIT)
            message = "Luau execution exceeded its VM memory limit";

        return newNativeExecutionResult(
            env,
            status,
            nullptr,
            message,
            elapsedNanos,
            context);
    }

    const int resultCount = lua_gettop(thread);
    jobjectArray values = newNativeValueArray(env, thread, resultCount);
    if (values == nullptr)
        return nullptr;
    jobject result = newNativeExecutionResult(
        env,
        EXECUTION_COMPLETED,
        values,
        {},
        elapsedNanos,
        context);
    env->DeleteLocalRef(values);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_io_github_suterxom_luau_internal_jni_NativeBindings_compile(
    JNIEnv* env,
    jclass bindingClass,
    jbyteArray chunkNameUtf8,
    jbyteArray sourceUtf8)
{
    using namespace suterxom::luau;
    return guardNativeCall<jbyteArray>(
        env,
        [&]()
        {
            return compileImpl(env, bindingClass, chunkNameUtf8, sourceUtf8);
        });
}

JNIEXPORT jobjectArray JNICALL
Java_io_github_suterxom_luau_internal_jni_NativeBindings_typecheck(
    JNIEnv* env,
    jclass bindingClass,
    jbyteArray chunkNameUtf8,
    jbyteArray sourceUtf8)
{
    using namespace suterxom::luau;
    return guardNativeCall<jobjectArray>(
        env,
        [&]()
        {
            return typecheckImpl(env, bindingClass, chunkNameUtf8, sourceUtf8);
        });
}

JNIEXPORT jobject JNICALL
Java_io_github_suterxom_luau_internal_jni_NativeBindings_execute(
    JNIEnv* env,
    jclass bindingClass,
    jbyteArray chunkNameUtf8,
    jbyteArray bytecodeBytes,
    jlong maxRuntimeNanos,
    jlong maxInterruptChecks,
    jlong maxMemoryBytes)
{
    using namespace suterxom::luau;
    return guardNativeCall<jobject>(
        env,
        [&]()
        {
            return executeImpl(
                env,
                bindingClass,
                chunkNameUtf8,
                bytecodeBytes,
                maxRuntimeNanos,
                maxInterruptChecks,
                maxMemoryBytes);
        });
}
}
