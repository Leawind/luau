# Luau Runtime

Luau Runtime 是一个为 Minecraft 模组提供沙箱化 Luau 编译和执行能力的基础库模组。

项目尚处于早期开发阶段。当前实现包含：

- Fabric、Forge 和 NeoForge 共用的模组元数据。
- 固定版本的官方 Luau C++ 运行时。
- JNI 原生库构建、内嵌、校验和装载。
- 单文件 Luau 编译、沙箱执行和基础安全返回值。
- 执行时间、interrupt 检查次数和 VM 堆内存硬限制。

完整设计见 [`docs/DESIGN.md`](docs/DESIGN.md)。

## 构建

初始化 submodule 后运行：

```bash
git submodule update --init --recursive
./gradlew build
```

普通构建会编译当前平台的 JNI 库并放入 JAR，不会在运行时下载任何文件。
