# Luau Runtime Mod 设计文档

## 定位

Luau Runtime Mod 是一个供其他 Minecraft 模组使用的 Luau 运行时基础库模组，模组
ID 为 `luau`。

本项目首先服务于 SuterXOM，但公开 API 不包含设备、网络、物品或 Minecraft 世界等
SuterXOM 专有概念。它的定位类似于 KubeJS 使用的 Rhino：上层模组依赖稳定的 Java
API，而本项目负责语言实现、运行隔离、资源限制和调试能力。

本项目负责：

- Luau 源码的解析、类型检查、编译和执行。
- 创建相互隔离的脚本实例。
- 注册由宿主模组实现的虚拟模块。
- Java 与 Luau 安全值之间的编解码。
- 沙箱、内存限制、执行中断和生命周期管理。
- 断点、暂停、恢复、单步、终止、调用栈和源码位置等调试能力。
- Luau 原生库的跨平台构建、内嵌和装载。

本项目不负责：

- Minecraft 服务端 tick 调度和世界级预算分配。
- 设备事件队列、端口、设备网络和世界交互。
- 脚本源码、编译诊断或设备状态的持久化。
- 验证和提交脚本产生的游戏命令。
- 调试 GUI、调试网络协议和玩家权限。
- 文件系统、网络、进程或任意 Java 类的脚本绑定。
- SuterXOM 的物品令牌和账本。

## 设计原则

### 默认不信任脚本

玩家提供的源码、参数和持久化数据都视为不可信输入。脚本只能使用 Luau 安全标准库
和宿主显式注册的虚拟模块，不得获得 Java 对象、Minecraft 对象、文件路径、网络连接
或其他隐式宿主能力。

### 宿主显式授予能力

宿主模块按照名称显式注册，例如 `@suterxom/api/v1`。编译器和运行时只能解析本次
程序声明的源码模块及宿主模块。未知模块、真实文件路径和动态外部模块加载均被拒绝。

### Java API 稳定，原生实现可替换

Luau 的 C++ 分析接口可能随上游演进。所有上游接口都封装在本项目内部，不得泄漏到
公开 Java API。公开 API 通过 `io.github.suterxom.luau.v1.api` 之类的版本化包维护
兼容性。

### 失败应可分类和恢复

语法错误、类型错误、运行时错误、宿主错误、资源超限、主动终止和原生库装载失败必须
具有不同的结构化结果。普通脚本错误不应只表现为日志或没有类型的 Java 异常。

### 调试不得阻塞服务器

命中断点时，当前 Luau coroutine 进入可恢复的暂停状态，执行调用立即返回 Java。
暂停状态可以跨 Minecraft tick 保存，但不得占用服务器线程，也不得影响其他脚本
实例。

## 技术方案

### Luau 实现

项目直接使用官方 `luau-lang/luau` C++ 实现，并固定到一个明确的上游 commit。固定
版本和 commit 应写入构建信息及最终 JAR 的清单，以便复现构建和排查兼容性问题。

第一阶段静态链接以下 Luau 组件：

- VM 和安全标准库。
- Compiler 和 Bytecode。
- Ast、Analysis 和 Config。

第一版只使用 Luau 字节码解释器，不启用 native code generation 或 JIT。这样可以
减少可执行内存、安全审计和平台差异方面的复杂度。将来若引入原生代码生成，应作为
单独的设计变更处理，不能悄然改变默认执行模式。

Java 与 C++ 之间使用 JNI。JNI 层只提供本项目 Java 实现所需的窄接口，不将 Luau
指针、栈索引或原始 C++ 对象暴露为公开 Java API。

### Minecraft 加载器兼容

运行时实现不引用 Minecraft 类或加载器 API。最终 JAR 同时包含：

- `fabric.mod.json`
- `META-INF/mods.toml`
- `META-INF/neoforge.mods.toml`

Fabric、Forge 和 NeoForge 使用同一个构建产物。模组不需要加载器专用入口点，公开
API 第一次被调用时才初始化原生运行时。由于没有 Minecraft 源码兼容层，本项目不使用
Stonecutter，也不为每个 Minecraft 版本分别复制源码或生成 JAR。

初始基线为 Java 17 和 Minecraft 1.20.1 及以上。提高 Java 或 Minecraft 最低版本
属于兼容性变更，必须在发布说明中明确记录。

