package api.chasm.gui;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 声明式界面构建器（Chasm.gui 的核心）。
 *
 * <p>用几行链式声明即可得到一个自定义界面，隐藏底层 {@link MenuType} /
 * {@link ChasmMenu} / 网络包的细节：</p>
 *
 * <pre>{@code
 * Chasm.gui("mymod", "magic_box")
 *     .title("魔法盒")
 *     .size(9, 3)                       // 9 列 3 行的网格
 *     .slot(0, 0, 2, 2)                 // (0,0) 起一个 2x2 的物品存储区
 *     .button("传送", 5, 0, (player, click) -> {
 *         player.teleportTo(player.getX(), player.getY() + 10, player.getZ());
 *     })
 *     .button("治疗", 7, 0, (player, click) -> player.heal(10.0f))
 *     .onOpen(ctx -> ctx.setItem(0, net.minecraft.world.item.Items.EMERALD, 5))
 *     .register();
 * }</pre>
 *
 * <p><b>网格单元</b>：{@code size(cols, rows)} 定义 cols&times;rows 个 18px 单元。
 * 未显式声明的单元默认变成存储槽（箱子式全格子）；{@code slot()} 覆盖指定矩形为存储槽，
 * {@code button()} 把指定单元变成虚拟按钮（不占容器）。只要显式声明过任一单元，
 * 则仅声明的存储槽参与内容同步。</p>
 */
public final class ChasmGuiBuilder {

	private static final int WIRE_MIN = ChasmGuiState.WIRE_MIN;
	private static final int WIRE_MAX = ChasmGuiState.WIRE_MAX;

	private static final byte CELL_NONE = 0;
	private static final byte CELL_SLOT = 1;
	private static final byte CELL_BUTTON = 2;

	private final String modId;
	private final String name;
	private Component title = Component.literal("Chasm");
	private ResourceLocation background;
	private int pendingCooldown;
	private int cols = 9;
	private int rows = 1;
	private final Set<Long> cells = new HashSet<>();           // 被显式声明的单元 key（仅记录存在）
	private final List<ChasmStorageSlot> storageSlots = new ArrayList<>();
	private final List<ChasmButton> buttons = new ArrayList<>();
	private double bindRangeSq = ChasmGuiBounds.square(ChasmGuiBounds.DEFAULT_RANGE);
	private ResourceLocation themeId;
	private BackgroundRegion backgroundRegion;
	private int panelWidth;
	private int panelHeight;
	private int playerInvX;
	private int playerInvY;
	private int playerInvSpacing;
	private int hotbarY;
	private boolean panelSuppressed;
	private boolean playerInventoryArtProvided;
	private final List<ChasmWidgetSpec> widgets = new ArrayList<>();
	/** 声明式内容源（null = 传统声明式布局）。 */
	private api.chasm.gui.decl.GuiSource declSource;
	private final Map<String, api.chasm.gui.decl.GuiDeclarations.ActionHandler> declActions = new HashMap<>();
	private final Map<String, ChasmWidgetHandler> widgetHandlers = new HashMap<>();
	private final List<ChasmGuiState.IntSlot> intSlots = new ArrayList<>();
	private final List<ChasmGuiState.ValueKey<?>> stateKeys = new ArrayList<>();
	private Consumer<ChasmOpenContext> onOpen;
	private Runnable onClose;

	/** 仅由 {@link api.chasm.Chasm#gui(String, String)} 创建。 */
	public ChasmGuiBuilder(String modId, String name) {
		this.modId = Objects.requireNonNull(modId, "modId");
		this.name = Objects.requireNonNull(name, "name");
	}

	/** 窗口标题（中文/任意字面量）。 */
	public ChasmGuiBuilder title(String title) {
		this.title = Component.literal(title);
		return this;
	}

	/** 窗口标题（{@link Component}，支持可翻译键）。 */
	public ChasmGuiBuilder title(Component title) {
		this.title = Objects.requireNonNull(title, "title");
		return this;
	}

	/**
	 * 设置网格尺寸（单元数）。
	 *
	 * @param cols 列数（每单元 18px 宽）
	 * @param rows 行数（每单元 18px 高）
	 */
	public ChasmGuiBuilder size(int cols, int rows) {
		if (cols < 1 || cols > 18) {
			throw new IllegalArgumentException("列数需在 [1,18]，实际 " + cols);
		}
		if (rows < 1 || rows > 6) {
			throw new IllegalArgumentException("行数需在 [1,6]，实际 " + rows);
		}
		this.cols = cols;
		this.rows = rows;
		return this;
	}

