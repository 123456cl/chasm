package api.chasm.recipe;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.List;
import java.util.Map;

/**
 * 配方声明（DataGen 生成配方 json 的数据源）。
 *
 * @param id      配方注册名（不含命名空间）
 * @param pattern 合成图案（如 [" A ", " A ", " S "]）
 * @param keys    图案字符 → 材料
 * @param result  产物
 */
public record RecipeDeclaration(
	String id,
	List<String> pattern,
	Map<Character, Ingredient> keys,
	ItemStack result) {
}
