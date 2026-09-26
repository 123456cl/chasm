package api.chasm.recipe;

import api.chasm.context.ChasmContextRegistry;
import api.chasm.context.ModContext;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配方声明 Builder（声明式合成，DataGen 自动生成配方 json）。
 *
 * <p>用法（在 {@code onInitialize} 中，扫描器之后调用）：</p>
 *
 * <pre>{@code
 * Chasm.recipe("mana_sword")
 *     .shaped(" A ", " A ", " S ",
 *         'A', Items.AMETHYST_SHARD, 'S', Items.STICK)
 *     .result(MANA_SWORD)
 *     .register();
 * }</pre>
 */
public final class RecipeBuilder {

	private final String id;
	private final List<String> pattern = new ArrayList<>();
	private final Map<Character, Ingredient> keys = new LinkedHashMap<>();
	private ItemStack result;

	/** 仅由 {@link api.chasm.Chasm#recipe(String)} 创建。 */
	public RecipeBuilder(String id) {
		this.id = id;
	}

	/** 定义合成图案与材料（字符参数按 字符,材料 成对出现）。 */
	public RecipeBuilder shaped(List<String> pattern, Object... keyMaterials) {
		this.pattern.clear();
		this.pattern.addAll(pattern);
		if (keyMaterials.length % 2 != 0) {
			throw new IllegalArgumentException("材料参数必须成对（字符, 材料）");
		}
		for (int i = 0; i < keyMaterials.length; i += 2) {
			char key = (Character) keyMaterials[i];
			Object material = keyMaterials[i + 1];
			define(key, material);
		}
		return this;
	}

	/** 便捷：图案字符串数组 + 成对材料。 */
	public RecipeBuilder shaped(String[] pattern, Object... keyMaterials) {
		return shaped(List.of(pattern), keyMaterials);
	}

	/** 便捷：图案逐行字符串 + 成对材料。 */
	public RecipeBuilder shaped(String row1, String row2, String row3, Object... keyMaterials) {
		return shaped(List.of(row1, row2, row3), keyMaterials);
	}

	/** 定义单个图案字符对应的材料（物品或 Ingredient）。 */
	public RecipeBuilder define(char key, Object material) {
		if (material instanceof Item item) {
			keys.put(key, Ingredient.of(item));
		} else if (material instanceof Ingredient ingredient) {
			keys.put(key, ingredient);
		} else {
			throw new IllegalArgumentException("材料必须是 Item 或 Ingredient: " + material);
		}
		return this;
	}

	/** 产物。 */
	public RecipeBuilder result(Item item) {
		this.result = new ItemStack(item);
		return this;
	}

	/** 产物（自定义数量）。 */
	public RecipeBuilder result(ItemStack stack) {
		this.result = stack;
		return this;
	}

	/** 记录到当前模组的配方空间（DataGen 时自动生成 json）。 */
	public void register() {
		ModContext context = ChasmContextRegistry.current();
		context.addRecipe(new RecipeDeclaration(id, List.copyOf(pattern), Map.copyOf(keys), result));
	}
}
