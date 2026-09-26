package api.chasm.context;

import api.chasm.data.ChasmData;
import api.chasm.item.ChasmItemController;
import api.chasm.item.ChasmItemSupport;

import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 模组上下文：一个使用 Chasm 的模组的全部状态与扩展点（按 modId 隔离）。
 *
 * <p>隔离内容：</p>
 * <ul>
 *   <li>注册 id 空间（modId 前缀的 ResourceLocation，天然隔离）</li>
 *   <li>物品控制台（{@link ChasmItemController}）：本模组注册的物品，可被其他模组查询/追加/覆写行为</li>
 *   <li>数据空间（{@link ChasmData.ModDataSpace}）：本模组声明的数据组件</li>
 * </ul>
 */
public final class ModContext {

	private final String modId;
	private final Map<String, ChasmItemController> items = new LinkedHashMap<>();

	ModContext(String modId) {
		this.modId = Objects.requireNonNull(modId, "modId");
	}

	/** 模组命名空间。 */
	public String id() {
		return modId;
	}

	/**
	 * 获取本模组注册的物品控制台（不存在返回 null）。
	 *
	 * @param id 物品注册名（不含命名空间）
	 */
	public ChasmItemController item(String id) {
		return items.get(id);
	}

	/** 获取本模组全部物品控制台（只读视图）。 */
	public Map<String, ChasmItemController> items() {
		return Collections.unmodifiableMap(items);
	}

	/**
	 * 注册物品并暴露为控制台（由扫描器/手动注册调用）。
	 *
	 * @param id      注册名
	 * @param support 物品实例（需为 {@link ChasmItemSupport} 才能获得行为扩展能力；
	 *                普通物品、剑等任意 Chasm 类型皆可）
	 */
	public void exposeItem(String id, ChasmItemSupport support) {
		ResourceLocation registryId = ResourceLocation.fromNamespaceAndPath(modId, id);
		items.put(id, new ChasmItemController(registryId, support));
	}

	/** 本模组的数据空间（数据组件按 modId 隔离）。 */
	public ChasmData.ModDataSpace data() {
		return ChasmData.space(modId);
	}

	// —— 方块空间 ——

	private final Map<String, api.chasm.block.ChasmBlockController> blocks = new LinkedHashMap<>();

	/** 注册方块并暴露为控制台（由扫描器调用）。 */
	public void exposeBlock(String id, api.chasm.block.ChasmBlock block, net.minecraft.world.item.Item item) {
		ResourceLocation registryId = ResourceLocation.fromNamespaceAndPath(modId, id);
		blocks.put(id, new api.chasm.block.ChasmBlockController(registryId, block, item));
	}

	/** 获取本模组注册的方块控制台（不存在返回 null）。 */
	public api.chasm.block.ChasmBlockController block(String id) {
		return blocks.get(id);
	}

	/** 本模组全部方块控制台（只读视图）。 */
	public Map<String, api.chasm.block.ChasmBlockController> blocks() {
		return Collections.unmodifiableMap(blocks);
	}

	// —— 配方空间（声明式合成，DataGen 自动生成） ——

	private final Map<String, api.chasm.recipe.RecipeDeclaration> recipes = new LinkedHashMap<>();

	/** 记录一个配方声明（由 RecipeBuilder.register 调用）。 */
	public void addRecipe(api.chasm.recipe.RecipeDeclaration recipe) {
		recipes.put(recipe.id(), recipe);
	}

	/** 本模组声明的全部配方（只读视图）。 */
	public Map<String, api.chasm.recipe.RecipeDeclaration> recipes() {
		return Collections.unmodifiableMap(recipes);
	}

	// —— 方块实体空间 ——

	private final Map<String, net.minecraft.world.level.block.entity.BlockEntityType<?>> blockEntityTypes = new LinkedHashMap<>();

	/** 注册方块实体类型并暴露为控制台（由扫描器/手动注册调用）。 */
	public void exposeBlockEntity(String id, net.minecraft.world.level.block.entity.BlockEntityType<?> type) {
		blockEntityTypes.put(id, type);
	}

	/** 获取本模组注册的方块实体类型（不存在返回 null）。 */
	public net.minecraft.world.level.block.entity.BlockEntityType<?> blockEntityType(String id) {
		return blockEntityTypes.get(id);
	}

	/** 本模组全部方块实体类型（只读视图）。 */
	public Map<String, net.minecraft.world.level.block.entity.BlockEntityType<?>> blockEntityTypes() {
		return Collections.unmodifiableMap(blockEntityTypes);
	}
}