	/**
	 * 在网格 (col,row) 起声明一个 {@code width*height} 的存储槽区域。
	 *
	 * <p>该区域内每个单元都是一个真实物品槽（可放/取/预设）；占用单元按行列顺序获得
	 * 连续索引，供 {@code onOpen(ctx.setItem(i, ...))} 定位。</p>
	 */
	public ChasmGuiBuilder slot(int col, int row, int width, int height) {
		return slot(col, row, width, height, null);
	}

	/**
	 * 带**物品过滤**的存储槽区域：只有满足 filter 的物品能放进去（原版 Slot.mayPlace 语义）。
	 *
	 * <pre>{@code
	 * // 神化式宝石槽：只允许放宝石
	 * .slot(2, 0, 1, 1, Gems::isGem)
	 * }</pre>
	 *
	 * <p>没有过滤时什么都能塞 —— 这正是「只该放某类东西的槽位结果啥都能放」的老问题。</p>
	 */
	public ChasmGuiBuilder slot(int col, int row, int width, int height,
								java.util.function.Predicate<net.minecraft.world.item.ItemStack> filter) {
		checkBounds(col, row);
		int w = Math.max(1, width);
		int h = Math.max(1, height);
		if (col + w > cols || row + h > rows) {
			throw new IllegalArgumentException(
				"存储槽区域 (col=" + col + ",row=" + row + ",w=" + w + ",h=" + h + ") 超出网格 " + cols + "x" + rows);
		}
		int index = storageSlots.size();
		for (int dy = 0; dy < h; dy++) {
			for (int dx = 0; dx < w; dx++) {
				int c = col + dx;
				int r = row + dy;
				long key = key(c, r);
				cells.add(key);
				storageSlots.add(new ChasmStorageSlot(index++, c, r, filter));
			}
		}
		return this;
	}

	/**
	 * 设置背景九宫格贴图（resource pack 提供）。
	 *
	 * <p>约定：背景纹理为 32x32，8px 边框（九宫格切 9 区；4 角 8x8，四边与中心
	 * 拉伸填充到网格区域）。不调用本方法则使用客户端默认灰色面板。</p>
	 *
	 * @param bg 背景贴图 {@link ResourceLocation}
	 */
	public ChasmGuiBuilder background(ResourceLocation bg) {
		this.background = Objects.requireNonNull(bg, "bg");
		return this;
	}

	/**
	 * 设置"下一个按钮"的冷却（tick 数），随后调用 {@code button()} / {@code buttonIcon()} /
	 * {@code buttonAction()} 创建出的那个按钮将携带该冷却，之后自动复位。
	 *
	 * <p>冷却在服务端生效（见 {@link ChasmGuiChannel}），用于防高频点击刷服务器。</p>
	 *
	 * @param ticks 冷却 tick 数，必须 &ge; 0
	 * @throws IllegalArgumentException ticks &lt; 0
	 */
	public ChasmGuiBuilder buttonCooldown(int ticks) {
		if (ticks < 0) {
			throw new IllegalArgumentException("冷却不能为负，实际 " + ticks);
		}
		this.pendingCooldown = ticks;
		return this;
	}

	/**
	 * 在网格 (col,row) 声明一个带纹理图标的虚拟按钮（不占物品槽）。
	 *
	 * <p>图标约定：贴图为 32x32，上下两帧，正常帧在 {@code (iconU, iconV)}，
	 * 悬停帧在 {@code (iconU, iconV+16)}。客户端绘制 16x16 图标替代文字。</p>
	 *
	 * @param label   按钮文字（服务端命中断点用）
	 * @param icon    图标贴图 {@link ResourceLocation}
	 * @param col     列
	 * @param row     行
	 * @param handler 点击回调（服务端主线程）
	 */
	public ChasmGuiBuilder buttonIcon(String label, ResourceLocation icon, int col, int row, ChasmButtonHandler handler) {
		checkBounds(col, row);
		long key = key(col, row);
		cells.add(key);
		buttons.add(new ChasmButton(label, col, row, handler, icon, 0, 0, pendingCooldown));
		pendingCooldown = 0;
		return this;
	}

	/**
	 * 在网格 (col,row) 声明一个由"命名动作"驱动的虚拟按钮：从
	 * {@link ChasmGuiActions#get(ResourceLocation)} 反查 handler 绑定。
	 *
	 * @param actionId 已注册动作 id（{@code modId:name}）
	 * @param label    按钮文字
	 * @param col      列
	 * @param row      行
	 */
	public ChasmGuiBuilder buttonAction(ResourceLocation actionId, String label, int col, int row) {
		ChasmButtonHandler handler = ChasmGuiActions.get(actionId);
		if (handler == null) {
			api.chasm.log.ChasmLogger.warn(
				actionId == null ? "chasm" : actionId.getNamespace(),
				"动作 {} 未注册，按钮将无响应", actionId);
			// 注册一个 no-op 代替，避免 NPE；按钮仍可渲染/点按但无效果
			handler = (player, click) -> { };
		}
		return button(label, col, row, handler);
	}

