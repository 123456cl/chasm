# key-map：真机元素 → 端口 node key 的映射，以及 state 粒度差异

> 本文是 `tools/probe-diff/` 的配套说明。所有"真机侧"结论都标注了来源文件与行号；
> 没有读过的代码不写结论。真机源码位置：`C:\mcmod\chasm2\_ref\Tetra-1.20\tetra-1.20\src\main\java\se\mickelus\tetra\...`。

## 0. 为什么需要这张表

两侧 dump 是**同一套 schema**（`chasm.gui.probe/1`），但两侧的**界面表示模型根本不同**：

| | 真机侧（Tetra 1.20 / mutil） | 端口侧（Chasm 1.21.1 / Fabric） |
|---|---|---|
| 表示 | `Gui` **对象树**，复合部件带子元素 | **扁平 `nodes[]` 列表**，一个 node = 一次绘制遍或一个命中区 |
| 身份 | 对象引用；语义身份散在字段里（如 `GuiModule.slotKey`） | 字符串 `key`（`frame:<slotPath>` 等），由构造处显式写死 |
| 坐标 | `x/y` **相对** attachment 点与父级；绘制时才 `refX + x` | `rect` 是**面板绝对像素**（`GuiProbe` 注释：面板像素） |
| 动作 | `onClick`/`hoverHandler` 回调对象，**没有字符串 action id** | `node.action()` 字符串 + `value`，另有框架 handler 注册表 |
| 状态 | 容器同步 + 客户端对象字段 | `ChasmGuiState.IntSlot` / `ValueKey` 同步 int 键 |

结论：**节点不可能 1:1 配上**。probe-diff 的节点配对优先走 `nodes[].key`，所以**真实侧探针必须用与端口相同的 key 合成约定**；否则只能退化成"同 type + 相对位置贪心配对"，并被标成 low-confidence。

## 1. 模块槽族（核心）：`GuiModule.slotKey` → `frame:/glyph:/slot:/name:`

### 1.1 真机侧事实

`GuiModule.java`（真机，188 行）：

- 第 23 行：`protected String slotKey = null;` —— 这个字符串**就是模块槽路径**（如 `sword/blade`）。
- 第 43 行：点击回调 `() -> slotClickHandler.accept(slotKey)`；第 178 行 hover：`hoverHandler.accept(slotKey, null)`。
- 第 102–139 行 `setupChildren(...)` 建四个子部件：
  - `GuiModuleMinorBackdrop backdrop`：11×11，uv (68,0)（`GuiModuleMinorBackdrop.java:9`）
  - `GuiTextureOffset tweakingIndicator`：11×11，uv (192,32)（仅 `tweakable` 时，第 110–117 行）
  - `GuiString moduleString`：文字，x = 12 或 -12，y = 1（第 120–125 行）
  - `GuiModuleGlyph glyph`：8×8，y = 1（第 130–137 行，构造 `8, 8`）
- 第 127 行：`width = moduleString.getWidth() + 12;`
- `GuiModuleList.java:81,89,127`：`majorModuleKeys[i]` / `minorModuleKeys[i]`（来自 `item.getMajorModuleKeys(itemStack)`）就是传给 `GuiModule` 的 `slotKey`。

### 1.2 端口侧事实（key 的权威定义）

`tetra-port/.../TetraUi.java` 的 `buildSlotNode`（第 505–566 行）：

| 端口 node key | 端口出处 | 端口几何 | 真机侧对应 |
|---|---|---|---|
| `frame:<slotPath>` | TetraUi.java:522 | `frame`×`frame`；**主模块 uv(52,0) 15×15，次模块 uv(68,0) 11×11** | `GuiModuleMinorBackdrop`（次）/ `GuiModuleBackdrop`（主，15×15 uv(52,0)，`GuiModuleBackdrop.java:12`） |
| `glyph:<slotPath>` | TetraUi.java:534 / 540 | 主模块 `spriteRegion` 16×16 画在 `(frameX-1, y-1)`；次模块 `sprite` 8×8 画在 `(frameX, y+1)` | `GuiModuleGlyph`（真机构造 8×8→内部 9×9，绘制 8×8，见 §3 风险 R3） |
| `slot:<slotPath>` | TetraUi.java:551 | 仅主模块；标签文字 | `GuiString` 的 slotName 分支（真机把 slotName 作为 `moduleString` 的**回退文本**，`GuiModule.java:120`；端口把它拆成独立节点） |
| `name:<slotPath>` | TetraUi.java:555(主,y+5) / 561(次,y+1) | 模块名文字 | `GuiString moduleString`（`GuiModule.java:120`） |

