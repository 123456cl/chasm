package api.chasm.blockentity;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 方块实体类型声明 Builder（声明式 {@link BlockEntityType}，配套 {@code @Register} 注解自动注册）。
 *
 * <p>用法（<b>方块在前、类型在后</b>，方块对类型的引用用惰性 Supplier 打破循环引用）：</p>
 * <pre>{@code
 * // 机器方块（先声明；对类型用惰性引用，类型字段在下方声明）
 * @Register("cooking_pot_block")
 * public static final Block COOKING_POT_BLOCK = Chasm.block()
 *     .strength(3.0f, 3.0f)
 *     .name("Cooking Pot")
 *     .texture("minecraft:block/cauldron_side")
 *     .blockEntity(() -> COOKING_POT)          // ★ 惰性引用类型，接线 ticker 驱动
 *     .register();
 *
 * // 机器 BE 类型（后声明；此时方块已初始化，可直接传实例）
 * @Register("cooking_pot")
 * public static final BlockEntityType<CookingPotBE> COOKING_POT =
 *     Chasm.blockEntityType(CookingPotBE::new) // ★ 工厂：继承 ChasmBlockEntity 的 BE 类
 *         .blocks(COOKING_POT_BLOCK)           // ★ 该方块放置时创建本类型 BE
 *         .register();
 * }</pre>
 *
 * <p><b>循环引用说明</b>：方块与 BE 类型互相引用。静态字段按声明顺序初始化，故「类型引用方块」
 * 必须等方块声明完成（把方块声明在前即可）；「方块引用类型」必须用惰性
 * {@link java.util.function.Supplier}（{@code .blockEntity(() -> COOKING_POT)}），
 * 待静态初始化完成后才在运行时解析。</p>
 *
 * @param <T> 方块实体类型
 */
public final class ChasmBlockEntityTypeBuilder<T extends ChasmBlockEntity> {

	private final BlockEntityType.BlockEntitySupplier<T> factory;
	private final List<Block> blocks = new ArrayList<>();

	/**
	 * @param factory 方块实体工厂（如 {@code CookingPotBE::new}，仅接受 {@code (BlockPos, BlockState)}）
	 */
	public ChasmBlockEntityTypeBuilder(BlockEntityType.BlockEntitySupplier<T> factory) {
		this.factory = Objects.requireNonNull(factory, "factory");
	}

	/** 追加一个有效方块（该方块放置时创建本类型 BE）。 */
	public ChasmBlockEntityTypeBuilder<T> block(Block block) {
		blocks.add(Objects.requireNonNull(block, "block"));
		return this;
	}

	/** 追加多个有效方块。 */
	public ChasmBlockEntityTypeBuilder<T> blocks(Block... blocks) {
		Collections.addAll(this.blocks, blocks);
		return this;
	}

	/**
	 * 构建 {@link BlockEntityType}（真正注册由 {@code @Register} 字段 + 扫描器完成）。
	 *
	 * @throws IllegalStateException 未声明任何有效方块（空类型无法在任何位置创建 BE）
	 */
	public BlockEntityType<T> register() {
		return build();
	}

	/**
	 * **按 id 直接注册进方块实体注册表**（推荐用法）。
	 *
	 * <p>为什么要有它：无参的 {@link #register()} 只 {@code build} 不注册，必须靠 {@code @Register}
	 * 注解 + 扫描器补一刀。忘了注解的后果很隐蔽 —— BE 类型永远进不了注册表，注册表冻结时抛
	 * {@code Some intrusive holders were not registered}，**datagen 会失败**（模型静默不产出 →
	 * 游戏里一片紫黑格）。这个重载把 id 显式写出来，一步到位。</p>
	 *
	 * <pre>{@code
	 * public static final BlockEntityType<MyBE> MY_BE =
	 *     Chasm.blockEntityType(MyBE::new).blocks(MY_BLOCK).register("mymod", "my_type");
	 * }</pre>
	 */
	public BlockEntityType<T> register(String modId, String name) {
		BlockEntityType<T> type = build();
		BlockEntityType<T> registered = net.minecraft.core.Registry.register(
			net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE,
			net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(modId, name), type);
		api.chasm.log.ChasmLogger.info(modId, "注册方块实体类型 {}", name);
		return registered;
	}

	private BlockEntityType<T> build() {
		if (blocks.isEmpty()) {
			throw new IllegalStateException("ChasmBlockEntityType 至少必须关联一个方块（.block/.blocks）");
		}
		return BlockEntityType.Builder.of(factory, blocks.toArray(new Block[0])).build(null);
	}
}