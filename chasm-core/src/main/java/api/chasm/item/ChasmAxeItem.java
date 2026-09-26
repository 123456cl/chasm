package api.chasm.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Chasm 斧物品：真正的 {@link AxeItem} 子类。
 *
 * <p>类型系统映射：声明 {@code ChasmTypes.AXE} 类型的物品实例化为本类，获得斧的原版语义
 * ——剥皮（useOn 对原木/原木去皮）、挖掘速度与耐久消耗、可附斧类附魔等。</p>
 *
 * <p>{@link #useOn} 沿用原版剥皮逻辑（由 {@code AxeItem} 实现，本类不覆写），因此右键仍可剥皮；
 * 行为（特质/回调/默认组件）委托给共享的 {@link ChasmItemBehavior}。</p>
 */
public class ChasmAxeItem extends AxeItem implements ChasmItemSupport {

	private final ChasmItemBehavior behavior;

	public ChasmAxeItem(Tier tier, ChasmItemBehavior behavior, Properties properties) {
		super(tier, properties);
		this.behavior = behavior;
	}

	/** 共享行为载体（供 SPI 扩展/注册扫描器使用）。 */
	@Override
	public ChasmItemBehavior chasmBehavior() {
		return behavior;
	}

	/** 声明的基础攻击力（普通攻击伤害）。Trait 可据此计算魔法伤害差额。 */
	public float getAttackDamage() {
		return behavior.getAttackDamage();
	}

	@Override
	public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
		if (behavior.handleAttack(stack, target, attacker)) {
			return true; // 特质已完全消费本次攻击，不再走原版伤害逻辑
		}
		return super.hurtEnemy(stack, target, attacker);
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		return behavior.handleUse(level, player, hand, player.getItemInHand(hand));
	}

	@Override
	public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean selected) {
		behavior.handleInventoryTick(stack, level, entity, slotId, selected);
		super.inventoryTick(stack, level, entity, slotId, selected);
	}

	@Override
	public ItemStack getDefaultInstance() {
		return behavior.applyDefaultInstance(super.getDefaultInstance());
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
		super.appendHoverText(stack, context, tooltip, flag);
		ChasmTooltipUtil.transform(stack, tooltip);
	}
}