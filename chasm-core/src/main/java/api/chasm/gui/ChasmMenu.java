package api.chasm.gui;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/**
 * Chasm 声明式界面的服务端/客户端容器菜单。
 *
 * <p>同时承担两种角色，由 {@code clientSide} 区分：</p>
 * <ul>
 *   <li><b>服务端</b>（{@code clientSide=false}）：持有 {@link ChasmStorage} 决定的容器（临时/外部/物品自带），
 *       玩家打开时执行 {@code onOpen} 预填物品，关窗时把槽内物品归还玩家。</li>
 *   <li><b>客户端</b>（{@code clientSide=true}）：仅复刻槽位布局（容器内容为空），
 *       供 {@code ChasmScreen} 绘制；内容由服务端逐槽/整包同步。</li>
 * </ul>
 *
 * <p>存储槽（物品）与虚拟按钮（{@link ChasmButton}，不占容器）在网格中共存：
 * 存储槽来自 {@link ChasmGui#storageSlots()}，按钮不参与此容器、仅由客户端屏幕命中测试。</p>
 */
public final class ChasmMenu extends AbstractContainerMenu {

	/** 网格单元尺寸（像素）。 */
	public static final int CELL = 18;

	/**
	 * **网格原点**（面板像素）：旧式网格布局的 (0,0) 单元左上角。
	 *
	 * <p>是全框架**唯一**的网格原点定义 —— 槽位、控件、声明式节点都从这里换算，
	 * 不再各自写 {@code +8 / +18}。</p>
	 */
	public static final int GRID_X = 8;
	public static final int GRID_Y = 18;

	private final ChasmGui gui;
	private final boolean clientSide;
	private final ChasmStorage storage;
	private final Player player; // 服务端持有；客户端为 null
	private final ChasmGuiState state;   // 服务端权威；客户端为已同步副本
	private BlockPos boundPos;           // 绑定的方块（null = 便携界面，不校验距离）
	private ResourceKey<Level> boundDimension;

	/**
	 * 供 {@code MenuType} 使用（客户端反解或服务端快捷创建的入口，不带玩家）。
	 *
	 * @param syncId          容器同步 id
	 * @param playerInventory 玩家背包
	 * @param gui             所属界面描述
	 * @param clientSide      true=客户端界面（不执行 onOpen / 归还逻辑）
	 */
	public ChasmMenu(int syncId, Inventory playerInventory, ChasmGui gui, boolean clientSide) {
		this(syncId, playerInventory, gui, null, clientSide);
	}

	/**
	 * 服务端由 {@code SimpleMenuProvider} 创建：带玩家实体，可执行 onOpen 预填与关窗归还。
	 *
	 * @param player 服务端玩家（客户端反解时传 null）
	 */
	public ChasmMenu(int syncId, Inventory playerInventory, ChasmGui gui, Player player, boolean clientSide) {
		this(syncId, playerInventory, gui, player, clientSide, null, null);
	}

	/**
	 * 带**方块绑定**的构造：非 null 时 {@link #stillValid} 会校验维度一致 + 玩家在绑定范围内，
	 * 防止"隔着半张地图操作机器"（见 {@link ChasmGuiBounds}）。
	 *
	 * @param boundPos       绑定方块（null = 便携界面）
	 * @param boundDimension 绑定维度（null = 便携界面）
	 */
	public ChasmMenu(int syncId, Inventory playerInventory, ChasmGui gui, Player player, boolean clientSide,
					 BlockPos boundPos, ResourceKey<Level> boundDimension) {
		this(syncId, playerInventory, gui, player, clientSide, boundPos, boundDimension, null);
	}

