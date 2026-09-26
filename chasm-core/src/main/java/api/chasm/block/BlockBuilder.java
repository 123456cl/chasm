package api.chasm.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 方块声明 Builder（玩法行为的链式组合 + 资源声明）。
 *
 * <p>用法：</p>
 *
 * <pre>{@code
 * Chasm.block()
 *     .strength(3.0f, 3.0f).requiresCorrectToolForDrops()
 *     .name("Mana Crystal")
 *     .texture("minecraft:block/amethyst_block")
 *     .onUse(ctx -> ctx.sendMessage("The crystal hums!"))
 *     .register()
 * }</pre>
 */
public final class BlockBuilder {

	private final BlockBehaviour.Properties properties = BlockBehaviour.Properties.of();
	private final List<Consumer<BlockUseContext>> useHandlers = new ArrayList<>();
	private final List<Consumer<BlockUseContext>> placeHandlers = new ArrayList<>();
	private final List<Consumer<BlockUseContext>> breakHandlers = new ArrayList<>();
	private final List<Consumer<BlockUseContext>> stepHandlers = new ArrayList<>();
	private String displayName;
	private String texture;
	private String requiredTool;
	/** 掉落声明；{@code null} = 自带掉落表（{@link #ownLoot()}），DataGen 不生成。 */
	private LootDeclaration loot = LootDeclaration.DEFAULT;
	/** 关联的方块实体类型（惰性解析；用 Supplier 打破「方块 ↔ BE 类型」静态初始化循环引用）。 */
	private Supplier<BlockEntityType<?>> blockEntityType;

	/** 仅由 {@link api.chasm.Chasm#block()} 创建。 */
	public BlockBuilder() {
	}

	/** 硬度和爆炸抗性（如 3.0f, 3.0f = 石头级）。 */
	public BlockBuilder strength(float destroyTime, float explosionResistance) {
		properties.destroyTime(destroyTime).explosionResistance(explosionResistance);
		return this;
	}

	/**
	 * 需要镐才能掉落（自动生成 mineable/pickaxe 标签）。
	 *
	 * <p>注意：与 {@link #loot(java.util.function.Consumer)} 互斥——后者通过 match_tool
	 * 条件管理多工具掉落，不要再叠加本方法（否则非镐工具挖不出掉落）。</p>
	 */
	public BlockBuilder requiresPickaxe() {
		return requiresTool("minecraft:mineable/pickaxe");
	}

	/** 需要斧才能掉落（自动生成 mineable/axe 标签；与 loot() 互斥）。 */
	public BlockBuilder requiresAxe() {
		return requiresTool("minecraft:mineable/axe");
	}

	/** 需要锹才能掉落（自动生成 mineable/shovel 标签；与 loot() 互斥）。 */
	public BlockBuilder requiresShovel() {
		return requiresTool("minecraft:mineable/shovel");
	}

	/**
	 * 需要指定工具标签才能掉落（完全自定义，自动生成该标签）。
	 *
	 * @param toolTag 标签完整路径，如 "minecraft:mineable/pickaxe" 或自定义 "mymod:mineable/magic"
	 */
	public BlockBuilder requiresTool(String toolTag) {
		properties.requiresCorrectToolForDrops();
		this.requiredTool = toolTag;
		return this;
	}

	/** 掉落方块自身（默认）。 */
	public BlockBuilder dropsSelf() {
		this.loot = LootDeclaration.DEFAULT;
		return this;
	}

	/** 掉落指定物品固定数量（如掉碎片 {@code .drops(Items.AMETHYST_SHARD, 2)}，支持其他模组物品）。 */
	public BlockBuilder drops(net.minecraft.world.level.ItemLike item, int count) {
		this.loot = LootDeclaration.single(LootDeclaration.LootType.ITEM, item, count, 0, 0);
		return this;
	}

	/** 掉落指定物品 1 个。 */
	public BlockBuilder drops(net.minecraft.world.level.ItemLike item) {
		return drops(item, 1);
	}

	/** 掉落指定物品随机数量（每次在 min~max 之间，如碎片 {@code .dropsRandom(Items.AMETHYST_SHARD, 1, 3)}）。 */
	public BlockBuilder dropsRandom(net.minecraft.world.level.ItemLike item, int min, int max) {
		this.loot = LootDeclaration.single(LootDeclaration.LootType.ITEM_RANDOM, item, 0, min, max);
		return this;
	}

	/**
	 * 多工具多种掉落声明：每种工具（物品/标签）独立掉落，可带概率。
	 *
	 * <pre>{@code
	 * .loot(l -> l
	 *     .onTool(Items.DIAMOND_PICKAXE, r -> r.dropsSelf())
	 *     .onTool(Items.DIAMOND_SWORD, r -> r.dropsRandom(Items.AMETHYST_SHARD, 1, 3))
	 *     .onTool(Items.DIAMOND_HOE, r -> r.chance(0.5f).dropsSelf())
	 *     .onToolTag("mymod:special_tools", r -> r.drops(OtherModItem.SOMETHING))
	 *     .otherwise(r -> r.drops(Items.AMETHYST_SHARD, 2)))
	 * }</pre>
	 */
	public BlockBuilder loot(java.util.function.Consumer<LootBuilder> config) {
		LootBuilder builder = new LootBuilder();
		config.accept(builder);
		this.loot = new LootDeclaration(builder.build());
		return this;
	}

