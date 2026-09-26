package api.chasm.item.event;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物品栈来源注册表（开放）：每个事件种类决定"从实体的哪些格子取栈参与分发"。
 *
 * <p>内置来源：{@link #MAIN_HAND}、{@link #OFF_HAND}、{@link #HELD}（主+副）、
 * {@link #EQUIPPED}（手持 + 四件护甲）。第三方可为任意事件种类注册自定义来源
 * （背包、饰品、宠物、自定义容器…），无需修改框架。</p>
 */
public final class ItemStackSources {

	private static final Map<ResourceLocation, List<ItemStackSource>> BY_EVENT = new ConcurrentHashMap<>();

	/** 主手。 */
	public static final ItemStackSource MAIN_HAND = entity -> List.of(entity.getMainHandItem());
	/** 副手。 */
	public static final ItemStackSource OFF_HAND = entity -> List.of(entity.getOffhandItem());
	/** 主手 + 副手。 */
	public static final ItemStackSource HELD = entity -> List.of(entity.getMainHandItem(), entity.getOffhandItem());
	/** 手持 + 护甲四件。 */
	public static final ItemStackSource EQUIPPED = entity -> {
		List<ItemStack> out = new ArrayList<>(6);
		out.add(entity.getMainHandItem());
		out.add(entity.getOffhandItem());
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
				out.add(entity.getItemBySlot(slot));
			}
		}
		return out;
	};

	private ItemStackSources() {
	}

	/** 为某事件种类追加一个栈来源（幂等追加，顺序稳定）。 */
	public static void register(ItemEventKey<?> event, ItemStackSource source) {
		BY_EVENT.computeIfAbsent(event.id(), k -> new ArrayList<>()).add(source);
	}

	/** 收集该事件应当参与分发的全部栈（未注册来源时为空 → 零开销）。 */
	public static List<ItemStack> collect(ItemEventKey<?> event, LivingEntity entity) {
		List<ItemStackSource> sources = BY_EVENT.get(event.id());
		if (sources == null || sources.isEmpty()) {
			return List.of();
		}
		List<ItemStack> out = new ArrayList<>(sources.size() * 2);
		for (ItemStackSource source : sources) {
			for (ItemStack stack : source.stacks(entity)) {
				if (stack != null && !stack.isEmpty()) {
					out.add(stack);
				}
			}
		}
		return out;
	}
}