### 包结构

所有 Java 源码和 Java 类型均位于 `io.github.suterxom.luau` 根包下，不使用其他
顶级 Java 包。建议的逻辑结构为：

```text
io.github.suterxom.luau
├── v1.api
│   ├── compile
│   ├── debug
│   ├── module
│   ├── runtime
│   └── value
└── internal
    ├── jni
    ├── loader
    └── runtime
```

`v1.api` 是稳定的公开 API；`internal` 中的类型不属于兼容性承诺。公开 API 不引用
`internal` 类型。

C++ 源码使用 `suterxom::luau` 命名空间，并按 bridge、runtime 和 analysis 划分。
JNI 导出只对应 `io.github.suterxom.luau.internal.jni` 中的类。

## 编译与类型检查

### 程序模型

一次编译请求由以下内容组成：

- 唯一的根模块名。
- 一个或多个以内存字符串提供的 Luau 源码模块。
- 本次程序允许使用的宿主虚拟模块。
- 类型检查模式和编译限制。

模块名是逻辑标识符，不是文件路径。运行时不根据模块名访问文件系统。宿主可以自行从
任何存储后端读取源码，但交给本项目后必须是完整的内存数据。

编译流程为：

1. 校验模块名、源码数量、单模块大小和总源码大小。
2. 构建只包含已登记模块的内存 `FileResolver` 和 `ConfigResolver`。
3. 加载宿主模块的 Luau 类型声明。
4. 解析根模块和所有可达依赖。
5. 在 strict 模式下执行类型检查和 lint。
6. 仅在没有阻断性诊断时编译可信 bytecode。
7. 返回诊断和不可变的已编译程序。

类型检查和编译可以在服务器工作线程执行，不要求在 Minecraft 服务器线程执行。
`LuauRuntime.compile` 应当是线程安全的；每次请求使用独立的分析上下文，或使用不会
在请求间泄漏源码和类型环境的受控池。

### 诊断

`CompilationResult` 返回零个或多个 `Diagnostic`。诊断至少包含：

- 阶段：parse、type、lint 或 compile。
- 严重度：error、warning 或 information。
- 稳定的本项目错误代码。
- 模块名。
- 起止行列。
- 面向用户的中文或英文无关原始消息。

Luau 上游诊断字符串可以变化，因此调用者不应解析消息文本。稳定控制流只能依赖阶段、
严重度和本项目错误代码。

普通语法错误和类型错误通过 `CompilationResult` 返回，不抛 Java 异常。参数违反
Java API 契约、运行时关闭或原生环境损坏等宿主错误才使用 Java 异常。

### 已编译程序

`CompiledProgram` 是不可变的 `AutoCloseable` 对象。它保存：

- 各模块的可信 bytecode。
- 模块依赖关系。
- 源码标识和调试行号信息。
- 编译时使用的宿主模块接口版本。
- Luau 上游版本和本项目 bytecode 格式版本。

公开 API 不返回可修改的原始 bytecode。运行时只执行由当前兼容编译器生成或经过内部
完整性验证的 bytecode。第一版不支持把已编译程序跨服务器重启持久化；上层模组应保存
源码和诊断，并在需要时重新编译。

## 宿主虚拟模块

### 模块定义

一个 `HostModule` 同时定义：

- 完整且带版本的模块名。
- 用于类型检查的 Luau 声明。
- 运行时导出表的 factory。
- 模块接口版本或身份标识。

类型声明和运行时导出必须由宿主共同维护。运行时验证导出名称、函数签名所需的基本
约束，但 Luau 类型声明是否准确最终由注册模块的宿主负责。

公开 API 使用 `HostModule(name, interfaceVersion, typeDeclaration, factory)` 表示
宿主模块。`typeDeclaration` 是一个只参与类型检查的 strict Luau 模块体，例如：

```luau
return (nil :: any) :: {
    send: (string) -> boolean,
    receive: () -> buffer?,
}
```

该声明不会编译或执行。`factory` 为每个 `ScriptInstance` 创建一份独立的
`Map<String, HostFunction>`；无状态的 `CompiledChunk.execute` 每次执行也创建一份
新的导出。编译过程本身不调用 factory。

