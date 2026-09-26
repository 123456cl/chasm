package api.chasm.item.event;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** 事件源的"从哪个实体取哪些物品栈参与分发"策略（开放：可按事件注册多个）。 */
@FunctionalInterface
public interface ItemStackSource {
	List<ItemStack> stacks(LivingEntity entity);
}