端口 `buildSlotNode` 的注释本身写明了锚点公式（第 506 行）："mutil 的锚点公式：框的 x 恒为 +1；右对齐时 = 父宽 - 框宽 + 1"。

### 1.3 粒度对照（重要）

**1 个真机 `GuiModule` → 最多 4 个端口 node**（`frame:` + `glyph:` + `slot:` + `name:`），且：

- 次模块**没有** `slot:` 节点（TetraUi.java:548 的 `if (major)`）。
- `glyph:` 只在 `installed != null && TetraGlyphs.isKnown(...)` 时才建（TetraUi.java:529）；真机同样只在 `glyphData != null` 时画字形（`GuiModule.java:129`）——条件语义一致，但判据来源不同。
- 所以 `nodesCount` 天然不等，**绝不能拿节点总数当验收指标**。

### 1.4 真实侧探针该怎么合成 key

`GuiModule` 的 `slotKey` 已经就是槽位路径，所以直接照抄端口约定即可：

| 真机对象 | 建议 key |
|---|---|
| `GuiModuleMinorBackdrop` / `GuiModuleBackdrop` | `frame:` + `slotKey` |
| `GuiModuleGlyph` | `glyph:` + `slotKey` |
| `GuiString moduleString` | `name:` + `slotKey` |
| `GuiModule` 自身（命中区，若有独立的 slot 标签） | `slot:` + `slotKey` |
| `GuiTextureOffset tweakingIndicator` | 端口目前**没有**对应 key（见 §6 未对齐项） |

## 2. 其它 key 族

端口的 key 前缀与语义 provider 的判据全部来自 `TetraGuiProbe.java` 的 `nodeFacts`（第 140–261 行），它逐条写明了每个前缀对应的真实构件与出处行号。下表左两列是**端口侧权威**；第三列的真机类名已核实存在于真机源码目录，但**内部字段未逐一读过**，故只标"对应部件"，需要真实侧探针自行确认合成方式。

| 端口 key 形态 | 端口语义 provider 填入的字段（TetraGuiProbe.java） | 真机侧对应部件（类名已核实存在） |
|---|---|---|
| `grow:<moduleKey>` / `g:<..>` / `gname:<..>` | `moduleKey`, `slotPath`（选中槽）, `langKey = tetra.module.<族>/<模块>.name` | `GuiModuleList`（大类行）/ `GuiModuleMajor` |
| `msel:<variantKey>` / `mrow:<..>` / `m:<..>` / `mname:<..>` / `mqty:<..>` | `variantKey`, `slotPath`, `moduleKey`, `materialKey`, `materialPrefixKey`, `langKey = tetra.variant.<key>`, `quantity`, `cost` | `GuiModuleDetails` / `SchemaSlotGui` / `SchematicRequirementGui` |
| `brow:<blueprintKey>` / `b:<..>` / `bname:<..>` | `blueprintKey`, `langKey = tetra/schematic/<key>.name` | `GuiSchematicList` / `GuiSchematicListItem` / `GuiSchematicDetail` |
| `trow:<tweakKey>` / `tname:<..>` | `tweakKey`, `langKey = tweak.labelKey()` | `GuiTweakControls` / `GuiTweakSlider` |
| `act_<部位>:<actionKey>`（`act_hit/act_left/act_right/act_top/act_bottom/act_label/act_icon/act_tool/act_tool_level`） | `actionKey`, `langKey = Action#labelKey` | `GuiActionList` / `GuiActionButton` / `CraftButtonGui` |
| `tetra.stats.<..>:<部位>` / `tool:<动作>:<部位>` | `statKey`（key 本身就是语言键）, `part`, `toolAction` | `WorkbenchStatsGui` |
| `integrity:<i>` / `integrity_over:<i>` | `segment`（第几段）, `overflow` | `GuiIntegrityBar` |
| `gem:<part>:<i>` | `part`, `gemIndex` | （端口 `TetraGemsUi`；真机对应部件未在本次核对范围内确认） |
| `hl:<variantKey>:<i>:frame` / `:dot<n>` | （TetraGuiProbe 未覆盖该前缀） | `GuiInventoryHighlight` |
| `list_*` / `detail_*` / `material_*` / `craft_*` | `builder`（构件定位） | `WorkbenchScreen` 的静态装饰（`GuiButtonOutlined` 等） |
| `player_inventory` / `diamond` | —— | `GuiInventoryInfo` / `WorkbenchScreen` 底图 |
| `tab:hit:<i>` / `tab:indicator:<i>` / `tab:icon:<i>` / `reqtool:icon:*` / `reqtool:level:*` / `rep_*` | —— | `RepairInfoGui` / `ToolRequirementGui` / `ToolRequirementListGui` |