每个脚本实例只获得创建它时明确传入的模块集合。注册表中存在某个模块，不代表所有
脚本都自动获得该模块。

### `require`

沙箱提供本项目实现的 `require`：

- 参数必须是字符串模块名。
- 只允许请求当前程序已登记的源码模块或宿主模块。
- 每个脚本实例缓存模块执行结果。
- 循环依赖返回明确的模块错误。
- 不支持相对文件系统查找、环境变量或搜索路径。
- 不支持加载原生动态库。

宿主模块的导出表在构造完成后设为只读。脚本不能替换宿主函数或修改共享标准库。

### 宿主函数

宿主函数只接收：

- 当前调用的受控 `HostCallContext`。
- 已解码的安全参数列表。

宿主函数只返回安全值，或抛出可转换为 Luau 运行时错误的 `HostCallException`。
Java 异常不得穿过 JNI 边界。未预期的 Java 异常在 JNI 层捕获并转换为宿主内部错误，
同时保留供服务端日志使用的原因。

`HostCallContext` 可以让宿主实现访问当前设备和命令缓冲，但这些 Java 对象永远不会
编码或暴露给 Luau。上下文还负责累计宿主调用次数和宿主主动登记的输出成本。
Java 能力使用显式类型键放入上下文，宿主函数通过 `find` 或 `require` 读取；
`recordOutputBytes` 登记会计成本。一个上下文对应一个上层逻辑调用，不应同时交给
多个并发执行共享。

宿主函数必须快速、确定且不阻塞。文件访问、数据库操作或其他可能长时间阻塞的工作
应由上层模组在脚本执行之外调度。

## 安全值

Java 与 Luau 之间只支持以下值：

- `nil`
- `boolean`
- 双精度 `number`
- UTF-8 字符串
- 字节 buffer
- 数组
- 仅以字符串为键的表

公开 Java 表示使用不可变的 sealed `LuauValue` 层次。数组、表和 buffer 在构造时
防御性复制，公开访问时不得泄漏可修改的内部存储。

以下值不得跨越 Java/Luau 边界：

- Luau 函数。
- coroutine 或 thread。
- userdata 和 light userdata。
- metatable。
- 任意 Java 对象或类。
- 包含循环引用的数组或表。
- 使用非字符串键的表。

每次编解码都受最大深度、节点数、字符串字节数、buffer 字节数和总字节数限制。超出
限制产生结构化的编码错误或资源超限结果，不允许递归到 JVM 或原生栈溢出。

SuterXOM 的 16 字节物品令牌可用 buffer 表示，但固定长度、不可变性、权限和账本
语义由 SuterXOM API 验证，不属于本项目通用值层。

## 脚本实例

### 隔离方式

每个 `ScriptInstance` 拥有独立的 `lua_State`、allocator、全局环境和模块缓存。不同
脚本实例不共享可变 Luau 对象。

不使用同一个 VM 中的内存分类来模拟设备隔离，原因是：

- 独立 allocator 才能可靠执行每实例硬内存上限。
- 关闭或重置一个实例不会影响其他实例。
- 暂停的调试会话不会占用其他实例的执行状态。
- 一个脚本无法通过共享 registry 或对象引用污染其他脚本。

标准库代码可以来自同一个原生二进制，但每个 VM 的可变运行状态相互独立。

### 初始化和导出

创建实例后，运行时在受预算控制的初始化调用中执行根模块。根模块应返回一个只供宿主
调用的导出表。上层模组可以按名称调用导出的 Luau 函数，例如 SuterXOM 将设备事件名
映射为处理函数名。

建议的 Java 调用形式为：

```java
ExecutionResult result =
    instance.invokeExport("onMessage", arguments, hostContext, budget);
```

运行时本身不解释 `onMessage` 的业务含义。

一个实例同一时刻只允许一个初始化或导出调用。调用尚未结束或处于调试暂停状态时，再次
调用其他导出应立即失败。事件排队由上层模组负责。

### 执行结果

`ExecutionResult` 使用封闭的结构化类型，至少包含：

- `Completed`：正常完成及返回值。
- `Paused`：命中断点、单步或主动暂停。
- `RuntimeFailed`：Luau 或宿主函数运行时错误。
- `LimitExceeded`：内存、时间、中断检查、宿主调用或编解码预算超限。
- `Terminated`：由宿主或调试器主动终止。

