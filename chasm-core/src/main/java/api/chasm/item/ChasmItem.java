package api.chasm.item;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Chasm 玩法物品基类（普通物品）。
 *
 * <p>不再直接持有行为实现，而是委托给共享的 {@link ChasmItemBehavior}，使普通物品
 * 与其他原版子类（如 {@link ChasmSwordItem}）复用同一套特质/回调/默认组件逻辑。</p>
 *
 * <p>行为列表保持可变：其他模组可通过 {@link ChasmItemController} 在加载后追加/覆写行为
 * （SPI 扩展能力）。</p>
 */
public class ChasmItem extends Item implements ChasmItemSupport {

	private final ChasmItemBehavior behavior;

	public ChasmItem(ChasmItemBehavior behavior, Properties properties) {
		super(properties);
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
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		return behavior.handleUse(level, player, hand, player.getItemInHand(hand));
	}

	@Override
	public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
		if (behavior.handleAttack(stack, target, attacker)) {
			return true; // 特质已完全消费本次攻击，不再走原版伤害逻辑
		}
		return super.hurtEnemy(stack, target, attacker);
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

	/**
	 * **按栈动态命名**（把 {@link ChasmItemBehavior#nameOf} 接到原版的物品名路径上）。
	 *
	 * <h2>为什么这个覆写就是全部接线</h2>
	 * <p>1.21.1 的物品名只有一条路：{@code ItemStack.getHoverName()}
	 * → {@code getItem().getName(stack)}（反汇编 {@code ItemStack#getHoverName} 第 34-42 行：
	 * 先看 {@code CUSTOM_NAME}、再看 {@code ITEM_NAME}，都没有才调 {@code Item.getName(ItemStack)}）。
	 * 之前的 {@link ChasmItemBehavior#nameOf} **只有定义、全仓无调用者**，于是"同一物品不同组件 = 不同名字"
	 * 的玩法（宝石纯度、模块化工具名、双头协同名）只能退化成"名字不变、全靠 tooltip"。</p>
	 *
	 * <h2>行为边界</h2>
	 * <ul>
	 *   <li>{@code nameOf} 返回 null（没设 provider，或 provider 明确表态"用静态名"）→ **原样走
	 *       {@code super.getName}**，与从前逐字一致（语言文件里的静态名照常生效）；</li>
	 *   <li>{@code nameOf} 抛异常 → 退回静态名（渲染线程/提示框上抛异常会崩客户端）；</li>
	 *   <li>铁砧改名/命名牌走 {@code CUSTOM_NAME} 组件，根本不经过这里（原版优先级，不受影响）。</li>
	 * </ul>
	 */
	@Override
	public Component getName(ItemStack stack) {
		try {
			Component dynamic = behavior.nameOf(stack);
			if (dynamic != null) {
				return dynamic;
			}
		} catch (RuntimeException e) {
			api.chasm.log.ChasmLogger.error("chasm", "物品 {} 的动态命名失败（回落到静态名）: {}",
				this, e.toString());
		}
		return super.getName(stack);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
		// R12：行为可以在原版基础行**之前**插入（真实 Tetra 的模块清单就在这里，Fabric 的
		// ItemTooltipCallback 只能追加到最底部，位置不对）
		List<Component> before = behavior.tooltipLines(stack, flag, ChasmItemBehavior.TooltipSlot.BEFORE_BASE);
		if (!before.isEmpty()) {
			tooltip.addAll(before);
		}
		super.appendHoverText(stack, context, tooltip, flag);
		List<Component> after = behavior.tooltipLines(stack, flag, ChasmItemBehavior.TooltipSlot.AFTER_BASE);
		if (!after.isEmpty()) {
			tooltip.addAll(after);
		}
		if (behavior.transformsVanillaTooltip()) {
			// 把原版"修饰符"式攻击速度行替换为易读的总数值（如 -2.4 → 攻击速度: 1.6）
			ChasmTooltipUtil.transform(stack, tooltip);
		}
	}

	// ------------------------------------------------------------ R4：挖掘钩子（转发给行为）

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