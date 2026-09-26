package api.chasm.item;

import net.minecraft.core.Holder;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Tier;

/**
 * 物品类型工厂的构建上下文：把共享行为与构建好的 {@link Item.Properties} 交给
 * {@link ChasmItemFactory}，由具体的类型决定实例化哪个原版子类。
 *
 * @param behavior      共享行为载体（特质/回调/默认组件已就绪）
 * @param properties    已构建完成的物品属性（含数据组件、属性修饰符、耐久等）
 * @param tier          可选的原版 Tier（用于工具类物品，如剑；null 表示不指定）
 * @param armorMaterial 可选的护甲材质（仅护甲类型使用，经 {@code ItemBuilder.armor(...)} 指定）
 * @param armorType     可选的护甲部位（仅护甲类型使用，经 {@code ItemBuilder.armor(...)} 指定）
 */
public record ChasmItemBuildContext(
	ChasmItemBehavior behavior,
	Item.Properties properties,
	Tier tier,
	Holder<ArmorMaterial> armorMaterial,
	ArmorItem.Type armorType) {
}