	/**
	 * 便捷重载：按钮文字用动作 id 的 path（{@code actionId.getPath()}）。
	 *
	 * @param actionId 已注册动作 id（{@code modId:name}）
	 * @param col      列
	 * @param row      行
	 */
	public ChasmGuiBuilder buttonAction(ResourceLocation actionId, int col, int row) {
		String label = actionId == null ? "?" : actionId.getPath();
		return buttonAction(actionId, label, col, row);
	}

	/**
	 * 声明按钮（**按文字长度自动算宽度**）。
	 *
	 * <p>踩过的坑：固定 1 格宽的按钮遇到"标记·已退役"这种长标签，文字会溢出按钮框、
	 * 与相邻按钮**重叠**。这里默认按 {@link ChasmGuiText#cells} 自动取宽，并按占地检查重叠。</p>
	 */
	public ChasmGuiBuilder button(String label, int col, int row, ChasmButtonHandler handler) {
		return button(label, col, row, ChasmGuiText.cells(label), 1, handler);
	}

	/** 声明按钮（**自定义宽度**，格）。 */
	public ChasmGuiBuilder button(String label, int col, int row, int widthCells, ChasmButtonHandler handler) {
		return button(label, col, row, widthCells, 1, handler);
	}

	/** 声明按钮（**自定义宽高**，格）。长标签务必给够宽度，否则文字会溢出重叠。 */
	public ChasmGuiBuilder button(String label, int col, int row, int widthCells, int heightCells,
								  ChasmButtonHandler handler) {
		checkBounds(col, row);
		checkFit(label, col, row, widthCells, heightCells);
		buttons.add(new ChasmButton(label, col, row, widthCells, heightCells, handler));
		return this;
	}

	/** 旧的 1×1 按钮路径（保留给需要精确控制的老代码）。 */
	private ChasmGuiBuilder buttonFixed(String label, int col, int row, ChasmButtonHandler handler) {
		checkBounds(col, row);
		long key = key(col, row);
		cells.add(key);
		buttons.add(new ChasmButton(label, col, row, handler, null, 0, 0, pendingCooldown));
		pendingCooldown = 0;
		return this;
	}

	/** 打开界面回调（服务端）：常用于预设物品（数据绑定 L4）。 */
	public ChasmGuiBuilder onOpen(Consumer<ChasmOpenContext> onOpen) {
		this.onOpen = onOpen;
		return this;
	}

	/** 关闭界面回调（服务端）。 */
	public ChasmGuiBuilder onClose(Runnable onClose) {
		this.onClose = onClose;
		return this;
	}

	/**
	 * 完成声明并注册：创建 {@link MenuType}、写入 {@link ChasmGuiRegistry}。
	 *
	 * @return 不可变的界面描述（可持有用于打开/查询）
	 */
	/**
	 * 声明一个**整数同步键**（走原版 {@code ContainerData}：零自定义包、自动增量、20Hz）。
	 *
	 * <pre>{@code
	 * .data("energy", 0, 0, 10_000)     // 键、默认值、最小值、最大值
	 * // 服务端：menu.setData("energy", menu.data("energy") + 32);
	 * // 客户端：menu.data("energy") / menu.smoothData("energy")（平滑显示值）
	 * }</pre>
	 *
	 * <p><b>范围必须落在 16 位内</b>（-32768~32767，原版线上是 short）；越界会在注册时抛错并提示
	 * 改用 {@link #state(String, com.mojang.serialization.Codec, Object)}。</p>
	 */
	public ChasmGuiBuilder data(String key, int defaultValue, int min, int max) {
		ChasmGuiState.IntSlot slot = new ChasmGuiState.IntSlot(key, defaultValue, min, max);
		for (ChasmGuiState.IntSlot existing : intSlots) {
			if (existing.key().equals(key)) {
				throw new IllegalArgumentException("整数键重复声明: " + key);
			}
		}
		for (ChasmGuiState.ValueKey<?> existing : stateKeys) {
			if (existing.key().equals(key)) {
				throw new IllegalArgumentException("键 " + key + " 已作为大值声明，不可重复用作整数键");
			}
		}
		intSlots.add(slot);
		return this;
	}

	/** 便捷：范围取整数通道的全量程（16 位）。 */
	public ChasmGuiBuilder data(String key, int defaultValue) {
		return data(key, Math.max(WIRE_MIN, defaultValue), WIRE_MIN, WIRE_MAX);
	}

