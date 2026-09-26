package api.chasm.gui;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 一个已注册的声明式界面（不可变布局 + 可扩展回调）。
 *
 * <p>由 {@link ChasmGuiBuilder#register()} 生成并写入 {@link ChasmGuiRegistry}。
 * 同一份描述在服务端（生成 {@link ChasmMenu} 的槽位）与客户端（{@link ChasmScreen}
 * 绘制按钮/槽位）共用，布局无需通过网络分发——仅按钮点击走 C2S 包。</p>
 *
 * <p>字段：</p>
 * <ul>
 *   <li>{@code id}：{@code modId:name} 全局唯一</li>
 *   <li>{@code title}：窗口标题（{@link Component}）</li>
 *   <li>{@code background}：背景九宫格贴图（可为 null，null 表示无自定义背景，客户端用默认灰色）</li>
 *   <li>{@code cols}/{@code rows}：网格尺寸（单元数，每单元 18px）</li>
 *   <li>{@code storageSlots}：真实物品槽（可放/取/预设物品）</li>
 *   <li>{@code buttons}：虚拟按钮（点击发送 C2S）</li>
 *   <li>{@code onOpen}/{@code onClose}：打开/关闭回调（服务端）</li>
 * </ul>
 */
public final class ChasmGui {

	private final ResourceLocation id;
	private final Component title;
	private final ResourceLocation background;
	private final int cols;
	private final int rows;
	private final List<ChasmStorageSlot> storageSlots;
	private final List<ChasmButton> buttons;
	private final List<ChasmGuiState.IntSlot> intSlots;
	private final List<ChasmGuiState.ValueKey<?>> stateKeys;
	private final double bindRangeSq;
	private final ResourceLocation themeId;   // null = 默认主题
	private final BackgroundRegion backgroundRegion;   // 大图集取图（可空）
	private final int panelWidth;    // 0 = 按网格推导
	private final int panelHeight;   // 0 = 按网格推导
	private final int playerInvX;    // 玩家物品栏起点（像素，相对面板）
	private final int playerInvY;
	private final int playerInvSpacing;   // 背包槽间距（0 = 网格单元 18）
	private final int hotbarY;            // 快捷栏 y（0 = 由背包推导）
	private final boolean panelSuppressed; // true = 不画框架底板
	private final boolean playerInventoryArtProvided; // true = 背包槽框由作者贴图提供
	private final List<ChasmWidgetSpec> widgets;
	private final Map<String, ChasmWidgetHandler> widgetHandlers;
	private final Consumer<ChasmOpenContext> onOpen;
	private final Runnable onClose;
	private volatile MenuType<ChasmMenu> menuType; // register() 后设置

	ChasmGui(ResourceLocation id, Component title, ResourceLocation background, int cols, int rows,
		List<ChasmStorageSlot> storageSlots, List<ChasmButton> buttons,
		List<ChasmGuiState.IntSlot> intSlots, List<ChasmGuiState.ValueKey<?>> stateKeys,
		double bindRangeSq, ResourceLocation themeId, BackgroundRegion backgroundRegion,
		int panelWidth, int panelHeight, int playerInvX, int playerInvY, int playerInvSpacing, int hotbarY,
		boolean panelSuppressed, boolean playerInventoryArtProvided,
		List<ChasmWidgetSpec> widgets, Map<String, ChasmWidgetHandler> widgetHandlers,
		Consumer<ChasmOpenContext> onOpen, Runnable onClose) {
		this.id = Objects.requireNonNull(id, "id");
		this.title = Objects.requireNonNull(title, "title");
		this.background = background;
		this.cols = cols;
		this.rows = rows;
		this.storageSlots = List.copyOf(storageSlots);
		this.buttons = List.copyOf(buttons);
		this.intSlots = List.copyOf(intSlots);
		this.stateKeys = List.copyOf(stateKeys);
		this.bindRangeSq = bindRangeSq;
		this.themeId = themeId;
		this.backgroundRegion = backgroundRegion;
		this.panelWidth = panelWidth;
		this.panelHeight = panelHeight;
		this.playerInvX = playerInvX;
		this.playerInvY = playerInvY;
		this.playerInvSpacing = playerInvSpacing;
		this.hotbarY = hotbarY;
		this.panelSuppressed = panelSuppressed;
		this.playerInventoryArtProvided = playerInventoryArtProvided;
		this.widgets = List.copyOf(widgets);
		this.widgetHandlers = Map.copyOf(widgetHandlers);
		this.onOpen = onOpen;
		this.onClose = onClose;
	}

	/** 界面 id（{@code modId:name}）。 */
	public ResourceLocation id() {
		return id;
	}

	/** 窗口标题。 */
	public Component title() {
		return title;
	}

	/** 背景九宫格贴图；null 表示无自定义背景（客户端用默认灰色面板）。 */
	public ResourceLocation background() {
		return background;
	}

	/** 网格列数。 */
	public int cols() {
		return cols;
	}

	/** 网格行数。 */
	public int rows() {
		return rows;
	}

	/** 存储槽位（只读）。 */
	public List<ChasmStorageSlot> storageSlots() {
		return Collections.unmodifiableList(storageSlots);
	}

	/** 存储槽位数量（= 临时容器大小）。 */
	public int storageSlotCount() {
		return storageSlots.size();
	}

	/** 虚拟按钮列表（只读）。 */
	public List<ChasmButton> buttons() {
		return Collections.unmodifiableList(buttons);
	}

	/** 整数同步键（走原版 ContainerData，声明顺序 = 数据槽索引）。 */
	public List<ChasmGuiState.IntSlot> intSlots() {
		return Collections.unmodifiableList(intSlots);
	}

	/** 大值同步键（走批量 codec 快照包）。 */
	public List<ChasmGuiState.ValueKey<?>> stateKeys() {
		return Collections.unmodifiableList(stateKeys);
	}

	/** 是否有任何同步数据键。 */
	public boolean hasSyncData() {
		return !intSlots.isEmpty() || !stateKeys.isEmpty();
	}

	/** 控件列表（只读，索引即控件索引）。 */
	public List<ChasmWidgetSpec> widgets() {
		return Collections.unmodifiableList(widgets);
	}

	/** 按索引取控件（越界返回 null）。 */
	public ChasmWidgetSpec widgetByIndex(int index) {
		return index >= 0 && index < widgets.size() ? widgets.get(index) : null;
	}

	/** 按键取控件处理器（未声明的控件返回 null → 服务端回写绑定的数据键）。 */
	public ChasmWidgetHandler widgetHandler(String key) {
		return widgetHandlers.get(key);
	}

	/** 可聚焦控件索引（升序；键盘 Tab 轮转用）。 */
	public List<Integer> focusableWidgetIndexes() {
		List<Integer> out = new java.util.ArrayList<>();
		for (int i = 0; i < widgets.size(); i++) {
			ChasmWidgetKind kind = ChasmWidgetKinds.get(widgets.get(i).kind());
			if (kind != null && kind.focusable()) {
				out.add(i);
			}
		}
		return out;
	}

	/**
	 * 大图集取图（可空）。用于"别人的 GUI 是一张 256x384 大图，主面板只是其中一块区域"的场景。
	 *
	 * <p>来源（真实移植案例）：神化重铸台 {@code reforge.png} 是 256x384 图集，
	 * 主底板 = {@code (u=0,v=0,w=176,h=266)}，副框 = {@code (u=20+46i, v=273, 46, 35)}。
	 * 这些参数必须从源码里抄，不能猜。</p>
	 */
	public BackgroundRegion backgroundRegion() {
		return backgroundRegion;
	}

	/** 面板宽（像素；0 = 按网格推导）。 */
	public int panelWidth() {
		return panelWidth;
	}

	/** 面板高（像素；0 = 按网格推导）。 */
	public int panelHeight() {
		return panelHeight;
	}

	/** 玩家物品栏起点 x（像素，相对面板；移植固定像素 UI 时用）。 */
	public int playerInvX() {
		return playerInvX;
	}

	/** 玩家物品栏起点 y（像素，相对面板）。 */
	public int playerInvY() {
		return playerInvY;
	}

	/** 背包槽间距（像素；0 = 用网格单元 {@link ChasmMenu#CELL}）。Tetra 的真实值是 17。 */
	public int playerInvSpacing() {
		return playerInvSpacing;
	}

	/** 快捷栏 y（像素；0 = 由背包推导）。 */
	public int hotbarY() {
		return hotbarY;
	}

	/** 是否抑制框架底板（见 {@code ChasmGuiBuilder.noPanel()}）。 */
	public boolean panelSuppressed() {
		return panelSuppressed;
	}

	/** 玩家背包的槽框是否由作者贴图提供（false = 框架补 36 个槽框）。 */
	public boolean playerInventoryArtProvided() {
		return playerInventoryArtProvided;
	}

	/** 玩家背包布局（服务端建槽 / 客户端画框 / 声明式节点共用的唯一来源）。 */
	public PlayerInventoryLayout playerInventory() {
		return PlayerInventoryLayout.of(this);
	}

	/** 主题 id（null = 默认主题 {@code chasm:default}）。资源包换肤无需改这里。 */
	public ResourceLocation themeId() {
		return themeId;
	}

	/** 绑定方块的交互范围平方（仅对 {@code openAt} 打开的界面生效）。 */
	public double bindRangeSq() {
		return bindRangeSq;
	}

	/** 打开回调（服务端；可能为 null）。 */
	public Consumer<ChasmOpenContext> onOpen() {
		return onOpen;
	}

	/** 关闭回调（服务端；可能为 null）。 */
	public Runnable onClose() {
		return onClose;
	}

	/** 已注册的 {@code MenuType}（register() 之后非 null）。 */
	public MenuType<ChasmMenu> menuType() {
		return menuType;
	}

	/** 按按钮名索引一个按钮；不存在返回 null。 */
	public ChasmButton buttonByIndex(int index) {
		return index >= 0 && index < buttons.size() ? buttons.get(index) : null;
	}

	void attachMenuType(MenuType<ChasmMenu> mt) {
		if (menuType != null) {
			throw new IllegalStateException("GUI " + id + " 的 MenuType 已注册");
		}
		this.menuType = mt;
	}

	/**
	 * 为该界面生成一个 {@link SimpleMenuProvider}，配合 {@code player.openMenu(...)} 在服务端弹出。
	 *
	 * <p>创建的是带服务端玩家的 {@link ChasmMenu}（执行 onOpen 预填、关窗归还）。</p>
	 */
	public SimpleMenuProvider provider() {
		return new SimpleMenuProvider(
			(int syncId, Inventory playerInventory, Player player) ->
				new ChasmMenu(syncId, playerInventory, this, player, false),
			title);
	}

	/**
	 * 打开界面（便携，不校验距离）：服务端调用。
	 *
	 * @param player 目标玩家
	 */
	public void open(net.minecraft.server.level.ServerPlayer player) {
		player.openMenu(provider());
	}

	/**
	 * 打开**绑定到某方块**的界面（服务端调用）：玩家走远或换维度后原版会自动关窗。
	 *
	 * <pre>{@code
	 * // 机器的右键交互里
	 * ExampleMod.MACHINE_PANEL.openAt(serverPlayer, pos);
	 * }</pre>
	 *
	 * @param player 目标玩家
	 * @param pos    绑定方块（机器/容器位置）
	 */
	public void openAt(net.minecraft.server.level.ServerPlayer player, net.minecraft.core.BlockPos pos) {
		net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension =
			player.level().dimension();
		// 若该位置是方块实体且它实现了 Container，就直接用它的库存（内容随方块实体存档持久化）
		ChasmStorage storage = null;
		if (player.level().getBlockEntity(pos) instanceof net.minecraft.world.Container container) {
			storage = ChasmStorage.of(container);
		}
		final ChasmStorage boundStorage = storage;
		player.openMenu(new net.minecraft.world.MenuProvider() {
			@Override
			public Component getDisplayName() {
				return title;
			}

			@Override
			public net.minecraft.world.inventory.AbstractContainerMenu createMenu(int syncId, Inventory playerInventory,
																				 Player p) {
				return new ChasmMenu(syncId, playerInventory, ChasmGui.this, p, false, pos, dimension, boundStorage);
			}
		});
		// 把绑定信息告诉客户端（仅用于显示/绘制；权限判定始终在服务端）
		ChasmGuiChannel.sendBinding(player, player.containerMenu.containerId, pos, dimension);
	}

	/**
	 * 用**指定的存储来源**打开界面（服务端）：内容放哪、关窗怎么处理由调用方决定。
	 *
	 * <pre>{@code
	 * MACHINE.openWith(player, ChasmStorage.of(myBlockEntityContainer));   // 机器：内容存在 BE 里
	 * BOX.openWith(player, ChasmStorage.ofItem(player.getMainHandItem(), 9)); // 便携盒：内容存在物品里
	 * }</pre>
	 */
	public void openWith(net.minecraft.server.level.ServerPlayer player, ChasmStorage storage) {
		player.openMenu(new net.minecraft.world.MenuProvider() {
			@Override
			public Component getDisplayName() {
				return title;
			}

			@Override
			public net.minecraft.world.inventory.AbstractContainerMenu createMenu(int syncId, Inventory playerInventory,
																				 Player p) {
				return new ChasmMenu(syncId, playerInventory, ChasmGui.this, p, false, null, null, storage);
			}
		});
	}

	/**
	 * 便携容器：把界面内容存进**物品自带的容器组件**（{@code minecraft:container}），关窗后内容仍在物品里。
	 *
	 * <p>关窗时若该物品已不在玩家身上（被丢出/被拿走），内容会掉落到地面而不是消失。</p>
	 *
	 * @param player 目标玩家
	 * @param stack  承载内容的物品栈（服务端持有的那一份）
	 */
	public void openItem(net.minecraft.server.level.ServerPlayer player, net.minecraft.world.item.ItemStack stack) {
		openWith(player, ChasmStorage.ofItem(stack, storageSlotCount()));
	}
}