> 端口 `TetraGuiProbe.builderOf(key)`（第 370–409 行）能把 key 反查回构件，diff 报告里 `node_missing`/`node_extra` 的备注就直接带这个 builder，便于定位。

## 3. `slots[]` / `interactive[]` / `state` 三个"非节点"块

### 3.1 `slots[]`

- 端口：来自 `menu.gui().storageSlots()` 的 **ChasmStorageSlot 声明**，逐槽写 `i/containerIndex/x/y/active/dynamic/filtered/empty/item/count`，
  再叠加 `slotFacts` provider 的 `role`（`target`/`material`）、`materialIndex`、`selectedSlotPath`、`installedModuleAtSelectedSlot`（`TetraGuiProbe.java:119–134`）。
  实测示例：加工台声明 4 个存储槽 = 目标槽 + 3 个材料槽（`TetraGuiProbeDumpTest.java:174` 断言 `slots.size()==4`、`slots[0].x==152 / y==58`、`slots[0].sem.role=="target"`）。
- 真机：**没有等价的"存储槽声明"结构**。真机的槽位几何来自 `WorkbenchScreen` 的布局常量、物品来自容器的 `Slot`。
- **风险**：真实侧探针若不能产出 `slots[]`（或产出的 `slots[i].i` / `containerIndex` 与端口不同），
  probe-diff 会直接报一片 `slot_missing` / `slot_extra`；这类差异**不代表移植错了**，而是**探针没对齐**。
  对齐要求：真机侧必须用**同样的 `i` 顺序**（0 = 目标槽，1..3 = 材料槽）并同样用 provider 写 `sem.role`。

**run-3 实测：真机探针的 `slots[]` 里混进了 36 个玩家背包槽。**
关键事实：这些背包槽的 `sem.role` **也是 `material`**、`sem.containerClass` 也全是 `net.minecraft.world.SimpleContainer`，
唯一能区分它们的是 `containerIndex`（容器自有槽 `0..3`、背包槽 `9..44`）。所以"按 `role` 过滤"**并不能**排除背包槽 ——
实测 `--slots-scope role` 下 `slot_missing=132`，换成 `--slots-scope storage-only --slots-container-max 9` 后降到 `12`
（A 侧参与比较的槽 160 → 39）。

因此 `slots[]` 的对齐除了"两端都用 provider 写 role"，还要满足其一：
(a) 真机侧**只把容器自有槽放进 `slots[]`"（推荐，最干净）；或
(b) 用 `--slots-scope storage-only --slots-container-max <容器槽上界>` 在比对侧裁掉背包槽。
两侧数组下标顺序不同不影响：非 `real-all` 模式下槽位按 `(sem.role, containerIndex)` 配对 ——
fixture2 里故意错位的下标在 `real-all` 下产生 `slot_sem=24 / slot_coord=14`，换档后归零。

### 3.2 `interactive[]`

- 端口：由 `DeclClient` 的节点列表里**所有 `action != null` 的节点**导出（`GuiProbe.java:591–618`），
  字段 `i/node/action/value/enabled/rect/handler/handlerClass/handlerRegistered`；
  `handler` 是 `GuiDeclarations` 注册表查到的**声明类名**（lambda 去掉 `$$Lambda…`）。