	/**
	 * 声明一个**大值同步键**（任意 codec 类型，走批量快照包）。
	 *
	 * <pre>{@code
	 * .state("status", Codec.STRING, "空闲")
	 * .state("recipe", MyRecipe.CODEC, MyRecipe.NONE)
	 * }</pre>
	 *
	 * <p>大值只在**变化时**随包发送，且同 tick 多次修改会合并成一次，因此比"每 tick 全长轮询"
	 * 便宜得多；适合状态字符串、配方快照、结构化数据。</p>
	 */
	public <T> ChasmGuiBuilder state(String key, com.mojang.serialization.Codec<T> codec, T defaultValue) {
		ChasmGuiState.ValueKey<T> valueKey = new ChasmGuiState.ValueKey<>(key, codec, defaultValue);
		for (ChasmGuiState.ValueKey<?> existing : stateKeys) {
			if (existing.key().equals(key)) {
				throw new IllegalArgumentException("大值键重复声明: " + key);
			}
		}
		for (ChasmGuiState.IntSlot existing : intSlots) {
			if (existing.key().equals(key)) {
				throw new IllegalArgumentException("键 " + key + " 已作为整数声明，不可重复用作大值键");
			}
		}
		stateKeys.add(valueKey);
		return this;
	}

	/**
	 * 声明**绑定方块**时的交互范围（格）：配合 {@code ChasmGui.openAt(player, pos)} 使用，
	 * 玩家超出该范围或换维度时原版会自动关闭界面。默认 {@value ChasmGuiBounds#DEFAULT_RANGE} 格。
	 *
	 * <p>便携界面（{@code open(player)}）不受此范围限制。</p>
	 */
	public ChasmGuiBuilder bindRange(double blocks) {
		if (!(blocks > 0.0) || Double.isNaN(blocks) || Double.isInfinite(blocks)) {
			throw new IllegalArgumentException("bindRange 必须是有限正数，收到 " + blocks);
		}
		this.bindRangeSq = ChasmGuiBounds.square(blocks);
		return this;
	}