	/**
	 * 全参构造：可指定**存储来源**（临时 / 外部容器 / 物品自带容器）。
	 *
	 * @param storage 存储来源；null = 临时存储（关窗归还玩家，旧行为）
	 */
	public ChasmMenu(int syncId, Inventory playerInventory, ChasmGui gui, Player player, boolean clientSide,
					 BlockPos boundPos, ResourceKey<Level> boundDimension, ChasmStorage storage) {
		super(Objects.requireNonNull(gui.menuType(), "menuType"), syncId);
		this.boundPos = boundPos;
		this.boundDimension = boundDimension;
		this.gui = gui;
		this.clientSide = clientSide;
		this.player = player;
		this.storage = storage != null ? storage
			: api.chasm.gui.ChasmStorage.transientStorage(gui.storageSlotCount());
		this.state = new ChasmGuiState(gui.intSlots(), gui.stateKeys());

		// 存储槽（网格坐标 → 像素，起点 (8, 18)，见 ChasmScreen 绘制坐标）
		// 位置带菜单：声明了 slotPxDynamic 的槽位由**当前同步状态**现算（见 refreshLayout）
		for (ChasmStorageSlot s : gui.storageSlots()) {
			this.addSlot(createStorageSlot(s));
		}

		// 玩家背包（3 行）+ 快捷栏（1 行）：坐标全部来自 PlayerInventoryLayout（与客户端绘制、声明式节点同源）
		PlayerInventoryLayout bag = PlayerInventoryLayout.of(gui);
		for (int row = 0; row < 3; row++) {
			for (int c = 0; c < 9; c++) {
				int invIndex = c + (row + 1) * 9;
				this.addSlot(new Slot(playerInventory, invIndex, bag.slotX(invIndex), bag.slotY(invIndex)));
			}
		}
		for (int c = 0; c < 9; c++) {
			this.addSlot(new Slot(playerInventory, c, bag.slotX(c), bag.slotY(c)));
		}

		// 数据同步：整数键注册为原版数据槽（两侧同一顺序 → id 对齐；原版每 tick 只发变化槽）
		if (state.hasIntData()) {
			this.addDataSlots(state.containerData());
		}

		// 服务端专属：打开即预填（onOpen 数据绑定）
		if (!clientSide && player != null && gui.onOpen() != null) {
			try {
				gui.onOpen().accept(new ChasmOpenContext(player, this));
			} catch (RuntimeException e) {
				api.chasm.log.ChasmLogger.error(gui.id().getNamespace(), "onOpen 回调异常: {}", e.toString());
			}
		}
		// onOpen 可能刚写下"槽位数"这类状态 → 动态位置的槽位在此刻归位
		refreshLayout();
	}

	/**
	 * **建一个存储槽**（含物品过滤 + 可见性 + 动态位置）。
	 *
	 * <p>抽成一个方法是为了 {@link #refreshLayout()} 能"换掉"已经建好的槽位 ——
	 * 原版 {@code Slot.x/y} 是 {@code public final int}（javap 已核），位置变了只能换对象。</p>
	 */
	private Slot createStorageSlot(ChasmStorageSlot s) {
		// 带物品过滤 + 可见性的槽位：filter 决定"能放什么"，visibility 决定"此刻在不在"
		return new Slot(this.storage.container(), s.index(), s.pixelX(this), s.pixelY(this)) {
			@Override
			public boolean mayPlace(net.minecraft.world.item.ItemStack stack) {
				// 不可见的槽位也不该被 Shift 快捷移动填进去
				return s.isActive(ChasmMenu.this) && s.mayPlace(stack);
			}

			@Override
			public boolean isActive() {
				return s.isActive(ChasmMenu.this);
			}
		};
	}

	/**
	 * **把"位置由状态现算"的存储槽挪到新坐标**（声明式界面的动态布局）。
	 *
	 * <p>触发点有两个，缺一不可：</p>
	 * <ol>
	 *   <li><b>客户端</b>：{@link #setData(int, int)} —— 原版
	 *       {@code ClientPacketListener.handleContainerSetData} 收到 {@code ContainerData}
	 *       变化后就是调用它，位置在这里才第一次知道真实槽位数；</li>
	 *   <li><b>服务端</b>：构造函数里 onOpen 之后 —— 那时"槽位数"已写进状态。</li>
	 * </ol>
	 *
	 * <p>只重建**位置真的变了**的槽位：没变就不动，避免每 tick 制造垃圾对象。
	 * 位置没声明动态函数的槽位一律跳过（老界面行为零变化）。</p>
	 */
	public void refreshLayout() {
		java.util.List<ChasmStorageSlot> declared = gui.storageSlots();
		int count = Math.min(declared.size(), this.slots.size());
		for (int i = 0; i < count; i++) {
			ChasmStorageSlot s = declared.get(i);
			if (!s.isDynamic()) {
				continue;
			}
			Slot current = this.slots.get(i);
			int x = s.pixelX(this);
			int y = s.pixelY(this);
			if (current.x == x && current.y == y) {
				continue;
			}
			this.slots.set(i, createStorageSlot(s));
		}
	}

	/**
	 * 原版数据槽写入点（客户端收到 {@code ClientboundContainerSetDataPacket} 时调用）。
	 *
	 * <p>覆写它只为动态布局：值一到位就把槽位挪过去。其余行为逐字沿用父类。</p>
	 */
	@Override
	public void setData(int id, int value) {
		super.setData(id, value);
		refreshLayout();
	}

	/** 所属界面描述。 */
	public ChasmGui gui() {
		return gui;
	}

