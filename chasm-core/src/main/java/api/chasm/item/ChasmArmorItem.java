package api.chasm.item;

import api.chasm.data.ChasmData;
import api.chasm.template.ArmorSetSpec;
import api.chasm.template.ArmorTickContext;
import api.chasm.template.ChasmTemplates;

import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Chasm 护甲物品：真正的 {@link ArmorItem} 子类（第十四步 T-A2）。
 *
 * <p>保留原版护甲语义（自动穿戴/装备槽判定/材质属性/附魔等级/修复），同时：</p>
 * <ul>
 *   <li>共享 {@link ChasmItemBehavior}（特质/回调/默认组件，经 {@code ChasmItemSupport} SPI）</li>
 *   <li>物品栈携带 {@code ARMOR_SET_ID} 组件 → 每 tick 统计已穿戴件数，按阈值触发
 *       半套/全套效果（{@link ArmorSetSpec#tick(ArmorTickContext)}，服务端）</li>
 * </ul>
 */
public class ChasmArmorItem extends ArmorItem implements ChasmItemSupport {

	private final ChasmItemBehavior behavior;

	public ChasmArmorItem(Holder<ArmorMaterial> material, Type type,
		ChasmItemBehavior behavior, Properties properties) {
		super(material, type, properties);
		this.behavior = behavior;
	}

	/** 共享行为载体（供 SPI 扩展/注册扫描器使用）。 */
	@Override
	public ChasmItemBehavior chasmBehavior() {
		return behavior;
	}

	@Override
	public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean selected) {
		behavior.handleInventoryTick(stack, level, entity, slotId, selected);
		super.inventoryTick(stack, level, entity, slotId, selected);
		tickArmorSet(stack, level, entity);
	}

	@Override
	public ItemStack getDefaultInstance() {
		return behavior.applyDefaultInstance(super.getDefaultInstance());
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
		super.appendHoverText(stack, context, tooltip, flag);
		// 护甲无攻击速度行，无需 ChasmTooltipUtil 变换
	}

	/**
	 * 套装效果 tick（服务端）：读取本件护甲的 {@code ARMOR_SET_ID}，统计玩家已穿戴件数，
	 * 交给 {@link ArmorSetSpec#tick(ArmorTickContext)} 分发半套/全套效果。
	 */
	private void tickArmorSet(ItemStack stack, Level level, Entity entity) {
		if (level.isClientSide || !(entity instanceof Player player)) {
			return;
		}
		ResourceLocation setId = stack.get(ChasmData.ARMOR_SET_ID);
		if (setId == null) {
			return;
		}
		ArmorSetSpec spec = ChasmTemplates.INSTANCE.getArmorSet(setId);
		if (spec == null) {
			return;
		}
		int count = countPieces(player, setId);
		if (count > 0) {
			spec.tick(new ArmorTickContext(player, stack, count));
		}
	}

	/** 统计玩家已穿戴的本套装件数（4 个经典护甲位，含当前件）。 */
	private static int countPieces(Player player, ResourceLocation setId) {
		int count = 0;
		for (ItemStack piece : player.getInventory().armor) {
			if (!piece.isEmpty() && setId.equals(piece.get(ChasmData.ARMOR_SET_ID))) {
				count++;
			}
		}
		return count;
	}
}
