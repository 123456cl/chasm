package api.chasm.item;

import net.minecraft.world.item.Item;

/**
 * 物品类型工厂：根据 {@link ChasmItemBuildContext} 实例化对应的原版物品子类。
 *
 * <p>这是类型系统「原版类映射」的执行点：普通物品类型使用 {@code ChasmItem}，
 * 剑类型使用 {@code ChasmSwordItem}，后续可扩展斧/镐/防具等子类。</p>
 */
@FunctionalInterface
public interface ChasmItemFactory {

	/** 由类型工厂构造具体的物品实例。 */
	Item create(ChasmItemBuildContext context);
}