	/**
	 * **这一帧界面状态的廉价签名**：全部 int 数据键 + 全部大值键 + 全部槽位物品身份。
	 *
	 * <p>{@link api.chasm.gui.decl.client.DeclClient#nodes} 用它做"节点列表签名缓存"：
	 * 签名相同就复用上一帧已经建好的节点列表，不再每帧重建。
	 * 槽内物品用 {@code getComponents().hashCode()} 参与，所以模块数据（ModuleData 组件）变了也会 miss。</p>
	 *
	 * <p>注意：界面源若还依赖菜单之外的东西（数据包重载、语言切换、玩家经验…），
	 * 由 DeclClient 的"最大缓存年龄"兜底（见那边的常量），不会出现永久过期。</p>
	 */
	public long signature() {
		ChasmGuiState st = state();
		long h = gui.hashCode();
		for (ChasmGuiState.IntSlot slot : st.intSlots()) {
			h = h * 31 + st.intValue(slot.key());
		}
		for (ChasmGuiState.ValueKey<?> key : st.valueKeys()) {
			Object value = st.value(key.key());
			h = h * 31 + (value == null ? 0 : value.hashCode());
		}
		for (int i = 0; i < this.slots.size(); i++) {
			net.minecraft.world.item.ItemStack stack = this.slots.get(i).getItem();
			if (stack == null || stack.isEmpty()) {
				h = h * 31;
			} else {
				h = h * 31 + stack.getItem().hashCode();
				h = h * 31 + stack.getCount();
				h = h * 31 + stack.getComponents().hashCode();
			}
		}
		return h;
	}

	/** 数据状态（服务端权威 / 客户端副本）。 */
	public ChasmGuiState state() {
		return state;
	}

	/** 绑定的方块坐标（便携界面为 null）。 */
	public BlockPos boundPos() {
		return boundPos;
	}

	/** 是否绑定了方块（机器/容器类界面）。 */
	public boolean isBound() {
		return boundPos != null && boundDimension != null;
	}

	/** 客户端：接收服务端下发的绑定信息（仅用于显示/绘制，不参与权限判定）。 */
	public void acceptBinding(BlockPos pos, ResourceKey<Level> dimension) {
		this.boundPos = pos;
		this.boundDimension = dimension;
	}

	// ------------------------------------------------------------ 数据绑定

	/** 读取整数键（服务端读权威值，客户端读已同步值）。 */
	public int data(String key) {
		return state.intValue(key);
	}

	/** 写入整数键（服务端；自动夹取范围，原版数据槽会在本 tick 增量同步）。 */
	public int setData(String key, int value) {
		int result = state.setInt(key, value);
		// 动态布局与"已同步容量"保持一致：服务端在 onOpen/运行期写容量时也要立刻归位
		// （客户端那份走覆写的 setData(int,int)，见上方说明）
		refreshLayout();
		return result;
	}

	/** 增减整数键（服务端）。 */
	public int addData(String key, int delta) {
		return state.setInt(key, state.intValue(key) + delta);
	}

	/** 是否声明了该整数数据键。 */
	public boolean hasData(String key) {
		return state.hasIntKey(key);
	}

	/** 读取大值键。 */
	public <T> T state(String key) {
		return state.value(key);
	}

	/** 写入大值键（服务端；值未变不发包，同 tick 多次修改合并为一次）。 */
	public <T> void setState(String key, T value) {
		state.setValue(key, value);
	}

	/** 客户端：接收一批已编码的大值。 */
	public void receiveState(net.minecraft.nbt.CompoundTag values) {
		state.receiveAll(values);
	}

	/** 客户端：推进平滑显示值（每客户端 tick 一次）。 */
	public void tickSmoothClient(double factor) {
		state.tickSmooth(factor);
	}

	/** 客户端：平滑显示值（渲染帧读取，避免 20Hz 数值一格一格跳）。 */
	public double smoothData(String key) {
		return state.smooth(key);
	}

