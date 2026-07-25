#include <jni.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <limits>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "Luau/BuiltinDefinitions.h"
#include "Luau/Ast.h"
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
constexpr int VALUE_BUFFER = 4;
constexpr int VALUE_ARRAY = 5;
constexpr int VALUE_TABLE = 6;

constexpr size_t VALUE_MAX_DEPTH = 32;
constexpr size_t VALUE_MAX_NODES = 10'000;
constexpr size_t VALUE_MAX_STRING_BYTES = 512 * 1024;
constexpr size_t VALUE_MAX_BUFFER_BYTES = 1024 * 1024;
constexpr size_t VALUE_MAX_TOTAL_BYTES = 1024 * 1024;

constexpr int EXECUTION_COMPLETED = 0;
constexpr int EXECUTION_RUNTIME_FAILED = 1;
constexpr int EXECUTION_RUNTIME_LIMIT = 2;
constexpr int EXECUTION_INTERRUPT_LIMIT = 3;
constexpr int EXECUTION_MEMORY_LIMIT = 4;
constexpr int EXECUTION_VALUE_ERROR = 5;
constexpr int EXECUTION_VALUE_LIMIT = 6;
constexpr int EXECUTION_HOST_CALL_LIMIT = 7;
constexpr int EXECUTION_HOST_OUTPUT_LIMIT = 8;
constexpr int EXECUTION_PAUSED = 9;
constexpr int EXECUTION_TERMINATED = 10;

enum class ActiveOperation
{
    None,
    Execute,
    Initialize,
    InvokeExport,
};

struct DebugStackFrame
{
    std::string moduleName;
    std::string functionName;
    int line = 0;
};

struct PauseSnapshot
{
    int reason = 0;
    std::string moduleName;
    int line = 0;
    std::vector<DebugStackFrame> stackFrames;
};

struct ExecutionContext
{
    size_t currentMemory = 0;
    size_t peakMemory = 0;
    size_t maxMemory = 0;
    uint64_t interruptChecks = 0;
    uint64_t maxInterruptChecks = 0;
    uint64_t hostCalls = 0;
    uint64_t maxHostCalls = 0;
    uint64_t hostOutputBytes = 0;
    uint64_t maxHostOutputBytes = 0;
    uint64_t elapsedNanos = 0;
    uint64_t maxRuntimeNanos = 0;
    std::chrono::steady_clock::time_point startedAt;
    std::chrono::steady_clock::time_point deadline;
    int exceededLimit = EXECUTION_COMPLETED;
    int lastStatus = EXECUTION_COMPLETED;
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

struct NativeScriptInstance;
int requireSourceModule(lua_State* state);
void debuggerBreak(lua_State* state, lua_Debug* debug);
bool pushHostModule(
    lua_State* state,
    NativeScriptInstance* instance,
    std::string_view moduleName);
int invokeHostFunction(lua_State* state);

struct NativeScriptInstance
{
    ExecutionContext context;
    LuaStatePtr globalState;
    lua_State* thread = nullptr;
    std::string rootModuleName;
    std::unordered_map<std::string, std::string> moduleBytecodes;
    std::unordered_map<std::string, int> moduleCache;
    std::unordered_map<std::string, int> moduleClosureRefs;
    std::unordered_set<std::string> loadingModules;
    std::unordered_map<std::string, std::unordered_set<int>> breakpoints;
    std::unordered_map<const void*, size_t> activeArrayLengths;
    JavaVM* javaVm = nullptr;
    jobject hostRegistry = nullptr;
    jobject currentHostContext = nullptr;
    bool faulted = false;
    bool initialized = false;
    bool paused = false;
    bool skipBreakpointOnce = false;
    ActiveOperation activeOperation = ActiveOperation::None;
    std::optional<PauseSnapshot> pauseSnapshot;
    int exportsRef = -1;

    NativeScriptInstance(
        JNIEnv* env,
        std::string rootModuleName,
        std::unordered_map<std::string, std::string> moduleBytecodes,
        jobject hostRegistry,
        size_t maxMemory)
        : globalState(nullptr)
        , rootModuleName(std::move(rootModuleName))
        , moduleBytecodes(std::move(moduleBytecodes))
    {
        if (env->GetJavaVM(&javaVm) != JNI_OK)
            return;
        this->hostRegistry = env->NewGlobalRef(hostRegistry);
        if (this->hostRegistry == nullptr)
            return;

        context.maxMemory = maxMemory;
        globalState.reset(lua_newstate(limitedAllocate, &context));
        if (globalState == nullptr)
            return;

        luaL_openlibs(globalState.get());
        lua_pushcfunction(globalState.get(), requireSourceModule, "require");
        lua_setglobal(globalState.get(), "require");
        luaL_sandbox(globalState.get());
        lua_Callbacks* callbacks = lua_callbacks(globalState.get());
        callbacks->userdata = &context;
        callbacks->debugbreak = debuggerBreak;
        lua_setthreaddata(globalState.get(), this);

        thread = lua_newthread(globalState.get());
        if (thread != nullptr)
        {
            luaL_sandboxthread(thread);
            lua_setthreaddata(thread, this);
        }
    }

    ~NativeScriptInstance()
    {
        if (hostRegistry != nullptr && javaVm != nullptr)
        {
            JNIEnv* env = nullptr;
            if (javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) == JNI_OK)
                env->DeleteGlobalRef(hostRegistry);
        }
    }

    bool valid() const
    {
        return hostRegistry != nullptr &&
            globalState != nullptr &&
            thread != nullptr &&
            moduleBytecodes.find(rootModuleName) != moduleBytecodes.end();
    }

    JNIEnv* environment() const
    {
        JNIEnv* env = nullptr;
        if (javaVm == nullptr ||
            javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) != JNI_OK)
            return nullptr;
        return env;
    }
};

class InMemorySourceResolver final : public Luau::FileResolver
{
public:
    explicit InMemorySourceResolver(
        std::unordered_map<std::string, std::string> sources)
        : sources(std::move(sources))
    {
    }

    std::optional<Luau::SourceCode> readSource(const Luau::ModuleName& requestedName) override
    {
        const auto source = sources.find(requestedName);
        if (source == sources.end())
            return std::nullopt;
        return Luau::SourceCode{source->second, Luau::SourceCode::Module};
    }

