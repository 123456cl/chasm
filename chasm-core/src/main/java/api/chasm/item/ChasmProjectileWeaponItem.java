package api.chasm.item;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * **投射武器类物品基类**（弓 / 弩 / 三叉戟 / 枪械这类"按住蓄力、松手发射"的物品）。
 *
 * <h2>为什么它必须是 {@link ProjectileWeaponItem} 的子类（1.21.1 硬约束，非风格选择）</h2>
 * <p>真实的模块化弓需要"从玩家背包里找弹药"。1.20 的 Forge 里这件事是
 * {@code Player#getProjectile(ItemStack)} 无条件完成的，所以 Tetra 的弓
 * （{@code ModularBowItem extends ModularItem}，**不是** ProjectileWeaponItem）照样能找箭；
 * 弩则专门造了一个只用来查弹药的假武器
 * （{@code ShootableDummyItem extends ProjectileWeaponItem}，真实源码
 * {@code items/modular/impl/crossbow/ShootableDummyItem.java:11-38}）——
 * 这个"Dummy 必须继承 ProjectileWeaponItem"的事实本身就说明：能不能查弹药由继承关系决定。</p>
 *
 * <p>1.21.1 的实现可以直接反汇编看到：{@code Player#getProjectile(ItemStack)} 的第一段是
 * {@code instanceof ProjectileWeaponItem}，**不是就返回 {@code ItemStack.EMPTY}**：</p>
 * <pre>
 * javap -p -c net.minecraft.world.entity.player.Player  （getProjectile 字节码偏移 0-13）
 *    0: aload_1
 *    1: invokevirtual  ItemStack.getItem:()Lnet/minecraft/world/item/Item;
 *    4: instanceof     class net/minecraft/world/item/ProjectileWeaponItem
 *    7: ifne 14
 *   10: getstatic      ItemStack.EMPTY
 *   13: areturn
 * </pre>
 * <p>推论：端口里{@code ChasmItem}（{@code Item} 子类）做弓的话，{@code player.getProjectile(bowStack)}
 * <b>恒为空</b>，弓永远射不出一支箭 —— 无论其它逻辑写得多对。所以"零 mixin 的 1.21.1 等价物"
 * 只能是让弓/弩物品本体成为 {@code ProjectileWeaponItem}（这也是原版 {@code BowItem} /
 * {@code CrossbowItem} 的做法），而不是自己遍历背包（那样会与箭袋类模组/原版副手规则分叉）。</p>
 *
 * <h2>与 {@link ChasmItem} 的关系</h2>
 * <p>本类**逐条复刻** {@link ChasmItem} 的委托（{@code use / hurtEnemy / inventoryTick /
 * getDefaultInstance / getName / appendHoverText / 挖掘三钩子}），只把父类从 {@code Item} 换成
 * {@link ProjectileWeaponItem}，这样 {@code ChasmItemBehavior} 的那一套（特质、右键回调、动态命名、
 * 提示行、挖掘接管、默认数据组件）在投射武器上**一个不少**。</p>
 *
 * <h2>蓄力/发射钩子留给子类</h2>
 * <p>1.21.1 的蓄力链是四个原版方法（反汇编 {@code net.minecraft.world.item.Item}）：
 * {@code use(Level,Player,InteractionHand)} / {@code getUseDuration(ItemStack,LivingEntity)} /
 * {@code getUseAnimation(ItemStack)} / {@code onUseTick(Level,LivingEntity,ItemStack,int)} /
 * {@code releaseUsing(ItemStack,Level,LivingEntity,int)} —— 注意
 * {@code getUseDuration} 在 1.21.1 **多了一个 {@code LivingEntity} 参数**（1.20 是单参）。
 * 这四个方法本类**不表态**（不覆写），由具体武器子类实现，避免把弓/弩的差异硬塞进基类。</p>
 */
public abstract class ChasmProjectileWeaponItem extends ProjectileWeaponItem implements ChasmItemSupport {

	private final ChasmItemBehavior behavior;

	public ChasmProjectileWeaponItem(ChasmItemBehavior behavior, Properties properties) {
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

	/** 按栈动态命名（与 {@link ChasmItem#getName(ItemStack)} 同一条路径与同一套兜底）。 */
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
			ChasmTooltipUtil.transform(stack, tooltip);
		}
	}

	/**
	 * **{@code ProjectileWeaponItem} 的抽象方法**（javap：
	 * {@code protected abstract void shootProjectile(LivingEntity, Projectile, int, float, float, float, LivingEntity)}）。
	 *
	 * <p>实现体与**原版弓逐字一致**（反汇编 {@code BowItem.shootProjectile}：
	 * {@code projectile.shootFromRotation(shooter, shooter.getXRot(), shooter.getYRot() + angle, 0.0F, velocity, inaccuracy)}）。
	 * 为什么必须给出实现而不是留给子类：真实 Tetra 的弓弩**不走**原版
	 * {@code ProjectileWeaponItem.shoot} 那条路（它们自己算速度/伤害/多重射击，见
	 * {@code ModularBowItem.fireProjectile}），所以这个方法在端口里通常不会被调用；
	 * 但它一旦被调用（例如将来某个子类改用原版射击流程），**表现得像原版弓**才是对的默认，
	 * 而不是抛异常或静默什么都不做。</p>
	 */
	@Override
	protected void shootProjectile(LivingEntity shooter, net.minecraft.world.entity.projectile.Projectile projectile,
								   int index, float velocity, float inaccuracy, float angle, LivingEntity target) {
		projectile.shootFromRotation(shooter, shooter.getXRot(), shooter.getYRot() + angle, 0.0F, velocity, inaccuracy);
	}

	// ------------------------------------------------------------ 挖掘钩子（转发给行为）

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
