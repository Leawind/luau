# Luau Runtime

Luau Runtime 是一个为 Minecraft 模组提供沙箱化 Luau 编译和执行能力的基础库模组。

项目尚处于早期开发阶段。当前实现包含：

- Fabric、Forge 和 NeoForge 共用的模组元数据。
- 固定版本的官方 Luau C++ 运行时。
- JNI 原生库构建、内嵌、校验和装载。
- 内存多源码 Luau 程序的严格类型检查、编译和沙箱执行。
- 仅访问已登记模块的受控 `require`、每实例模块缓存和循环依赖检测。
- 带 strict 类型声明、每实例状态和受控 Java 调用上下文的宿主虚拟模块。
- nil、boolean、number、UTF-8 string、buffer、
  数组和字符串键 table 的双向安全编解码。
- 执行时间、interrupt 检查次数、VM 堆内存、宿主调用和宿主输出硬限制。
- 相互隔离的持久 `ScriptInstance`、所有者线程约束和 faulted/reset 生命周期。
- 根模块初始化、只读导出表和带安全参数的导出函数调用。
- 源码断点解析、可恢复暂停、调用栈快照、继续执行和主动终止。

完整设计见 [`docs/DESIGN.md`](docs/DESIGN.md)。

当前调试接口已完成根调用、依赖模块顶层加载和脚本自行创建的 coroutine 的断点闭环。
跨线程调用可以原子地请求在下一个 safepoint 暂停或终止，单步将在后续阶段补齐。

## 构建

初始化 submodule 后运行：

```bash
git submodule update --init --recursive
./gradlew build
```

普通构建会编译当前平台的 JNI 库并放入 JAR，不会在运行时下载任何文件。