    std::optional<Luau::ModuleInfo> resolveModule(
        const Luau::ModuleInfo*,
        Luau::AstExpr* expression,
        const Luau::TypeCheckLimits&) override
    {
        if (const auto* name = expression->as<Luau::AstExprConstantString>())
            return Luau::ModuleInfo{
                std::string(name->value.data, name->value.size)};
        return std::nullopt;
    }

private:
    std::unordered_map<std::string, std::string> sources;
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

bool isValidUtf8(std::string_view value)
{
    size_t index = 0;
    while (index < value.size())
    {
        const auto first = static_cast<unsigned char>(value[index]);
        if (first <= 0x7f)
        {
            ++index;
            continue;
        }

        size_t continuationCount = 0;
        unsigned char secondMinimum = 0x80;
        unsigned char secondMaximum = 0xbf;
        if (first >= 0xc2 && first <= 0xdf)
            continuationCount = 1;
        else if (first >= 0xe0 && first <= 0xef)
        {
            continuationCount = 2;
            if (first == 0xe0)
                secondMinimum = 0xa0;
            else if (first == 0xed)
                secondMaximum = 0x9f;
        }
        else if (first >= 0xf0 && first <= 0xf4)
        {
            continuationCount = 3;
            if (first == 0xf0)
                secondMinimum = 0x90;
            else if (first == 0xf4)
                secondMaximum = 0x8f;
        }
        else
            return false;

        if (index + continuationCount >= value.size())
            return false;
        const auto second = static_cast<unsigned char>(value[index + 1]);
        if (second < secondMinimum || second > secondMaximum)
            return false;
        for (size_t continuation = 2; continuation <= continuationCount; ++continuation)
        {
            const auto byte =
                static_cast<unsigned char>(value[index + continuation]);
            if (byte < 0x80 || byte > 0xbf)
                return false;
        }
        index += continuationCount + 1;
    }
    return true;
}

bool readNamedByteArrays(
    JNIEnv* env,
    jobjectArray namesUtf8,
    jobjectArray values,
    std::unordered_map<std::string, std::string>& output,
    std::vector<std::string>& orderedNames)
{
    if (namesUtf8 == nullptr || values == nullptr)
    {
        throwNativeException(env, "Native module arrays must not be null");
        return false;
    }
    const jsize count = env->GetArrayLength(namesUtf8);
    if (env->ExceptionCheck())
        return false;
    if (count <= 0 || count > 64 || env->GetArrayLength(values) != count)
    {
        throwNativeException(
            env,
            "Native module arrays must have equal lengths between 1 and 64");
        return false;
    }

    output.reserve(static_cast<size_t>(count));
    orderedNames.reserve(static_cast<size_t>(count));
    for (jsize index = 0; index < count; ++index)
    {
        auto nameBytes =
            static_cast<jbyteArray>(env->GetObjectArrayElement(namesUtf8, index));
        auto valueBytes =
            static_cast<jbyteArray>(env->GetObjectArrayElement(values, index));
        if (env->ExceptionCheck() || nameBytes == nullptr || valueBytes == nullptr)
        {
            if (nameBytes != nullptr)
                env->DeleteLocalRef(nameBytes);
            if (valueBytes != nullptr)
                env->DeleteLocalRef(valueBytes);
            if (!env->ExceptionCheck())
                throwNativeException(env, "Native module entries must not be null");
            return false;
        }

        std::string name = getUtf8Bytes(env, nameBytes);
        std::string value = getUtf8Bytes(env, valueBytes);
        env->DeleteLocalRef(nameBytes);
        env->DeleteLocalRef(valueBytes);
        if (env->ExceptionCheck())
            return false;
        if (name.empty() || name.size() > 256 ||
            !isValidUtf8(name) ||
            name.find('\0') != std::string::npos)
        {
            throwNativeException(env, "Native module name is invalid");
            return false;
        }
        if (!output.emplace(name, std::move(value)).second)
        {
            throwNativeException(env, "Duplicate native module name: " + name);
            return false;
        }
        orderedNames.push_back(std::move(name));
    }
    return true;
}

std::string luaError(lua_State* state)
{
    size_t length = 0;
    const char* message = lua_tolstring(state, -1, &length);
    return message == nullptr ? "Unknown Luau error" : std::string(message, length);
}

std::string debugModuleName(
    const NativeScriptInstance& instance,
    const char* source)
{
    if (source == nullptr || source[0] == '\0')
        return instance.rootModuleName;

    std::string name(source);
    if (instance.moduleBytecodes.find(name) != instance.moduleBytecodes.end())
        return name;
    if ((name[0] == '=' || name[0] == '@') &&
        instance.moduleBytecodes.find(name.substr(1)) != instance.moduleBytecodes.end())
        return name.substr(1);
    return name;
}

std::string debugFunctionName(const char* name)
{
    if (name == nullptr)
        return {};

    constexpr size_t MAX_FUNCTION_NAME_BYTES = 256;
    const size_t length = std::strlen(name);
    if (length <= MAX_FUNCTION_NAME_BYTES)
        return std::string(name, length);

    size_t truncatedLength = MAX_FUNCTION_NAME_BYTES;
    while (truncatedLength > 0 &&
           (static_cast<unsigned char>(name[truncatedLength]) & 0xc0) == 0x80)
        --truncatedLength;
    return std::string(name, truncatedLength);
}

void debuggerBreak(lua_State* state, lua_Debug*)
{
    auto* instance =
        static_cast<NativeScriptInstance*>(lua_getthreaddata(state));
    if (instance == nullptr ||
        state != instance->thread ||
        instance->activeOperation == ActiveOperation::None)
        return;
    if (instance->skipBreakpointOnce)
    {
        instance->skipBreakpointOnce = false;
        return;
    }

    PauseSnapshot snapshot;
    snapshot.reason = 0;
    constexpr int MAX_STACK_FRAMES = 256;
    for (int level = 0; level < MAX_STACK_FRAMES; ++level)
    {
        lua_Debug frame{};
        if (lua_getinfo(state, level, "nsl", &frame) == 0)
            break;
        if (frame.currentline <= 0)
            continue;

        DebugStackFrame captured{
            debugModuleName(*instance, frame.source),
            debugFunctionName(frame.name),
            frame.currentline};
        if (snapshot.line == 0)
        {
            snapshot.moduleName = captured.moduleName;
            snapshot.line = captured.line;
        }
        snapshot.stackFrames.push_back(std::move(captured));
    }
    if (snapshot.line == 0)
        return;

    instance->pauseSnapshot = std::move(snapshot);
    instance->paused = true;
    lua_break(state);
}

void applyModuleBreakpoints(
    NativeScriptInstance& instance,
    lua_State* state,
    const std::string& moduleName,
    int closureIndex)
{
    const auto configured = instance.breakpoints.find(moduleName);
    if (configured == instance.breakpoints.end())
        return;

    const int absoluteIndex = lua_absindex(state, closureIndex);
    for (int line : configured->second)
        lua_breakpoint(state, absoluteIndex, line, true);
}

void rememberModuleClosure(
    NativeScriptInstance& instance,
    lua_State* state,
    const std::string& moduleName,
    int closureIndex)
{
    const int absoluteIndex = lua_absindex(state, closureIndex);
    applyModuleBreakpoints(instance, state, moduleName, absoluteIndex);
    const int reference = lua_ref(state, absoluteIndex);
    if (const auto existing = instance.moduleClosureRefs.find(moduleName);
        existing != instance.moduleClosureRefs.end())
    {
        lua_unref(state, existing->second);
        existing->second = reference;
    }
    else
        instance.moduleClosureRefs.emplace(moduleName, reference);
}

int requireSourceModule(lua_State* state)
{
    auto* instance =
        static_cast<NativeScriptInstance*>(lua_getthreaddata(state));
    if (instance == nullptr)
        luaL_error(state, "%s", "Luau require context is unavailable");
    if (lua_gettop(state) != 1 || lua_type(state, 1) != LUA_TSTRING)
        luaL_error(state, "%s", "require expects exactly one string module name");

    size_t nameLength = 0;
    const char* nameBytes = lua_tolstring(state, 1, &nameLength);
    const std::string_view nameView(
        nameBytes == nullptr ? "" : nameBytes,
        nameLength);
    if (!isValidUtf8(nameView) ||
        nameView.find('\0') != std::string_view::npos)
    {
        luaL_error(
            state,
            "%s",
            "require module name must be valid UTF-8 without NUL");
    }
    const std::string moduleName(nameView);

    if (const auto cached = instance->moduleCache.find(moduleName);
        cached != instance->moduleCache.end())
    {
        lua_getref(state, cached->second);
        return 1;
    }
    if (instance->loadingModules.find(moduleName) !=
        instance->loadingModules.end())
    {
        const std::string message =
            "Circular module dependency while requiring: " + moduleName;
        luaL_error(state, "%s", message.c_str());
    }

    const auto module = instance->moduleBytecodes.find(moduleName);
    if (module == instance->moduleBytecodes.end())
    {
        if (pushHostModule(state, instance, moduleName))
        {
            const int reference = lua_ref(state, -1);
            instance->moduleCache.emplace(moduleName, reference);
            return 1;
        }
        const std::string message =
            "Module is not registered in this program: " + moduleName;
        luaL_error(state, "%s", message.c_str());
    }

    instance->loadingModules.insert(moduleName);
    lua_State* globalState = lua_mainthread(state);
    lua_State* moduleThread = lua_newthread(globalState);
    lua_xmove(globalState, state, 1);
    luaL_sandboxthread(moduleThread);
    lua_setthreaddata(moduleThread, instance);

    const int loadStatus =
        luau_load(
            moduleThread,
            moduleName.c_str(),
            module->second.data(),
            module->second.size(),
            0);
    if (loadStatus != LUA_OK)
    {
        const std::string errorMessage =
            "Failed to load required module " + moduleName + ": " +
            luaError(moduleThread);
        instance->loadingModules.erase(moduleName);
        lua_pop(state, 1);
        luaL_error(state, "%s", errorMessage.c_str());
    }
    rememberModuleClosure(*instance, moduleThread, moduleName, -1);

    const int executionStatus = lua_resume(moduleThread, state, 0);
    if (executionStatus != LUA_OK)
    {
        std::string errorMessage;
        if (executionStatus == LUA_YIELD)
            errorMessage = "Required module cannot yield: " + moduleName;
        else
            errorMessage =
                "Error while running required module " + moduleName + ": " +
                luaError(moduleThread);
        instance->loadingModules.erase(moduleName);
        lua_pop(state, 1);
        luaL_error(state, "%s", errorMessage.c_str());
    }
    if (lua_gettop(moduleThread) != 1)
    {
        const std::string errorMessage =
            "Required module must return exactly one value: " + moduleName;
        instance->loadingModules.erase(moduleName);
        lua_pop(state, 1);
        luaL_error(state, "%s", errorMessage.c_str());
    }

    instance->loadingModules.erase(moduleName);
    lua_xmove(moduleThread, state, 1);
    lua_remove(state, -2);
    const int reference = lua_ref(state, -1);
    instance->moduleCache.emplace(moduleName, reference);
    return 1;
}

jobject newNativeValue(
    JNIEnv* env,
    int kind,
    bool booleanValue = false,
    double numberValue = 0.0,
    std::optional<std::string_view> bytes = std::nullopt,
    jobjectArray elements = nullptr,
    jobjectArray keysUtf8 = nullptr)
{
    jclass valueClass = env->FindClass("io/github/suterxom/luau/internal/jni/NativeValue");
    if (valueClass == nullptr)
        return nullptr;

    jmethodID constructor = env->GetMethodID(
        valueClass,
        "<init>",
        "(IZD[B[Lio/github/suterxom/luau/internal/jni/NativeValue;[[B)V");
    if (constructor == nullptr)
    {
        env->DeleteLocalRef(valueClass);
        return nullptr;
    }

    jbyteArray byteValue = nullptr;
    if (bytes.has_value())
    {
        byteValue = newByteArray(env, *bytes);
        if (byteValue == nullptr)
        {
            env->DeleteLocalRef(valueClass);
            return nullptr;
        }
    }

    jobject result = env->NewObject(
        valueClass,
        constructor,
        static_cast<jint>(kind),
        static_cast<jboolean>(booleanValue),
        static_cast<jdouble>(numberValue),
        byteValue,
        elements,
        keysUtf8);
    env->DeleteLocalRef(valueClass);
    if (byteValue != nullptr)
        env->DeleteLocalRef(byteValue);
    return result;
}

struct ValueCodecContext
{
    explicit ValueCodecContext(lua_State* state = nullptr)
        : state(state)
    {
    }

    ~ValueCodecContext()
    {
        if (state != nullptr)
        {
            for (int reference : arrayReferences)
                lua_unref(state, reference);
        }
    }

    size_t nodes = 0;
    size_t stringBytes = 0;
    size_t bufferBytes = 0;
    size_t totalBytes = 0;
    bool limitExceeded = false;
    std::string error;
    std::unordered_set<const void*> activeTables;
    std::unordered_map<const void*, size_t> arrayLengths;

    void rememberArray(lua_State* owner, int index, size_t length)
    {
        const void* pointer = lua_topointer(owner, index);
        arrayLengths[pointer] = length;
        arrayReferences.push_back(lua_ref(owner, index));
    }

    void resetBudget()
    {
        nodes = 0;
        stringBytes = 0;
        bufferBytes = 0;
        totalBytes = 0;
        limitExceeded = false;
        error.clear();
        activeTables.clear();
    }

    bool visit(size_t depth)
    {
        if (depth > VALUE_MAX_DEPTH)
            return fail("Luau value exceeds maximum depth " + std::to_string(VALUE_MAX_DEPTH), true);
        if (++nodes > VALUE_MAX_NODES)
            return fail(
                "Luau value exceeds maximum node count " + std::to_string(VALUE_MAX_NODES),
                true);
        return true;
    }

    bool addStringBytes(size_t count)
    {
        return addBytes(
            "string",
            stringBytes,
            count,
            VALUE_MAX_STRING_BYTES);
    }

    bool addBufferBytes(size_t count)
    {
        return addBytes(
            "buffer",
            bufferBytes,
            count,
            VALUE_MAX_BUFFER_BYTES);
    }

    bool fail(std::string message, bool isLimit = false)
    {
        if (error.empty())
        {
            error = std::move(message);
            limitExceeded = isLimit;
        }
        return false;
    }

private:
    lua_State* state;
    std::vector<int> arrayReferences;

    bool addBytes(
        std::string_view kind,
        size_t& current,
        size_t count,
        size_t maximum)
    {
        if (count > maximum - std::min(current, maximum))
            return fail(
                "Luau value exceeds maximum " + std::string(kind) +
                    " byte count " + std::to_string(maximum),
                true);
        if (count > VALUE_MAX_TOTAL_BYTES - std::min(totalBytes, VALUE_MAX_TOTAL_BYTES))
            return fail(
                "Luau value exceeds maximum total byte count " +
                    std::to_string(VALUE_MAX_TOTAL_BYTES),
                true);
        current += count;
        totalBytes += count;
        return true;
    }
};

struct ActiveTableGuard
{
    ValueCodecContext& context;
    const void* table;

