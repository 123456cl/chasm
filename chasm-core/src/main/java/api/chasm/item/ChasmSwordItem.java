package api.chasm.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Chasm 剑物品：真正的 {@link SwordItem} 子类。
 *
 * <p>类型系统的核心兑现：声明 {@code ChasmTypes.SWORD} 类型的物品将实例化为本类，
 * 从而获得剑的原版语义——被 {@code Player.attack} 判定为剑（触发横扫 {@code sweepAttack}）、
 * 可附魔剑类附魔（关键链接 {@code #minecraft:swords} 标签，供横扫之刃等生效）、
 * 攻击方块判定等同原版剑。</p>
 *
 * <p>行为（特质/回调/默认组件）仍全部委托给共享的 {@link ChasmItemBehavior}，
 * 与 {@link ChasmItem} 完全复用，不重复实现。</p>
 */
public class ChasmSwordItem extends SwordItem implements ChasmItemSupport {

	private final ChasmItemBehavior behavior;

	public ChasmSwordItem(Tier tier, ChasmItemBehavior behavior, Properties properties) {
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

	// ------------------------------------------------------------ 挖掘钩子（与 ChasmItem 逐字一致）

	/**
	 * 挖掘钩子的转发（**补齐**：{@code ChasmItem} 早就有这三个覆写，剑这边漏了 ——
	 * 而模块化工具在本移植里正是剑体，缺了它"模块化工具真的能挖"就无从谈起）。
	 * 语义与 {@link ChasmItem} 完全相同，全靠 {@link ChasmItemBehavior.MiningHooks} 表态。
	 */
	@Override
	public float getDestroySpeed(ItemStack stack, net.minecraft.world.level.block.state.BlockState state) {
		float custom = behavior.destroySpeed(stack, state);
		return custom >= 0.0F ? custom : super.getDestroySpeed(stack, state);
	}

	@Override
	public boolean isCorrectToolForDrops(ItemStack stack, net.minecraft.world.level.block.state.BlockState state) {
		Boolean custom = behavior.isCorrectToolForDrops(stack, state);
		return custom != null ? custom : super.isCorrectToolForDrops(stack, state);
	}

	@Override
	public boolean mineBlock(ItemStack stack, Level level, net.minecraft.world.level.block.state.BlockState state,
							 net.minecraft.core.BlockPos pos, LivingEntity miner) {
		if (behavior.handlesMining()) {
			behavior.onMineBlock(stack, level, state, pos, miner);
			return true;   // 已接管：耐久/进度由行为自己算
		}
		return super.mineBlock(stack, level, state, pos, miner);
	}
}