每个结果包含适用的执行指标：

- 本次及当前事件累计运行时间。
- interrupt/safepoint 检查次数。
- 当前和峰值 VM 内存。
- 宿主函数调用次数。
- 输入、返回和宿主登记的输出大小。

运行时错误、资源超限和主动终止都会终止当前 coroutine。为避免继续使用已经部分修改
的 Lua 全局状态，实例随后进入 faulted 状态；宿主必须调用 `reset` 或重新创建实例后
才能处理下一个事件。`reset` 使用原有 `CompiledProgram` 建立一个全新的 VM。

## 沙箱

创建 VM 时必须：

- 只打开 Luau 的安全标准库。
- 对标准库和共享 metatable 使用 `luaL_sandbox`。
- 对脚本线程使用 `luaL_sandboxthread`。
- 将所有自定义宿主表设为只读。
- 使用 Luau 的安全 environment 优化。
- 安装中断、调试、内存和线程生命周期回调。
- 对暴露给脚本的指针信息进行编码或完全隐藏。

本项目不集成 Lute。Lute 面向通用程序的文件系统、HTTP、socket 和进程能力与本项目
默认不信任玩家脚本的边界不一致。

即使 Luau 标准库本身是安全的，宿主模块仍可能破坏沙箱。因此所有宿主函数都必须遵守：

- 不返回 userdata 或 Java 对象句柄。
- 不提供反射、类加载或任意方法调用。
- 在访问上层状态前执行上层权限验证。
- 不在单次调用中执行无界循环或阻塞 I/O。
- 将可产生世界影响的操作写入上层命令缓冲，而不是直接修改世界。

## 资源限制

### 实例级限制

`InstanceConfig` 至少包含：

- VM 最大堆内存。
- 最大 Luau 调用栈或等价的递归限制。
- 最大模块数量。
- 最大导出数量。
- 默认值编解码限制。

每个 VM 使用自定义 allocator 追踪当前和峰值分配。新分配会使实例超过硬上限时，
allocator 拒绝分配，运行时将其转换为内存超限结果。内存统计不得只依赖 Java 堆或
定期采样。

### 调用级限制

`ExecutionBudget` 至少包含：

- 最大实际运行时间。
- 最大 interrupt/safepoint 检查次数。
- 最大宿主函数调用次数。
- 最大输入、返回和宿主输出大小。

Luau 的 interrupt 回调保证在循环回边、调用、返回和 GC 等 safepoint 最终被调用，
但它不提供跨版本稳定的精确字节码指令计数。因此公开 API 使用“中断检查预算”，不承诺
精确的 Luau 指令数。

运行时间只累计 VM 实际运行的时间；调试暂停期间不计时。同一事件在多次暂停和恢复之间
累计使用同一个总预算，不能通过反复继续执行重置预算。

宿主函数自身的耗时包含在运行时间内。对于可能长时间运行的单次原生宿主函数，中断
safepoint 无法介入，因此公开 API 要求宿主函数不得阻塞，并在文档中明确这一限制。

世界级预算、设备优先级和每 tick 可执行多少实例由 SuterXOM 调度器负责。本项目只
执行传入的单实例调用预算。

## 调试

### 调试能力

`ScriptDebugger` 至少提供：

- 设置、删除、启用和禁用源码断点。
- 请求在下一个可中断位置暂停。
- 继续执行。
- 单步进入。
- 单步跳过。
- 单步跳出。
- 终止当前调用。
- 查看暂停原因、当前源码位置和 Luau 调用栈。

第一版不提供任意局部变量、upvalue 或对象图遍历。这些能力可能泄漏宿主实现细节，并
显著扩大安全和值编解码边界。

当前已完成所有 Luau coroutine 上的断点设置、删除、清空、暂停、恢复、终止和跨
coroutine 调用栈快照，且保留原宿主上下文与累计预算。依赖模块顶层加载中的断点也会
通过可继续的 `require` 边界传播。`requestPause` 和 `requestTerminate` 使用原子
请求与受保护的 native handle，可由非所有者线程调用。step-in、step-over 和
step-out 均已按逻辑 Luau 调用栈深度实现，并能跨 coroutine 边界工作。