    ~ActiveTableGuard()
    {
        context.activeTables.erase(table);
    }
};

jobject convertValue(
    JNIEnv* env,
    lua_State* state,
    int index,
    ValueCodecContext& context,
    size_t depth);

jobject convertTable(
    JNIEnv* env,
    lua_State* state,
    int index,
    ValueCodecContext& context,
    size_t depth)
{
    const int tableIndex = lua_absindex(state, index);
    if (lua_getmetatable(state, tableIndex))
    {
        lua_pop(state, 1);
        context.fail("Luau tables with metatables cannot cross the value boundary");
        return nullptr;
    }

    const void* tablePointer = lua_topointer(state, tableIndex);
    if (!context.activeTables.insert(tablePointer).second)
    {
        context.fail("Cyclic Luau tables cannot cross the value boundary");
        return nullptr;
    }
    ActiveTableGuard activeGuard{context, tablePointer};

    size_t entryCount = 0;
    size_t largestArrayIndex = 0;
    bool hasArrayKeys = false;
    bool hasStringKeys = false;
    const auto knownArray = context.arrayLengths.find(tablePointer);
    lua_pushnil(state);
    while (lua_next(state, tableIndex) != 0)
    {
        ++entryCount;
        const int keyType = lua_type(state, -2);
        if (keyType == LUA_TNUMBER || keyType == LUA_TINTEGER)
        {
            const double number = lua_tonumber(state, -2);
            if (!std::isfinite(number) || number < 1 || std::floor(number) != number ||
                number > static_cast<double>(std::numeric_limits<int>::max()))
            {
                lua_pop(state, 2);
                context.fail("Luau array keys must be consecutive positive integers");
                return nullptr;
            }
            hasArrayKeys = true;
            largestArrayIndex = std::max(largestArrayIndex, static_cast<size_t>(number));
        }
        else if (keyType == LUA_TSTRING)
        {
            hasStringKeys = true;
        }
        else
        {
            const std::string keyTypeName = lua_typename(state, keyType);
            lua_pop(state, 2);
            context.fail("Unsupported Luau table key type: " + keyTypeName);
            return nullptr;
        }

        if (entryCount > VALUE_MAX_NODES)
        {
            lua_pop(state, 2);
            context.fail(
                "Luau value exceeds maximum node count " +
                    std::to_string(VALUE_MAX_NODES),
                true);
            return nullptr;
        }
        lua_pop(state, 1);
    }

    if (hasStringKeys && (hasArrayKeys || knownArray != context.arrayLengths.end()))
    {
        context.fail("Mixed Luau array and string table keys are not supported");
        return nullptr;
    }
    if (knownArray == context.arrayLengths.end() &&
        hasArrayKeys &&
        largestArrayIndex != entryCount)
    {
        context.fail("Sparse Luau arrays cannot cross the value boundary");
        return nullptr;
    }
    const bool isArray = hasArrayKeys || knownArray != context.arrayLengths.end();
    const size_t arrayLength =
        knownArray == context.arrayLengths.end()
            ? entryCount
            : std::max(knownArray->second, largestArrayIndex);
    if (arrayLength > VALUE_MAX_NODES)
    {
        context.fail(
            "Luau value exceeds maximum node count " +
                std::to_string(VALUE_MAX_NODES),
            true);
        return nullptr;
    }

    jclass valueClass = env->FindClass("io/github/suterxom/luau/internal/jni/NativeValue");
    if (valueClass == nullptr)
        return nullptr;
    jobjectArray elements =
        env->NewObjectArray(
            static_cast<jsize>(isArray ? arrayLength : entryCount),
            valueClass,
            nullptr);
    env->DeleteLocalRef(valueClass);
    if (elements == nullptr)
        return nullptr;

    if (isArray)
    {
        for (size_t arrayIndex = 1; arrayIndex <= arrayLength; ++arrayIndex)
        {
            lua_rawgeti(state, tableIndex, static_cast<int>(arrayIndex));
            jobject value =
                convertValue(env, state, -1, context, depth + 1);
            lua_pop(state, 1);
            if (value == nullptr)
            {
                env->DeleteLocalRef(elements);
                return nullptr;
            }
            env->SetObjectArrayElement(
                elements,
                static_cast<jsize>(arrayIndex - 1),
                value);
            env->DeleteLocalRef(value);
            if (env->ExceptionCheck())
            {
                env->DeleteLocalRef(elements);
                return nullptr;
            }
        }
        jobject result =
            newNativeValue(env, VALUE_ARRAY, false, 0.0, std::nullopt, elements);
        env->DeleteLocalRef(elements);
        return result;
    }

    jclass byteArrayClass = env->FindClass("[B");
    if (byteArrayClass == nullptr)
    {
        env->DeleteLocalRef(elements);
        return nullptr;
    }
    jobjectArray keysUtf8 =
        env->NewObjectArray(static_cast<jsize>(entryCount), byteArrayClass, nullptr);
    env->DeleteLocalRef(byteArrayClass);
    if (keysUtf8 == nullptr)
    {
        env->DeleteLocalRef(elements);
        return nullptr;
    }

    size_t outputIndex = 0;
    lua_pushnil(state);
    while (lua_next(state, tableIndex) != 0)
    {
        size_t keyLength = 0;
        const char* key = lua_tolstring(state, -2, &keyLength);
        const std::string_view keyView(key, keyLength);
        if (!isValidUtf8(keyView))
        {
            lua_pop(state, 2);
            env->DeleteLocalRef(keysUtf8);
            env->DeleteLocalRef(elements);
            context.fail("Luau table keys must contain valid UTF-8");
            return nullptr;
        }
        if (!context.addStringBytes(keyLength))
        {
            lua_pop(state, 2);
            env->DeleteLocalRef(keysUtf8);
            env->DeleteLocalRef(elements);
            return nullptr;
        }

        jbyteArray keyBytes = newByteArray(env, keyView);
        jobject value =
            convertValue(env, state, -1, context, depth + 1);
        if (keyBytes == nullptr || value == nullptr)
        {
            lua_pop(state, 2);
            if (keyBytes != nullptr)
                env->DeleteLocalRef(keyBytes);
            if (value != nullptr)
                env->DeleteLocalRef(value);
            env->DeleteLocalRef(keysUtf8);
            env->DeleteLocalRef(elements);
            return nullptr;
        }
        env->SetObjectArrayElement(
            keysUtf8,
            static_cast<jsize>(outputIndex),
            keyBytes);
        env->SetObjectArrayElement(
            elements,
            static_cast<jsize>(outputIndex),
            value);
        env->DeleteLocalRef(keyBytes);
        env->DeleteLocalRef(value);
        if (env->ExceptionCheck())
        {
            lua_pop(state, 2);
            env->DeleteLocalRef(keysUtf8);
            env->DeleteLocalRef(elements);
            return nullptr;
        }
        ++outputIndex;
        lua_pop(state, 1);
    }

    jobject result =
        newNativeValue(
            env,
            VALUE_TABLE,
            false,
            0.0,
            std::nullopt,
            elements,
            keysUtf8);
    env->DeleteLocalRef(keysUtf8);
    env->DeleteLocalRef(elements);
    return result;
}

jobject convertValue(
    JNIEnv* env,
    lua_State* state,
    int index,
    ValueCodecContext& context,
    size_t depth)
{
    if (!context.visit(depth))
        return nullptr;

    switch (lua_type(state, index))
    {
    case LUA_TNIL:
        return newNativeValue(env, VALUE_NIL);
    case LUA_TBOOLEAN:
        return newNativeValue(env, VALUE_BOOLEAN, lua_toboolean(state, index) != 0);
    case LUA_TNUMBER:
    case LUA_TINTEGER:
        return newNativeValue(env, VALUE_NUMBER, false, lua_tonumber(state, index));
    case LUA_TSTRING:
    {
        size_t length = 0;
        const char* value = lua_tolstring(state, index, &length);
        const std::string_view stringValue(value == nullptr ? "" : value, length);
        if (!isValidUtf8(stringValue))
        {
            context.fail("Luau strings must contain valid UTF-8");
            return nullptr;
        }
        if (!context.addStringBytes(length))
            return nullptr;
        return newNativeValue(
            env,
            VALUE_STRING,
            false,
            0.0,
            stringValue);
    }
    case LUA_TBUFFER:
    {
        size_t length = 0;
        const auto* value = static_cast<const char*>(lua_tobuffer(state, index, &length));
        if (!context.addBufferBytes(length))
            return nullptr;
        return newNativeValue(
            env,
            VALUE_BUFFER,
            false,
            0.0,
            std::string_view(value == nullptr ? "" : value, length));
    }
    case LUA_TTABLE:
        return convertTable(env, state, index, context, depth);
    default:
        context.fail(
            std::string("Unsupported Luau return type: ") +
            lua_typename(state, lua_type(state, index)));
        return nullptr;
    }
}

bool pushNativeValue(
    JNIEnv* env,
    lua_State* state,
    jobject value,
    ValueCodecContext& context,
    size_t depth)
{
    if (!context.visit(depth))
        return false;
    jclass valueClass = env->FindClass("io/github/suterxom/luau/internal/jni/NativeValue");
    if (valueClass == nullptr)
        return false;
    jmethodID kindMethod = env->GetMethodID(valueClass, "kind", "()I");
    jmethodID booleanMethod = env->GetMethodID(valueClass, "booleanValue", "()Z");
    jmethodID numberMethod = env->GetMethodID(valueClass, "numberValue", "()D");
    jmethodID bytesMethod = env->GetMethodID(valueClass, "bytes", "()[B");
    jmethodID elementsMethod = env->GetMethodID(
        valueClass,
        "elements",
        "()[Lio/github/suterxom/luau/internal/jni/NativeValue;");
    jmethodID keysMethod = env->GetMethodID(valueClass, "keysUtf8", "()[[B");
    if (kindMethod == nullptr || booleanMethod == nullptr || numberMethod == nullptr ||
        bytesMethod == nullptr || elementsMethod == nullptr || keysMethod == nullptr)
    {
        env->DeleteLocalRef(valueClass);
        return false;
    }
    env->DeleteLocalRef(valueClass);

    const jint kind = env->CallIntMethod(value, kindMethod);
    if (env->ExceptionCheck())
        return false;
    switch (kind)
    {
    case VALUE_NIL:
        lua_pushnil(state);
        return true;
    case VALUE_BOOLEAN:
        lua_pushboolean(state, env->CallBooleanMethod(value, booleanMethod));
        return !env->ExceptionCheck();
    case VALUE_NUMBER:
        lua_pushnumber(state, env->CallDoubleMethod(value, numberMethod));
        return !env->ExceptionCheck();
    case VALUE_STRING:
    case VALUE_BUFFER:
    {
        auto bytes = static_cast<jbyteArray>(env->CallObjectMethod(value, bytesMethod));
        if (env->ExceptionCheck() || bytes == nullptr)
            return false;
        const std::string stringValue = getUtf8Bytes(env, bytes);
        env->DeleteLocalRef(bytes);
        if (env->ExceptionCheck())
            return false;
        if (kind == VALUE_STRING)
        {
            if (!isValidUtf8(stringValue))
                return context.fail("Native string bytes must contain valid UTF-8");
            if (!context.addStringBytes(stringValue.size()))
                return false;
            lua_pushlstring(state, stringValue.data(), stringValue.size());
        }
        else
        {
            if (!context.addBufferBytes(stringValue.size()))
                return false;
            void* buffer = lua_newbuffer(state, stringValue.size());
            if (!stringValue.empty())
                std::memcpy(buffer, stringValue.data(), stringValue.size());
        }
        return true;
    }
    case VALUE_ARRAY:
    {
        auto elements =
            static_cast<jobjectArray>(env->CallObjectMethod(value, elementsMethod));
        if (env->ExceptionCheck() || elements == nullptr)
            return false;
        const jsize count = env->GetArrayLength(elements);
        lua_createtable(state, count, 0);
        const int tableIndex = lua_absindex(state, -1);
        context.rememberArray(state, tableIndex, static_cast<size_t>(count));
        for (jsize index = 0; index < count; ++index)
        {
            jobject element = env->GetObjectArrayElement(elements, index);
            if (element == nullptr ||
                !pushNativeValue(env, state, element, context, depth + 1))
            {
                if (element != nullptr)
                    env->DeleteLocalRef(element);
                env->DeleteLocalRef(elements);
                return false;
            }
            env->DeleteLocalRef(element);
            lua_rawseti(state, tableIndex, index + 1);
        }
        env->DeleteLocalRef(elements);
        return true;
    }
    case VALUE_TABLE:
    {
        auto elements =
            static_cast<jobjectArray>(env->CallObjectMethod(value, elementsMethod));
        auto keysUtf8 =
            static_cast<jobjectArray>(env->CallObjectMethod(value, keysMethod));
        if (env->ExceptionCheck() || elements == nullptr || keysUtf8 == nullptr)
        {
            if (elements != nullptr)
                env->DeleteLocalRef(elements);
            if (keysUtf8 != nullptr)
                env->DeleteLocalRef(keysUtf8);
            return false;
        }
        const jsize count = env->GetArrayLength(elements);
        if (env->GetArrayLength(keysUtf8) != count)
        {
            env->DeleteLocalRef(keysUtf8);
            env->DeleteLocalRef(elements);
            return context.fail("Native table key/value length mismatch");
        }

        lua_createtable(state, 0, count);
        const int tableIndex = lua_absindex(state, -1);
        std::unordered_set<std::string> keys;
        for (jsize index = 0; index < count; ++index)
        {
            auto keyBytes =
                static_cast<jbyteArray>(env->GetObjectArrayElement(keysUtf8, index));
            jobject element = env->GetObjectArrayElement(elements, index);
            if (keyBytes == nullptr || element == nullptr)
            {
                if (keyBytes != nullptr)
                    env->DeleteLocalRef(keyBytes);
                if (element != nullptr)
                    env->DeleteLocalRef(element);
                env->DeleteLocalRef(keysUtf8);
                env->DeleteLocalRef(elements);
                return false;
            }
            const std::string key = getUtf8Bytes(env, keyBytes);
            env->DeleteLocalRef(keyBytes);
            if (env->ExceptionCheck() || !isValidUtf8(key) ||
                !context.addStringBytes(key.size()))
            {
                if (!env->ExceptionCheck() && context.error.empty())
                    context.fail("Native table keys must contain valid UTF-8");
                env->DeleteLocalRef(element);
                env->DeleteLocalRef(keysUtf8);
                env->DeleteLocalRef(elements);
                return false;
            }
            if (!keys.insert(key).second)
            {
                env->DeleteLocalRef(element);
                env->DeleteLocalRef(keysUtf8);
                env->DeleteLocalRef(elements);
                return context.fail("Duplicate native table key");
            }

            lua_pushlstring(state, key.data(), key.size());
            if (!pushNativeValue(env, state, element, context, depth + 1))
            {
                env->DeleteLocalRef(element);
                env->DeleteLocalRef(keysUtf8);
                env->DeleteLocalRef(elements);
                return false;
            }
            env->DeleteLocalRef(element);
            lua_rawset(state, tableIndex);
        }
        env->DeleteLocalRef(keysUtf8);
        env->DeleteLocalRef(elements);
        return true;
    }
    default:
        return context.fail("Unsupported Java-to-Luau value kind");
    }
}

jobjectArray newNativeValueArray(
    JNIEnv* env,
    lua_State* state,
    int resultCount,
    ValueCodecContext& context)
{
    jclass valueClass = env->FindClass("io/github/suterxom/luau/internal/jni/NativeValue");
    if (valueClass == nullptr)
        return nullptr;

    jobjectArray result = env->NewObjectArray(resultCount, valueClass, nullptr);
    env->DeleteLocalRef(valueClass);
    if (result == nullptr)
        return nullptr;

    for (int index = 1; index <= resultCount; ++index)
    {
        jobject value = convertValue(env, state, index, context, 0);
        if (env->ExceptionCheck() || value == nullptr)
        {
            env->DeleteLocalRef(result);
            return nullptr;
        }
        env->SetObjectArrayElement(result, index - 1, value);
        env->DeleteLocalRef(value);
        if (env->ExceptionCheck())
        {
            env->DeleteLocalRef(result);
            return nullptr;
        }
    }
    return result;
}

void reportAndClearJavaException(JNIEnv* env)
{
    if (env->ExceptionCheck())
    {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

bool pushHostModule(
    lua_State* state,
    NativeScriptInstance* instance,
    std::string_view moduleName)
{
    JNIEnv* env = instance->environment();
    if (env == nullptr)
        luaL_error(state, "%s", "Java host environment is unavailable");

    jclass registryClass = env->GetObjectClass(instance->hostRegistry);
    jmethodID exportsMethod = registryClass == nullptr
        ? nullptr
        : env->GetMethodID(registryClass, "exports", "([B)[[B");
    jbyteArray moduleNameBytes =
        exportsMethod == nullptr ? nullptr : newByteArray(env, moduleName);
    jobjectArray exports = moduleNameBytes == nullptr
        ? nullptr
        : static_cast<jobjectArray>(
            env->CallObjectMethod(
                instance->hostRegistry,
                exportsMethod,
                moduleNameBytes));
    if (moduleNameBytes != nullptr)
        env->DeleteLocalRef(moduleNameBytes);
    if (registryClass != nullptr)
        env->DeleteLocalRef(registryClass);
    if (env->ExceptionCheck())
    {
        reportAndClearJavaException(env);
        luaL_error(state, "%s", "Host module lookup failed internally");
    }
    if (exports == nullptr)
        return false;

    const jsize exportCount = env->GetArrayLength(exports);
    lua_createtable(state, 0, exportCount);
    const int tableIndex = lua_absindex(state, -1);
    for (jsize index = 0; index < exportCount; ++index)
    {
        auto exportNameBytes =
            static_cast<jbyteArray>(env->GetObjectArrayElement(exports, index));
        if (env->ExceptionCheck() || exportNameBytes == nullptr)
        {
            if (exportNameBytes != nullptr)
                env->DeleteLocalRef(exportNameBytes);
            env->DeleteLocalRef(exports);
            reportAndClearJavaException(env);
            luaL_error(state, "%s", "Host module export lookup failed internally");
        }
        const std::string exportName = getUtf8Bytes(env, exportNameBytes);
        env->DeleteLocalRef(exportNameBytes);
        if (env->ExceptionCheck() ||
            exportName.empty() ||
            exportName.size() > 256 ||
            !isValidUtf8(exportName) ||
            exportName.find('\0') != std::string::npos)
        {
            env->DeleteLocalRef(exports);
            reportAndClearJavaException(env);
            luaL_error(state, "%s", "Host module returned an invalid export name");
        }

        lua_pushlstring(state, moduleName.data(), moduleName.size());
        lua_pushlstring(state, exportName.data(), exportName.size());
        lua_pushcclosure(
            state,
            invokeHostFunction,
            "host",
            2);
        lua_setfield(state, tableIndex, exportName.c_str());
    }
    env->DeleteLocalRef(exports);
    lua_setreadonly(state, tableIndex, true);
    return true;
}

int invokeHostFunction(lua_State* state)
{
    auto* instance =
        static_cast<NativeScriptInstance*>(lua_getthreaddata(state));
    if (instance == nullptr)
        luaL_error(state, "%s", "Luau host call context is unavailable");
    JNIEnv* env = instance->environment();
    if (env == nullptr || instance->currentHostContext == nullptr)
        luaL_error(state, "%s", "Java host call context is unavailable");

    ExecutionContext& execution = instance->context;
    ++execution.hostCalls;
    if (execution.hostCalls > execution.maxHostCalls)
    {
        execution.exceededLimit = EXECUTION_HOST_CALL_LIMIT;
        luaL_error(state, "%s", "Luau execution exceeded its host-call budget");
    }

    size_t moduleNameLength = 0;
    size_t exportNameLength = 0;
    const char* moduleName =
        lua_tolstring(state, lua_upvalueindex(1), &moduleNameLength);
    const char* exportName =
        lua_tolstring(state, lua_upvalueindex(2), &exportNameLength);
    const std::string_view moduleNameView(
        moduleName == nullptr ? "" : moduleName,
        moduleNameLength);
    const std::string_view exportNameView(
        exportName == nullptr ? "" : exportName,
        exportNameLength);

    ValueCodecContext argumentContext;
    jobjectArray arguments =
        newNativeValueArray(
            env,
            state,
            lua_gettop(state),
            argumentContext);
    if (arguments == nullptr)
    {
        if (env->ExceptionCheck())
        {
            reportAndClearJavaException(env);
            luaL_error(state, "%s", "Host argument conversion failed internally");
        }
        execution.exceededLimit =
            argumentContext.limitExceeded ? EXECUTION_VALUE_LIMIT : EXECUTION_VALUE_ERROR;
        luaL_error(
            state,
            "%s",
            argumentContext.error.empty()
                ? "Failed to encode host function arguments"
                : argumentContext.error.c_str());
    }

    jclass registryClass = env->GetObjectClass(instance->hostRegistry);
    jmethodID invokeMethod = registryClass == nullptr
        ? nullptr
        : env->GetMethodID(
            registryClass,
            "invoke",
            "([B[B[Lio/github/suterxom/luau/internal/jni/NativeValue;"
            "Lio/github/suterxom/luau/v1/api/host/HostCallContext;)"
            "Lio/github/suterxom/luau/internal/jni/NativeHostCallResult;");
    jbyteArray moduleNameBytes =
        invokeMethod == nullptr ? nullptr : newByteArray(env, moduleNameView);
    jbyteArray exportNameBytes =
        moduleNameBytes == nullptr ? nullptr : newByteArray(env, exportNameView);
    jobject callResult =
        exportNameBytes == nullptr
            ? nullptr
            : env->CallObjectMethod(
                instance->hostRegistry,
                invokeMethod,
                moduleNameBytes,
                exportNameBytes,
                arguments,
                instance->currentHostContext);
    if (exportNameBytes != nullptr)
        env->DeleteLocalRef(exportNameBytes);
    if (moduleNameBytes != nullptr)
        env->DeleteLocalRef(moduleNameBytes);
    env->DeleteLocalRef(arguments);
    if (registryClass != nullptr)
        env->DeleteLocalRef(registryClass);
    if (env->ExceptionCheck() || callResult == nullptr)
    {
        if (callResult != nullptr)
            env->DeleteLocalRef(callResult);
        reportAndClearJavaException(env);
        luaL_error(state, "%s", "Host function failed internally");
    }

    jclass resultClass = env->GetObjectClass(callResult);
    jmethodID statusMethod =
        resultClass == nullptr ? nullptr : env->GetMethodID(resultClass, "status", "()I");
    jmethodID valuesMethod = resultClass == nullptr
        ? nullptr
        : env->GetMethodID(
            resultClass,
            "values",
            "()[Lio/github/suterxom/luau/internal/jni/NativeValue;");
    jmethodID messageMethod =
        resultClass == nullptr ? nullptr : env->GetMethodID(resultClass, "messageUtf8", "()[B");
    jmethodID outputMethod =
        resultClass == nullptr ? nullptr : env->GetMethodID(resultClass, "outputBytes", "()J");
    if (statusMethod == nullptr ||
        valuesMethod == nullptr ||
        messageMethod == nullptr ||
        outputMethod == nullptr)
    {
        if (resultClass != nullptr)
            env->DeleteLocalRef(resultClass);
        env->DeleteLocalRef(callResult);
        reportAndClearJavaException(env);
        luaL_error(state, "%s", "Host function result binding failed internally");
    }

    const jint status = env->CallIntMethod(callResult, statusMethod);
    if (env->ExceptionCheck())
    {
        env->DeleteLocalRef(resultClass);
        env->DeleteLocalRef(callResult);
        reportAndClearJavaException(env);
        luaL_error(state, "%s", "Host function returned an invalid boundary result");
    }
    const jlong outputBytes = env->CallLongMethod(callResult, outputMethod);
    if (env->ExceptionCheck() || outputBytes < 0)
    {
        env->DeleteLocalRef(resultClass);
        env->DeleteLocalRef(callResult);
        reportAndClearJavaException(env);
        luaL_error(state, "%s", "Host function returned an invalid boundary result");
    }
    auto messageBytes =
        static_cast<jbyteArray>(env->CallObjectMethod(callResult, messageMethod));
    if (env->ExceptionCheck())
    {
        env->DeleteLocalRef(resultClass);
        env->DeleteLocalRef(callResult);
        reportAndClearJavaException(env);
        luaL_error(state, "%s", "Host function returned an invalid boundary result");
    }
    auto values =
        static_cast<jobjectArray>(env->CallObjectMethod(callResult, valuesMethod));
    if (env->ExceptionCheck() || values == nullptr)
    {
        if (messageBytes != nullptr)
            env->DeleteLocalRef(messageBytes);
        env->DeleteLocalRef(resultClass);
        env->DeleteLocalRef(callResult);
        reportAndClearJavaException(env);
        luaL_error(state, "%s", "Host function returned an invalid boundary result");
    }

    const uint64_t output = static_cast<uint64_t>(outputBytes);
    if (output >
        execution.maxHostOutputBytes -
            std::min(execution.hostOutputBytes, execution.maxHostOutputBytes))
    {
        env->DeleteLocalRef(values);
        if (messageBytes != nullptr)
            env->DeleteLocalRef(messageBytes);
        env->DeleteLocalRef(resultClass);
        env->DeleteLocalRef(callResult);
        execution.hostOutputBytes =
            output > std::numeric_limits<uint64_t>::max() - execution.hostOutputBytes
                ? std::numeric_limits<uint64_t>::max()
                : execution.hostOutputBytes + output;
        execution.exceededLimit = EXECUTION_HOST_OUTPUT_LIMIT;
        luaL_error(state, "%s", "Luau execution exceeded its host-output budget");
    }
    execution.hostOutputBytes += output;

    if (status != 0)
    {
        const std::string message =
            messageBytes == nullptr
                ? "Host function failed"
                : getUtf8Bytes(env, messageBytes);
        env->DeleteLocalRef(values);
        if (messageBytes != nullptr)
            env->DeleteLocalRef(messageBytes);
        env->DeleteLocalRef(resultClass);
        env->DeleteLocalRef(callResult);
        if (env->ExceptionCheck())
        {
            reportAndClearJavaException(env);
            luaL_error(state, "%s", "Host function error conversion failed internally");
        }
        if (status == 3)
            execution.exceededLimit = EXECUTION_VALUE_LIMIT;
        luaL_error(state, "%s", message.c_str());
    }

    if (messageBytes != nullptr)
        env->DeleteLocalRef(messageBytes);
    const jsize resultCount = env->GetArrayLength(values);
    ValueCodecContext resultContext(state);
    for (jsize index = 0; index < resultCount; ++index)
    {
        jobject value = env->GetObjectArrayElement(values, index);
        if (value == nullptr ||
            !pushNativeValue(
                env,
                state,
                value,
                resultContext,
                0))
        {
            if (value != nullptr)
                env->DeleteLocalRef(value);
            env->DeleteLocalRef(values);
            env->DeleteLocalRef(resultClass);
            env->DeleteLocalRef(callResult);
            if (env->ExceptionCheck())
            {
                reportAndClearJavaException(env);
                luaL_error(state, "%s", "Host return conversion failed internally");
            }
            execution.exceededLimit =
                resultContext.limitExceeded ? EXECUTION_VALUE_LIMIT : EXECUTION_VALUE_ERROR;
            luaL_error(
                state,
                "%s",
                resultContext.error.empty()
                    ? "Failed to decode host function return values"
                    : resultContext.error.c_str());
        }
        env->DeleteLocalRef(value);
    }
    env->DeleteLocalRef(values);
    env->DeleteLocalRef(resultClass);
    env->DeleteLocalRef(callResult);
    return resultCount;
}

jobject newNativeStackFrame(
    JNIEnv* env,
    const DebugStackFrame& frame)
{
    jclass frameClass =
        env->FindClass("io/github/suterxom/luau/internal/jni/NativeStackFrame");
    if (frameClass == nullptr)
        return nullptr;
    jmethodID constructor =
        env->GetMethodID(frameClass, "<init>", "([B[BI)V");
    if (constructor == nullptr)
    {
        env->DeleteLocalRef(frameClass);
        return nullptr;
    }

    jbyteArray moduleName = newByteArray(env, frame.moduleName);
    jbyteArray functionName = newByteArray(env, frame.functionName);
    if (moduleName == nullptr || functionName == nullptr)
    {
        if (moduleName != nullptr)
            env->DeleteLocalRef(moduleName);
        if (functionName != nullptr)
            env->DeleteLocalRef(functionName);
        env->DeleteLocalRef(frameClass);
        return nullptr;
    }
    jobject result = env->NewObject(
        frameClass,
        constructor,
        moduleName,
        functionName,
        static_cast<jint>(frame.line));
    env->DeleteLocalRef(moduleName);
    env->DeleteLocalRef(functionName);
    env->DeleteLocalRef(frameClass);
    return result;
}

jobject newNativePauseInfo(
    JNIEnv* env,
    const PauseSnapshot& pause)
{
    jclass frameClass =
        env->FindClass("io/github/suterxom/luau/internal/jni/NativeStackFrame");
    if (frameClass == nullptr)
        return nullptr;
    jobjectArray frames =
        env->NewObjectArray(
            static_cast<jsize>(pause.stackFrames.size()),
            frameClass,
            nullptr);
    env->DeleteLocalRef(frameClass);
    if (frames == nullptr)
        return nullptr;

    for (size_t index = 0; index < pause.stackFrames.size(); ++index)
    {
        jobject frame = newNativeStackFrame(env, pause.stackFrames[index]);
        if (frame == nullptr)
        {
            env->DeleteLocalRef(frames);
            return nullptr;
        }
        env->SetObjectArrayElement(
            frames,
            static_cast<jsize>(index),
            frame);
        env->DeleteLocalRef(frame);
        if (env->ExceptionCheck())
        {
            env->DeleteLocalRef(frames);
            return nullptr;
        }
    }

    jclass pauseClass =
        env->FindClass("io/github/suterxom/luau/internal/jni/NativePauseInfo");
    if (pauseClass == nullptr)
    {
        env->DeleteLocalRef(frames);
        return nullptr;
    }
    jmethodID constructor = env->GetMethodID(
        pauseClass,
        "<init>",
        "(I[BI[Lio/github/suterxom/luau/internal/jni/NativeStackFrame;)V");
    jbyteArray moduleName =
        constructor == nullptr ? nullptr : newByteArray(env, pause.moduleName);
    if (constructor == nullptr || moduleName == nullptr)
    {
        if (moduleName != nullptr)
            env->DeleteLocalRef(moduleName);
        env->DeleteLocalRef(pauseClass);
        env->DeleteLocalRef(frames);
        return nullptr;
    }

    jobject result = env->NewObject(
        pauseClass,
        constructor,
        static_cast<jint>(pause.reason),
        moduleName,
        static_cast<jint>(pause.line),
        frames);
    env->DeleteLocalRef(moduleName);
    env->DeleteLocalRef(pauseClass);
    env->DeleteLocalRef(frames);
    return result;
}

jobject newNativeExecutionResult(
    JNIEnv* env,
    int status,
    jobjectArray values,
    std::string_view message,
    uint64_t elapsedNanos,
    const ExecutionContext& context,
    const PauseSnapshot* pause = nullptr)
{
    jclass resultClass =
        env->FindClass("io/github/suterxom/luau/internal/jni/NativeExecutionResult");
    if (resultClass == nullptr)
        return nullptr;
    jmethodID constructor = env->GetMethodID(
        resultClass,
        "<init>",
        "(I[Lio/github/suterxom/luau/internal/jni/NativeValue;[BJJJJJJ"
        "Lio/github/suterxom/luau/internal/jni/NativePauseInfo;)V");
    if (constructor == nullptr)
    {
        env->DeleteLocalRef(resultClass);
        return nullptr;
    }

    jbyteArray messageBytes = message.empty() ? nullptr : newByteArray(env, message);
    if (!message.empty() && messageBytes == nullptr)
        return nullptr;
    jobject pauseInfo = pause == nullptr ? nullptr : newNativePauseInfo(env, *pause);
    if (pause != nullptr && pauseInfo == nullptr)
    {
        if (messageBytes != nullptr)
            env->DeleteLocalRef(messageBytes);
        return nullptr;
    }

    jobject result = env->NewObject(
        resultClass,
        constructor,
        static_cast<jint>(status),
        values,
        messageBytes,
        static_cast<jlong>(elapsedNanos),
        static_cast<jlong>(context.interruptChecks),
        static_cast<jlong>(context.currentMemory),
        static_cast<jlong>(context.peakMemory),
        static_cast<jlong>(context.hostCalls),
        static_cast<jlong>(context.hostOutputBytes),
        pauseInfo);
    env->DeleteLocalRef(resultClass);
    if (messageBytes != nullptr)
        env->DeleteLocalRef(messageBytes);
    if (pauseInfo != nullptr)
        env->DeleteLocalRef(pauseInfo);
    return result;
}

void prepareExecution(
    ExecutionContext& context,
    uint64_t maxRuntimeNanos,
    uint64_t maxInterruptChecks,
    uint64_t maxHostCalls,
    uint64_t maxHostOutputBytes)
{
    context.interruptChecks = 0;
    context.maxInterruptChecks = maxInterruptChecks;
    context.hostCalls = 0;
    context.maxHostCalls = maxHostCalls;
    context.hostOutputBytes = 0;
    context.maxHostOutputBytes = maxHostOutputBytes;
    context.elapsedNanos = 0;
    context.maxRuntimeNanos = maxRuntimeNanos;
    context.exceededLimit = EXECUTION_COMPLETED;
    context.lastStatus = EXECUTION_COMPLETED;
}

void startExecutionSegment(lua_State* thread, ExecutionContext& context)
{
    lua_callbacks(thread)->interrupt = executionInterrupt;
    context.startedAt = std::chrono::steady_clock::now();
    const uint64_t remaining =
        context.elapsedNanos >= context.maxRuntimeNanos
            ? 0
            : context.maxRuntimeNanos - context.elapsedNanos;
    context.deadline =
        context.startedAt +
        std::chrono::nanoseconds(static_cast<int64_t>(remaining));
}

void finishExecutionSegment(ExecutionContext& context)
{
    const auto finishedAt = std::chrono::steady_clock::now();
    const uint64_t segmentNanos = static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(
            finishedAt - context.startedAt)
            .count());
    context.elapsedNanos =
        segmentNanos > std::numeric_limits<uint64_t>::max() - context.elapsedNanos
            ? std::numeric_limits<uint64_t>::max()
            : context.elapsedNanos + segmentNanos;
    if (context.elapsedNanos >= context.maxRuntimeNanos &&
        context.exceededLimit == EXECUTION_COMPLETED)
        context.exceededLimit = EXECUTION_RUNTIME_LIMIT;
}

std::string executionFailureMessage(
    lua_State* thread,
    int status,
    int executionStatus)
{
    if (status == EXECUTION_RUNTIME_LIMIT)
        return "Luau execution exceeded its maximum runtime";
    if (status == EXECUTION_INTERRUPT_LIMIT)
        return "Luau execution exceeded its interrupt-check budget";
    if (status == EXECUTION_MEMORY_LIMIT)
        return "Luau execution exceeded its VM memory limit";
    if (status == EXECUTION_VALUE_LIMIT)
        return "Luau execution exceeded its value-codec budget";
    if (status == EXECUTION_HOST_CALL_LIMIT)
        return "Luau execution exceeded its host-call budget";
    if (status == EXECUTION_HOST_OUTPUT_LIMIT)
        return "Luau execution exceeded its host-output budget";
    if (executionStatus == LUA_YIELD)
        return "Luau execution cannot yield across the host boundary";
    return luaError(thread);
}

jobject finishExecution(
    JNIEnv* env,
    NativeScriptInstance& instance,
    int executionStatus,
    bool captureExports)
{
    ExecutionContext& context = instance.context;
    lua_State* thread = instance.thread;
    if (executionStatus == LUA_BREAK &&
        context.exceededLimit == EXECUTION_COMPLETED)
    {
        context.lastStatus = EXECUTION_PAUSED;
        if (!instance.pauseSnapshot.has_value())
        {
            context.lastStatus = EXECUTION_RUNTIME_FAILED;
            instance.paused = false;
            instance.activeArrayLengths.clear();
            return newNativeExecutionResult(
                env,
                EXECUTION_RUNTIME_FAILED,
                nullptr,
                "Luau paused without debugger state",
                context.elapsedNanos,
                context);
        }
        return newNativeExecutionResult(
            env,
            EXECUTION_PAUSED,
            nullptr,
            {},
            context.elapsedNanos,
            context,
            &*instance.pauseSnapshot);
    }

    instance.paused = false;
    instance.pauseSnapshot.reset();
    if (executionStatus != LUA_OK ||
        context.exceededLimit != EXECUTION_COMPLETED)
    {
        int status = EXECUTION_RUNTIME_FAILED;
        if (context.exceededLimit != EXECUTION_COMPLETED)
            status = context.exceededLimit;
        else if (executionStatus == LUA_ERRMEM)
            status = EXECUTION_MEMORY_LIMIT;
        context.lastStatus = status;
        instance.activeArrayLengths.clear();
        return newNativeExecutionResult(
            env,
            status,
            nullptr,
            executionFailureMessage(thread, status, executionStatus),
            context.elapsedNanos,
            context);
    }

    if (captureExports)
    {
        if (lua_gettop(thread) != 1 || lua_type(thread, 1) != LUA_TTABLE)
        {
            context.lastStatus = EXECUTION_RUNTIME_FAILED;
            instance.activeArrayLengths.clear();
            return newNativeExecutionResult(
                env,
                EXECUTION_RUNTIME_FAILED,
                nullptr,
                "Root module must return exactly one export table",
                context.elapsedNanos,
                context);
        }
        lua_setreadonly(thread, 1, true);
        instance.exportsRef = lua_ref(thread, 1);
    }

    ValueCodecContext valueContext;
    valueContext.arrayLengths = instance.activeArrayLengths;
    jobjectArray values =
        newNativeValueArray(
            env,
            thread,
            captureExports ? 0 : lua_gettop(thread),
            valueContext);
    if (values == nullptr)
    {
        if (env->ExceptionCheck())
        {
            instance.activeArrayLengths.clear();
            return nullptr;
        }
        context.lastStatus =
            valueContext.limitExceeded ? EXECUTION_VALUE_LIMIT : EXECUTION_VALUE_ERROR;
        instance.activeArrayLengths.clear();
        return newNativeExecutionResult(
            env,
            context.lastStatus,
            nullptr,
            valueContext.error.empty() ? "Failed to encode Luau return values" : valueContext.error,
            context.elapsedNanos,
            context);
    }
    if (env->ExceptionCheck())
    {
        instance.activeArrayLengths.clear();
        return nullptr;
    }
    context.lastStatus = EXECUTION_COMPLETED;
    instance.activeArrayLengths.clear();
    jobject result = newNativeExecutionResult(
        env,
        EXECUTION_COMPLETED,
        values,
        {},
        context.elapsedNanos,
        context);
    env->DeleteLocalRef(values);
    return result;
}

jobject executeChunk(
    JNIEnv* env,
    NativeScriptInstance& instance,
    const std::string& chunk,
    const std::string& bytecode,
    uint64_t maxRuntimeNanos,
    uint64_t maxInterruptChecks,
    uint64_t maxHostCalls,
    uint64_t maxHostOutputBytes,
    bool captureExports = false)
{
    ExecutionContext& context = instance.context;
    lua_State* thread = instance.thread;
    prepareExecution(
        context,
        maxRuntimeNanos,
        maxInterruptChecks,
        maxHostCalls,
        maxHostOutputBytes);

    const int loadStatus =
        luau_load(thread, chunk.c_str(), bytecode.data(), bytecode.size(), 0);
    if (loadStatus != LUA_OK)
    {
        context.lastStatus =
            loadStatus == LUA_ERRMEM ? EXECUTION_MEMORY_LIMIT : EXECUTION_RUNTIME_FAILED;
        return newNativeExecutionResult(
            env,
            context.lastStatus,
            nullptr,
            luaError(thread),
            context.elapsedNanos,
            context);
    }
    rememberModuleClosure(instance, thread, chunk, -1);

    startExecutionSegment(thread, context);
    const int executionStatus = lua_resume(thread, nullptr, 0);
    finishExecutionSegment(context);
    return finishExecution(env, instance, executionStatus, captureExports);
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
    jbyteArray rootModuleNameUtf8,
    jobjectArray moduleNamesUtf8,
    jobjectArray sourcesUtf8)
{
    using namespace suterxom::luau;

    const std::string rootModuleName =
        getUtf8Bytes(env, rootModuleNameUtf8);
    std::unordered_map<std::string, std::string> sources;
    std::vector<std::string> orderedNames;
    if (env->ExceptionCheck() ||
        !readNamedByteArrays(
            env,
            moduleNamesUtf8,
            sourcesUtf8,
            sources,
            orderedNames))
        return nullptr;
    if (sources.find(rootModuleName) == sources.end())
    {
        throwNativeException(env, "Root module is not present in native source set");
        return nullptr;
    }

    InMemorySourceResolver fileResolver(std::move(sources));
    StrictConfigResolver configResolver;
    Luau::FrontendOptions options;
    options.retainFullTypeGraphs = false;
    options.runLintChecks = false;
    options.moduleTimeLimitSec = 2.0;
    options.applyInternalLimitScaling = true;

    Luau::Frontend frontend(&fileResolver, &configResolver, options);
    Luau::registerBuiltinGlobals(frontend, frontend.globals);
    Luau::freeze(frontend.globals.globalTypes);

    struct Diagnostic
    {
        int stage;
        std::string moduleName;
        std::string message;
    };
    std::vector<Diagnostic> diagnostics;
    std::unordered_set<std::string> seenDiagnostics;
    std::vector<std::string> namesToCheck;
    namesToCheck.reserve(orderedNames.size());
    namesToCheck.push_back(rootModuleName);
    for (const std::string& moduleName : orderedNames)
    {
        if (moduleName != rootModuleName)
            namesToCheck.push_back(moduleName);
    }

    for (const std::string& checkedName : namesToCheck)
    {
        Luau::CheckResult checkResult = frontend.check(checkedName);
        for (const Luau::TypeError& error : checkResult.errors)
        {
            const Luau::Location& location = error.location;
            const int stage =
                Luau::get<Luau::SyntaxError>(error) == nullptr ? 1 : 0;
            const std::string moduleName =
                error.moduleName.empty() ? checkedName : error.moduleName;
            const std::string message =
                std::to_string(location.begin.line + 1) + ":" +
                std::to_string(location.begin.column + 1) + ": " +
                Luau::toString(
                    error,
                    Luau::TypeErrorToStringOptions{&fileResolver});
            const std::string key =
                std::to_string(stage) + "\n" + moduleName + "\n" + message;
            if (seenDiagnostics.insert(key).second)
                diagnostics.push_back({stage, moduleName, message});
        }
        for (const Luau::ModuleName& timedOutModule : checkResult.timeoutHits)
        {
            const std::string message =
                "Type checking timed out for module " + timedOutModule;
            const std::string key = "1\n" + timedOutModule + "\n" + message;
            if (seenDiagnostics.insert(key).second)
                diagnostics.push_back({1, timedOutModule, message});
        }
    }

    jclass diagnosticClass = env->FindClass(
        "io/github/suterxom/luau/internal/jni/NativeCompilationDiagnostic");
    if (diagnosticClass == nullptr)
        return nullptr;
    jmethodID constructor =
        env->GetMethodID(diagnosticClass, "<init>", "(I[B[B)V");
    if (constructor == nullptr)
    {
        env->DeleteLocalRef(diagnosticClass);
        return nullptr;
    }
    jobjectArray result =
        env->NewObjectArray(
            static_cast<jsize>(diagnostics.size()),
            diagnosticClass,
            nullptr);
    if (result == nullptr)
    {
        env->DeleteLocalRef(diagnosticClass);
        return nullptr;
    }

    for (size_t index = 0; index < diagnostics.size(); ++index)
    {
        jbyteArray moduleName =
            newByteArray(env, diagnostics[index].moduleName);
        jbyteArray message =
            newByteArray(env, diagnostics[index].message);
        if (moduleName == nullptr || message == nullptr)
        {
            if (moduleName != nullptr)
                env->DeleteLocalRef(moduleName);
            if (message != nullptr)
                env->DeleteLocalRef(message);
            env->DeleteLocalRef(diagnosticClass);
            env->DeleteLocalRef(result);
            return nullptr;
        }
        jobject diagnostic = env->NewObject(
            diagnosticClass,
            constructor,
            static_cast<jint>(diagnostics[index].stage),
            moduleName,
            message);
        env->DeleteLocalRef(moduleName);
        env->DeleteLocalRef(message);
        if (diagnostic == nullptr)
        {
            env->DeleteLocalRef(diagnosticClass);
            env->DeleteLocalRef(result);
            return nullptr;
        }
        env->SetObjectArrayElement(
            result,
            static_cast<jsize>(index),
            diagnostic);
        env->DeleteLocalRef(diagnostic);
        if (env->ExceptionCheck())
        {
            env->DeleteLocalRef(diagnosticClass);
            env->DeleteLocalRef(result);
            return nullptr;
        }
    }
    env->DeleteLocalRef(diagnosticClass);
    return result;
}

jobject executeImpl(
    JNIEnv* env,
    jclass,
    jbyteArray rootModuleNameUtf8,
    jobjectArray moduleNamesUtf8,
    jobjectArray bytecodeArrays,
    jobject hostRegistry,
    jobject hostContext,
    jlong maxRuntimeNanos,
    jlong maxInterruptChecks,
    jlong maxHostCalls,
    jlong maxHostOutputBytes,
    jlong maxMemoryBytes)
{
    using namespace suterxom::luau;

    const std::string rootModuleName =
        getUtf8Bytes(env, rootModuleNameUtf8);
    std::unordered_map<std::string, std::string> moduleBytecodes;
    std::vector<std::string> orderedNames;
    if (env->ExceptionCheck() ||
        !readNamedByteArrays(
            env,
            moduleNamesUtf8,
            bytecodeArrays,
            moduleBytecodes,
            orderedNames))
        return nullptr;
    if (moduleBytecodes.find(rootModuleName) == moduleBytecodes.end())
    {
        throwNativeException(env, "Root module is not present in native bytecode set");
        return nullptr;
    }
    if (hostRegistry == nullptr || hostContext == nullptr)
    {
        throwNativeException(env, "Native host registry and call context must not be null");
        return nullptr;
    }
    if (maxRuntimeNanos <= 0 ||
        maxInterruptChecks <= 0 ||
        maxHostCalls < 0 ||
        maxHostOutputBytes < 0 ||
        maxMemoryBytes <= 0)
    {
        throwNativeException(env, "Native execution limits are invalid");
        return nullptr;
    }

    auto instance = std::make_unique<NativeScriptInstance>(
        env,
        rootModuleName,
        std::move(moduleBytecodes),
        hostRegistry,
        static_cast<size_t>(maxMemoryBytes));
    if (env->ExceptionCheck())
        return nullptr;
    if (!instance->valid())
    {
        instance->context.exceededLimit = EXECUTION_MEMORY_LIMIT;
        return newNativeExecutionResult(
            env,
            EXECUTION_MEMORY_LIMIT,
            nullptr,
            "Failed to create a Luau VM for the compiled program",
            0,
            instance->context);
    }

    const auto rootBytecode =
        instance->moduleBytecodes.find(instance->rootModuleName);
    instance->loadingModules.insert(instance->rootModuleName);
    instance->activeOperation = ActiveOperation::Execute;
    instance->currentHostContext = hostContext;
    jobject result = executeChunk(
        env,
        *instance,
        instance->rootModuleName,
        rootBytecode->second,
        static_cast<uint64_t>(maxRuntimeNanos),
        static_cast<uint64_t>(maxInterruptChecks),
        static_cast<uint64_t>(maxHostCalls),
        static_cast<uint64_t>(maxHostOutputBytes));
    instance->currentHostContext = nullptr;
    instance->loadingModules.erase(instance->rootModuleName);
    instance->activeOperation = ActiveOperation::None;
    return result;
}

jlong createInstanceImpl(
    JNIEnv* env,
    jbyteArray rootModuleNameUtf8,
    jobjectArray moduleNamesUtf8,
    jobjectArray bytecodeArrays,
    jobject hostRegistry,
    jlong maxMemoryBytes)
{
    using namespace suterxom::luau;

    const std::string rootModuleName =
        getUtf8Bytes(env, rootModuleNameUtf8);
    std::unordered_map<std::string, std::string> moduleBytecodes;
    std::vector<std::string> orderedNames;
    if (env->ExceptionCheck() ||
        !readNamedByteArrays(
            env,
            moduleNamesUtf8,
            bytecodeArrays,
            moduleBytecodes,
            orderedNames))
        return 0;
    if (moduleBytecodes.find(rootModuleName) == moduleBytecodes.end())
    {
        throwNativeException(env, "Root module is not present in native bytecode set");
        return 0;
    }
    if (hostRegistry == nullptr)
    {
        throwNativeException(env, "Native host registry must not be null");
        return 0;
    }
    if (maxMemoryBytes <= 0)
    {
        throwNativeException(env, "Native instance memory limit must be positive");
        return 0;
    }

    auto instance = std::make_unique<NativeScriptInstance>(
        env,
        rootModuleName,
        std::move(moduleBytecodes),
        hostRegistry,
        static_cast<size_t>(maxMemoryBytes));
    if (env->ExceptionCheck())
        return 0;
    if (!instance->valid())
    {
        throwNativeException(env, "Failed to create a Luau VM within its memory limit");
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(instance.release()));
}

jobject executeInstanceImpl(
    JNIEnv* env,
    jlong handle,
    jobject hostContext,
    jlong maxRuntimeNanos,
    jlong maxInterruptChecks,
    jlong maxHostCalls,
    jlong maxHostOutputBytes)
{
    using namespace suterxom::luau;

    auto* instance =
        reinterpret_cast<NativeScriptInstance*>(static_cast<intptr_t>(handle));
    if (instance == nullptr)
    {
        throwNativeException(env, "Script instance handle is null");
        return nullptr;
    }
    if (instance->faulted || instance->paused)
    {
        throwNativeException(env, "Script instance cannot execute in its current state");
        return nullptr;
    }
    if (hostContext == nullptr)
    {
        throwNativeException(env, "Native host call context must not be null");
        return nullptr;
    }
    if (maxRuntimeNanos <= 0 ||
        maxInterruptChecks <= 0 ||
        maxHostCalls < 0 ||
        maxHostOutputBytes < 0)
    {
        throwNativeException(env, "Native execution limits are invalid");
        return nullptr;
    }

    lua_resetthread(instance->thread);
    lua_setsafeenv(instance->thread, LUA_GLOBALSINDEX, false);
    const auto rootBytecode =
        instance->moduleBytecodes.find(instance->rootModuleName);
    instance->loadingModules.insert(instance->rootModuleName);
    instance->activeOperation = ActiveOperation::Execute;
    instance->currentHostContext = hostContext;
    jobject result = executeChunk(
        env,
        *instance,
        instance->rootModuleName,
        rootBytecode->second,
        static_cast<uint64_t>(maxRuntimeNanos),
        static_cast<uint64_t>(maxInterruptChecks),
        static_cast<uint64_t>(maxHostCalls),
        static_cast<uint64_t>(maxHostOutputBytes));
    instance->currentHostContext = nullptr;
    if (instance->context.lastStatus != EXECUTION_PAUSED)
    {
        instance->loadingModules.erase(instance->rootModuleName);
        instance->activeOperation = ActiveOperation::None;
    }
    if (result == nullptr ||
        (instance->context.lastStatus != EXECUTION_COMPLETED &&
         instance->context.lastStatus != EXECUTION_PAUSED))
        instance->faulted = true;
    return result;
}

jobject initializeInstanceImpl(
    JNIEnv* env,
    jlong handle,
    jobject hostContext,
    jlong maxRuntimeNanos,
    jlong maxInterruptChecks,
    jlong maxHostCalls,
    jlong maxHostOutputBytes)
{
    using namespace suterxom::luau;

    auto* instance =
        reinterpret_cast<NativeScriptInstance*>(static_cast<intptr_t>(handle));
    if (instance == nullptr ||
        instance->faulted ||
        instance->initialized ||
        instance->paused)
    {
        throwNativeException(env, "Script instance cannot be initialized in its current state");
        return nullptr;
    }
    if (hostContext == nullptr)
    {
        throwNativeException(env, "Native host call context must not be null");
        return nullptr;
    }
    if (maxRuntimeNanos <= 0 ||
        maxInterruptChecks <= 0 ||
        maxHostCalls < 0 ||
        maxHostOutputBytes < 0)
    {
        throwNativeException(env, "Native execution limits are invalid");
        return nullptr;
    }

    lua_resetthread(instance->thread);
    lua_setsafeenv(instance->thread, LUA_GLOBALSINDEX, false);
    const auto rootBytecode =
        instance->moduleBytecodes.find(instance->rootModuleName);
    instance->loadingModules.insert(instance->rootModuleName);
    instance->activeOperation = ActiveOperation::Initialize;
    instance->currentHostContext = hostContext;
    jobject result = executeChunk(
        env,
        *instance,
        instance->rootModuleName,
        rootBytecode->second,
        static_cast<uint64_t>(maxRuntimeNanos),
        static_cast<uint64_t>(maxInterruptChecks),
        static_cast<uint64_t>(maxHostCalls),
        static_cast<uint64_t>(maxHostOutputBytes),
        true);
    instance->currentHostContext = nullptr;
    if (instance->context.lastStatus != EXECUTION_PAUSED)
    {
        instance->loadingModules.erase(instance->rootModuleName);
        instance->activeOperation = ActiveOperation::None;
    }
    if (result == nullptr ||
        (instance->context.lastStatus != EXECUTION_COMPLETED &&
         instance->context.lastStatus != EXECUTION_PAUSED))
        instance->faulted = true;
    else if (instance->context.lastStatus == EXECUTION_COMPLETED)
        instance->initialized = true;
    return result;
}

jobject invokeExportImpl(
    JNIEnv* env,
    jlong handle,
    jbyteArray exportNameUtf8,
    jobjectArray arguments,
    jobject hostContext,
    jlong maxRuntimeNanos,
    jlong maxInterruptChecks,
    jlong maxHostCalls,
    jlong maxHostOutputBytes)
{
    using namespace suterxom::luau;

    auto* instance =
        reinterpret_cast<NativeScriptInstance*>(static_cast<intptr_t>(handle));
    if (instance == nullptr ||
        instance->faulted ||
        !instance->initialized ||
        instance->paused)
    {
        throwNativeException(env, "Script instance cannot invoke exports in its current state");
        return nullptr;
    }
    if (hostContext == nullptr)
    {
        throwNativeException(env, "Native host call context must not be null");
        return nullptr;
    }
    if (maxRuntimeNanos <= 0 ||
        maxInterruptChecks <= 0 ||
        maxHostCalls < 0 ||
        maxHostOutputBytes < 0)
    {
        throwNativeException(env, "Native execution limits are invalid");
        return nullptr;
    }

    const std::string exportName = getUtf8Bytes(env, exportNameUtf8);
    if (env->ExceptionCheck())
        return nullptr;

    ExecutionContext& context = instance->context;
    prepareExecution(
        context,
        static_cast<uint64_t>(maxRuntimeNanos),
        static_cast<uint64_t>(maxInterruptChecks),
        static_cast<uint64_t>(maxHostCalls),
        static_cast<uint64_t>(maxHostOutputBytes));
    instance->activeArrayLengths.clear();
    lua_resetthread(instance->thread);
    lua_setsafeenv(instance->thread, LUA_GLOBALSINDEX, false);
    lua_getref(instance->thread, instance->exportsRef);
    lua_getfield(instance->thread, -1, exportName.c_str());
    lua_remove(instance->thread, -2);
    if (lua_type(instance->thread, -1) != LUA_TFUNCTION)
    {
        instance->context.lastStatus = EXECUTION_RUNTIME_FAILED;
        instance->faulted = true;
        return newNativeExecutionResult(
            env,
            EXECUTION_RUNTIME_FAILED,
            nullptr,
            "Export is missing or is not a function: " + exportName,
            0,
            instance->context);
    }

    const jsize argumentCount = env->GetArrayLength(arguments);
    ValueCodecContext argumentContext(instance->thread);
    for (jsize index = 0; index < argumentCount; ++index)
    {
        jobject argument = env->GetObjectArrayElement(arguments, index);
        if (argument == nullptr ||
            !pushNativeValue(
                env,
                instance->thread,
                argument,
                argumentContext,
                0))
        {
            if (argument != nullptr)
                env->DeleteLocalRef(argument);
            if (!env->ExceptionCheck())
                throwNativeException(
                    env,
                    argumentContext.error.empty()
                        ? "Failed to decode Java arguments"
                        : argumentContext.error);
            return nullptr;
        }
        env->DeleteLocalRef(argument);
    }
    instance->activeArrayLengths = argumentContext.arrayLengths;

    instance->activeOperation = ActiveOperation::InvokeExport;
    instance->currentHostContext = hostContext;
    startExecutionSegment(instance->thread, context);
    const int executionStatus =
        lua_resume(instance->thread, nullptr, argumentCount);
    finishExecutionSegment(context);
    instance->currentHostContext = nullptr;
    jobject result =
        finishExecution(
            env,
            *instance,
            executionStatus,
            false);
    if (context.lastStatus != EXECUTION_PAUSED)
    {
        instance->activeOperation = ActiveOperation::None;
        if (result == nullptr || context.lastStatus != EXECUTION_COMPLETED)
            instance->faulted = true;
    }
    return result;
}

jint setBreakpointImpl(
    JNIEnv* env,
    jlong handle,
    jbyteArray moduleNameUtf8,
    jint line,
    jboolean enabled)
{
    using namespace suterxom::luau;

    auto* instance =
        reinterpret_cast<NativeScriptInstance*>(static_cast<intptr_t>(handle));
    if (instance == nullptr || instance->faulted || instance->paused)
    {
        throwNativeException(env, "Script instance cannot change breakpoints in its current state");
        return -1;
    }
    if (line <= 0)
    {
        throwNativeException(env, "Breakpoint line must be positive");
        return -1;
    }

    const std::string moduleName = getUtf8Bytes(env, moduleNameUtf8);
    if (env->ExceptionCheck())
        return -1;
    if (moduleName.empty() ||
        moduleName.size() > 256 ||
        !isValidUtf8(moduleName) ||
        moduleName.find('\0') != std::string::npos)
    {
        throwNativeException(env, "Breakpoint module name is invalid");
        return -1;
    }
    const auto module = instance->moduleBytecodes.find(moduleName);
    if (module == instance->moduleBytecodes.end())
    {
        throwNativeException(env, "Breakpoint module is not a registered source module");
        return -1;
    }

    int resolvedLine = -1;
    const auto loaded = instance->moduleClosureRefs.find(moduleName);
    if (loaded != instance->moduleClosureRefs.end())
    {
        lua_getref(instance->thread, loaded->second);
        resolvedLine =
            lua_breakpoint(
                instance->thread,
                -1,
                line,
                enabled != 0);
        lua_pop(instance->thread, 1);
    }
    else
    {
        lua_State* scratch = lua_newthread(instance->globalState.get());
        if (scratch == nullptr)
        {
            throwNativeException(env, "Failed to create a breakpoint resolution thread");
            return -1;
        }
        luaL_sandboxthread(scratch);
        lua_setthreaddata(scratch, instance);
        const int loadStatus =
            luau_load(
                scratch,
                moduleName.c_str(),
                module->second.data(),
                module->second.size(),
                0);
        if (loadStatus == LUA_OK)
            resolvedLine = lua_breakpoint(scratch, -1, line, false);
        else
            throwNativeException(
                env,
                "Failed to load source module while resolving breakpoint: " +
                    luaError(scratch));
        lua_pop(instance->globalState.get(), 1);
        if (loadStatus != LUA_OK)
            return -1;
    }

    if (resolvedLine > 0)
    {
        if (enabled != 0)
            instance->breakpoints[moduleName].insert(resolvedLine);
        else if (auto configured = instance->breakpoints.find(moduleName);
                 configured != instance->breakpoints.end())
        {
            configured->second.erase(resolvedLine);
            if (configured->second.empty())
                instance->breakpoints.erase(configured);
        }
    }
    return static_cast<jint>(resolvedLine);
}

jobject resumeInstanceImpl(
    JNIEnv* env,
    jlong handle,
    jobject hostContext)
{
    using namespace suterxom::luau;

    auto* instance =
        reinterpret_cast<NativeScriptInstance*>(static_cast<intptr_t>(handle));
    if (instance == nullptr ||
        instance->faulted ||
        !instance->paused ||
        instance->activeOperation == ActiveOperation::None)
    {
        throwNativeException(env, "Script instance is not in a resumable debugger pause");
        return nullptr;
    }
    if (hostContext == nullptr)
    {
        throwNativeException(env, "Native host call context must not be null");
        return nullptr;
    }

    const ActiveOperation operation = instance->activeOperation;
    instance->paused = false;
    instance->pauseSnapshot.reset();
    instance->skipBreakpointOnce = true;
    instance->currentHostContext = hostContext;
    startExecutionSegment(instance->thread, instance->context);
    const int executionStatus =
        lua_resume(instance->thread, nullptr, 0);
    finishExecutionSegment(instance->context);
    instance->currentHostContext = nullptr;

    jobject result =
        finishExecution(
            env,
            *instance,
            executionStatus,
            operation == ActiveOperation::Initialize);
    if (instance->context.lastStatus != EXECUTION_PAUSED)
    {
        instance->skipBreakpointOnce = false;
        instance->loadingModules.erase(instance->rootModuleName);
        instance->activeOperation = ActiveOperation::None;
        if (result == nullptr ||
            instance->context.lastStatus != EXECUTION_COMPLETED)
            instance->faulted = true;
        else if (operation == ActiveOperation::Initialize)
            instance->initialized = true;
    }
    return result;
}

jobject terminatePausedInstanceImpl(
    JNIEnv* env,
    jlong handle)
{
    using namespace suterxom::luau;

    auto* instance =
        reinterpret_cast<NativeScriptInstance*>(static_cast<intptr_t>(handle));
    if (instance == nullptr ||
        instance->faulted ||
        !instance->paused ||
        instance->activeOperation == ActiveOperation::None)
    {
        throwNativeException(env, "Script instance is not in a terminable debugger pause");
        return nullptr;
    }

    lua_resetthread(instance->thread);
    instance->loadingModules.clear();
    instance->activeArrayLengths.clear();
    instance->pauseSnapshot.reset();
    instance->paused = false;
    instance->skipBreakpointOnce = false;
    instance->activeOperation = ActiveOperation::None;
    instance->currentHostContext = nullptr;
    instance->faulted = true;
    instance->context.lastStatus = EXECUTION_TERMINATED;
    return newNativeExecutionResult(
        env,
        EXECUTION_TERMINATED,
        nullptr,
        "Luau execution was terminated while paused",
        instance->context.elapsedNanos,
        instance->context);
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
Java_io_github_suterxom_luau_internal_jni_NativeBindings_typecheckModules(
    JNIEnv* env,
    jclass bindingClass,
    jbyteArray rootModuleNameUtf8,
    jobjectArray moduleNamesUtf8,
    jobjectArray sourcesUtf8)
{
    using namespace suterxom::luau;
    return guardNativeCall<jobjectArray>(
        env,
        [&]()
        {
            return typecheckImpl(
                env,
                bindingClass,
                rootModuleNameUtf8,
                moduleNamesUtf8,
                sourcesUtf8);
        });
}

JNIEXPORT jobject JNICALL
Java_io_github_suterxom_luau_internal_jni_NativeBindings_execute(
    JNIEnv* env,
    jclass bindingClass,
    jbyteArray rootModuleNameUtf8,
    jobjectArray moduleNamesUtf8,
    jobjectArray bytecodeArrays,
    jobject hostRegistry,
    jobject hostContext,
    jlong maxRuntimeNanos,
    jlong maxInterruptChecks,
    jlong maxHostCalls,
    jlong maxHostOutputBytes,
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
                rootModuleNameUtf8,
                moduleNamesUtf8,
                bytecodeArrays,
                hostRegistry,
                hostContext,
                maxRuntimeNanos,
                maxInterruptChecks,
                maxHostCalls,
                maxHostOutputBytes,
                maxMemoryBytes);
        });
}