	/**
	 * 服务端每 tick 的同步点：先让原版同步物品槽与整数数据槽，再把本 tick 变化的大值批量发出。
	 */
	@Override
	public void broadcastChanges() {
		super.broadcastChanges();
		if (clientSide || !(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) {
			return;
		}
		// 物品自带容器：周期性落盘（内容变过才写），把崩溃丢失窗口压到几秒
		storage.tick(player);
		if (!state.hasDirty()) {
			return;
		}
		ChasmGuiStateChannel.send(serverPlayer, this.containerId, state.encodeDirty());
	}

	/** 是否为客户端界面副本。 */
	public boolean isClientSide() {
		return clientSide;
	}

	/** 在指定存储槽放入物品（供 onOpen 预填 / 外部写入；经由 broadcastChanges 增量同步）。 */
	public void setItem(int slotIndex, ItemStack stack) {
		if (slotIndex < 0 || slotIndex >= gui.storageSlotCount()) {
			return;
		}
		Slot slot = this.slots.get(slotIndex);
		slot.set(stack);
		slot.setChanged();
	}

	/**
	 * 读取指定存储槽物品。
	 *
	 * <p><b>必须读 {@link Slot}，不能读本地容器</b>：客户端那份容器是个空壳，真正同步过来的物品
	 * 落在原版槽位上。以前读容器导致客户端永远看到"空的" —— 于是所有基于物品的界面判断
	 * （材料够不够、按钮亮不亮）在客户端全部为假，表现为"明明有 64 个铁锭却说材料不足 / 点不动"。</p>
	 */
	public ItemStack getItem(int slotIndex) {
		if (slotIndex < 0 || slotIndex >= gui.storageSlotCount()) {
			return ItemStack.EMPTY;
		}
		return this.slots.get(slotIndex).getItem();
	}

	/**
	 * **玩家背包物品**（inventoryIndex：0..8 快捷栏，9..35 背包）。
	 *
	 * <p>同样必须走同步槽位：客户端那份 Player 背包是空的，真值在原版槽位上。
	 * 界面里"背包中哪些格子能当材料"这类判断全靠它。</p>
	 */
	public ItemStack playerInventoryItem(int inventoryIndex) {
		if (inventoryIndex < 0 || inventoryIndex >= PlayerInventoryLayout.SLOTS) {
			return ItemStack.EMPTY;
		}
		int storageCount = gui.storageSlotCount();
		int offset = inventoryIndex < 9
			? 27 + inventoryIndex                                    // 快捷栏：背包之后紧跟的 9 个
			: (inventoryIndex - 9) / 9 * 9 + (inventoryIndex - 9) % 9; // 背包 3 行
		int slot = storageCount + offset;
		return slot < this.slots.size() ? this.slots.get(slot).getItem() : ItemStack.EMPTY;
	}

	/** 底层存储来源。 */
	public ChasmStorage storageSource() {
		return storage;
	}

	/** 底层容器（服务端权威内容）。 */
	public Container storage() {
		return storage.container();
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		ItemStack result = ItemStack.EMPTY;
		Slot slot = this.slots.get(index);
		if (slot != null && slot.hasItem()) {
			ItemStack stack = slot.getItem();
			result = stack.copy();
			int playerStart = gui.storageSlotCount();
			if (index < playerStart) {
				if (!this.moveItemStackTo(stack, playerStart, this.slots.size(), true)) {
					return ItemStack.EMPTY;
				}
			} else {
				if (!this.moveItemStackTo(stack, 0, playerStart, false)) {
					return ItemStack.EMPTY;
				}
			}
			if (stack.isEmpty()) {
				slot.setByPlayer(ItemStack.EMPTY);
			} else {
				slot.setChanged();
			}
			if (stack.getCount() == result.getCount()) {
				return ItemStack.EMPTY;
			}
			slot.onTake(player, stack);
		}
		return result;
	}

	/**
	 * 有效性判定。
	 *
	 * <ul>
	 *   <li><b>未绑定方块</b>（便携界面）：只判存活，保持"随手可用"。</li>
	 *   <li><b>已绑定方块</b>（机器/容器）：维度必须一致，且玩家在
	 *       {@link ChasmGui#bindRangeSq()} 范围内；否则原版会主动关窗。</li>
	 *   <li>客户端副本恒 true（原版只在该关闭时由服务端驱动）。</li>
	 * </ul>
	 */
	@Override
	public boolean stillValid(Player player) {
		if (clientSide) {
			return true;
		}
		if (boundPos == null || boundDimension == null) {
			return this.player == null || this.player.isAlive();
		}
		return ChasmGuiBounds.inRange(player.level().dimension(), player.getX(), player.getY(), player.getZ(),
			boundDimension, boundPos, gui.bindRangeSq());
	}

	@Override
	public void removed(Player player) {
		super.removed(player);
		if (clientSide || this.player == null) {
			return;
		}
		// 服务端关窗：触发 onClose 回调，并把槽内未消耗物品归还玩家背包
		if (gui.onClose() != null) {
			try {
				gui.onClose().run();
			} catch (RuntimeException e) {
				api.chasm.log.ChasmLogger.error(gui.id().getNamespace(), "onClose 回调异常: {}", e.toString());
			}
		}
		// 存储收尾：临时存储把物品还回玩家背包；外部容器/物品容器则在这里落盘
		storage.onClosed(this.player);
	}
}