### 断点

加载模块时，原生层登记 chunk、closure 和源码行映射。设置断点时使用 Luau
`lua_breakpoint`，并将请求行解析为该函数中可执行的实际行。Java API 返回解析后的
位置，使客户端能够显示断点是否有效以及实际命中的行。

命中 `debugbreak` 回调时：

1. 记录暂停原因和当前 `lua_Debug` 信息。
2. 调用 `lua_break` 将 coroutine 置为 `LUA_BREAK`。
3. 从当前 JNI 执行调用返回 `Paused`。
4. 保留 VM、coroutine、预算累计值和当前宿主上下文。

暂停时仍未结束当前事件。SuterXOM 的命令缓冲继续由 SuterXOM 持有且不得提交。
继续并正常完成后才可提交；终止、错误或超限时由 SuterXOM 丢弃。

### 单步

单步期间临时启用 `lua_singlestep`：

- step-in 在下一处不同的可显示源码位置暂停。
- step-over 记录初始栈深和源码位置，在同层新位置或返回到更浅层时暂停。
- step-out 在调用栈低于初始深度后暂停。

完成一次单步后立即关闭逐指令回调，避免正常执行长期承担调试开销。原生/C 宿主函数
内部不可单步；单步会在其返回 Luau 后继续。

### 并发控制

脚本实例具有所有者线程。创建、调用、检查暂停状态、恢复、单步、重置和关闭都必须在
所有者线程执行。

`requestPause` 和 `requestTerminate` 可以从其他线程调用，但只设置原子请求标志。
真正的 VM 操作在所有者线程的 interrupt 回调中发生。调试网络处理线程不得直接读取
或修改 Luau 栈。

## 原生库内嵌与装载

### 支持平台

第一版内嵌：

- Windows x86-64。
- Linux glibc x86-64。
- Linux glibc AArch64。
- macOS x86-64。
- macOS ARM64。

暂不支持 musl Linux、Windows ARM64、Android 和其他平台。支持矩阵属于发布产物
契约；新增平台不改变 Java API。

### JAR 布局

所有支持平台所需文件直接放入最终 JAR，例如：

```text
META-INF/luau/natives/
├── linux-aarch64/libluau_jni.so
├── linux-x86_64/libluau_jni.so
├── macos-aarch64/libluau_jni.dylib
├── macos-x86_64/libluau_jni.dylib
└── windows-x86_64/luau_jni.dll
```

JAR 还包含记录文件路径、大小、SHA-256、目标平台、JNI ABI 版本和 Luau commit 的
原生清单。

运行时绝不从互联网或 Maven 下载原生库、辅助程序或其他平台文件。缺少对应平台文件
时立即返回明确的 `UnsupportedPlatformException`，其中包含检测到的 OS、架构和当前
支持列表。

### 装载

JVM 不能直接从 JAR 内部 `System.load` 动态库，因此装载器会把当前平台的内嵌文件
提取到本地缓存或临时目录。这里的“提取”只复制 JAR 中已有文件，不进行网络访问。

装载流程为：

1. 将 `os.name` 和 `os.arch` 规范化为内部平台 ID。
2. 从内嵌清单选择唯一匹配的原生文件。
3. 读取资源并校验 SHA-256。
4. 使用包含模组版本和内容散列的目录及文件名提取。
5. 使用文件锁和原子移动处理多个 ClassLoader 或进程的并发提取。
6. 对磁盘文件再次校验 SHA-256。
7. 调用 `System.load`。
8. 执行 Java API、JNI ABI 和 native 实现版本握手。

清单校验失败、文件被替换或 ABI 不匹配时禁止继续执行。装载器在同一 ClassLoader 中
只初始化一次，并缓存成功结果或失败原因。

## 生命周期和资源释放

以下类型实现 `AutoCloseable`：

- `CompiledProgram`
- `ScriptInstance`
- 需要持有原生分析资源的其他公开句柄

`close` 必须幂等。关闭后调用操作方法会抛出明确的 `IllegalStateException`。Java
实现可以使用 `Cleaner` 作为遗漏关闭时的最后保障，但不能依赖 GC 及时释放 VM。

JNI 层集中管理原生 handle：