JNIEXPORT jlong JNICALL
Java_io_github_suterxom_luau_internal_jni_NativeBindings_createInstance(
    JNIEnv* env,
    jclass,
    jbyteArray rootModuleNameUtf8,
    jobjectArray moduleNamesUtf8,
    jobjectArray bytecodeArrays,
    jobject hostRegistry,
    jlong maxMemoryBytes)
{
    using namespace suterxom::luau;
    try
    {
        return createInstanceImpl(
            env,
            rootModuleNameUtf8,
            moduleNamesUtf8,
            bytecodeArrays,
            hostRegistry,
            maxMemoryBytes);
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
    return 0;
}

JNIEXPORT jobject JNICALL
Java_io_github_suterxom_luau_internal_jni_NativeBindings_executeInstance(
    JNIEnv* env,
    jclass,
    jlong handle,
    jobject hostContext,
    jlong maxRuntimeNanos,
    jlong maxInterruptChecks,
    jlong maxHostCalls,
    jlong maxHostOutputBytes)
{
    using namespace suterxom::luau;
    return guardNativeCall<jobject>(
        env,
        [&]()
        {
            return executeInstanceImpl(
                env,
                handle,
                hostContext,
                maxRuntimeNanos,
                maxInterruptChecks,
                maxHostCalls,
                maxHostOutputBytes);
        });
}

JNIEXPORT jobject JNICALL
Java_io_github_suterxom_luau_internal_jni_NativeBindings_initializeInstance(
    JNIEnv* env,
    jclass,
    jlong handle,
    jobject hostContext,
    jlong maxRuntimeNanos,
    jlong maxInterruptChecks,
    jlong maxHostCalls,
    jlong maxHostOutputBytes)
{
    using namespace suterxom::luau;
    return guardNativeCall<jobject>(
        env,
        [&]()
        {
            return initializeInstanceImpl(
                env,
                handle,
                hostContext,
                maxRuntimeNanos,
                maxInterruptChecks,
                maxHostCalls,
                maxHostOutputBytes);
        });
}

JNIEXPORT jobject JNICALL
Java_io_github_suterxom_luau_internal_jni_NativeBindings_invokeExport(
    JNIEnv* env,
    jclass,
    jlong handle,
    jbyteArray exportNameUtf8,
    jobjectArray arguments,
    jobject hostContext,
    jlong maxRuntimeNanos,
    jlong maxInterruptChecks,
    jlong maxHostCalls,
    jlong maxHostOutputBytes)
{
    using namespace suterxom::luau;
    return guardNativeCall<jobject>(
        env,
        [&]()
        {
            return invokeExportImpl(
                env,
                handle,
                exportNameUtf8,
                arguments,
                hostContext,
                maxRuntimeNanos,
                maxInterruptChecks,
                maxHostCalls,
                maxHostOutputBytes);
        });
}

JNIEXPORT jint JNICALL
Java_io_github_suterxom_luau_internal_jni_NativeBindings_setBreakpoint(
    JNIEnv* env,
    jclass,
    jlong handle,
    jbyteArray moduleNameUtf8,
    jint line,
    jboolean enabled)
{
    using namespace suterxom::luau;
    try
    {
        return setBreakpointImpl(
            env,
            handle,
            moduleNameUtf8,
            line,
            enabled);
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
    return -1;
}

JNIEXPORT jobject JNICALL
Java_io_github_suterxom_luau_internal_jni_NativeBindings_resumeInstance(
    JNIEnv* env,
    jclass,
    jlong handle,
    jobject hostContext)
{
    using namespace suterxom::luau;
    return guardNativeCall<jobject>(
        env,
        [&]()
        {
            return resumeInstanceImpl(
                env,
                handle,
                hostContext);
        });
}

JNIEXPORT jobject JNICALL
Java_io_github_suterxom_luau_internal_jni_NativeBindings_terminatePausedInstance(
    JNIEnv* env,
    jclass,
    jlong handle)
{
    using namespace suterxom::luau;
    return guardNativeCall<jobject>(
        env,
        [&]()
        {
            return terminatePausedInstanceImpl(env, handle);
        });
}

JNIEXPORT void JNICALL
Java_io_github_suterxom_luau_internal_jni_NativeBindings_closeInstance(
    JNIEnv*,
    jclass,
    jlong handle)
{
    using namespace suterxom::luau;
    delete reinterpret_cast<NativeScriptInstance*>(static_cast<intptr_t>(handle));
}
}