- 真机：动作是 `GuiClickable` 的 `onClick`/`hoverHandler` **回调对象**，没有字符串 action id。
- **风险**：真实侧探针必须**自己给每个可点部件起一个 action id**。若两端的 id 不同名（例如端口 `back` vs 真机侧命名 `return`），
  probe-diff 只能按 `node` 名回退配对并标 low-confidence，同时报 `ia_action`。
  **建议**：真实侧探针直接采用端口的 id 集合（`select_slot` / `pick_variant` / `craft` / `page` / `back` / `tweak` / `action` …）。
- 真机侧同样**没有** handler 注册表，所以 `handler`/`handlerClass`/`handlerRegistered` 天生不可比 —— probe-diff 已把它归到 `node_handler`/`ia_handler`（medium），不参与 critical 判定。

### 3.3 `state` 粒度差异

端口加工台的**全部整数状态键**（权威：`TetraGuiProbeDumpTest.java:69–70` 的 `STATE_KEYS`）：

| 端口 state 键 | 端口侧含义（TetraGuiProbe / TetraUi 用法） | 真机侧对应（**待真实侧探针确认**） |
|---|---|---|
| `modules` | 选中的模块槽**下标**（`-1` = 未选）；`selectedSlotPath()` 用它查 `Modules.slotsFor(target)` | WorkbenchScreen 的 focus/selected slot |
| `group` | 选中的大类行下标（`TetraModuleGroups.of(slotPath)` 的下标） | 大类列表选中态 |
| `variant` | 选中的变体下标 | 材料列表选中态 |
| `special` | 选中的特殊蓝图行下标 | 特殊蓝图列表选中态 |
| `group_page` | 大类列表翻页 | 列表滚动/翻页 |
| `material_page` | 材料列表翻页 | 列表滚动/翻页 |
| `gate` | 合成门禁码（`TetraPort.CraftGate` 的序数） | 门禁/不可合成原因 |

- 端口侧 `stateDecl` 是 `[默认值, min, max]`（int 键）或字符串 `value`（大值键），
  `stateValues` 是**大值键的 `toString()`（截断 400 字）**。真机侧若没有"大值键"概念，`stateValues` 会整块缺失。
- **配对后果**：probe-diff 的记录配对键是 `(gui, 归一化 state 签名)`。
  **只要两侧键名集合不同，精确配对就一定失败**，会依次降级为
  `(gui, 键名集合)` → `(gui, 出现次序)` 并**全部标 low-confidence**（report.md 的"记录配对"表会写明降级原因）。
- **缓解**：
  1. 真实侧探针尽量采用上表这套**同名键**；
  2. 无法改名时用 `--state-ignore <键,..>` 把"只在一侧存在的键"排除出配对签名（注意：它只影响**配对**，不影响 `state` 差异报告本身）；
  3. 报告里的 `state_keys_only_a` / `state_keys_only_b` 就是"哪些键一侧有一侧没有"的清单。
- 值本身不同（同键名、不同值）会上报 `state_value`；`stateDecl` 的默认值/min/max 不同会上报 `state_decl`（能区分"点了没反应"是不是被 clamp）。

## 4. 已知对齐风险清单（按严重度）