- handle 带类型和代际信息，避免释放后误用。
- Java callback 使用的 global reference 在实例关闭时全部释放。
- C++ 异常统一捕获并转换为 Java 内部异常。
- Java 异常统一捕获并转换为 Luau 宿主错误。
- 不允许 C++ 异常或 Luau long jump 越过 JNI 边界。

Minecraft 服务器停止时，上层模组负责关闭所有实例；运行时同时提供关闭时的泄漏检测
日志，列出仍未释放的实例数量，但不得在 JVM shutdown hook 中访问 Minecraft 世界。

## 版本与兼容性

本项目同时维护三种版本：

- 模组版本：面向发布和依赖声明。
- Java API 版本：体现在 `v1.api` 包路径中。
- JNI ABI 版本：Java 实现与内嵌原生库启动时握手。

Luau 上游 commit 不是公开 Java API 版本，但必须可以查询。更新 Luau 时必须运行完整
的编译、沙箱、资源限制和调试回归测试。

`v1.api` 中已经发布的类型和语义遵循向后兼容原则。破坏性 Java API 变更使用新的
版本包；仅内部 JNI 和 C++ 重构不改变 Java API 版本。

第一阶段不发布 Maven artifact。SuterXOM 开发时通过本地复合构建、直接项目依赖或
手动提供模组 JAR 使用本项目。将来是否发布 Maven 及其坐标另行决定，当前 Gradle
配置不创建远程 Maven 发布任务。

## 构建

项目使用 Gradle 构建 Java 和最终 JAR，使用 CMake 构建 C++。Gradle 提供统一任务：

- 编译和测试当前平台 native。
- 编译 Java。
- 运行 Java/JNI 集成测试。
- 校验模组元数据和原生清单。
- 汇总各平台 CI artifact。
- 生成包含全部平台文件的最终通用 JAR。

本地普通 `build` 可以只构建和测试当前平台，但生成“可发布 JAR”的任务必须检查五个
目标平台文件全部存在。缺少任何目标时任务失败，避免误发布仅包含构建机平台的 JAR。

构建产物包含：

- 通用模组 JAR。
- sources JAR。
- Luau 及其他第三方许可证。
- 原生文件和校验和清单。
- 构建版本、Git commit 和 Luau commit 信息。

即使暂不发布 Maven，也保留 sources JAR，便于 IDE 查看 API 源码和 GitHub Release
分发。

## CI 与发布

CI 参考 SystemStorageLib，分为普通构建和 tag 发布，但原生部分使用平台矩阵。

普通构建工作流在以下情况触发：

- 推送到 `main`。
- 针对 `main` 的 pull request。

普通构建至少执行：

- Java 单元测试和静态检查。
- Linux JNI 单元与集成测试。
- Windows、Linux 和 macOS 的原生矩阵构建。
- 各平台最小 JNI smoke test。
- 汇总后验证通用 JAR 包含全部目标平台文件。

发布工作流只由维护者手动推送符合 `v*` 规则的 Git tag 触发。普通分支推送、pull
request 和普通手动 workflow dispatch 均不得发布。

tag 发布流程为：

1. 校验 tag 与项目版本一致。
2. 从同一个 tag commit 构建全部平台 native artifact。
3. 在汇总 job 中生成通用 JAR 和 sources JAR。
4. 运行原生清单、许可证和 JAR 内容校验。
5. 创建 GitHub Release 并上传产物。

第一阶段不发布到 Maven、Modrinth 或 CurseForge。将来由维护者编写专门发布脚本时，
脚本消费已经验证的通用 JAR，不改变本设计规定的构建和安全边界。

## 测试

### 编译测试

- 正确源码可以通过 strict 类型检查并生成程序。
- 语法、类型、lint 和编译诊断具有正确模块与位置。
- 未登记模块、循环依赖和非法模块名被拒绝。
- 类型声明与虚拟模块可以被根模块正确引用。
- 源码数量、长度、分析时间和复杂度限制有效。

### 值与宿主 API 测试

- 所有安全值可以 Java → Luau → Java 往返。
- UTF-8、空 buffer、嵌套数组和表保持语义。
- 非字符串键、函数、thread、userdata 和循环表被拒绝。
- 深度、节点数和总字节限制在边界处生效。
- 宿主正常错误和未预期 Java 异常都不会穿过 JNI。
- 宿主调用计数和输出成本准确累计。