	/** 方块声音类型。 */
	public BlockBuilder sound(SoundType sound) {
		properties.sound(sound);
		return this;
	}

	/** 显示名（自动生成语言文件）。 */
	public BlockBuilder name(String name) {
		this.displayName = name;
		return this;
	}

	/** 方块贴图路径（自动生成模型，如 "minecraft:block/amethyst_block"）。 */
	public BlockBuilder texture(String texture) {
		this.texture = texture;
		return this;
	}

	/**
	 * **自带资源**：本模组在 {@code assets/<ns>/blockstates/<id>.json} 与
	 * {@code assets/<ns>/models/{block,item}/<id>.json} 里已经提供真实资源，DataGen 不要为这个方块生成它们。
	 *
	 * <p>为什么必须显式声明（与 {@link api.chasm.item.ItemBuilder#ownModel()} 同一个铁律）：
	 * 自动生成的 {@code blockstates/<id>.json} 与自带资源**同路径**，两者同时在资源里时
	 * {@code processResources} 会因"重复条目"直接失败（§49 事故）。</p>
	 *
	 * <p>实现上等价于"不声明贴图"——DataGen 的方块生成器以 {@code chasmTexture() != null} 为门槛
	 * （见 {@code ChasmDataGen.ChasmModelProvider#generateBlockStateModels}），所以这里把贴图清成 null。</p>
	 */
	public BlockBuilder ownModel() {
		this.texture = null;
		return this;
	}

	/**
	 * **自带掉落表**：本模组已在 {@code data/<ns>/loot_table/blocks/<id>.json} 提供真实掉落表，
	 * DataGen 不要为这个方块生成掉落表。
	 *
	 * <p>与 {@link #ownModel()} 同一个理由：自动生成的掉落表与真实资源**同路径**，
	 * 两者同时在资源里时 {@code processResources} 直接报 duplicate（§49 铁律）。
	 * 典型场景：真实掉落与"默认掉自己"不同（如 {@code basic_workbench} 掉的是合成台）。</p>
	 */
	public BlockBuilder ownLoot() {
		this.loot = null;
		return this;
	}

	/** 玩家右键点击方块时触发的玩法行为（可叠加多个）。 */
	public BlockBuilder onUse(Consumer<BlockUseContext> handler) {
		useHandlers.add(handler);
		return this;
	}

	/** 方块被放置时触发的玩法行为（可叠加多个）。 */
	public BlockBuilder onPlace(Consumer<BlockUseContext> handler) {
		placeHandlers.add(handler);
		return this;
	}

	/** 方块被玩家破坏时触发的玩法行为（可叠加多个）。 */
	public BlockBuilder onBreak(Consumer<BlockUseContext> handler) {
		breakHandlers.add(handler);
		return this;
	}

	/** 实体踩踏方块时触发的玩法行为（可叠加多个）。 */
	public BlockBuilder onStep(Consumer<BlockUseContext> handler) {
		stepHandlers.add(handler);
		return this;
	}

	/**
	 * 关联方块实体类型（立即引用；该方块放置时创建该类型 BE，并由 {@link ChasmBlockEntityTicker} 驱动 tick）。
	 *
	 * <p>仅当类型已在此前声明（如常量/其它类）时可用；若方块与类型在同一个 {@code @ChasmMod}
	 * 类中相互引用，请用 {@link #blockEntity(java.util.function.Supplier)} 传惰性引用打破循环。</p>
	 */
	public BlockBuilder blockEntity(BlockEntityType<?> type) {
		return blockEntity(() -> type);
	}

	/**
	 * 关联方块实体类型（惰性引用；运行时才解析，用于打破「方块 ↔ BE 类型」静态初始化循环引用）。
	 *
	 * <pre>{@code
	 * @Register("cooking_pot_block")
	 * public static final Block BLOCK = Chasm.block()
	 *     ...
	 *     .blockEntity(() -> COOKING_POT)   // COOKING_POT 在字段声明顺序上晚于 BLOCK
	 *     .register();
	 * }</pre>
	 */
	public BlockBuilder blockEntity(Supplier<BlockEntityType<?>> typeSupplier) {
		this.blockEntityType = typeSupplier;
		return this;
	}

	/** 构建并返回方块实例（注册由 {@code @Register} 字段 + 扫描器完成，BlockItem 自动生成）。 */
	public Block register() {
		return new ChasmBlock(new ArrayList<>(useHandlers), new ArrayList<>(placeHandlers),
			new ArrayList<>(breakHandlers), new ArrayList<>(stepHandlers),
			properties, displayName, texture, requiredTool, loot, blockEntityType);
	}
}