	/**
	 * 指定界面主题（可选）。不设置时用默认主题 {@code chasm:default}。
	 *
	 * <p>注意：**资源包换肤不需要调用它** —— 默认主题用的是约定贴图名，
	 * 资源包把同名 PNG 放进 {@code assets/chasm/textures/gui/sprites/} 即可全局换肤。
	 * 本方法用于"某几个界面想要不同皮肤"的场景。</p>
	 */
	/**
	 * 从**大图集**里取一块区域作为面板背景（正确做法，见 {@link BackgroundRegion}）。
	 *
	 * <pre>{@code
	 * // 神化重铸台的真实参数（抄自 ReforgingScreen.renderBg，不要猜）
	 * .backgroundRegion(new BackgroundRegion(
	 *     ResourceLocation.parse("apotheosis:textures/gui/reforge.png"), 256, 384, 0, 0, 176, 266))
	 * }</pre>
	 */
	/**
	 * **固定面板尺寸**（像素）。不设置时按网格推导（{@code 8 + cols*18 + 8} × {@code 115 + rows*18}）。
	 *
	 * <p>移植别人的固定像素界面时（例：神化重铸台 176×266），必须用它，否则底板会被拉伸变形。</p>
	 */
	public ChasmGuiBuilder panelSize(int width, int height) {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("面板尺寸必须为正: " + width + "x" + height);
		}
		this.panelWidth = width;
		this.panelHeight = height;
		return this;
	}

	/**
	 * **玩家物品栏起点**（像素，相对面板）。默认按网格推导；移植别人的界面时对方通常是 (8,184) 之类。
	 */
	public ChasmGuiBuilder playerInventoryAt(int x, int y) {
		if (x < 0 || y < 0) {
			throw new IllegalArgumentException("玩家物品栏起点不可为负");
		}
		this.playerInvX = x;
		this.playerInvY = y;
		return this;
	}

	/**
	 * **背包槽间距**（像素）。默认 18（= 网格单元），但**原版玩家背包本身就是 18**，
	 * 而 Tub… Tetra 这类模组的背包是 **17**（{@code x*17+84}）—— 照抄 18 会越到右边越错位。
	 */
	public ChasmGuiBuilder playerInvSpacing(int spacing) {
		if (spacing < 8 || spacing > 32) {
			throw new IllegalArgumentException("背包槽间距应在 [8,32]，实际 " + spacing);
		}
		this.playerInvSpacing = spacing;
		return this;
	}

	/**
	 * **不要框架底板**（背景完全自己画）。
	 *
	 * <p>声明式界面（{@link #source}）**本来就是**没有底板的，不需要调这个；
	 * 它是给"老式网格界面但想要透明背景"的场景留的显式开关 —— 把"要不要底"从
	 * 框架的猜测变成作者的一句话。</p>
	 */
	public ChasmGuiBuilder noPanel() {
		this.panelSuppressed = true;
		return this;
	}

	/**
	 * **玩家背包的槽框由作者贴图提供**（真实 UI 移植几乎总是如此：对方的贴图里就画好了 36 个格子）。
	 *
	 * <p>声明后框架不再补背包槽框。**不声明**时框架会给声明式界面补上 36 个槽框 ——
	 * 结构性地保证不会出现"槽位在那儿、屏幕上却什么都没画"的幽灵。
	 * 存储槽同理：节点列表里**绑定**了某个槽位（{@code GuiNode.slot}）就等于认领它的外观，
	 * 没被认领的活跃槽位由框架补框。</p>
	 */
	public ChasmGuiBuilder playerInventoryArtProvided() {
		this.playerInventoryArtProvided = true;
		return this;
	}

	/** **快捷栏 y**（像素，相对面板）。不声明时 = 背包底 + 4px。 */
	public ChasmGuiBuilder hotbarAt(int y) {
		if (y < 0) {
			throw new IllegalArgumentException("快捷栏 y 不可为负");
		}
		this.hotbarY = y;
		return this;
	}

	/**
	 * **像素级物品槽**（16×16），坐标相对面板左上角。
	 *
	 * <pre>{@code
	 * // 神化重铸台：主物品槽在 (81,62)，材料槽在 (39,40)
	 * .slotPx(81, 62, stack -> stack.getMaxStackSize() == 1)
	 * }</pre>
	 */
	public ChasmGuiBuilder slotPx(int x, int y, java.util.function.Predicate<net.minecraft.world.item.ItemStack> filter) {
		storageSlots.add(new ChasmStorageSlot(storageSlots.size(), GuiRect.slotAt(x, y), filter));
		return this;
	}

	/**
	 * **像素级物品槽 + 可见性条件**（由**已同步状态**决定此刻在不在）。
	 *
	 * <pre>{@code
	 * // 真实需求（Tetra 加工台）：材料槽只在选中某个槽位时才出现
	 * .slotPx(194, 108, null, menu -> menu.data("modules") >= 0)
	 * }</pre>
	 *
	 * <p>谓词在服务端与客户端各自执行，输入是**同一份同步数据**，所以两边结论一致；
	 * 不可见的槽位在客户端不画、不悬停、不响应点击，也不会被 Shift 快捷移动填充。</p>
	 */
	public ChasmGuiBuilder slotPx(int x, int y, java.util.function.Predicate<net.minecraft.world.item.ItemStack> filter,
								  java.util.function.Predicate<ChasmMenu> visibility) {
		storageSlots.add(new ChasmStorageSlot(storageSlots.size(), GuiRect.slotAt(x, y), filter, visibility));
		return this;
	}

	/** 像素级物品槽（不过滤）。 */
	public ChasmGuiBuilder slotPx(int x, int y) {
		return slotPx(x, y, null);
	}

	/**
	 * **位置由"已同步状态"现算的像素级物品槽**。
	 *
	 * <p>为什么需要它：真实界面里有一类槽位布局**不是常量** —— Tetra 的工具带界面
	 * （{@code ToolbeltContainer.java:36-71}）里 storage 固定在 y=108，quiver/potion/quick
	 * 依次 {@code y = 108 - offset} 往上堆，{@code offset} 随前面每个区块的**行数**累加，
	 * 而行数又取决于该工具带装了几个附件模块（{@code getNumSlots} 对模块效果等级求和）。
	 * 静态声明写不出"每开一次可能不一样"的布局。</p>
	 *
	 * <p>两个函数在**服务端与客户端各跑一次**，输入是同一份菜单（{@code menu.data(key)}
	 * 是原版 {@code ContainerData} 同步过来的值），所以两边算出的位置一致 ——
	 * 这正是"点得到的地方就是看得见的地方"的前提。</p>
	 *
	 * <p>位置随数据变化时，{@link ChasmMenu} 会把对应 {@code Slot} 对象**重建**到新坐标
	 * （原版 {@code Slot.x/y} 是 {@code public final int}，改不了，只能换对象）。</p>
	 *
	 * @param x          面板像素 x（输入：当前菜单；一般读 {@code menu.data(key)} 再算）
	 * @param y          面板像素 y（同上）
	 * @param filter     允许放入的物品判定（null = 不过滤）
	 * @param visibility 该槽此刻是否可见/可用（null = 恒可见）
	 */
	public ChasmGuiBuilder slotPxDynamic(java.util.function.ToIntFunction<ChasmMenu> x,
										 java.util.function.ToIntFunction<ChasmMenu> y,
										 java.util.function.Predicate<net.minecraft.world.item.ItemStack> filter,
										 java.util.function.Predicate<ChasmMenu> visibility) {
		java.util.Objects.requireNonNull(x, "x");
		java.util.Objects.requireNonNull(y, "y");
		storageSlots.add(new ChasmStorageSlot(storageSlots.size(), 0, 0, filter, null, visibility, x, y));
		return this;
	}

	/** 动态位置物品槽（不过滤、恒可见）。 */
	public ChasmGuiBuilder slotPxDynamic(java.util.function.ToIntFunction<ChasmMenu> x,
										 java.util.function.ToIntFunction<ChasmMenu> y) {
		return slotPxDynamic(x, y, null, null);
	}

	/** **像素级按钮**（移植别人 UI 时用：对方给的就是绝对像素矩形）。 */
	public ChasmGuiBuilder buttonPx(String label, int x, int y, int width, int height, ChasmButtonHandler handler) {
		buttons.add(new ChasmButton(label, 0, 0, 1, 1, new GuiRect(x, y, width, height),
			handler, null, 0, 0, pendingCooldown));
		pendingCooldown = 0;
		return this;
	}

	public ChasmGuiBuilder backgroundRegion(BackgroundRegion region) {
		this.backgroundRegion = region;
		return this;
	}

	public ChasmGuiBuilder theme(ResourceLocation themeId) {
		this.themeId = themeId;
		return this;
	}

	// ------------------------------------------------------------ 控件（开放种类）

	/**
	 * 声明一个**滑块**：拖动或方向键调整，值会写回绑定的整数数据键（客户端立即看到同步值）。
	 *
	 * @param key   整数数据键（须先用 {@link #data} 声明）
	 * @param col/row 网格位置
	 * @param widthCells 宽度（格）
	 */
	public ChasmGuiBuilder slider(String key, int col, int row, int widthCells) {
		ChasmGuiState.IntSlot slot = declaredIntSlot(key);
		return widget(ChasmWidgetKinds.SLIDER.id(), slot.key(), slot.key(), col, row, widthCells, 1,
			slot.min(), slot.max(), slot.defaultValue(), null, null, null);
	}

	/** 滑块（自定义值域 + 自定义处理器；不写数据键时由处理器自行处理）。 */
	public ChasmGuiBuilder slider(String key, int col, int row, int widthCells, int min, int max, int initial,
								  ChasmWidgetHandler handler) {
		return widget(ChasmWidgetKinds.SLIDER.id(), key, key, col, row, widthCells, 1, min, max, initial,
			null, null, handler);
	}

	/** 声明一个**只读进度/能量条**，直接展示某个整数数据键的值（含客户端平滑）。 */
	public ChasmGuiBuilder bar(String key, int col, int row, int widthCells, int heightCells) {
		ChasmGuiState.IntSlot slot = declaredIntSlot(key);
		return widget(ChasmWidgetKinds.BAR.id(), slot.key(), slot.key(), col, row, widthCells, heightCells,
			slot.min(), slot.max(), slot.defaultValue(), null, null, null);
	}

	/** 声明一个**开关**（0/1 写入绑定的整数数据键）。 */
	public ChasmGuiBuilder toggle(String key, String label, int col, int row, int widthCells) {
		ChasmGuiState.IntSlot slot = declaredIntSlot(key);
		return widget(ChasmWidgetKinds.TOGGLE.id(), slot.key(), label, col, row, widthCells, 1,
			0, 1, slot.defaultValue(), null, null, null);
	}

	/**
	 * 声明任意种类的控件（**开放扩展点**：第三方注册自己的 {@link ChasmWidgetKind} 即可，
	 * 例如液体槽、滚动列表、标签页）。
	 *
	 * @param kind        控件种类 id（须已注册，否则注册界面时抛错）
	 * @param key         绑定键（数据键或自定义语义键）
	 * @param label       显示名
	 * @param handler     服务端处理器（可 null → 若 key 是数据键则自动回写该键）
	 */
	public ChasmGuiBuilder widget(ResourceLocation kind, String key, String label,
								  int col, int row, int widthCells, int heightCells,
								  int min, int max, int initial,
								  String tooltipKey, String narrationKey, ChasmWidgetHandler handler) {
		if (!ChasmWidgetKinds.isRegistered(kind)) {
			throw new IllegalArgumentException("控件种类 " + kind + " 未注册；第三方控件请先调用 ChasmWidgetKinds.register(...)");
		}
		ChasmWidgetSpec spec = new ChasmWidgetSpec(kind, key, label, col, row, widthCells, heightCells,
			min, max, initial, tooltipKey, narrationKey);
		for (ChasmWidgetSpec existing : widgets) {
			if (existing.key().equals(key)) {
				throw new IllegalArgumentException("控件键重复: " + key + "（每个控件的键必须唯一）");
			}
		}
		widgets.add(spec);
		if (handler != null) {
			widgetHandlers.put(key, handler);
		}
		return this;
	}

	/**
	 * **像素坐标控件**（与 {@code slotPx}/{@code buttonPx} 同一套坐标）。
	 *
	 * <p>为什么需要它：界面面板常用像素布局（例如复刻 Tetra 加工台的 320×240），
	 * 而控件原本只有网格坐标（1 格 18px），混排时对不上。像素模式下
	 * {@code x,y} 是像素位置，{@code width,height} 是像素尺寸。</p>
	 */
	public ChasmGuiBuilder widgetPx(ResourceLocation kind, String key, String label,
									int x, int y, int width, int height,
									int min, int max, int initial,
									String tooltipKey, String narrationKey, ChasmWidgetHandler handler) {
		if (!ChasmWidgetKinds.isRegistered(kind)) {
			throw new IllegalArgumentException("控件种类 " + kind + " 未注册；第三方控件请先调用 ChasmWidgetKinds.register(...)");
		}
		ChasmWidgetSpec spec = new ChasmWidgetSpec(kind, key, label, x, y, width, height,
			min, max, initial, tooltipKey, narrationKey, true);
		for (ChasmWidgetSpec existing : widgets) {
			if (existing.key().equals(key)) {
				throw new IllegalArgumentException("控件键重复: " + key + "（每个控件的键必须唯一）");
			}
		}
		widgets.add(spec);
		if (handler != null) {
			widgetHandlers.put(key, handler);
		}
		return this;
	}

	/** 像素版进度条（值由整数数据键驱动）。 */
	public ChasmGuiBuilder barPx(String key, int x, int y, int width, int height) {
		ChasmGuiState.IntSlot slot = declaredIntSlot(key);
		return widgetPx(ChasmWidgetKinds.BAR.id(), slot.key(), slot.key(), x, y, width, height,
			slot.min(), slot.max(), slot.defaultValue(), null, null, null);
	}

	/** 取已声明的整数数据键（未声明直接报错，并列出可用键）。 */
	private ChasmGuiState.IntSlot declaredIntSlot(String key) {
		for (ChasmGuiState.IntSlot slot : intSlots) {
			if (slot.key().equals(key)) {
				return slot;
			}
		}
		throw new IllegalArgumentException("控件 " + key + " 需要先用 .data(\"" + key
			+ "\", 默认值, 最小值, 最大值) 声明整数数据键；已声明: " + intSlots.stream()
			.map(ChasmGuiState.IntSlot::key).toList());
	}

	/**
	 * **声明式界面**：只写"状态 → 节点列表"这一个函数，绘制/命中/显隐全部由它派生。
	 *
	 * <p>用它可以完全不碰控件与网格坐标；不在列表里的东西**既画不出来也点不到**。</p>
	 */
	public ChasmGuiBuilder source(api.chasm.gui.decl.GuiSource guiSource) {
		this.declSource = guiSource;
		return this;
	}

	/** 注册一个节点动作（节点 {@code action(id, value)} 的 id）。 */
	public ChasmGuiBuilder nodeAction(String id, api.chasm.gui.decl.GuiDeclarations.ActionHandler handler) {
		this.declActions.put(id, handler);
		return this;
	}

	public ChasmGui register() {
		// 未显式声明任何单元 → 整个网格默认全部是存储槽（**只对"纯网格界面"成立**）
		//
		// ⚠️ 2026-09-18 实测的重大坑：这条默认值以前无条件生效，于是"自己声明了像素槽位"的界面
		// （Tetra 加工台声明 4 个、神化重铸台声明 3 个）会被**额外补上一整片 9x1 网格槽**。
		// 那些槽是**真槽位**：在左上角排成一行（像快捷栏），鼠标移上去会泛白高亮、能被 Shift 塞东西、
		// 里面的物品照常显示 —— 而且它们自己占掉了 storage 索引，界面代码读 index 0 拿到的东西都可能错位。
		// 所以：作者声明过像素槽位（storageSlots 非空）或这是声明式界面（source 非空）时，一律不补。
		if (cells.isEmpty() && storageSlots.isEmpty() && declSource == null) {
			int index = 0;
			for (int r = 0; r < rows; r++) {
				for (int c = 0; c < cols; c++) {
					storageSlots.add(new ChasmStorageSlot(index++, c, r));
				}
			}
		}

		ResourceLocation id = ResourceLocation.fromNamespaceAndPath(modId, name);
		ResourceLocation declId = ResourceLocation.fromNamespaceAndPath(modId, name);
		if (declSource != null) {
			// 声明式控件种类先入册（幂等）：界面构建期会校验控件种类是否已注册
			ChasmWidgetKinds.register(ChasmWidgetKinds.DECL);
			// 覆盖整面板的节点控件：它的值就是"命中到的节点序号"
			int coverW = panelWidth > 0 ? panelWidth : 400;
			int coverH = panelHeight > 0 ? panelHeight : 300;
			widgetPx(ChasmWidgetKinds.DECL.id(), "__decl", "", 0, 0, coverW, coverH, 0, 255, 0,
				null, null, (player, value, menu) -> api.chasm.gui.decl.GuiDeclarations.dispatch(
					declId, player, value, new api.chasm.gui.decl.GuiState(menu)));
		}
		if (declSource != null) {
			// 登记"内容源 + 动作表"：客户端据此建节点、服务端据此派发（缺了这一步节点根本不会画）
			api.chasm.gui.decl.GuiDeclarations.register(declId, declSource, declActions);
		}
		ChasmGui gui = new ChasmGui(id, title, background, cols, rows, storageSlots, buttons,
			intSlots, stateKeys, bindRangeSq, themeId, backgroundRegion,
			panelWidth, panelHeight, playerInvX, playerInvY, playerInvSpacing, hotbarY, panelSuppressed,
			playerInventoryArtProvided, widgets, widgetHandlers, onOpen, onClose);

		// MenuType（按钮虚拟槽与物品槽共存：物品槽由 gui.storageSlots 决定，按钮不占容器）
		MenuType<ChasmMenu> menuType = new MenuType<>(
			(int syncId, Inventory playerInventory) ->
				new ChasmMenu(syncId, playerInventory, gui, true),
			FeatureFlags.DEFAULT_FLAGS);

		gui.attachMenuType(menuType);
		Registry.register(BuiltInRegistries.MENU, id, (MenuType<?>) menuType);
		ChasmGuiRegistry.register(gui);
		api.chasm.log.ChasmLogger.info(modId, "注册声明式界面 {} ({}x{}, 存储槽 {} 个, 按钮 {} 个, 整数键 {} 个, 大值键 {} 个)",
			id, cols, rows, gui.storageSlotCount(), buttons.size(), intSlots.size(), stateKeys.size());
		return gui;
	}

	/** 便捷：为玩家打开该界面（服务端调用，等价于 {@code gui.provider()}）。 */
	public SimpleMenuProvider provider() {
		ChasmGui existing = ChasmGuiRegistry.get(ResourceLocation.fromNamespaceAndPath(modId, name));
		return (existing != null ? existing : register()).provider();
	}

	private static long key(int col, int row) {
		return (((long) col) << 32) | (row & 0xffffffffL);
	}

	private void checkBounds(int col, int row) {
		if (col < 0 || col >= cols || row < 0 || row >= rows) {
			throw new IllegalArgumentException(
				"单元 (" + col + "," + row + ") 超出网格 " + cols + "x" + rows + "（先调用 size 声明网格）");
		}
	}

	/** 校验带宽高的元素整体落在网格内。 */
	private void checkFit(String what, int col, int row, int widthCells, int heightCells) {
		if (widthCells < 1 || heightCells < 1) {
			throw new IllegalArgumentException(what + " 的宽高至少 1 格，收到 " + widthCells + "x" + heightCells);
		}
		if (col + widthCells > cols || row + heightCells > rows) {
			throw new IllegalArgumentException(what + " 占地 (" + col + "," + row + ") 起 "
				+ widthCells + "x" + heightCells + " 格，超出网格 " + cols + "x" + rows);
		}
	}

	// ------------------------------------------------------------ 关于"重叠校验"被删掉

	/*
	 * 这里原来是 100 多行的 validateLayout()：按钮不许重叠、控件不许压住槽位、像素控件按矩形相交……
	 * 现在**整个删掉**，因为它是错的问题的一种对答案。
	 *
	 * 正确的问题不是"如何禁止重叠"，而是"重叠时谁在上面"。声明式范式的答案是：
	 *
	 *     列表顺序 = 绘制顺序（z 序） = 命中优先级（从后往前找）
	 *
	 * 于是重叠不再是错误，而是**特性**：想把谁压住就把它放在列表后面。绘制与命中读的是同一份
	 * 列表、同一个坐标空间，所以"看得见却点不到 / 看不见却点得到"在构造上不可能发生。
	 *
	 * 而 validateLayout 这种运行期抛异常的思路，代价是：① 库作者替使用者决定了什么叫"合法布局"；
	 * ② 每加一种元素就要给校验器打一个补丁（像素控件那次 320px 当成 320 格的误报就是这么来的）；
	 * ③ 真正想叠一层高亮/贴花时反而被拦住。删掉它是净收益。
	 */
}