### 沙箱与限制测试

- 脚本不能访问文件、网络、进程、Java 类和外部模块。
- 一个实例不能读取或修改另一个实例的全局变量。
- 无限循环被时间或中断检查预算终止。
- 大量分配在硬内存上限处失败，且不影响其他 VM。
- 深递归和恶意嵌套值不会造成 JVM 或原生栈崩溃。
- 错误、超限和终止后实例进入 faulted 状态。
- reset 后得到没有旧可变状态的新 VM。

### 调试测试

- 断点解析到正确模块和可执行行。
- 命中断点后 Java 调用返回且服务器线程不被占用。
- 暂停状态可跨任意数量 tick 保存并继续。
- step-in、step-over 和 step-out 正确处理嵌套与递归调用。
- 主动暂停和终止从其他线程发起时不直接操作 VM。
- 暂停后继续正常完成仍保持累计预算。
- 暂停后终止产生 `Terminated`，不再执行宿主 callback。
- 一个实例暂停时其他实例仍可独立运行。

### 原生和平台测试

- 五个目标平台都能完成装载、版本握手、编译和执行 smoke test。
- 不支持的平台得到明确异常且不尝试联网。
- 内嵌资源和提取文件的散列不一致时拒绝装载。
- 重复初始化、并发提取和重复关闭不会泄漏或崩溃。
- Debug native 构建运行 AddressSanitizer 和 UndefinedBehaviorSanitizer。
- 模块解析、bytecode 边界和安全值 codec 逐步加入 fuzz 测试。

### 模组测试

- 同一个 JAR 可被 Fabric、Forge 和 NeoForge 识别。
- 专用服务器环境不加载客户端类。
- 无其他模组调用 API 时不会提前提取或加载 native。
- 代表性的 Minecraft/加载器版本可以启动并调用最小运行时示例。

## 实施顺序

### 构建与 JNI 骨架

- 建立 Gradle Wrapper、CMake、Luau 固定依赖和许可证。
- 添加三种加载器元数据。
- 完成当前平台原生构建、资源提取和版本握手。
- 建立普通 CI 和跨平台 native artifact 矩阵。

### 编译与类型检查

- 实现内存源码和虚拟模块 resolver。
- 实现 strict 类型检查、lint 和统一诊断。
- 实现可信 bytecode 与 `CompiledProgram` 生命周期。
- 增加编译时间、大小和复杂度限制。

### VM 与宿主模块

- 实现每实例独立 VM 和完整沙箱初始化。
- 实现根模块、源码模块和宿主模块加载。
- 实现安全值 codec、宿主函数和导出调用。
- 完成正常结果、运行时错误和资源释放。

### 资源限制

- 实现硬内存 allocator。
- 实现运行时间、中断检查和宿主调用预算。
- 实现 faulted、reset 和跨 JNI 异常转换。
- 加入恶意脚本和边界测试。

### 调试

- 实现源码映射、断点、暂停、恢复和调用栈。
- 实现单步进入、跳过、跳出和终止。
- 验证暂停跨 tick、预算累计和线程归属。

### 通用产物与发布

- 完成五个平台原生构建。
- 汇总并校验内嵌全部平台文件的通用 JAR。
- 添加 tag 驱动的 GitHub Release 工作流。
- 使用最小宿主示例验证 SuterXOM 所需的完整调用链。

## 第一版验收条件

第一版可以认为具备接入 SuterXOM 的条件，当且仅当：

- Fabric、Forge、NeoForge 使用同一个包含全部目标 native 的 JAR。
- 严格类型检查可以识别 `@suterxom/api/v1` 的类型声明。
- 两个脚本实例之间不存在可观察的可变状态共享。
- 无限循环和内存爆炸都能被硬限制终止而不影响服务器进程。
- Luau 只能调用明确授予的宿主模块，不能获得任意 Java 或系统能力。
- 宿主可以调用根模块导出函数并获得结构化结果。
- 断点暂停立即返回 Java，之后可以跨 tick 继续、单步或终止。
- 所有失败路径都能确定当前事件不可提交，并使实例进入明确状态。
- CI 能从 tag 对应的同一 commit 构建并发布可验证的 GitHub Release 产物。
