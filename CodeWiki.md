# Chasm 2.0 开发者手册

> **适用版本**：Chasm core **1.2.0**（`gradle.properties: mod_version`）· Minecraft **1.21.1** / Fabric Loader 0.19.3 / Fabric API 0.116.15+1.21.1 / Java 21
> **更新**：2026-09-26
> **读者**：用 Chasm 开发 Fabric **玩法**模组的作者。本文签名与行为均核对自当前源码；标「已知限制」的是刻意保留或尚未实现的能力。
> **三条设计底线**：**零 Mixin**（不注入任何原版类）、**声明式优先**（`ui = f(state)`）、**数据驱动优先**（能写 JSON 就不写代码）。
> 第三方模组的适配/排错过程记录在内部文件 `docs/内部-移植笔记.md`，**不属于本手册**（本手册到第 25 章结束）。

---

## 目录

0. [**本版速览（1.2.0）**：常用 API、类型与性能规则](#0-本版速览120常用-api类型与性能规则)
1. [项目是什么](#1-项目是什么)
2. [快速开始：5 分钟跑通一个 Chasm 模组](#2-快速开始)
3. [核心心智模型与代码地图](#3-核心心智模型与代码地图)
4. [物品 Item](#4-物品-item)
5. [物品类型系统 ItemType](#5-物品类型系统-itemtype)
6. [数据组件 Data Component](#6-数据组件-data-component)
7. [行为特质 Trait](#7-行为特质-trait)
8. [方块 Block](#8-方块-block)
9. [合成配方 Recipe](#9-合成配方-recipe)
10. [伤害类型与伤害计算](#10-伤害类型与伤害计算)
11. [属性 Attribute](#11-属性-attribute)
12. [自定义附魔 Enchantment（数据驱动）](#12-自定义附魔-enchantment)
13. [玩家持久化变量 PlayerVar](#13-玩家持久化变量-playervar)
14. [声明式 GUI](#14-声明式-gui)
15. [自定义按键 Custom Key](#15-自定义按键-custom-key)
16. [JSON 软编码（物品 / GUI）](#16-json-软编码)
17. [日志系统](#17-日志系统)
18. [DataGen 自动化](#18-datagen-自动化)
19. [跨模组扩展（SPI）](#19-跨模组扩展-spi)
20. [测试](#20-测试)
21. [已知限制与注意事项（当前代码核实）](#21-已知限制与注意事项)
22. [版本变更记录（2026-09 重构说明）](#22-版本变更记录)
23. [玩法模板子系统（第十四步）](#23-玩法模板子系统)
24. [附录](#24-附录)
25. [移植与排错记录（摘要）](#25-移植与排错记录摘要)——完整记录在内部文件 `docs/内部-移植笔记.md`，不随产品分发

---

## 0. 本版速览（1.2.0）：常用 API、类型与性能规则

> 只想最快写出来的人看这一章就够；细节在后面的分章。

### 0.1 门面入口（`api.chasm.Chasm`，全 static）

| 方法 | 返回类型 | 用途 |
|---|---|---|
| `Chasm.item(String id)` | `ItemBuilder` | 声明物品 |
| `Chasm.block(String id)` | `BlockBuilder` | 声明方块 |
| `Chasm.gui(String id)` | `GuiBuilder` | 声明界面（几何 + 节点源） |
| `Chasm.recipe(...)` / `Chasm.shaped(...)` | `RecipeBuilder` | 声明配方 |
| `Chasm.register(Object holder)` / `ChasmRegistrar.scanContent(Class)` | `void` / `int` | 扫描 `@Register` 静态字段（内容容器） |

### 0.2 声明式界面的核心契约（一句话）

`ui = f(state)`：**每一帧**由状态重算**一张节点列表**；列表顺序 = z 序 = 命中优先级；**存在 ≡ 可见 ≡ 可命中**。
因此「哪一页在画」必须由**状态**保证互斥，不能靠几何重叠去猜。

### 0.3 `GuiNode` 能力全表（1.2.0）

| 分类 | 方法（返回 `GuiNode`，可链式） | 说明 |
|---|---|---|
| 建节点 | `GuiNode.of(key,x,y,w,h)` · `sprite(key,x,y,w,h,texture,u,v,tw,th)` · `fill(key,x,y,w,h,argb)` | 文本 / 贴图 / 色块 |
| 文本 | `.text(String text,int color)` · `.textOutlined(String text,int color)` | 后者为 **8 向黑描边**（真机 `GuiStringOutline` 语义） |
| 交互 | `.action(String id,int value)` · `.hover(int color)` · `.enabled(boolean)` · `.hoverWhenDisabled(boolean)` | 最后一项 = 不能点但仍能悬停/出提示 |
| 提示 | `.tooltip(String)` · `.tooltipLazy(Supplier<String>)` · `.tooltipWhen(String key,String)` · `.tooltipWhenLazy(String key,Supplier<String>)` | 键条件（如 `GuiNode.SHIFT_KEY`）在**渲染时**求值；惰性版把文本构造推迟到**悬停那一刻** |
| 动画 | `.delay(double)` · `.slideIn(int dx,int dy,int ms)` · `.alphaTimeline(tag,double from,long delay,long[] frames,double[] targets)` · `.opacityWhenHovered(float)` / `.opacityWhenHovered(float,String regionKey)` | `regionKey` = 悬停判定挂在**别的**节点上（真机页签：悬停按钮本体 → 标签淡入） |
| 图层 | `.layer(GuiNode.Layer)` · `.behind()` | `BEHIND` 画在物品之前 |

## 0.4 性能规则（每条都有实测依据）

1. **界面源每帧会被调用**：`build(GuiState)` 里**不要**做全表扫描、不要每次拼 `Component.translatable(...)`。
   → 文本用 `.tooltipLazy(...)`；全表查询按数据 `revision()` 缓存成 O(1) 查表。
2. **框架自带签名缓存**：`ChasmMenu.signature()` 覆盖「全部数据键 + 全部槽位物品身份」，签名不变就复用上一帧的节点列表（**最多 250ms 强制重建一次**）。
   → 界面源**只允许依赖菜单状态**；依赖菜单之外的东西（数据包重载 / 语言切换 / 玩家经验…）最坏滞后 250ms。
3. **实测（同一台机器，93~98 节点的加工台列表页）**：构建耗时 **3,433 µs → 807 µs**；其中枚举特殊蓝图的 `specialsFor` **462,062 ns → ~1,000 ns**；惰性 tooltip 让属性条那 53 条提示不再每帧构造。
4. **缓存纪律模板**：键 = `(输入身份, 数据 revision)` + 一个短 TTL 兜底；不要只按可变对象引用缓存。

### 0.5 构建 / 发布

```powershell
.\gradlew.bat :chasm-example:runClient        # 开发：进游戏
.\gradlew.bat :chasm-core:test                # 框架单测
.\gradlew.bat build                            # 全量（含测试）→ 各模块 build/libs/*.jar
.\gradlew.bat :chasm-core:publishToMavenLocal  # 发到本地 maven 仓库
```

### 0.6 最快的三条路径（按"少写代码"排序）

| 路径 | 写法 | 适合 |
|---|---|---|
| **① JSON 软编码** | 把物品/界面写成 `data/<modid>/chasm/items/*.json`、`chasm/gui/*.json` 之类的数据文件（见第 16 章） | 数值/文案频繁调整、想让整合包作者改 |
| **② 内容容器** | 一个类 `implements ChasmContentHolder`，把一组内容写成 `@Register` 静态字段，`ChasmRegistrar.scanContent(MyContent.class)` 一次注册 | 一组相关物品/方块/配方 |
| **③ 链式声明** | `Chasm.item("foo").trait(...).component(...).recipe(...)`（见第 4/7/9 章） | 需要代码行为（回调、条件、动画） |

三条可以混用：**同一个模组里**数据文件提供默认值，代码只在需要行为时补。

### 0.7 测试基建（不用开游戏）

框架的声明式部件（`GuiNode` 构建、注册表、数据加载、属性折叠、存档读写…）都能在**纯 JVM 单测**里跑：
`SharedConstants.tryDetectVersion()` → `Bootstrap.bootStrap()` → 注册测试数据 → 直接调你的 `build(GuiState)` / 注册函数断言。
`chasm-core/src/test` 里有现成范式；这是"改一行就能验"的最快回路（比开客户端快一个数量级）。

---

## 0.8 本手册的写法约定

- 所有行为都标了**源码出处**（`文件:行号`）或**实测数字**；没有依据的猜测不写进来。
- 「已知限制」= 框架刻意不做或尚未做的事，写在对应章节里，不藏。
- 示例默认放在 `chasm-example`（modId=`mymod`），可以直接 `runClient` 看到效果。

---
## 1. 项目是什么

Chasm（`api.chasm`）是一套 **"玩法优先"的 Minecraft Fabric 模组开发 API**，把"声明一个物品/方块 → 组合玩法行为 → 绑定数据 → 自动注册 → 自动生成资源"做成一行行链式声明，目标是**消灭样板代码与手写 JSON**，同时保留原版语义（横扫、附魔池、挖掘等级、剥皮/耕地、容器界面、按键、存档持久化…）。

### 1.1 模块结构

| 模块 | 内容 | 规模（当前） |
|---|---|---|
| `chasm-core` | API 库本体（`api.chasm.*`：`item` / `item.context` / `block` / `blockentity` / `gui(.decl)` / `advancement` / `effect` / `particle` / `loot` / `net` / `spi` / `template` / `memo` / `multiblock` …） | 见 `chasm-core/src/main/java`（随版本变化，不在此硬编码） |
| `chasm-core/src/test` | 纯 JVM 单元测试（JUnit 5，不需要开游戏） | 同上 |
| `chasm-example` | 演示模组（`com.example.chasm`，modId=`mymod`）：魔法武器/食物/护甲套装/自定义附魔/类型/模板/GUI/按键/JSON 软编码 | 3 个文件 ≈ 572 行 |

### 1.2 技术要点

- 依赖：`gradle.properties` 定义 `minecraft_version=1.21.1`、`loader_version=0.19.3`、`loom_version=1.17-SNAPSHOT`、`fabric_api_version=0.116.15+1.21.1`、`mod_version=1.2.0`（三个模块共用这一个值）。
- `chasm-example` 通过 `modImplementation project(':chasm-core')` 依赖 core（`chasm-example/build.gradle:47`），并在 `fabric.mod.json` 声明 `"chasm-core": "*"`。
- **零 Mixin**：框架不注入任何原版类；附魔台/附魔池集成完全依赖 1.21.1 数据驱动标签。core 的 `fabric.mod.json`：`main`(`api.chasm.ChasmInit`)；`client`(`ChasmGuiClient`、`ChasmKeyClient`、`PlayerVarClient`)。
- 构建命令：
  - `./gradlew :chasm-example:build`（build 会自动先跑 `runDatagen` 再编译）
  - `./gradlew :chasm-example:runClient` / `runServer` / `runDatagen`
  - `./gradlew :chasm-core:test`（跑 P0 单测）
  - `./gradlew :chasm-core:publishToMavenLocal`

### 1.3 改完代码怎么在游戏里看到（**必读，最容易踩的坑**）

| 改了什么 | 命令顺序 |
|---|---|
| 只改下游模块（`chasm-example` / 移植模块） | `gradlew :<模块>:build` → 清 `.gradle\loom-cache\remapped_mods` 下对应 jar → 启动客户端 |
| 改了 **chasm-core** | 先编 core，再清 loom remap 缓存，最后编依赖方：`gradlew :chasm-core:remapJar --rerun-tasks` → 删 `.gradle/loom-cache/remapped_mods/remapped/api/chasm/chasm-core-<hash>` → `gradlew :<模块>:build` |
| 想一步到位（推荐） | `powershell -ExecutionPolicy Bypass -File refresh-dev.ps1`（停守护进程 → 清 loom-cache → 重建） |

**为什么**：loom 的项目依赖读的是**已重映射的 jar**，普通 `jar` 任务只写 `build/devlibs/*-dev.jar`；
改动 core 后不让 loom 重新 remap，客户端加载的还是旧类 —— 表现就是「代码明明改了，游戏里没变化」。
另外：**绝不要删** `chasm-core/build/libs/chasm-core-<版本>.jar`，`:apoth-port` 在**配置阶段**就会读它。

---

## 2. 快速开始

### 2.1 工程骨架

一个 Chasm 模组 = 1 个 `@ChasmMod` 入口类 + 若干 `@Register` 静态字段 + `onInitialize` 里扫描一次：

```java
package com.example.mymod;

import api.chasm.Chasm;
import api.chasm.ChasmMod;
import api.chasm.data.DataComponent;
import api.chasm.item.ItemBuilder;
import api.chasm.registry.ChasmRegistrar;
import api.chasm.registry.Register;
import net.fabricmc.api.ModInitializer;
import net.minecraft.world.item.Item;

@ChasmMod(id = "mymod")                                  // ★ modId 命名空间
public class MyMod implements ModInitializer {

    // —— 数据组件（record + 注解，Codec 自动生成）——
    @DataComponent("mana")
    public static final ManaData MANA = ManaData.EMPTY;

    // —— 物品：声明即注册（id = mymod:mana_sword）——
    @Register("mana_sword")
    public static final Item MANA_SWORD = Chasm.item()
        .type(ChasmTypes.SWORD)          // 成为真正的 SwordItem（横扫/剑类附魔池）
        .maxStackSize(1)
        .durability(1200)
        .attackDamage(10.0f)             // 总攻击伤害（自动换算原版修饰符）
        .name("Mana Sword")              // DataGen 自动生成语言文件
        .texture("minecraft:item/diamond_sword")  // DataGen 自动生成模型
        .register();                     // 只构建实例；真正注册由 scan 完成
        // 给 Record 组件绑默认值见 6.3 节：.component(ManaData.class, new ManaData(50, 50))

    @Override
    public void onInitialize() {
        ChasmRegistrar.scan(this.getClass());   // ★ 扫描本类全部 @Register / @DataComponent
        Chasm.itemLoader().loadAll();           // 可选：JSON 软编码物品
    }

    public record ManaData(int current, int max) {
        public static final ManaData EMPTY = new ManaData(0, 0);
    }
}
```

> 注意：上面 `.component(...)` 一行仅示意占位——给 Record 组件绑默认值请用
> `.component(ManaData.class, new ManaData(50, 50))`（延迟解析重载），不要传 null。
> 完整可运行写法见第 4 章示例。

### 2.2 需要什么

- **注册表冻结规则**：`ChasmRegistrar.scan` 必须在 `onInitialize`（或更早静态初始化）内执行——原版注册表在 `onInitialize` 结束后冻结。
- **客户端/服务端**：声明代码（物品/数据/特质/按键/伤害类型…）是共享安全的，不要触碰客户端类；客户端专属初始化放在 `ClientModInitializer`。
- **DataGen**：接入 `ExampleDataGen` 同样写法，入口类实现 `DataGeneratorEntrypoint` 并调用 `ChasmDataGen.generate(generator, MyMod.class)`。

---

## 3. 核心心智模型与代码地图

### 3.1 一句话心智模型

> **声明（`@ChasmMod`/`@Register`/`@DataComponent`）→ 行为（Builder）→ 数据（Codec）→ 注册（自动）→ 暴露（SPI 可扩展）→ 资源（DataGen 自动生成）**

### 3.2 门面入口（`api.chasm.Chasm`，全 static）

| 入口 | 返回 | 说明 |
|---|---|---|
| `Chasm.item()` | `ItemBuilder` | 声明物品 |
| `Chasm.block()` | `BlockBuilder` | 声明方块 |
| `Chasm.recipe(String id)` | `RecipeBuilder` | 声明合成配方（DataGen 出 json） |
| `Chasm.gui(modId, name)` | `ChasmGuiBuilder` | 声明式界面 |
| `Chasm.keys()` | `ChasmKeys` | 自定义按键注册表 |
| `Chasm.traits()` | `ChasmTraits` | 行为特质注册表（use/attack/inventoryTick） |
| `Chasm.data()` | `ChasmData` | 数据组件类型安全读写 |
| `Chasm.playerVar(modId, name, codec)` | `PlayerVar.Builder` | 玩家持久化变量 |
| `Chasm.damageTypes()` | `ChasmDamageTypes` | 伤害类型注册表 |
| `Chasm.attributes()` | `ChasmAttributes` | 属性注册表 |
| `Chasm.types()` | `ChasmTypes` | 物品类型注册表 |
| `Chasm.enchantments()` | `ChasmEnchantments` | 自定义附魔注册表 |
| `Chasm.itemLoader()` / `Chasm.guiLoader()` | 加载器单例 | JSON 软编码加载 |
| `Chasm.templates()` | `ChasmTemplates` | 玩法模板插件层：weapon / food / armorSet（第十四步） |
| `Chasm.mod(modId)` / `Chasm.mods()` | `ModContext` / 集合 | 按 modId 隔离的上下文（SPI 扩展点） |

### 3.3 代码地图（chasm-core）

| 包 | 内容 |
|---|---|
| `api.chasm` | 门面 `Chasm`、注解 `@ChasmMod`、core 入口 `ChasmInit` |
| `registry` | `ChasmRegistrar`（注解扫描）、`ChasmRegistration`（极简注册）、`@Register` |
| `context` | `ChasmContextRegistry`（mod 上下文多例）、`ModContext`（聚合根） |
| `item` | `ItemBuilder`、`ChasmItemBehavior`（行为枢纽）、8 个物品子类（含 `ChasmArmorItem`/`ChasmFoodItem`）、`ItemType`/`ChasmTypes`、`ChasmItemController`、`ChasmKeyBinding`（按键句柄） |
| `item/context` | 行为回调上下文：`UseContext`/`AttackContext`/`InventoryTickContext`/`KeyContext`（★ 已从 item/key 抽出，专用于打破包环） |
| `trait` | `ChasmTraits` 注册表 + `UseTrait`/`AttackTrait`/`InventoryTickTrait`/`ItemTraitEvent` |
| `data` | `ChasmData`、`ChasmCodec`（record 自动 Codec）、`ChasmCodecs`、`@DataComponent` |
| `block` | `ChasmBlock`、`BlockBuilder`、`ChasmBlockController`、`LootBuilder`/`LootDeclaration`、`BlockUseContext` |
| `recipe` | `RecipeBuilder`/`RecipeDeclaration` |
| `damage` | `ChasmDamageTypes`、`ChasmDamageTypeBuilder`、`DamageTypeInfo`、`DamageSourcesResolver`、`DamageContext` |
| `attribute` | `ChasmAttributes`、`AttributeInfo` |
| `enchantment` | `ChasmEnchantments`、`EnchantmentDeclaration`、`EnchantmentConfig` |
| `gui`（+`gui/client`） | `ChasmGuiBuilder`/`ChasmGui`/`ChasmMenu`/`ChasmGuiChannel`/`ChasmGuiRegistry`/`ChasmGuiActions`；客户端 `ChasmGuiClient`/`ChasmScreen` |
| `key`（+`key/client`） | `ChasmKeys`、`ChasmKeyChannel`、`KeyPressC2SPayload`；客户端 `ChasmKeyClient` |
| `player`（+`player/client`） | `PlayerVar`（含多人 S2C 同步）、`PlayerVarChannel`、`PlayerVarSyncS2CPayload`；客户端 `PlayerVarClient` |
| `template` | `ChasmTemplates`、`WeaponTemplate`/`FoodTemplate`/`ArmorSetSpec`/`ArmorTickContext`、`ChasmKillEvents`/`ChasmKillRewards`/`KillReward`/`KillContext`（第十四步） |
| `loader` | `ChasmItemLoader`/`ChasmGuiLoader`/`ChasmResources`（JSON 软编码） |
| `log` | `ChasmLogger`/`ChasmFileLogger`/`AsyncLogWriter` |
| `net` | `RateLimiter`（服务端网络限流，GUI/按键通道共用） |
| `datagen` | `ChasmDataGen`（9 个 Provider + 覆写机制） |

### 3.4 生命周期

- **运行时**：`ChasmRegistrar.scan(class)` 读 `@Register` 字段 → 按类型路由注册进原版注册表（`BuiltInRegistries.ITEM/BLOCK/MENU/DATA_COMPONENT_TYPE/ATTRIBUTE…`）→ 暴露到 `ModContext`。
- **DataGen 期**：`ChasmDataGen.generate` 内部调用 `scan(modClass, false, false)` **只收集声明不注册**（注册表已冻结），随后 9 个 Provider 根据各 `ModContext` 输出 JSON 到 `src/main/generated`。

---

## 4. 物品 Item

### 4.1 ItemBuilder 完整方法表

创建：`Chasm.item()`。`register()` 只**构建**物品实例（注册交给 `@Register` 扫描），返回 `Item`（实际为绑定的类型子类）。

| 方法 | 语义 |
|---|---|
| `type(ItemType)` | 绑定物品类型（见第 5 章）；null = 普通物品 |
| `tier(Tier)` | 指定原版 Tier（仅工具类类型生效；缺省用类型内置默认铁级） |
| `maxStackSize(int)` | 堆叠上限，默认 64 |
| `durability(int)` | 耐久，默认无限 |
| `attackDamage(float total)` | **总**攻击伤害。自动写主手修饰符 = `total − BASE`（BASE = 原版 `ATTACK_DAMAGE` 默认值，1.21.1 为 2.0）。`total == BASE` 时不加修饰符。例：`attackDamage(10)` → 修饰符 +8 |
| `attackSpeed(float total)` | 总攻击速度，修饰符 = `total − 4.0`。例：0.5 → −3.5 |
| `armor/armorToughness/movementSpeed/knockbackResistance(float)` | 便捷属性（见第 11 章槽位组语义） |
| `attribute(Attribute, amount, slot)` 等 3 个重载 | 任意属性修饰符（自动 id `chasm:item_<attr>_<n>`，`ADD_VALUE`） |
| `name(String)` | 显示名（DataGen 语言文件） |
| `texture(String)` | 贴图路径（DataGen 模型），如 `minecraft:item/diamond_sword` |
| `onRightClick(Consumer<UseContext>)` | 右键行为（可多个，按声明顺序执行） |
| `onAttack(Consumer<AttackContext>)` | 攻击行为（可多个） |
| `component(DataComponentType<T>, T)` | 默认数据组件（写进 `Item.Properties`） |
| `component(Class<T> recordClass, T value)` | **延迟绑定** record 组件默认值（解决静态初始化时序，运行时 `getDefaultInstance` 才解析） |
| `useTrait(id) / useTrait(modId, name)` | 绑定 USE 特质（见第 7 章） |
| `attackTrait(...)` | 绑定 ATTACK 特质 |
| `inventoryTrait(...)` | 绑定 INVENTORY_TICK 特质（持续效果） |
| `onKeyPress(ChasmKeyBinding, Consumer<KeyContext>) / onKeyPress(ResourceLocation, ...)` | 绑定自定义按键（见第 15 章） |
| `enchant(Holder/ResourceKey/EnchantmentDeclaration, level)` | 物品自带附魔（写 `ENCHANTMENTS` 组件；注册键运行时解析） |
| `enchantable(key/decl, minLevel, maxLevel, weight[, treasureOnly])` | 把附魔加进**本物品所绑类型的附魔池**（必须先 `.type(...)`） |
| `food(FoodTemplate)` / `food(nutrition, saturation)` | 绑定食物属性（类型自动为 FOOD） |
| `killReward(ResourceLocation rewardId)` / `killReward(KillReward)` | 绑定击杀奖励（写入内部组件，AFTER_DEATH 触发，见第 23 章） |
| `template(WeaponTemplate)` | 应用武器模板（伤害/攻速/命中/击杀掉落一次到位） |
| `armor(ArmorSetSpec, ArmorItem.Type)` / `armor(Holder<ArmorMaterial>, ArmorItem.Type)` | 护甲部位（类型自动为 ARMOR；set 版还绑定套装 id，见第 23 章） |
| `register()` | 构建并返回 `Item` |

### 4.2 属性换算与"覆盖而非累加"

- `register()` 会把「类型默认属性模板 + 物品自身声明」合并，**同 (属性+槽位+运算) 时物品覆盖类型默认**（丢弃类型项），再统一写入 `DataComponents.ATTRIBUTE_MODIFIERS`。因此工具提示、整合包、附魔/重铸系统都能读到真实属性。
- 攻击伤害面板 = 原版基础 2.0 + 修饰符；`ChasmSwordItem` 等子类还暴露 `getAttackDamage()` 供特质计算差额。

### 4.3 六个内置物品类（都委托共享的 `ChasmItemBehavior`）

| 类 | 原版父类 | 用途 |
|---|---|---|
| `ChasmItem` | `Item` | 普通物品（`NONE` 类型） |
| `ChasmSwordItem` | `SwordItem` | 横扫 / 剑类附魔池 |
| `ChasmPickaxeItem` | `PickaxeItem` | 挖掘等级 |
| `ChasmAxeItem` | `AxeItem` | 剥皮（useOn 原版） |
| `ChasmShovelItem` | `ShovelItem` | 铺路（useOn 原版） |
| `ChasmHoeItem` | `HoeItem` | 耕地（useOn 原版） |

五个工具/剑子类都完整覆写并委托：`use`、`hurtEnemy`、`inventoryTick`、`getDefaultInstance`、`appendHoverText`。**右键/物品栏/攻击特质与回调在工具物品上都有效。**

> ⚠️ **真实边界（务必阅读）**：工具/剑子类**刻意不覆写 `useOn`**，以保留剥皮/铺路/耕地等原版右键行为。因此右键**指向一个该工具可作用的方块**（如斧→原木）时，原版 `useOn` 会返回成功并**短路 `use()`**——绑定的 `useTrait`/`onRightClick` **不会触发**；右键空中或不可作用方块才进入 `use()`。需要"点击方块也触发玩法"时请改用方块事件（第 8 章）或特质只做服务端结算。

### 4.4 行为上下文（`api.chasm.item.context`，回调参数）

**UseContext**（右键/use）：`level()/player()/hand()/stack()`、`sendMessage(String)`（仅服务端）、`serverOnly(Runnable)`、`clientOnly(Runnable)`、`swingArm()`、`consumeItem()/consumeItem(int)`、`damageItem(int)`（返回是否仍可用）。

**AttackContext**（攻击/hurtEnemy）：`stack()/target()/attacker()/level()/isServer()`、`serverOnly`、`sendMessage`（仅服务端且攻击者为玩家）、`damageItem(int)`。

**InventoryTickContext**（物品栏 tick）：`level()/player()/stack()/slotId()/selected()`、`isServer()`、`sendMessage`、`serverOnly`、`clientOnly`。

**KeyContext**（record）：`(Level, Player, ItemStack, ResourceLocation keyId)`，服务端主线程执行。

> 惯例：**数值结算（扣法力、伤害、改存档）一律放 `serverOnly`**；客户端回调只做表现。

### 4.5 完整示例

```java
// 字段声明顺序：数据组件/特质/附魔等要先于引用它们的物品
@DataComponent("mana")
public static final ManaData MANA = ManaData.EMPTY;             // new ManaData(50,50)

public static final ItemType WAND = Chasm.types()
    .register("mymod", "wand", ctx -> new ChasmItem(ctx.behavior(), ctx.properties()))
    .tag("mymod:magic").build();

@Register("arcane_wand")
public static final Item ARCANE_WAND = Chasm.item()
    .type(WAND)
    .maxStackSize(1).durability(300)
    .name("Arcane Wand").texture("minecraft:item/blaze_rod")
    .component(ManaData.class, new ManaData(50, 50))           // 延迟绑定默认组件
    .useTrait("mymod", "mana_use")                             // 绑定特质
    .onKeyPress(MAGIC_KEY, ctx -> ctx.player().sendSystemMessage(Component.literal("按下自定义键！")))
    .register();
```

---

## 5. 物品类型系统 ItemType

**类型 ≠ 继承哪个类**，而是一份可复用模板：原版类映射工厂 + 默认属性 + 自动标签 + 专属附魔池。

### 5.1 内置类型（`ChasmTypes` 静态字段）

| 常量 | id | 实例化类 | 默认标签 | 默认属性（主手） |
|---|---|---|---|---|
| `NONE` | `chasm:none` | `ChasmItem` | — | — |
| `FOOD` | `chasm:food` | `ChasmFoodItem` | — | — |
| `ARMOR` | `chasm:armor` | `ChasmArmorItem` | — | — |
| `SWORD` | `chasm:sword` | `ChasmSwordItem` | `c:swords`, `minecraft:swords` | 攻 +1.0，攻速 −2.4 |
| `PICKAXE` | `chasm:pickaxe` | `ChasmPickaxeItem` | `c:pickaxes`, `minecraft:pickaxes` | 攻 +1.0，攻速 −2.8 |
| `AXE` | `chasm:axe` | `ChasmAxeItem` | `c:axes`, `minecraft:axes` | 攻 +5.0，攻速 −3.0 |
| `SHOVEL` | `chasm:shovel` | `ChasmShovelItem` | `c:shovels`, `minecraft:shovels` | 攻 +1.5，攻速 −3.0 |
| `HOE` | `chasm:hoe` | `ChasmHoeItem` | `c:hoes`, `minecraft:hoes` | 攻 +0.0，攻速 −3.0 |

内置默认 Tier：`ChasmSwordTier`/`ChasmToolTier`（耐久/速度 ≈ 铁级、附魔等级 15、铁锭修复、攻击加成 0——伤害全交给属性修饰符）。物品可用 `.tier(Tiers.DIAMOND)` 覆盖。

### 5.2 注册自定义类型

```java
// 工厂决定原版类映射：可用内置类、自定义子类、或 ChasmItem 直接包装
Chasm.types().register("mymod", "staff",
        ctx -> new ChasmItem(ctx.behavior(), ctx.properties()))
    .tag("mymod:magic").tag("chasm:staffs")                  // 类型自动标签
    .defaultAttribute(Attributes.ATTACK_DAMAGE.value(), 1.0f) // 默认属性模板
    .enchantment(MANA_VAMPIRE, 1, 3, 10)                     // 专属附魔池（附魔台可刷）
    .build();                                                 // build 即登记进 ChasmTypes
```

ItemType.Builder 方法：`defaultAttribute(a, amount[, slot])`（完整重载支持自定义 modifierId+operation，同 id 覆盖）、`tag/tags(...)`、`enchantment(decl|key, min, max, weight[, treasureOnly])`（treasureOnly=true 只进战利品不进附魔台）、`build()`。

查询：`Chasm.types().get(id)` → `Optional`；`getOrThrow`；`all()`；`get("sword")` 自动补 `chasm:` 前缀。类型 id 形如 `mymod:staff`，绑定 `.type(WAND)` 的普通物品亦可 `@Register("wand_item")` 复用。

---

## 6. 数据组件 Data Component

数据组件（1.21 Data Component）用于给"每个物品栈"挂类型安全的附加数据，自动序列化/同步/存档。

### 6.1 声明方式（三种等价）

1. **`@DataComponent("mana")` record 字段**（最常用，推荐）：

```java
@DataComponent("mana")                                  // id 相对 modId → mymod:mana
public static final ManaData MANA = ManaData.EMPTY;     // 字段值通常是默认实例

public record ManaData(int current, int max) {
    public static final ManaData EMPTY = new ManaData(50, 50);
}
```

扫描器（`ChasmRegistrar.scan`）遇到 `@DataComponent` 字段：`ChasmCodec.codecFor(ManaData.class)` 自动生成 Codec → `ChasmData.register(modId, "mana", ManaData.class, codec)` → 组件类型注册进原版 `DATA_COMPONENT_TYPE`（id `mymod:mana`），并按类建索引。

2. **纯 Codec 组件**（标量，无需 record 类索引）：

```java
public static final DataComponentType<Integer> SOULS =
    ChasmData.register("mymod", "souls", ChasmCodecs.INT);
// 物品里 .component(SOULS, 100)
```

3. **record 自带 CODEC**（完全自定义编解码时）：record 内放 `static final Codec<T> CODEC` 或静态 `codec()` 方法，`ChasmCodec.codecFor` 优先使用。

### 6.2 ChasmCodec 自动编解码规则

- 仅接受 **record** 类型（非 record 抛异常）。
- 支持字段类型：基本类型、String、枚举（按 `name()`）、**嵌套 record**（递归）。
- 编码为「字段名→值」映射；解码反射调用规范构造器。
- **递归深度上限 64**（防止恶意深度嵌套导致 StackOverflowError，超限抛异常/返回 error）。
- 需要自定义时给 record 提供静态 `CODEC` 字段或 `codec()` 方法即可。

### 6.3 类型安全读写（`Chasm.data()`）

```java
ManaData m = Chasm.data().get(stack, ManaData.class);                       // 无则 null
ManaData m2 = Chasm.data().getOrDefault(stack, ManaData.class, ManaData.EMPTY);
Chasm.data().set(stack, ManaData.class, new ManaData(5, 10));
DataComponentType<ManaData> t = Chasm.data().componentType(ManaData.class); // 未注册抛异常
```

> **索引规则（09-05 修复跨模组同名 record 覆盖）**：类索引已改为 `(Class → modId → DataComponentType)` 二级表（`BY_MOD_CLASS`），只对 `register(modId, id, Class, Codec)` 重载建立；纯 Codec 注册（重载 2）读不出类访问器。
> - 单模组注册的类：`get(stack, Class)` 等旧式访问仍可用；
> - **同一 record 类被多个模组注册**：无 modId 访问会抛歧义异常，请用 `Chasm.data().componentType(modId, class)` / `get(stack, modId, class)` / `set(stack, modId, class, v)`，或 `Chasm.mod(modId).data().get/set(...)`（ModDataSpace 已提供类型安全访问器）。

### 6.4 内部组件

框架自己注册了 5 个 `chasm` 命名空间组件：
- `USE_TRAIT_ID / ATTACK_TRAIT_ID / INVENTORY_TICK_TRAIT_ID`（存 `ResourceLocation` 特质 id）；
- `KILL_REWARD_ID`（击杀奖励 id，`ItemBuilder.killReward` 写入，第十四步）；
- `ARMOR_SET_ID`（护甲套装 id，`ItemBuilder.armor(set, type)` 写入，`ChasmArmorItem` 穿戴 tick 统计用）。

**不要占用 `chasm:` 前缀**。


---

## 7. 行为特质 Trait

**解决什么**：高频右键/攻击场景用全局注册的特质替代 List<Consumer> 遍历——物品栈只存一个 `ResourceLocation` 特质 id，运行时从 `ChasmTraits` O(1) 查回执行。

### 7.1 注册特质

```java
// 右键使用特质（ctx 是 UseContext）
public static final ResourceLocation MANA_USE =
    Chasm.traits().registerUse("mymod", "mana_use", ctx -> {
        if (ctx.player() == null) return InteractionResult.PASS;
        ctx.swingArm();                       // 双侧播放动画
        final InteractionResult[] r = { InteractionResult.PASS };
        ctx.serverOnly(() -> {                // 数值结算只在服务端
            PlayerMana.tryConsume(ctx.player(), 5);
            r[0] = InteractionResult.SUCCESS;
        });
        return r[0];
    });

// 攻击特质（ctx 是 AttackContext）
Chasm.traits().registerAttack("mymod", "mana_attack", ctx -> { ... });

// 物品栏 tick 特质（ctx 是 InventoryTickContext，仅 Player 持有者触发）
Chasm.traits().registerInventoryTick("mymod", "crystal_tick", ctx -> { ... });
```

### 7.2 绑定到物品 / 触发顺序

- 绑定：`.useTrait(id)` / `.attackTrait(id)` / `.inventoryTrait(id)`（`register()` 时写入对应内部数据组件）。
- 触发（见 `ChasmItemBehavior`）：**特质优先，回调兜底**。
  - `handleUse`：特质返回 SUCCESS/CONSUME/FAIL/PASS → 分别映射 success/consume/fail/pass 结果；特质异常/未注册 → 记 error 并回退回调列表。
  - `handleAttack`：特质返回 SUCCESS|CONSUME → 返回 true **短路 super.hurtEnemy**（不再执行原版命中副作用/耐久损耗）。
  - `handleInventoryTick`：仅当实体是 Player 才触发。

### 7.3 查询与预留

- `ChasmTraits.INSTANCE.get(ItemTraitEvent.USE, id)` 等（返回特质或 null）；注册返回 `modId:name` 的 id。
- `ItemTraitEvent` 预留 ENTITY_INTERACT / BLOCK_INTERACT / FINISH_USING / CUSTOM_ACTION（注册表骨架已通用支持，但绑定方法/物品覆写尚未接线——目前只能用已上线的三类）。



---

## 8. 方块 Block

### 8.1 BlockBuilder 方法表

创建 `Chasm.block()`；`register()` 返回 `ChasmBlock`（**只 new 不注册**，注册由 `@Register` 字段+扫描完成，BlockItem 自动生成同 id）。

| 方法 | 语义 |
|---|---|
| `strength(destroy, explosion)` | 硬度/爆炸抗性（3,3 ≈ 石头） |
| `requiresPickaxe()/requiresAxe()/requiresShovel()` | 需要对应工具（写入 mineable 标签） |
| `requiresTool(String tag)` | 任意挖掘标签（如 `mymod:mineable/magic`） |
| `sound(SoundType)` | 声音类型 |
| `name/texture` | 显示名/贴图（DataGen 生成语言/模型） |
| `dropsSelf()` | 掉落自身（默认） |
| `drops(item, count)` / `drops(item)` / `dropsRandom(item, min, max)` | 简单掉落 |
| `loot(Consumer<LootBuilder>)` | 多工具多样掉落（与 requires* 互斥） |
| `onUse/onPlace/onBreak/onStep(Consumer<BlockUseContext>)` | 四类事件（可叠加多个） |
| `register()` | 返回 ChasmBlock 实例 |

> requiresTool 与 loot(...) 互斥：前者是"需正确工具才掉落"，后者管理多工具条件掉落；不要同时声明。

### 8.2 多工具掉落（LootBuilder）

```java
Chasm.block()
    .strength(3.0f, 3.0f)
    .name("Mana Crystal").texture("minecraft:block/amethyst_block")
    .loot(l -> l
        .onTool(Items.DIAMOND_PICKAXE, r -> r.dropsSelf())
        .onToolTag("minecraft:mineable/pickaxe", r -> r.dropsRandom(Items.AMETHYST_SHARD, 1, 3))
        .onTool(Items.GOLDEN_HOE, r -> r.chance(0.5f).dropsSelf())
        .otherwise(r -> r.nothing()))
    .onUse(ctx -> ctx.sendMessage("The crystal hums!"))
    .register();
```

掉落规则类型：SELF（自身）/ ITEM（固定数量）/ ITEM_RANDOM（随机区间）/ NOTHING；可带 `chance` 概率。DataGen 的 LootProvider 据此生成 loot table。

### 8.3 ChasmBlock 事件与原版方法映射

| 原版覆写 | 事件 |
|---|---|
| `useWithoutItem`（空手右键） | use 回调（恒返回 sidedSuccess） |
| `setPlacedBy` | place 回调 |
| `playerWillDestroy` | break 回调 |
| `stepOn` | step 回调 |

上下文 `BlockUseContext`：`level()/pos()/player()/state()/hit()/entity()`（entity 回退到 player）、`swingArm()`、`sendMessage(String)`（仅服务端）。player() 在踩踏等事件可为 null；hit() 仅右键事件有值。

---

## 9. 合成配方 Recipe

```java
Chasm.recipe("mana_sword")                       // id 不含命名空间
    .shaped(" D ", " D ", " S ",                 // 图案（三行便捷重载）
        'D', Items.DIAMOND, 'S', Items.STICK)    // (字符, 材料) 成对，材料须 Item/Ingredient
    .result(MANA_SWORD)                          // Item 或 ItemStack
    .register();                                 // void：只写当前 mod 配方空间
```

- 其他重载：`shaped(List<String>, Object...)`、`shaped(String[], Object...)`；`define(char, Object)`；材料只接受 Item 或 Ingredient。
- register() **不注册原版配方**，只记录到 ModContext，由 DataGen 的 RecipeProvider 生成 `data/<ns>/recipe/*.json`（解锁条件自动取第一个材料）。必须在 scan 之后 / onInitialize 中调用（需要当前 mod 上下文）。
- 产物分类当前固定 RecipeCategory.COMBAT。



---

## 10. 伤害类型与伤害计算

### 10.1 声明自定义伤害类型

```java
public static final DamageTypeInfo ARCANE_BURN = Chasm.damageTypes()
    .register("mymod", "arcane_burn", DamageTypeKind.MAGIC)   // kind=来源类别
    .message("was consumed by arcane fire")                   // 死亡消息（需真实 DamageType 才生效）
    .bypassesArmor(true)
    .tag("magic").tag("fire")                                 // 聚合标签（查询用）
    .build();                                                 // 写入 ChasmDamageTypes 内存注册表
```

DamageTypeKind：PLAYER_ATTACK / MOB_ATTACK / MAGIC / FIRE / LIGHTNING / INDIRECT_MAGIC。内置模板 `ChasmDamageTypes.PHYSICAL`、`ChasmDamageTypes.MAGIC`。

### 10.2 生效机制（务必阅读）

- build() 仍**只写内存注册表**（ChasmDamageTypes.INSTANCE），但 **DataGen 已自动把声明落盘为原版 DamageType**（09-05 修复，见第 18 章）：
  - `ChasmDamageTypeProvider` 输出 `data/<ns>/damage_type/<path>.json`（message_id/exhaustion/scaling/effects/death_message_type）；
  - `ChasmDamageTypeTagProvider` 把 `bypassesArmor()/isFire()/LIGHTNING` 语义聚合进原版标签 `minecraft:bypasses_armor / is_fire / is_lightning`（`replace:false` 合并，运行时即被护甲/引燃/雷电机制识别）。
- `DamageSourcesResolver.resolve(sourceEntity, info)`：
  1. **优先真实注册类型**：类型 JSON 落盘并被数据包加载后，动态注册表存在该 Holder → `new DamageSource(registered, source)`——message()/bypassesArmor()/exhaustion() 由原版承担、全部生效；
  2. **否则回退按 kind 调原版便捷源**（playerAttack/mobAttack/indirectMagic/onFire/lightningBolt/generic）——仅 DataGen/未进包场景。
- 需要整体替换某类型 JSON 时用 `ChasmDataGen.overrideFile("<ns>/damage_type/<name>.json", json)`。`tag()` 仅用于 hasTag/聚合查询，不是原版 damage_type 标签。

### 10.3 DamageContext（伤害计算器）

```java
float dealt = new DamageContext(ARCANE_BURN)
    .base(8.0f)                    // 基础伤害
    .multiplier(1.0f)              // 倍率
    .critChance(0.3f, 2.0f)        // 30% 概率 ×2
    .onApply(() -> { /* 音效/粒子等副作用 */ })
    .apply(player, target, level.getRandom());  // 返回名义伤害（护甲等换算后的实际值由原版结算）
```

查询：`expected()`（base×multiplier）、`calculate(random)`、`resolveSource(attacker)`（只解析不施伤）、`type()`。

---

## 11. 属性 Attribute

### 11.1 内置投影常量（ChasmAttributes）

ATTACK_DAMAGE（默认值≈2.0）、ATTACK_SPEED（4.0）、ARMOR（0）、ARMOR_TOUGHNESS（0）、MOVEMENT_SPEED（0.1）、KNOCKBACK_RESISTANCE（0）。每个 AttributeInfo 持有真实原版 Attribute 引用，可用于 Entity.getAttribute。

### 11.2 注册自定义属性（真实写进原版注册表）

```java
AttributeInfo spellPower = Chasm.attributes()
    .registerRanged("mymod", "spell_power", 5.0f, 0.0f, 100.0f);
// 需要注册期钩子（如挂实体属性）用 registerHook(modId, name, def, min, max, consumer)
```

查询：`get(ResourceLocation)` → Optional；`get(name)`（缺省命名空间 minecraft）；`getOrThrow`；`size()`。

物品侧使用：ItemBuilder 的 attribute(...)/便捷方法把修饰符写进 ATTRIBUTE_MODIFIERS 组件（面板/重铸系统可读的真实属性来源）。



---

## 12. 自定义附魔 Enchantment（数据驱动）

1.21.1 附魔是数据驱动动态注册表：必须存在 `data/<ns>/enchantment/*.json` 才会被加载。Chasm 用"声明 + DataGen 自动输出"闭环。

### 12.1 声明

```java
// 静态字段声明（顺序先于引用它的类型/物品）
public static final EnchantmentDeclaration MANA_VAMPIRE =
    Chasm.enchantments().register("mymod", "mana_vampire")
        .weight(10).maxLevel(3).anvilCost(1)
        .minCost(5, 10).maxCost(15, 10)     // (base, perLevelAboveFirst)
        .slot("mainhand")                    // 可 slot/slots(...) 多组
        // .treasure()                       // 可选：宝藏附魔（仅战利品/自带）
        .build();                            // 登记声明并输出日志
```

### 12.2 让附魔生效（三条链路）

1. **DataGen 输出**：自动生成 `data/mymod/enchantment/mana_vampire.json`（description/supported_items/weight/max_level/min_cost/max_cost/anvil_cost/slots）。
2. **附魔台候选**：把附魔加进某物品类型的附魔池——ItemType.Builder 的 `.enchantment(decl, min, max, weight)`，或物品就地 `.enchantable(...)`（需先 .type(...)）。DataGen 再聚合出 supported_items 物品标签（`mymod:enchantable/mana_vampire`）→ 原版附魔台天然可刷出。treasureOnly 成员不进标签。
3. **物品自带**：`.enchant(MANA_VAMPIRE, 1)`（运行时注册表冻结后解析 Holder 写入 ENCHANTMENTS 组件）。

运行时解析附魔等级：`Chasm.enchantments().holderFor(key)` → Optional<Holder.Reference>（服务端启动后可用）。

> **名称/描述（09-05 已修复）**：构建时可 `.name("魔力汲取")` / `.description("...")`（缺省用注册名）；DataGen 语言 Provider 会自动为每个附魔生成 `enchantment.<ns>.<name>` 与 `.desc` 两条语言条目，开箱即可读。声明侧可用 `decl.translationKey()/descTranslationKey()/displayName()/displayDescription()` 读取。

---

## 13. 玩家持久化变量 PlayerVar

基于 Fabric DataAttachment：类型安全、随世界存档持久化、**死亡重生保留**、可选 min/max 自动钳制。**所有读写只在服务端（ServerPlayer）**。

```java
public static final PlayerVar<Integer> MANA =
    Chasm.playerVar("mymod", "mana", ChasmCodecs.INT)
        .defaultValue(50)     // 开局/未初始化值
        .min(0).max(100)      // 写入自动钳制（T 需 Comparable）
        .build();             // build 即注册（没有 register 方法）
```

API：`get`（客户端返回默认值并 WARN）、`getOrSet`、`set`、`modify(UnaryOperator)`、`has`、`clientSafeGet`（客户端读只读缓存、不打 WARN，HUD 用）、`cacheReadOnly`、`receiveSync(CompoundTag)`（数据包接收侧）、`location()/codec()/defaultValue()/min()/max()/isServerOnly/isClientSafe`；静态 `PlayerVar.byId(id)` / `PlayerVar.all()`（全局注册表）。

**多人同步（09-05 已修复，无需手写）**：服务端 `set/modify` 值**实际变化时**经 S2C 包（`PlayerVarSyncS2CPayload`，值用 NBT 包装 `{"v":...}`，任意 Codec 通用）增量推送给该玩家；**玩家加入时全量推送**所有已注册变量（`PlayerVarChannel` 监听 JOIN）。客户端 `api.chasm.player.client.PlayerVarClient` 接收并解码写入只读缓存——`clientSafeGet` 在多人模式 HUD/UI 也能读到实时值。值未变化不落附件也不发包。

---

## 14. 声明式 GUI

### 14.1 声明（一个界面 = 一段链式代码）

```java
public static final ChasmGui MAGIC_BOX = Chasm.gui("mymod", "magic_box")
    .title("魔法盒")
    .size(9, 3)                          // 列 [1,18] × 行 [1,6]
    .slot(0, 0, 2, 2)                    // 左上 2x2 存储区（槽索引 0..3，行主序）
    .button("传送", 5, 0, (player, click) -> player.teleportTo(
        player.getX(), player.getY() + 10.0, player.getZ()))   // 回调在服务端主线程
    .buttonCooldown(20)                  // 冷却 tick（作用于下一个按钮）
    .button("治疗", 7, 0, (p, c) -> p.heal(10.0f))
    .onOpen(ctx -> {                     // 打开即预填（服务端）
        ctx.setItem(0, Items.EMERALD, 5);
        ctx.player().sendSystemMessage(Component.literal("已打开"));
    })
    .onClose(() -> {})
    .register();                         // 返回 ChasmGui；MenuType 注册进原版 MENU
```

打开：`player.openMenu(MAGIC_BOX.provider())`（或 `Chasm.gui("mymod","magic_box").provider()`，未注册会自动 register）。

### 14.2 要点

- 网格单元：未声明任何单元时**整网格默认全是存储槽**；显式声明过的单元按行主序获得连续索引。
- 按钮是"虚拟按钮"（不占容器槽）：客户端命中 → 发 ChasmButtonClickC2SPayload → 服务端校验后主线程回调。
- **服务端校验链**（ChasmGuiChannel）：界面存在 → 玩家确实打开着该界面 → 按钮索引与 label 一致 → **两级限流**（每按钮 250ms + 每玩家全局 50ms，RateLimiter）→ 回调；任一不过静默忽略。**防刷已内置。**
- ChasmMenu.stillValid：`return player == null || player.isAlive()`——客户端副本恒 true；服务端玩家死亡即失效；**不校验距离/维度**（轻量容器设计）。
- 客户端屏幕自动绑定（ChasmGuiClient 在 client 入口遍历注册表 MenuScreens.register）。**若在客户端初始化后才注册 GUI，则该界面无屏幕**——尽量在 main 入口注册。
- 背景贴图：`.background(ResourceLocation)`，32×32 九宫格 8px 边框；缺省灰色面板。
- 命名动作按钮：ChasmGuiActions.register(modId, name, handler) 注册可复用动作（ConcurrentHashMap、同名覆盖），JSON 界面经 buttonAction 引用。

---

## 15. 自定义按键 Custom Key

把交互从左右键扩展到任意键（默认如 O=79、L=76 的 GLFW 码）。

### 15.1 声明 + 绑定

```java
// 静态声明（共享/服务端安全；请直接传键码字面量，避免在服务端加载 GLFW 类）
public static final ChasmKeyBinding MAGIC_KEY =
    Chasm.keys().register("mymod", "magic_blast", 79, "Magic Blast");

@Register("wand")
public static final Item WAND = Chasm.item()
    .onKeyPress(MAGIC_KEY, ctx -> ctx.player().sendSystemMessage(
        Component.literal("魔法弹射出！（服务端）")))
    .register();
```

### 15.2 机制与安全

- **声明侧**（ChasmKeys，api.chasm.key）：只记录 id/键码/显示名；句柄 ChasmKeyBinding 放在 **api.chasm.item** 包（不引用客户端类，服务端可安全链接）。
- **客户端**（ChasmKeyClient，api.chasm.key.client）：为每个声明创建原版 KeyMapping（分类 key.category.chasm，玩家可改键），在 END_CLIENT_TICK 用 consumeClick() 做**上升沿检测**发包（每帧只在下按瞬间发一次）。
- **服务端**（ChasmKeyChannel）校验链：物品非 AIR → 玩家确实在该手持有该物品 → 物品是 ChasmItemSupport 且绑定过该按键 handler → **每玩家×每按键 250ms 限流**（RateLimiter）→ server.execute 主线程回调，异常记日志。
- 显示名翻译：DataGen 自动生成 `key.chasm.<modId>.<name>` 与分类 `key.category.chasm`。



---

## 16. JSON 软编码（物品 / GUI）

**机制**：把 `data/<ns>/chasm/items/*.json` 与 `data/<ns>/chasm/gui/*.json` 放在模组资源里，运行时 `Chasm.itemLoader().loadAll()` / `Chasm.guiLoader().loadAll()`（onInitialize 中）即自动构建并注册——**可以不写 Java 代码加物品/界面**。内部由 ChasmResources 扫描 classpath（目录与 jar 均支持）。

### 16.1 物品 JSON schema（chasm/items/*.json）

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| id | string | ✔ | — | 注册 id（namespace:path） |
| type | string | ✘ | 普通物品 | 类型 id：`chasm:sword/pickaxe/axe/none`…（未命中快捷分支仅 sword/none/pickaxe/axe；shovel/hoe 走注册表查询，注册表存在则命中） |
| tier | string | ✘ | 类型默认 | `minecraft:diamond` 等（Tiers.valueOf，注意大写解析） |
| attack_damage | number | ✘ | 1.0 | 总攻击伤害 |
| attack_speed | number | ✘ | — | 总攻速（内部 −4.0） |
| max_stack / durability | int | ✘ | 64 / 无限 | 堆叠/耐久 |
| name / texture | string | ✘ | — | 显示名 / 贴图 |
| traits | object | ✘ | — | `{ "use": "mymod:id", "attack": "mymod:id", "inventory": "mymod:id" }` |
| enchant | array | ✘ | — | `[{ "id": "minecraft:fire_aspect", "level": 2 }]`（level 缺省 1） |
| components | object | ✘ | — | 组件 id → 值（按已注册 DATA_COMPONENT_TYPE 用 Codec 从 Gson 解码） |

```jsonc
{
  "id": "mymod:flame_sword",
  "type": "chasm:sword",
  "tier": "minecraft:diamond",
  "attack_damage": 12.0,
  "attack_speed": 1.6,
  "name": "Flame Sword",
  "texture": "minecraft:item/diamond_sword",
  "max_stack": 1,
  "durability": 1200,
  "traits": { "attack": "mymod:mana_attack" },
  "enchant": [ { "id": "minecraft:fire_aspect", "level": 2 } ]
}
```

行为：运行时真实注册进 BuiltInRegistries.ITEM（重复 id 跳过并 warn）；**DataGen 期只收集声明（注册表冻结）**；单文件失败不影响其他文件；幂等。

### 16.2 GUI JSON schema（chasm/gui/*.json）

| 字段 | 默认 | 说明 |
|---|---|---|
| id | 必填 | `namespace:path`（MenuType 只能注册一次，重复跳过） |
| title / cols / rows / background | — / 9 / 1 / — | 标题/网格/背景 |
| slots | — | `[{ col, row, width, height }]`（width/height 缺省 1） |
| buttons | — | `[{ label, col, row, action, cooldown }]`；须有 action 或 label（都无则跳过）；action 驱动经 ChasmGuiActions 反查（label 缺省用 action 的 path）；纯文字按钮为空 handler |

```jsonc
{
  "id": "mymod:simple_box",
  "title": "简单宝盒",
  "cols": 9, "rows": 3,
  "slots": [ { "col": 0, "row": 0, "width": 2, "height": 2 } ],
  "buttons": [ { "label": "传送", "col": 5, "row": 0, "action": "mymod:teleport", "cooldown": 20 } ]
}
```

注意：动作须先 `ChasmGuiActions.register("mymod","teleport", handler)` 再 loadAll；GUI 在 DataGen 期**不加载**（MenuType 注册表已冻结）。

---

## 17. 日志系统

`ChasmLogger`：按 modId 隔离的 SLF4J 增强包装——每行自动带 [modId] 前缀 + 结构化记录 + **异步会话文件落盘**（`<游戏目录>/chasm-logs/<时间戳>/session.log`），文件 IO 全部吞异常，绝不影响游戏主流程。

| 方法 | 输出形态 |
|---|---|
| `info/warn/error/debug(modId, fmt, args...)` | `[INFO] [mymod] ...`（debug 需开调试模式） |
| `call(modId, cls, method, detail, args...)` | `[mymod] CALL X.y: ...` |
| `injection(modId, targetCls, targetMethod, mixin)` | `[mymod] INJECT ... <- ...` |
| `event(modId, cls, method, event)` | `[mymod] EVENT X.y z` |
| `setDebugMode(true)` | 开 DEBUG 输出 |
| `flushAndClose()` | 显式 flush（JVM 关闭钩子已自动做） |

底层：ChasmFileLogger（会话目录）+ AsyncLogWriter（守护线程 + ConcurrentLinkedQueue，close 排空）。日志行未做换行清洗，请勿把玩家可写文本未经处理直接进日志（避免日志注入/伪造行）。

---

## 18. DataGen 自动化

**角色**：所有"声明"都会自动产出资源 JSON，消灭手写：

| Provider | 产出 |
|---|---|
| ChasmModelProvider | 物品/方块模型（texture 声明；含 sword/pickaxe/axe/shovel/hoe 用手持模板） |
| ChasmLanguageProvider | en_us 语言（item/block name；按键翻译；**附魔名称/描述**，09-05 补齐） |
| ChasmRecipeProvider | 配方 json（配方声明） |
| ChasmLootProvider | 方块掉落 loot table（掉落声明） |
| ChasmTagProvider | 方块挖掘标签（requiresTool） |
| ChasmEnchantmentProvider | 附魔 json（声明） |
| ChasmEnchantableTagProvider | 每附魔 supported_items 物品标签（类型附魔池聚合，treasureOnly 跳过） |
| ChasmDamageTypeProvider | 自定义伤害类型 json（`data/<ns>/damage_type/*.json`，09-05 新增） |
| ChasmDamageTypeTagProvider | 伤害语义标签（bypasses_armor / is_fire / is_lightning，replace:false 合并，09-05 新增） |

**接入**：入口类实现 `DataGeneratorEntrypoint`，`onInitializeDataGenerator` 调 `ChasmDataGen.generate(generator, MyMod.class)`。`chasm-example` 的 `build` 已 dependsOn runDatagen（编译前自动再生成）。输出目录 `src/main/generated`（已纳入资源集）。

**JSON 覆写**（第十二步）：`ChasmDataGen.setOverrides(...)` 或 `overrideFile(path, json)`，可整体替换某个生成文件。已接入的路径：`<ns>/enchantment/<path>.json`、`<ns>/damage_type/<path>.json`、`minecraft/tags/damage_type/<tag>.json`。

---

## 19. 跨模组扩展（SPI）

Chasm 的"每个声明都暴露"设计使**任意模组**（含闭源）都能被扩展——只要它用 Chasm 声明过：

```java
// 1) 拿到目标模组的隔离上下文
ModContext other = Chasm.mod("targetmod");          // 不存在则按需创建，恒非 null

// 2) 物品控制台：追加/覆写右键行为
ChasmItemController ctrl = other.item("mana_sword"); // null = 未注册
if (ctrl != null) {
    ctrl.appendRightClick(ctx -> { /* 追加行为（不覆盖） */ });
    // ctrl.replaceRightClick(...)  // 覆写全部
}

// 3) 方块控制台
ChasmBlockController bc = other.block("mana_crystal");
if (bc != null) bc.appendUse(ctx -> { ... });

// 4) 全局聚合查询
for (ModContext ctx : Chasm.mods()) { ... }          // Chasm.mod 按 modId
Chasm.damageTypes().get("othermod", "arcane");       // 跨模组查伤害类型
Chasm.types().get("othermod", "staff");              // 查物品类型
Chasm.attributes().get("spell_power");               // 属性投影
Chasm.enchantments().holderFor(key);                 // 附魔 Holder（服务端启动后）
```

要点：ModContext 聚合了 items/blocks/recipes 与数据空间（data()）；append 系列保留原有行为；所有 SPI 修改建议在**初始化期**完成（行为列表为 ArrayList，运行期并发修改存在竞争）。



---

## 20. 测试

chasm-core 已内置 **P0 纯 JVM 单元测试**（JUnit 5 + fabric-loader-junit；chasm-core/build.gradle 已配置 test 任务并 useJUnitPlatform）：

| 测试类 | 覆盖点 |
|---|---|
| api.chasm.data.ChasmCodecTest | record 自动 Codec：基本类型/枚举/嵌套 record 编解码往返、静态 CODEC 字段优先、非 record 拒绝、深度上限防 StackOverflowError |
| api.chasm.trait.ChasmTraitsTest | USE/ATTACK/INVENTORY_TICK 三类注册与 O(1) 查询、事件类型隔离、未注册返回 null、重复注册覆盖 |
| api.chasm.log.AsyncLogWriterTest | 父目录创建、行落盘、close 排空、close 后 enqueue no-op、close 幂等 |
| api.chasm.log.ChasmLoggerTest | 调试模式开关、按 modId Logger 缓存、结构化方法不抛异常、flushAndClose 幂等 |

运行：./gradlew :chasm-core:test

无需启动游戏（fabric-loader-junit 提供最小 loader 环境）。**建议继续补**的高价值纯逻辑：ItemBuilder.register() 的属性合并去重与 attackDamage 换算、ChasmData 类索引读写、RecipeBuilder 参数校验、RateLimiter 冷却语义。



---

## 21. 已知限制与注意事项（当前代码核实 2026-09-05）

> 本清单逐条对照当前源码核实过；标注"尚未实现/仍存在"的项请勿当作框架缺陷误用。

1. **工具物品右键方块边界**：工具/剑保留原版 useOn 语义（剥皮/耕地/铺路），右键"可作用方块"时会短路 use()——此时 useTrait/onRightClick 不触发（见 4.3）。需要方块侧交互请用方块事件（第 8 章）。
2. **GUI**：ChasmMenu.stillValid 无距离校验（轻量容器）；ChasmGuiRegistry 用 LinkedHashMap（仅初始化期读写是安全约定）；客户端初始化之后才注册的 GUI 没有屏幕。
3. **回调列表线程模型**：ItemBuilder/BlockBuilder 的行为列表为 ArrayList，SPI 追加/覆写请放在初始化期；运行期并发修改存在竞争。
4. **伤害语义澄清**：handleAttack 返回 true 是短路 super.hurtEnemy（跳过原版命中副作用/耐久），物理伤害本身已由 Player.attack 结算。
5. **ChasmEnchantments javadoc 残留 EnchantmentMenuMixin 引用**：框架零 Mixin，附魔台集成实为数据驱动标签——该注释属历史残留（无此类）。
6. **ItemType 默认 Tier 为铁级模板**（耐久/速度≈铁、附魔等级 15、铁锭修复）：需要更高挖掘等级请显式 .tier(Tiers.DIAMOND)。

> 此前 6 条限制已在 2026-09-05 修复，不再赘述（见 22.1）：伤害类型自动落盘、附魔语言键自动生成、ChasmData 跨模组同名同类二级索引、DataGen 冻结防护、PlayerVar 多人同步。ChasmMenu.stillValid 无距离校验（轻量容器）；ChasmGuiRegistry 用 LinkedHashMap（仅初始化期读写是安全约定）；客户端初始化之后才注册的 GUI 没有屏幕。
8. **回调列表线程模型**：ItemBuilder/BlockBuilder 的行为列表为 ArrayList，SPI 追加/覆写请放在初始化期；运行期并发修改存在竞争。
9. **伤害语义澄清**：handleAttack 返回 true 是短路 super.hurtEnemy（跳过原版命中副作用/耐久），物理伤害本身已由 Player.attack 结算。
10. **ChasmEnchantments javadoc 残留 EnchantmentMenuMixin 引用**：框架零 Mixin，附魔台集成实为数据驱动标签——该注释属历史残留（无此类）。
11. **ItemType 默认 Tier 为铁级模板**（耐久/速度≈铁、附魔等级 15、铁锭修复）：需要更高挖掘等级请显式 .tier(Tiers.DIAMOND)。



---

## 22. 版本变更记录（2026-09 重构说明）

> 本 Wiki 相对 2026-08-15 旧版 CodeWiki（v0.1.0、24 文件时代）与早期审计结论的差异核实。模组近两周经历较大重构，**以本文件为准**。

### 22.1 已落地的新能力/修复（当前代码确认）

| 项 | 位置 | 说明 |
|---|---|---|
| P0 单元测试 | chasm-core/src/test（4 文件）+ build.gradle JUnit5 | 此前 0 测试；现覆盖 ChasmCodec/ChasmTraits/AsyncLogWriter/ChasmLogger |
| 包级解耦重构 | item/context（Use/Attack/InventoryTick/KeyContext 迁入）；ChasmKeyBinding 移至 api.chasm.item | 打破 item↔trait、item↔key 包级循环依赖（依赖方向单向化：trait/key → item） |
| 服务端网络限流器 | api.chasm.net.RateLimiter | GUI 与按键通道共用（有界冷却表 + TTL 清理） |
| 按键通道防刷 | ChasmKeyChannel | 每玩家×每按键 250ms 冷却（修复安全审计 High：无服务端限流） |
| GUI 两级冷却 | ChasmGuiChannel | 每按钮 250ms + 每玩家全局 50ms（修复审计 Medium：按索引冷却可被轮换绕过） |
| Codec 深度上限 | ChasmCodec MAX_CODEC_DEPTH=64 | 编/解码递归限深，防恶意深嵌套 StackOverflowError（修复审计 Medium） |
| 日志器失败防递归 | ChasmLogger.fileInitFailed | 会话文件初始化失败后静默降级且不递归重试 |
| **PlayerVar 多人同步**（修复） | player（PlayerVarChannel / S2C payload / PlayerVarClient） | set/modify 值变化增量推送 + 玩家加入全量同步；客户端只读缓存实时可读 |
| **伤害类型自动落盘**（修复） | datagen ChasmDamageTypeProvider / ChasmDamageTypeTagProvider | 自定义 DamageType json + bypasses_armor/is_fire/is_lightning 标签，声明即真实生效 |
### 22.3 尚未处理 / 已知的小残留

第 21 章当前剩余 6 条（GUI 距离/注册表线程安全约定、ArrayList 行为列表、伤害语义文档澄清、ChasmEnchantments javadoc 的 EnchantmentMenuMixin 历史残留、ItemType 默认铁级 Tier）。此前列出的"伤害类型不落盘 / 附魔翻译键缺失 / BY_CLASS 跨模组覆盖 / DataGen 冻结注册 / PlayerVar 无多人同步"五条已在 2026-09-05 修复，移入 22.1。

---

## 23. 玩法模板子系统（第十四步 · 新增 2026-09-05）

**定位**：把社区验证过的玩法模式（Aquamirae 式武器/食物/护甲套装）收敛为「声明 + 行为钩子 + 参数槽位」的模板插件层。入口 `Chasm.templates()`（`ChasmTemplates.INSTANCE`）。模板全部在**静态初始化期 `build()` 即完成注册**，无需 in-game 时序；唯一显式引导点是击杀事件 `ChasmKillEvents.init()`（已由 core 的 `ChasmInit` 调用）。

### 23.1 三个模板 + 击杀奖励总览

| 入口 | 返回 | 落地方式（ItemBuilder） | 注册时机 |
|---|---|---|---|
| `templates().weapon(modId, name)` | `WeaponTemplate` | `.template(t)` | build() 自动注册命中特质 `<id>_hit` + 击杀奖励 `<id>_kill` |
| `templates().food()` | `FoodTemplate` | `.food(t)` | 纯数据捆绑，无运行时注册 |
| `templates().armorSet(modId, name)` | `ArmorSetSpec` | `.armor(set, ArmorItem.Type)` | build() 自动注册进套装表 |
| `templates().killReward(modId, name)` / `killRewards()` | `KillReward` / `ChasmKillRewards` | `.killReward(id 或 reward)` | build() 注册进击杀奖励表 |

### 23.2 武器模板 WeaponTemplate

```java
public static final WeaponTemplate GREED = Chasm.templates().weapon("mymod", "greed")
    .attackDamage(5.0f).attackSpeed(-2.4f)      // 0 = 不覆盖物品自身声明
    .onHit(ctx -> ctx.target().igniteForSeconds(2))   // 自动注册 mymod:greed_hit（恒 PASS，叠加在原版伤害之上）
    .killDrop(Items.EMERALD, 3, 8, 1.0f)               // 自动注册 mymod:greed_kill（区间+概率；必掉可省略 chance）
    .build();

@Register("greed_sword")
public static final Item GREED_SWORD = Chasm.item()
    .type(ChasmTypes.SWORD).maxStackSize(1).durability(800)
    .name("Greed Sword").texture("minecraft:item/diamond_sword")
    .template(GREED)                                   // 一行应用：基伤/攻速/命中特质/击杀奖励
    .register();
```

API：`attackDamage()/attackSpeed()/hitTraitId()/killRewardId()/id()`。Builder：`attackDamage/attackSpeed/onHit(Consumer<AttackContext>)/onKill(Consumer<KillContext>)/killDrop(item,count | min,max | min,max,chance)/build()`。

### 23.3 食物模板 FoodTemplate

```java
public static final FoodTemplate SEA_STEW = Chasm.templates().food()
    .nutrition(6).saturation(0.6f)
    .remainder(Items.BOWL)                             // 吃完返还碗（背包满自动掉落）
    .effect(MobEffects.REGENERATION, 100, 0, 0.5f)     // 50% 概率 5s 再生
    .build();

@Register("sea_stew")
public static final Item SEA_STEW_ITEM = Chasm.item()
    .maxStackSize(16).name("Abyssal Stew").texture("minecraft:item/mushroom_stew")
    .food(SEA_STEW)                                    // 自动切 ChasmTypes.FOOD（ChasmFoodItem）
    .register();
```

API：`nutrition()/saturationMod()/canAlwaysEat()/fast()/remainder()/effects()`。Builder：`nutrition/saturation/alwaysEat()/fast()/remainder/effect(effect,durationTicks,amplifier,chance)/build()`。效果/余物/食用动画由原版 `DataComponents.FOOD` 接管；无 FOOD 组件时 `ChasmFoodItem` 回退普通行为。

### 23.4 护甲套装 ArmorSetSpec

```java
public static final ArmorSetSpec ABYSSAL = Chasm.templates().armorSet("mymod", "abyssal")
    .defense(ArmorItem.Type.HELMET, 3).defense(ArmorItem.Type.CHESTPLATE, 6)
    .defense(ArmorItem.Type.LEGGINGS, 5).defense(ArmorItem.Type.BOOTS, 3)
    .enchantmentValue(15).repair(Ingredient.of(Items.IRON_INGOT))
    .layer(ResourceLocation.fromNamespaceAndPath("mymod", "abyssal"))
    .durabilityFactor(15).toughness(1.0f)
    .halfSet(ctx -> ctx.player().addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 5, 0))) // 半套 >=2 件，每 tick 服务端刷新
    .fullSet(ctx -> ctx.player().addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 5, 0)))      // 全套 >=4 件
    .build();

@Register("abyssal_helmet")
public static final Item ABYSSAL_HELMET = Chasm.item()
    .maxStackSize(1).name("Abyssal Helmet").texture("minecraft:item/diamond_helmet")
    .armor(ABYSSAL, ArmorItem.Type.HELMET)   // 胸甲/护腿/靴子同理；.armor 自动切 ChasmTypes.ARMOR → ChasmArmorItem
    .register();
```

- Builder：`defense(type, amount)`（某部位护甲值；全空只 warn）、`enchantmentValue`（默认 15）、`equipSound`、`repair`、`layer`（纹理层 assets/<ns>/textures/models/armor/<name>_layer_1.png）、`toughness`、`knockbackResistance`、`durabilityFactor`（部位耐久 = Type.getDurability(factor)；<=0 抛异常）、`halfCount`（默认 2）、`fullCount`（默认 4）、`halfSet/fullSet(Consumer<ArmorTickContext>)`、`build()`。
- 材质用 Holder.direct 直接持有（免 DataGen JSON/冻结时序）；未声明耐久时 `ItemBuilder.armor` 自动按 factor 派生。护甲类型缺材质/部位会快速失败抛 IllegalStateException（ChasmTypes.ARMOR_FACTORY）。
- 套装效果每 tick（服务端）触发，靠持续刷新维持；`ArmorTickContext`：`player()/stack()/pieceCount()/level()/sendMessage()/serverOnly()`。`ChasmArmorItem` 保留原版 ArmorItem 语义（自动穿戴/附魔/修复）。

### 23.5 击杀奖励 KillReward

```java
Chasm.templates().killReward("mymod", "boss_loot")     // 或 killRewards().create(...)
    .onKill(ctx -> ctx.sendMessage("你击杀了 " + ctx.victim().getName().getString()))
    .drop(Items.DIAMOND, 1, 3, 0.5f)
    .build();
```

- 触发链：实体死亡（服务端 ServerLivingEntityEvents.AFTER_DEATH）→ 来源实体为玩家（含箭矢等间接击杀取射手）→ 主手物品栈携带 KILL_REWARD_ID → O(1) 反查 ChasmKillRewards.get(id) → reward.apply(new KillContext(stack, victim, killer, source))；整链 try-catch 兜底。
- Builder：`onKill(Consumer<KillContext>)`（单槽覆盖，后写覆盖先写）、`drop(item, count | min,max | min,max,chance)`（可叠加列表）、`build()`。掉落经 ctx.spawnItemDrop(item, count) 在击杀位置生成。
- `KillContext`：`stack()/victim()/killer()/source()/level()/serverOnly()/sendMessage()/spawnItemDrop(ItemLike[, int])`（恒服务端执行）。

### 23.6 内部组件与注册表

- 新增内部组件：`ChasmData.KILL_REWARD_ID`、`ChasmData.ARMOR_SET_ID`（均为 chasm: 前缀 ResourceLocation 组件，勿占用）。
- 注册表：`ChasmTemplates`（护甲套装表 getArmorSet/armorSetCount/allArmorSets）、`ChasmKillRewards`（get/size/all，与 ChasmTraits 同构）。
- 跨模组查询：`Chasm.templates().killRewards().get(otherId)`、`Chasm.templates().getArmorSet(otherId)`。

---

## 24. 附录

### 24.1 关键类速查（按包）

- 门面/注解：Chasm、ChasmMod、ChasmInit；registry（ChasmRegistrar、ChasmRegistration、Register）
- 上下文：ChasmContextRegistry、ModContext
- 物品：ItemBuilder、ChasmItem、ChasmSwordItem、ChasmPickaxeItem、ChasmAxeItem、ChasmShovelItem、ChasmHoeItem、**ChasmArmorItem、ChasmFoodItem**、ChasmItemBehavior、ChasmItemController、ChasmKeyBinding、AttributeDeclaration、ChasmTooltipUtil
- 回调上下文：item/context（UseContext、AttackContext、InventoryTickContext、KeyContext）
- 类型：ItemType(+Builder)、ChasmTypes（NONE/FOOD/ARMOR/SWORD/PICKAXE/AXE/SHOVEL/HOE + 8 工厂）、ChasmItemFactory、ChasmItemBuildContext（+armorMaterial/armorType）、ChasmItemSupport
- 特质：ChasmTraits、ItemTraitEvent、ItemTrait、UseTrait、AttackTrait、InventoryTickTrait
- 数据：ChasmData（BY_MOD_CLASS 二级索引 + ModDataSpace）、ChasmCodec、ChasmCodecs、DataComponent
- 方块：ChasmBlock、BlockBuilder、ChasmBlockController、BlockUseContext、LootBuilder、LootDeclaration
- 配方：RecipeBuilder、RecipeDeclaration
- 伤害：ChasmDamageTypes、ChasmDamageTypeBuilder、DamageTypeInfo、DamageTypeKind、DamageSourcesResolver、DamageContext
- 属性：ChasmAttributes、AttributeInfo
- 附魔：ChasmEnchantments(+EnchantmentBuilder)、EnchantmentDeclaration（+translationKey/descTranslationKey/displayName/displayDescription）、EnchantmentConfig
- 模板（第十四步）：ChasmTemplates、WeaponTemplate、FoodTemplate、ArmorSetSpec、ArmorTickContext、ChasmKillEvents、ChasmKillRewards(+Builder)、KillReward、KillContext
- GUI：ChasmGuiBuilder、ChasmGui、ChasmMenu、ChasmButton、ChasmStorageSlot、ChasmGuiRegistry、ChasmGuiActions、ChasmGuiChannel、ChasmOpenContext、ChasmGuiClick、ChasmButtonHandler；client（ChasmGuiClient、ChasmScreen）
- 按键：ChasmKeys、ChasmKeyChannel、KeyPressC2SPayload；client（ChasmKeyClient）
- 玩家：PlayerVar(+Builder，+多人 S2C 同步)；player/client（PlayerVarClient）
- 加载：ChasmItemLoader、ChasmGuiLoader、ChasmResources
- 日志：ChasmLogger、ChasmFileLogger、AsyncLogWriter
- 网络工具：RateLimiter
- DataGen：ChasmDataGen（9 个 Provider + setOverrides/overrideFile）

### 24.2 常用术语

| 术语 | 含义 |
|---|---|
| @Register / @ChasmMod | 声明注册标记；@Register 字段类型自动路由到对应原版注册表 |
| @DataComponent | record 数据组件声明（自动 Codec、自动注册、按 (modId,类) 建索引） |
| ModContext / 控制台 | 按 modId 隔离的注册物视图；ChasmItemController / ChasmBlockController 是 SPI 扩展句柄 |
| Trait | 全局注册、O(1) 查回执行的行为单元（use / attack / inventoryTick） |
| ItemType | 类型模板（原版类映射 + 默认属性 + 标签 + 附魔池），不等于继承关系 |
| 内置类型 id | chasm:none / food / armor / sword / pickaxe / axe / shovel / hoe |
| 模板（Template） | 玩法模板：WeaponTemplate / FoodTemplate / ArmorSetSpec / KillReward（第十四步） |
| DataGen 收集模式 | scan(class, false, false)：只暴露声明不真实注册（注册表冻结期） |
| serverOnly | 仅服务端执行的回调包装——数值结算（扣法力/伤害/存档）的唯一正确位置 |
| PlayerVar 同步 | set/modify 值变化即 S2C 增量推送；玩家加入全量同步 |

### 24.3 一条完整链路（记忆锚点）

@ChasmMod(id=mymod) → 静态字段声明（@DataComponent 数据 / @Register 物品·方块 / 特质·类型·附魔·按键·模板） → onInitialize：ChasmRegistrar.scan + itemLoader/guiLoader 加载 JSON（击杀监听与网络通道由 ChasmInit 注册） → 注册进原版注册表并暴露到 ModContext → 其他模组 Chasm.mod("mymod").item("xxx").append/... 做 SPI 扩展 → build 时 runDatagen 自动补模型/语言/配方/掉落/附魔/伤害类型 JSON。
---

---

## 25. 移植与排错记录（摘要）

完整的适配/排错记录在 `docs/内部-移植笔记.md`（**内部文件，不随产品分发**）。这里只保留对**框架使用者**有用的结论：

1. **声明式界面**：一页 = 一组由状态唯一决定的节点；**跨页互斥必须由状态保证**，几何重叠兜不住。
2. **界面源每帧会被调用**：全表查询与文本构造必须缓存/惰性化（见 0.4 的四条规则）。
3. **内容容器**：一组内容写成一个类 + `ChasmRegistrar.scanContent(...)`，比逐个注册少一大截样板。
4. **数据驱动优先**：能写 JSON 的不写代码；数据文件同时是整合包作者的可调点。
5. **core 改动的可见性**：改完 core 必须重做 loom remap（见 1.3），否则游戏里永远是旧类。
6. **排错通用教训**：几何 / 命中 / 绘制共用**同一份**坐标换算（「所见即所点」）；界面状态与数据必须**同源**。
7. **性能是设计问题**：每帧重建的构建函数里做 O(全表) 扫描，必然表现为「卡」；缓存键用 (输入身份, 数据 revision)。

