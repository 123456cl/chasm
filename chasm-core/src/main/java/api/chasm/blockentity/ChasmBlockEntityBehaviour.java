package api.chasm.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * 方块实体行为插件（BlockEntity Behaviour）—— Chasm 的「BE-Trait」。
 *
 * <p>设计来源：Create-Fly 的 {@code BlockEntityBehaviour}（第十六步分析）。把「方块实体的
 * 能力」抽象为可插拔行为，按 {@link BlockEntityType} 全局注册（本模组或第三方都可附加），
 * 由 {@link ChasmBlockEntity} 模板壳统一编排生命周期。</p>
 *
 * <p>三个全局注册表，注入时机分离：</p>
 * <ul>
 *   <li>{@link #REGISTRY}：方块实体构造时注入（普通能力，如库存/热源）</li>
 *   <li>{@link #FIRST_READ_REGISTRY}：首次读 NBT 时注入（需要先读自定义数据的行为）</li>
 *   <li>{@link #CLIENT_REGISTRY}：客户端 {@code initialize()} 时注入（仅客户端能力）</li>
 * </ul>
 *
 * <p>注册示例（onInitialize）：</p>
 * <pre>{@code
 * ChasmBlockEntityBehaviour.add(ModBlocks.POT_BE.get(),
 *     be -> new CookingProcessBehaviour<>(be).time(200));
 * }</pre>
 *
 * @param <T> 行为绑定的方块实体类型
 */
public abstract class ChasmBlockEntityBehaviour<T extends ChasmBlockEntity> {

	/** 普通注册表：BE 构造时注入。 */
	public static final Map<BlockEntityType<?>, List<Function<ChasmBlockEntity, ChasmBlockEntityBehaviour<?>>>> REGISTRY =
		new ConcurrentHashMap<>();

	/** 首次读 NBT 注册表：读到自定义数据后才能构造的行为。 */
	public static final Map<BlockEntityType<?>, List<Function<ChasmBlockEntity, ChasmBlockEntityBehaviour<?>>>> FIRST_READ_REGISTRY =
		new ConcurrentHashMap<>();

	/** 客户端注册表：仅客户端注入的行为。 */
	public static final Map<BlockEntityType<?>, List<Function<ChasmBlockEntity, ChasmBlockEntityBehaviour<?>>>> CLIENT_REGISTRY =
		new ConcurrentHashMap<>();

	/** 行为绑定的方块实体（构造时注入）。 */
	public T blockEntity;

	private int lazyTickRate = 10;
	private int lazyTickCounter = 10;

	protected ChasmBlockEntityBehaviour(T be) {
		this.blockEntity = be;
	}

	// —— 全局注册（插件注入点） ——

	/** 注册一个普通行为（BE 构造时注入）。 */
	@SuppressWarnings("unchecked")
	public static <T extends ChasmBlockEntity> void add(
		BlockEntityType<T> type, Function<T, ChasmBlockEntityBehaviour<?>> factory) {
		REGISTRY.computeIfAbsent(type, t -> new CopyOnWriteArrayList<>())
			.add((Function<ChasmBlockEntity, ChasmBlockEntityBehaviour<?>>) (Function<?, ?>) factory);
	}

	/** 注册一个首次读 NBT 行为（读到自定义数据后注入）。 */
	@SuppressWarnings("unchecked")
	public static <T extends ChasmBlockEntity> void addFirstRead(
		BlockEntityType<T> type, Function<T, ChasmBlockEntityBehaviour<?>> factory) {
		FIRST_READ_REGISTRY.computeIfAbsent(type, t -> new CopyOnWriteArrayList<>())
			.add((Function<ChasmBlockEntity, ChasmBlockEntityBehaviour<?>>) (Function<?, ?>) factory);
	}

	/** 注册一个客户端专属行为（客户端 initialize() 注入）。 */
	@SuppressWarnings("unchecked")
	public static <T extends ChasmBlockEntity> void addClient(
		BlockEntityType<T> type, Function<T, ChasmBlockEntityBehaviour<?>> factory) {
		CLIENT_REGISTRY.computeIfAbsent(type, t -> new CopyOnWriteArrayList<>())
			.add((Function<ChasmBlockEntity, ChasmBlockEntityBehaviour<?>>) (Function<?, ?>) factory);
	}

	// —— 跨 BE 查询 ——

	/**
	 * 从任意方块实体上按类型键取行为（非 {@link ChasmBlockEntity} 返回 null）。
	 *
	 * @param be   方块实体（可为 null）
	 * @param type 行为类型键
	 * @param <B>  行为类型
	 */
	@SuppressWarnings("unchecked")
	@Nullable
	public static <B extends ChasmBlockEntityBehaviour<?>> B get(@Nullable BlockEntity be, BehaviourType<B> type) {
		if (be instanceof ChasmBlockEntity cbe) {
			return cbe.getBehaviour(type);
		}
		return null;
	}

	/**
	 * 从方块读取器按位置取行为（越界/并发读取返回 null，不抛异常）。
	 *
	 * @param reader 方块读取器（如 Level / LevelReader）
	 * @param pos    方块位置
	 * @param type   行为类型键
	 * @param <B>    行为类型
	 */
	@SuppressWarnings("unchecked")
	@Nullable
	public static <B extends ChasmBlockEntityBehaviour<?>> B get(
		BlockGetter reader, BlockPos pos, BehaviourType<B> type) {
		try {
			return get(reader.getBlockEntity(pos), type);
		} catch (java.util.ConcurrentModificationException e) {
			return null;
		}
	}

	// —— 生命周期钩子（全部默认空实现） ——

	/** 类型键（每个行为类持静态单例）。 */
	public abstract BehaviourType<?> getType();

	/** 行为初始化（BE 首次 tick / 附加时调用）。 */
	public void initialize() {
	}

	/** 每 tick 调用；自带 lazyTick 节流。 */
	public void tick() {
		if (lazyTickCounter-- <= 0) {
			lazyTickCounter = lazyTickRate;
			lazyTick();
		}
	}

	/** 低频 tick（由 {@link #setLazyTickRate(int)} 控制频率）。 */
	public void lazyTick() {
	}

	/** 读取持久化/同步数据。 */
	public void read(CompoundTag tag, boolean clientPacket) {
	}

	/** 写入持久化/同步数据（{@code clientPacket=true} 时只写客户端所需）。 */
	public void write(CompoundTag tag, boolean clientPacket) {
	}

	/** 方块状态变化（由 ChasmBlock 调 {@link ChasmBlockEntity#onBlockChanged} 转发）。 */
	public void onBlockChanged(BlockState oldState) {
	}

	/** 邻居方块更新。 */
	public void onNeighborChanged(BlockPos neighborPos) {
	}

	/** 其它行为加入时的联动（由 {@link ChasmBlockEntity#attachBehaviourLate} 触发）。 */
	public void onBehaviourAdded(BehaviourType<?> type, ChasmBlockEntityBehaviour<?> behaviour) {
	}

	/** 方块被移除/区块卸载，通常失效能力（如动力/库存引用）。 */
	public void unload() {
	}

	/** 方块被破坏/替换（需方块在 onRemove 时通知），通常断开连接。 */
	public void destroy() {
	}

	/** 设置本行为的低频 tick 频率（钳制到 ≥1，避免 0/负值导致每 tick 触发）。 */
	public void setLazyTickRate(int slowTickRate) {
		this.lazyTickRate = Math.max(1, slowTickRate);
		this.lazyTickCounter = this.lazyTickRate;
	}

	/** 行为所在方块位置。 */
	public BlockPos getPos() {
		return blockEntity.getBlockPos();
	}

	/** 行为所在方块实体所处的世界（可能为 null）。 */
	@Nullable
	public Level getLevel() {
		return blockEntity.getLevel();
	}
}