| # | 风险 | 证据 | probe-diff 的表现 / 建议 |
|---|---|---|---|
| R1 | **结构粒度**：1 个真机复合部件 ↔ 最多 4 个端口 node | TetraUi.java:522/534/540/551/555；GuiModule.java:102–139 | 节点数天然不等；只能靠 key 配对。真实侧必须实现同名 key 合成 |
| R2 | **坐标系**：真机 x/y 相对 attachment + 父级，端口 rect 是面板绝对像素 | GuiModule.java:104–137（topLeft 时 x=+1/+12，否则 -1/-12）；GuiModuleList.java:81,89,127（按 `offsets.getX(i)` 的符号选 topLeft/topRight）；mutil 绘制 `refX + x`（GuiModuleGlyph.java:39） | 真实侧必须把坐标解析成**面板绝对像素**，否则 `node_coord` 全红（high） |
| R3 | **半像素/pose 位移**：真机字形构造 8×8 却扩成 9×9，绘制时 `translate(0.5,0.5)` 且画 `width-1` | GuiModuleGlyph.java:16,36–40 | 真机字形实际落在 (x+0.5, y+0.5)、尺寸 8×8；端口是整数 8×8 → 可能恒差 0.5px。需约定**取整规则**（建议真机侧对 sprite 类节点统一 floor/round 并在 report 里注明） |
| R4 | **字号/半宽字体**：端口用 `textScale` + `TextMetrics` 近似真机的 `GuiStringSmall` 半宽字体 | TetraUi.java:569–579 注释原文："真实界面的小字用专用半宽字体（GuiStringSmall），本移植是把原版字体缩到 0.5 来近似…以前实测宽 43%" | 文本节点的 `rect[2]`（宽）**天然会差**。建议对 `type=text` 的 `node_size` 单列白名单/降级，否则会淹没真差异 |
| R5 | **文本内容 vs 语言键**：两侧 `text` 都是已解析字符串；只有端口的 `sem.langKey` 会写出语言键原文 | GuiProbe 文档（sem 由 provider 提供）；TetraGuiProbe.java:157–166 | `node_text` 会因**语言包不同**而误报。报告已在 `node_text` 备注里带 `langKey(A)/langKey(B)`；判移植对错应**先比 langKey 再比 text** |
| R6 | **handler 不可比**：真机侧没有框架 handler 注册表 | GuiModule.java:43,178（回调对象）；GuiProbe.java:624–643（注册表查不到就如实写 null） | 已归 medium，请人工判断，别当 critical |
| R7 | **数据依赖**：两端模块/材料数据（TetraModules 加载的 json）不一致会让行数与文字全变 | TetraGuiProbeDumpTest.java:87–91 要先 `loadFromDirectory` 才有行 | 建议先比 `sem.variantKey`/`moduleKey` 集合，再比文字；否则 diff 里全是数据差 |
| R8 | **动画语义不同名**：dump 只存**声明值**；两侧动画实现不同（mutil `KeyframeAnimation` vs `ChasmGuiAnimations`） | GuiModule.java:73–100；GuiProbe 文档"不采样逐帧动画" | `alphaTag`/`delay`/`slide` 归 low；不要期望数值相等 |
| R9 | **state 归一化**：见 §3.3 | —— | 三级降级配对 + 显式 low-confidence 标注；`--state-ignore` 缓解 |
| R10 | **key 缺失/冲突**：真机侧若给不出唯一 key（或出现重名），节点只能位置贪心配对 | probe-diff 的 `node_pair_lowconf` / `node_key_conflict` | 一律标 low-confidence，**必须人工复核**，工具不假装确定 |

## 5. 端口目前没有对应 key 的真机元素（未对齐项）

这些是真机存在、但端口 `buildSlotNode`/`nodeFacts` 前缀表里**没有同名 key** 的元素，真实侧探针若为它们合成 key，probe-diff 会全部报成 `node_extra`：

| 真机元素 | 出处 | 说明 |
|---|---|---|
| `tweakingIndicator`（GuiTextureOffset 11×11 uv(192,32)） | GuiModule.java:111 | 端口未建对应节点（调平用 `trow:`/`tname:` 行表达） |
| `GuiModuleMajor` 的主模块专用字形/额外子部件 | GuiModuleMajor.java（9133 字节） | 端口用 `frame:` 的 15×15 + `glyph:` 16×16 表达；`GuiModuleMajor` 内部未逐一核对 |
| `GuiSources` / `GuiExperience` / `GuiModuleEnchantment` / `GuiModuleImprovement` / `GuiSlotDetail` / `GuiSchematicDetail` / `RepairInfoGui` 的部分子元素 | 同目录各文件 | 端口有的用 `reqtool:`/`rep_*`/`gem:`/`tetra.stats.*` 表达，是否覆盖完全**未逐项核对**（本次任务只读了 GuiModule/GuiModuleList/GuiModuleBackdrop/GuiModuleGlyph 四个文件） |

## 6. 建议的比对顺序

1. 先看 report.md 的 **§2 记录配对**：确认两侧 `gui` 与 `state` 键名集合是否对得上（对不上先解决探针侧，别急着看节点）。
2. 再看 **critical** 一栏：`node_missing` / `node_extra` / `node_action` / `node_enabled` / `slot_item` / `slot_empty` / `ia_missing` —— 这些才是"功能错了"。
3. 然后看 **high**：`node_coord` / `node_size` 前先把 R3/R4 的取整与字体问题排除。
4. `node_pair_lowconf` 与 `node_key_conflict`（info）必须人工复核；这类 diff 里出现的坐标/文本差**可信度低**。
