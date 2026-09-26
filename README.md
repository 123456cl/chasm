# Chasm 2.0

面向 **Fabric 1.21.1** 的**玩法模组开发框架**，**零 Mixin**。

存在的理由：Forge / NeoForge 上成熟的玩法模组（内容层、数据层、装备与词缀、复杂 GUI）
迁到 Fabric 往往要重写。Chasm 把这些能力做成框架内的一等公民，
让玩法逻辑**搬过来**而不是**重新发明**——注册语义、数据驱动、界面声明都按"可迁移"设计。

## 模块

| 模块 | 说明 |
|---|---|
| `chasm-core` | 框架本体。262 个源文件 / 44 个测试套件，覆盖注册表、数据与 DataGen、网络与状态同步、属性·效果·附魔·伤害、掉落与配方、多方块、物品·方块·方块实体模板、工具与材料、随机数、日志、安全校验，以及**声明式 GUI**（`ui = f(state)`）。 |
| `chasm-example` | 示例模组：框架每个能力的最小可运行用法，也是回归测试的宿主。 |

> 第三方玩法模组的适配模块**在取得原作者授权前不包含在本仓库中**（其数据/资源属于第三方）。
> 见 `docs/README.md`。`settings.gradle` 会按目录是否存在自动决定是否构建它们。

## 环境要求

- **JDK 21+**（本仓库用 JDK 25 编译，字节码目标 21）
- Gradle Wrapper（随仓库附带，无需另装）
- 可访问 `maven.fabricmc.net` 与 `mavenCentral()`

## 构建

```powershell
cd C:\path\to\chasm2
.\gradlew.bat build          # 全部模块 + 全部测试
.\gradlew.bat :chasm-core:build
```

产物：`<module>/build/libs/<module>-<version>.jar`（版本号取自 `gradle.properties` 的 `mod_version`）。

## 运行

```powershell
.\gradlew.bat :chasm-example:runClient    # 开发客户端
.\gradlew.bat :chasm-example:runServer
```

## 文档

| 文件 | 内容 |
|---|---|
| [`CodeWiki.md`](CodeWiki.md) | **开发者手册**：API 与完整类型、性能与缓存规则、逐特性用法、构建与发布。**先看这个。** |
| [`docs/扩展点.md`](docs/扩展点.md) | 扩展点（SPI）速查：接入位置、注册顺序、多模组共存约定。 |
| [`docs/README.md`](docs/README.md) | 文档索引（区分对外手册与内部笔记）。 |

## 设计约定（摘要）

- **零 Mixin**：全部通过 Fabric 官方 API、注册表与事件接入。
- **声明式 GUI**：界面 = 状态的函数 `ui = f(state)`，节点列表顺序即层级与命中优先级；
  存在即可见即命中。见手册第 0 章。
- **数据驱动**：内容尽量写成数据文件 + DataGen，而不是硬编码。
- **性能**：禁止在界面构建期做全表扫描或翻译查询；按"输入标识 + 数据版本"缓存，加短 TTL。

## 授权

本仓库**未附开源许可证**，默认保留所有权利；如需使用请联系作者。

仓库与产物中**不包含**任何第三方模组的美术资源、模型、音效或数据文件。
