package api.chasm.blockentity;

import api.chasm.log.ChasmLogger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Chasm 方块实体模板壳（BlockEntity Behaviour 容器 + 生命周期编排）。
 *
 * <p>设计来源：Create-Fly 的 {@code SmartBlockEntity} + 森罗物语（KaleidoscopeCookery）的
 * {@code BaseBlockEntity#refresh()} 同步约定（第十六/十七步分析）。</p>
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li><b>行为容器</b>：按 {@link BehaviourType} 键控存储 {@link ChasmBlockEntityBehaviour}，
 *       子类在 {@link #addBehaviours(List)} 声明自带行为，同时自动注入全局注册表
 *       （{@link ChasmBlockEntityBehaviour#REGISTRY}）中第三方附加的行为。</li>
 *   <li><b>生命周期编排</b>：懒初始化（首次 tick）/ 懒 tick 节流 / 行为 tick /
 *       读 NBT（首读时注入 {@code FIRST_READ_REGISTRY}）/ 写 NBT / 卸载销毁。</li>
 *   <li><b>同步</b>：{@link #refresh()}（{@code setChanged + sendBlockUpdated}）与
 *       {@link #sendData()}（区块脏标记），客户端专属数据走 update tag（clientPacket=true）。</li>
 * </ul>
 *
 * <p><b>使用约束</b>：{@link #addBehaviours} 在父类构造器中调用，此时子类字段尚未初始化，
 * 行为必须使用「惰性字段访问」（如 {@code be -> be.inventory} 的函数式取用）或推迟到
 * {@link #addBehavioursDeferred(List)} 之后。</p>
 */
public abstract class ChasmBlockEntity extends BlockEntity {

	private final Map<BehaviourType<?>, ChasmBlockEntityBehaviour<?>> behaviours = new java.util.HashMap<>();

	/** 懒 tick 频率（默认 10 tick）。 */
	protected int lazyTickRate;
	/** 懒 tick 计数器。 */
	protected int lazyTickCounter;
	/** 是否已初始化。 */
	private boolean initialized;
	/** 是否已首次读 NBT。 */
	private boolean firstNbtRead = true;
	/** 是否仍需每 tick 调度（空闲机器可关掉，省去每 tick 遍历行为）。默认 true。 */
	private boolean active = true;

	protected ChasmBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
		setLazyTickRate(10);
		// 1) 子类声明自带行为
		ArrayList<ChasmBlockEntityBehaviour<?>> list = new ArrayList<>();
		addBehaviours(list);
		for (ChasmBlockEntityBehaviour<?> behaviour : list) {
			behaviours.put(behaviour.getType(), behaviour);
		}
		// 2) 全局注册表注入（第三方可附加行为）
		for (Function<ChasmBlockEntity, ChasmBlockEntityBehaviour<?>> factory :
			ChasmBlockEntityBehaviour.REGISTRY.getOrDefault(getType(), List.of())) {
			ChasmBlockEntityBehaviour<?> behaviour = factory.apply(this);
			behaviours.put(behaviour.getType(), behaviour);
		}
	}

	/**
	 * 声明自带行为（构造期调用；子类字段此时尚未初始化，须惰性访问）。
	 *
	 * @param behaviours 行为列表（追加 {@code new SomeBehaviour<>(this, ...)}）
	 */
	public abstract void addBehaviours(List<ChasmBlockEntityBehaviour<?>> behaviours);

	/**
	 * 延迟声明行为：首次读 NBT 前调用，可访问已读入的自定义 BE 数据。
	 *
	 * <p>默认空实现；依赖自定义数据的子类在此追加行为。</p>
	 *
	 * @param behaviours 行为列表
	 */
	public void addBehavioursDeferred(List<ChasmBlockEntityBehaviour<?>> behaviours) {
	}

	/** 初始化（首次 tick 或附加行为时调用）：注入首读/客户端行为 + 逐行为 initialize + 一次 lazyTick。 */
	public void initialize() {
		injectDeferredBehaviours();
		if (level != null && level.isClientSide()) {
			for (Function<ChasmBlockEntity, ChasmBlockEntityBehaviour<?>> factory :
				ChasmBlockEntityBehaviour.CLIENT_REGISTRY.getOrDefault(getType(), List.of())) {
				ChasmBlockEntityBehaviour<?> behaviour = factory.apply(this);
				behaviours.put(behaviour.getType(), behaviour);
			}
		}
		forEachBehaviour(ChasmBlockEntityBehaviour::initialize);
		lazyTick();
	}

	/**
	 * 注入「首次读 NBT」期行为（deferred 自带行为 + {@code FIRST_READ_REGISTRY} 第三方行为）。
	 *
	 * <p>在 {@link #initialize()}（新放置 BE 首 tick）与 {@link #loadAdditional}（磁盘加载/客户端
	 * 更新）两条路径共用，由 {@link #firstNbtRead} 保证只注入一次，服务端/客户端行为集一致。</p>
	 */
	private void injectDeferredBehaviours() {
		if (!firstNbtRead) {
			return;
		}
		firstNbtRead = false;
		ArrayList<ChasmBlockEntityBehaviour<?>> list = new ArrayList<>();
		addBehavioursDeferred(list);
		for (ChasmBlockEntityBehaviour<?> behaviour : list) {
			behaviours.put(behaviour.getType(), behaviour);
		}
		for (Function<ChasmBlockEntity, ChasmBlockEntityBehaviour<?>> factory :
			ChasmBlockEntityBehaviour.FIRST_READ_REGISTRY.getOrDefault(getType(), List.of())) {
			ChasmBlockEntityBehaviour<?> behaviour = factory.apply(this);
			behaviours.put(behaviour.getType(), behaviour);
		}
	}

	/** 主 tick（由 {@link ChasmBlockEntityTicker} 驱动）：懒初始化 + 懒 tick 节流 + 行为 tick。 */
	public void tick() {
		if (!initialized && hasLevel()) {
			initialize();
			initialized = true;
		}
		if (!active) {
			return; // P0-1 按需：空闲机器跳过行为遍历，仅在 initialize 首帧后省 tick
		}
		if (lazyTickCounter-- <= 0) {
			lazyTickCounter = lazyTickRate;
			lazyTick();
		}
		// 容错：逐行为 try/catch，坏行为只记日志不崩服
		for (ChasmBlockEntityBehaviour<?> behaviour : behaviours.values()) {
			try {
				behaviour.tick();
			} catch (Throwable ex) {
				ChasmLogger.error("chasm", "行为 {} tick 异常（已隔离，不影响服务器）: {}",
					behaviour.getType(), String.valueOf(ex));
			}
		}
	}

	/** 低频 tick（默认空实现，子类可重写）。 */
	public void lazyTick() {
	}

	// —— 序列化 ——

	/** 当前是否仍需每 tick 调度。 */
	public boolean isActive() {
		return active;
	}

	/** 开/关每 tick 调度（空闲机器调 setActive(false)，在需要时 markActive() 唤醒）。 */
	public void setActive(boolean active) {
		this.active = active;
	}

	/** 唤醒：恢复每 tick 调度（放入食材/邻居热源变化等时机调用）。 */
	public void markActive() {
		this.active = true;
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		forEachBehaviour(b -> b.write(tag, false));
	}

	/**
	 * 统一读钩子：1.21.1 中 {@code loadWithComponents}（final）在<b>磁盘加载与客户端更新</b>
	 * 两条路径都会先调用本方法，故行为读取在此统一汇聚（clientPacket 参数对框架恒为 false，
	 * 行为读写应使用相同键名，读取时对缺键用默认值兜底）。
	 */
	@Override
	public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		injectDeferredBehaviours();
		super.loadAdditional(tag, registries);
		forEachBehaviour(b -> b.read(tag, false));
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		CompoundTag tag = super.getUpdateTag(registries);
		forEachBehaviour(b -> b.write(tag, true));
		return tag;
	}

	@Override
	@Nullable
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	// —— 同步（源自森罗物语 BaseBlockEntity#refresh） ——

	/** 标记已变更 + 向客户端全量广播方块更新（视觉/渲染刷新用）。 */
	public void refresh() {
		setChanged();
		if (level != null) {
			BlockState state = level.getBlockState(worldPosition);
			level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_ALL);
		}
	}

	/** 仅标记区块脏（如进度类数据更新，不强制客户端刷新）。 */
	public void sendData() {
		setChanged();
	}

	// —— 生命周期终结 ——

	@Override
	public void setRemoved() {
		super.setRemoved();
		// 1.21.1 无 onChunkUnloaded 钩子（已被移除）；setRemoved 在方块被破坏时由原版调用，
		// 统一做 remove()（子类断开连接钩子）+ invalidate()（遍历行为 unload）
		remove();
		invalidate();
	}

	/** 方块被移除/区块卸载，通常失效能力（遍历行为 unload）。 */
	public void invalidate() {
		forEachBehaviour(ChasmBlockEntityBehaviour::unload);
	}

	/** 方块被破坏/拾取（如装配体），通常断开连接（子类可重写）。 */
	public void remove() {
	}

	/** 方块被破坏/替换（需方块在 onRemove 时调用），遍历行为 destroy。 */
	public void destroy() {
		forEachBehaviour(ChasmBlockEntityBehaviour::destroy);
	}

	// —— 行为容器 API ——

	/** 按类型键取行为（未注册返回 null）。 */
	@SuppressWarnings("unchecked")
	@Nullable
	public <B extends ChasmBlockEntityBehaviour<?>> B getBehaviour(BehaviourType<B> type) {
		return (B) behaviours.get(type);
	}

	/** 遍历全部行为。 */
	public void forEachBehaviour(Consumer<ChasmBlockEntityBehaviour<?>> action) {
		behaviours.values().forEach(action);
	}

	/** 全部行为（只读视图）。 */
	public Collection<ChasmBlockEntityBehaviour<?>> getAllBehaviours() {
		return behaviours.values();
	}

	/** 延迟附加一个行为（通知既有行为联动后注入并初始化）。 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public void attachBehaviourLate(ChasmBlockEntityBehaviour<?> behaviour) {
		BehaviourType<?> type = behaviour.getType();
		for (ChasmBlockEntityBehaviour<?> existing : behaviours.values()) {
			existing.onBehaviourAdded(type, behaviour);
		}
		behaviours.put(type, behaviour);
		// 通过原始类型赋值：行为持有的 blockEntity 引用与本 BE 绑定（类型擦除后按 T=ChasmBlockEntity 绑定）
		((ChasmBlockEntityBehaviour) behaviour).blockEntity = this;
		behaviour.initialize();
	}

	/** 移除行为并触发其 unload。 */
	public void removeBehaviour(BehaviourType<?> type) {
		ChasmBlockEntityBehaviour<?> removed = behaviours.remove(type);
		if (removed != null) {
			removed.unload();
		}
	}

	// —— 工具 ——

	/** 设置懒 tick 频率（正数）。 */
	public void setLazyTickRate(int slowTickRate) {
		this.lazyTickRate = Math.max(1, slowTickRate);
		this.lazyTickCounter = this.lazyTickRate;
	}

	/** 玩家能否交互（距离平方 <= 64，且该位置确为本 BE）。 */
	public boolean canPlayerUse(Player player) {
		if (level == null || level.getBlockEntity(worldPosition) != this) {
			return false;
		}
		return player.distanceToSqr(
			worldPosition.getX() + 0.5D,
			worldPosition.getY() + 0.5D,
			worldPosition.getZ() + 0.5D
		) <= 64.0D;
	}

	/** 方块状态变化通知（转发给行为；由方块在 stateChanged 等时机调用）。 */
	public void onBlockChanged(BlockState oldState) {
		forEachBehaviour(b -> b.onBlockChanged(oldState));
	}

	/** 邻居方块更新通知（转发给行为；由方块在 neighborChanged 调用）。 */
	public void onNeighborChanged(BlockPos neighborPos) {
		forEachBehaviour(b -> b.onNeighborChanged(neighborPos));
	}

	/** 判断当前世界是否为服务端。 */
	public boolean isServerSide() {
		return level != null && !level.isClientSide();
	}
}