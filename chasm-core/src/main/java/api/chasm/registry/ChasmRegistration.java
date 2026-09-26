package api.chasm.registry;

import api.chasm.ChasmMod;
import api.chasm.log.ChasmLogger;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Chasm 极简注册 API：直接向原版注册表注册物品/方块/方块物品。
 *
 * <p>这是 {@link ChasmRegistrar}（注解扫描器）的底层支撑，也是任何模组
 * （不一定要用 {@code @Register} 注解）都可以直接调用的静态注册入口。
 * 每次注册都会经由 {@link ChasmLogger} 打上 {@code [modid]} 前缀的结构化日志。</p>
 *
 * <p>底层注册走 Fabric/原版注册表，遵循注册表冻结规则：必须在
 * {@code onInitialize()}（或对齐的初始化阶段）内完成注册。</p>
 */
public final class ChasmRegistration {

	private ChasmRegistration() {
	}

	/**
	 * 注册一个物品到原版物品注册表。
	 *
	 * @param modId 模组命名空间（须与 {@code @ChasmMod(id=...)} 一致）
	 * @param name  注册名（不含命名空间），完整 id 为 {@code modId:name}
	 * @param item  物品实例
	 * @return 传入的物品实例（便于链式赋值到 {@code public static final} 字段）
	 */
	public static Item registerItem(String modId, String name, Item item) {
		ResourceLocation registryId = ResourceLocation.fromNamespaceAndPath(modId, name);
		Registry.register(BuiltInRegistries.ITEM, registryId, item);
		ChasmLogger.call(modId, "ChasmRegistration", "registerItem", "注册物品 {}", registryId);
		return item;
	}

	/**
	 * 注册一个方块到原版方块注册表（不会自动生成对应方块物品）。
	 *
	 * @param modId 模组命名空间
	 * @param name  注册名（不含命名空间）
	 * @param block 方块实例
	 * @return 传入的方块实例
	 */
	public static Block registerBlock(String modId, String name, Block block) {
		ResourceLocation registryId = ResourceLocation.fromNamespaceAndPath(modId, name);
		Registry.register(BuiltInRegistries.BLOCK, registryId, block);
		ChasmLogger.call(modId, "ChasmRegistration", "registerBlock", "注册方块 {}", registryId);
		return block;
	}

	/**
	 * 注册一个方块物品（BlockItem）到原版物品注册表，与方块同 id。
	 *
	 * <p>如果你需要为方块注册带自定义属性的方块物品（如 {@code Item.Properties}），
	 * 可先 {@link #registerBlock(String, String, Block)} 再调用本方法。</p>
	 *
	 * @param modId    模组命名空间
	 * @param name     注册名（与对应方块的 name 一致）
	 * @param block    方块实例
	 * @param settings 方块物品属性
	 * @return 新建的 BlockItem 实例
	 */
	public static BlockItem registerBlockItem(String modId, String name, Block block, Item.Properties settings) {
		BlockItem blockItem = new BlockItem(block, settings);
		ResourceLocation registryId = ResourceLocation.fromNamespaceAndPath(modId, name);
		Registry.register(BuiltInRegistries.ITEM, registryId, blockItem);
		ChasmLogger.call(modId, "ChasmRegistration", "registerBlockItem", "注册方块物品 {}", registryId);
		return blockItem;
	}

	/**
	 * 注册一个方块并自动生成、注册同 id 的方块物品（默认属性）。
	 *
	 * <p>等价于 {@link ChasmRegistrar} 扫描 {@code @Register} 方块字段时的行为。</p>
	 *
	 * @param modId 模组命名空间
	 * @param name  注册名
	 * @param block 方块实例
	 * @return 新建并注册的 BlockItem 实例
	 */
	public static BlockItem registerBlockWithItem(String modId, String name, Block block) {
		registerBlock(modId, name, block);
		return registerBlockItem(modId, name, block, new Item.Properties());
	}

	/**
	 * 注册一个方块实体类型到原版方块实体注册表。
	 *
	 * @param modId 模组命名空间
	 * @param name  注册名（不含命名空间），完整 id 为 {@code modId:name}
	 * @param type  方块实体类型（由 {@link api.chasm.blockentity.ChasmBlockEntityTypeBuilder} 构建）
	 * @param <T>   方块实体类型
	 * @return 传入的类型实例（便于链式赋值到 {@code public static final} 字段）
	 */
	public static <T extends BlockEntity> BlockEntityType<T> registerBlockEntityType(
		String modId, String name, BlockEntityType<T> type) {
		ResourceLocation registryId = ResourceLocation.fromNamespaceAndPath(modId, name);
		Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, registryId, type);
		ChasmLogger.call(modId, "ChasmRegistration", "registerBlockEntityType", "注册方块实体类型 {}", registryId);
		return type;
	}
}