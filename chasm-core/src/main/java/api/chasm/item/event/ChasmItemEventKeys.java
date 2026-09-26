package api.chasm.item.event;

import api.chasm.effect.ChasmDamageNumbers;
import api.chasm.effect.DamageNumber;
import api.chasm.item.event.payload.BlockBreakPayload;
import api.chasm.item.event.payload.BlockXpPayload;
import api.chasm.item.event.payload.DamagePayload;
import api.chasm.item.event.payload.HitPayload;
import api.chasm.item.event.payload.HurtPayload;
import api.chasm.item.event.payload.ProjectilePayload;
import api.chasm.item.event.payload.ShieldBlockPayload;
import api.chasm.item.event.payload.ShootPayload;
import api.chasm.item.event.payload.UseOnPayload;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * 内置事件种类 + 分发入口（受击 / 挖方块 / useOn / 射箭 / 盾挡）。
 *
 * <p><b>它们不是写死的模板</b>：每一个都只是"用开放注册表登记的一个事件种类"，享有与第三方
 * 自定义事件完全相同的机制（可换协议、可换栈来源、可被替换/扩展）。第三方可用同一套 API
 * 注册自己的事件（如 {@code mymod:alchemy_brew}）与自己的协议（如三态、权重表）。</p>
 *
 * <p>事件源适配器见 {@link ChasmItemHookAdapters}；{@code fire*} 方法为公共 API，
 * 供模组从自有钩子（含 mixin）触发同一套事件。</p>
 */
public final class ChasmItemEventKeys {

	private ChasmItemEventKeys() {
	}

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath("chasm", path);
	}

	/** 受击（伤害结算前，可完全取消）：结果 TRUE=允许，FALSE=取消。来源：受击者装备。 */
	public static final ItemEventKey<Boolean> ON_INCOMING_DAMAGE =
		ChasmItemEvents.key(id("on_incoming_damage"), ChasmProtocols.ALLOW);

	/** 命中（伤害已结算，返回额外伤害值，由适配器补结算）：来源：攻击者手持。 */
	public static final ItemEventKey<Float> ON_HIT =
		ChasmItemEvents.key(id("on_hit"), ChasmProtocols.ADDITIVE);

	/** 受击后效果（反伤/反击，返回额外伤害）：来源：受击者装备。 */
	public static final ItemEventKey<Float> ON_POST_HURT =
		ChasmItemEvents.key(id("on_post_hurt"), ChasmProtocols.ADDITIVE);

	/** 盾挡成功（返回反伤/额外效果强度）：来源：格挡者装备。 */
	public static final ItemEventKey<Float> ON_SHIELD_BLOCK =
		ChasmItemEvents.key(id("on_shield_block"), ChasmProtocols.ADDITIVE);

	/** 挖方块前（TRUE=允许，FALSE=取消）：来源：玩家手持。 */
	public static final ItemEventKey<Boolean> ON_BLOCK_BREAK_BEFORE =
		ChasmItemEvents.key(id("on_block_break_before"), ChasmProtocols.ALLOW);

	/** 挖方块后（返回附加强度，如额外掉落/经验系数）：来源：玩家手持。 */
	public static final ItemEventKey<Float> ON_BLOCK_BREAK_AFTER =
		ChasmItemEvents.key(id("on_block_break_after"), ChasmProtocols.ADDITIVE);

	/** 对方块右键 useOn（交互结果按优先级合并）：来源：玩家手持。 */
	public static final ItemEventKey<InteractionResult> ON_USE_ON =
		ChasmItemEvents.key(id("on_use_on"), ChasmProtocols.INTERACTION);

	/** 弹射物命中（射箭效果的实际结算点）：来源：射手手持。 */
	public static final ItemEventKey<Float> ON_PROJECTILE_HIT =
		ChasmItemEvents.key(id("on_projectile_hit"), ChasmProtocols.ADDITIVE);

	/** 射击释放（无原版事件；由模组自带适配器调用 {@link #fireShoot}）：来源：射手手持。 */
	public static final ItemEventKey<Float> ON_SHOOT =
		ChasmItemEvents.key(id("on_shoot"), ChasmProtocols.ADDITIVE);

	/**
	 * **耐久损耗无视比例**（0..1，多来源相加；1 = 完全不掉耐久）。载荷：{@code DurabilityLossPayload}。
	 *
	 * <p>对应神化宝石的 durability 加成。原版 hurtAndBreak 无法零 mixin 拦截，因此本事件在
	 * **框架自己的耐久扣减点**生效（UseContext/AttackContext.damageItem 走 ChasmDurability.damage）；
	 * 模组若要覆盖原版路径，可在扣减前自行调用 ChasmDurability.damage。</p>
	 */
	public static final ItemEventKey<Float> ON_DURABILITY_LOSS =
		ChasmItemEvents.key(id("on_durability_loss"), ChasmProtocols.ADDITIVE);

	/**
	 * **受击减伤比例**（0..1，多来源相加；1 = 完全免伤）：来源：受击者装备。载荷：{@code DamagePayload}。
	 *
	 * <p>对应神化宝石的 damage_reduction 加成。原版伤害量在 ALLOW_DAMAGE 之后不可修改，
	 * 因此框架只提供协议：调用方在受伤结算后按比例补回（移植模块的 DamageReductionAdapter 即此实现）。</p>
	 */
	public static final ItemEventKey<Float> ON_DAMAGE_REDUCTION =
		ChasmItemEvents.key(id("on_damage_reduction"), ChasmProtocols.ADDITIVE);

	/**
	 * **伤害结算前的数值修正**（护甲换算**之前**）：来源：攻击者主手。载荷：{@code HurtPayload}。
	 *
	 * <p>与布尔事件 {@link #ON_INCOMING_DAMAGE} 的分工：那个回答"能不能打"（可取消），
	 * 这个回答"打多少 + 削多少护甲"（{@link DamageNumber}：加法 / 乘算 / 下限 / 护甲无视）。</p>
	 *
	 * <p>真实依据（Forge 的 {@code LivingHurtEvent} 阶段，真实 Tetra 在这里做两件事）：</p>
	 * <ul>
	 *   <li>{@code quickStrike}：伤害低于 {@code (0.2 + 0.05 x level) x 攻击力} 时抬上去
	 *       （{@code ItemEffectHandler.java:214-223}）→ 用 {@link DamageNumber#atLeast(float)} 表达；</li>
	 *   <li>{@code armorPenetration}：给目标挂临时 ARMOR 修饰符 {@code -level x 0.01}（{@code MULTIPLY_TOTAL}）
	 *       （{@code ArmorPenetrationEffect.java:25-33}）→ 用 {@link DamageNumber#penetrate(float)} 表达。</li>
	 * </ul>
	 *
	 * <p><b>框架如何应用</b>（见 {@code ChasmItemHookAdapters}）：护甲无视在 {@code ALLOW_DAMAGE} 里
	 * 立刻转成目标的临时 ARMOR 修饰符（因此**这次**伤害就少算护甲，与真实完全一致），
	 * 伤害差额在 {@code AFTER_DAMAGE} 里补结算（1.21.1 的 Fabric 事件无法直接改 hurt 的入参，
	 * 这是协议边界，已在 {@code DamageNumber} 文档写明）。</p>
	 */
	public static final ItemEventKey<DamageNumber> ON_HURT =
		ChasmItemEvents.key(id("on_hurt"), ChasmDamageNumbers.NUMBER);

	/**
	 * **方块经验掉落**：来源：破坏方块的玩家主手。载荷：{@code BlockXpPayload}；结果 = 增量（ADDITIVE）。
	 *
	 * <p>框架不调用 {@code Block#tryDropExperience}（1.21.1 里是 {@code protected}），
	 * 而是捕捉经验球的真实数值（见 {@code api.chasm.effect.ChasmBlockXp}），因此对模组方块同样成立。
	 * 真实用例：{@code intuit}（{@code ItemModularHandheld.java:201-210}：打磨进度 += 经验 x 等级）、
	 * {@code satiating}（{@code ItemEffectHandler.java:138-151}：抽取掉落经验）。</p>
	 */
	public static final ItemEventKey<Float> ON_BLOCK_XP =
		ChasmItemEvents.key(id("on_block_xp"), ChasmProtocols.ADDITIVE);

	static {
		// 内置事件的默认栈来源（第三方可再追加来源，或按自己的事件注册来源）
		ItemStackSources.register(ON_INCOMING_DAMAGE, ItemStackSources.EQUIPPED);
		ItemStackSources.register(ON_POST_HURT, ItemStackSources.EQUIPPED);
		ItemStackSources.register(ON_SHIELD_BLOCK, ItemStackSources.EQUIPPED);
		ItemStackSources.register(ON_HIT, ItemStackSources.HELD);
		ItemStackSources.register(ON_PROJECTILE_HIT, ItemStackSources.HELD);
		ItemStackSources.register(ON_SHOOT, ItemStackSources.HELD);
		ItemStackSources.register(ON_BLOCK_BREAK_BEFORE, ItemStackSources.MAIN_HAND);
		ItemStackSources.register(ON_BLOCK_BREAK_AFTER, ItemStackSources.MAIN_HAND);
		ItemStackSources.register(ON_USE_ON, ItemStackSources.HELD);
		ItemStackSources.register(ON_DAMAGE_REDUCTION, ItemStackSources.EQUIPPED);
		ItemStackSources.register(ON_HURT, ItemStackSources.MAIN_HAND);
		ItemStackSources.register(ON_BLOCK_XP, ItemStackSources.MAIN_HAND);
	}

	// ---------------------------------------------------------------- fire*

	/** 触发"受击前"：返回 false 表示有物品取消了本次伤害。 */
	public static boolean fireIncomingDamage(LivingEntity victim, DamageSource source, float amount) {
		return ChasmItemEvents.dispatchEntity(victim, ON_INCOMING_DAMAGE, new DamagePayload(victim, source, amount));
	}

	/** 触发"命中"：返回额外伤害（0 无额外）。 */
	public static float fireHit(Entity attacker, LivingEntity victim, DamageSource source,
								float original, float finalDamage) {
		if (!(attacker instanceof LivingEntity living)) {
			return 0.0F;
		}
		return ChasmItemEvents.dispatchEntity(living, ON_HIT, new HitPayload(attacker, victim, source, original, finalDamage));
	}

	/** 触发"受击减伤"：返回减免比例（0..1，多来源相加）。 */
	public static float fireDamageReduction(LivingEntity victim, DamageSource source, float amount) {
		if (!ChasmItemEvents.isInteresting(ON_DAMAGE_REDUCTION)) {
			return 0.0F;
		}
		return ChasmItemEvents.dispatchEntity(victim, ON_DAMAGE_REDUCTION,
			new DamagePayload(victim, source, amount));
	}

	/** 触发"耐久损耗无视"：返回无视比例（0..1）。 */
	public static float fireDurabilityLoss(net.minecraft.world.item.ItemStack stack, int amount) {
		return ChasmItemEvents.dispatch(stack, ON_DURABILITY_LOSS,
			new api.chasm.item.event.payload.DurabilityLossPayload(stack, amount));
	}

	/**
	 * 触发"伤害数值前置"：返回本次伤害的数值修正（加法 / 乘算 / 下限 / 护甲无视）。
	 *
	 * <p>无监听者时返回 {@link DamageNumber#NEUTRAL}（等于"什么都不改"），调用方零分支可用。</p>
	 */
	public static DamageNumber fireHurt(LivingEntity victim, DamageSource source, float amount) {
		if (!ChasmItemEvents.isInteresting(ON_HURT)) {
			return DamageNumber.NEUTRAL;
		}
		net.minecraft.world.entity.Entity attacker = source == null ? null : source.getEntity();
		if (!(attacker instanceof LivingEntity living) || living == victim) {
			// 没有攻击者（摔落/火焰/环境）或自己打自己 → 没有人"手持"武器，直接中性
			return DamageNumber.NEUTRAL;
		}
		return ChasmItemEvents.dispatchEntity(living, ON_HURT, new HurtPayload(victim, source, living, amount));
	}

	/** 触发"方块经验掉落"：返回增量（0 = 不改；无监听者恒 0）。 */
	public static float fireBlockXp(BlockXpPayload payload) {
		if (payload == null || !ChasmItemEvents.isInteresting(ON_BLOCK_XP)) {
			return 0.0F;
		}
		return ChasmItemEvents.dispatchEntity(payload.player(), ON_BLOCK_XP, payload);
	}

	/** 触发"受击后"：返回反击伤害。 */
	public static float firePostHurt(LivingEntity victim, DamageSource source, float amount) {
		return ChasmItemEvents.dispatchEntity(victim, ON_POST_HURT, new DamagePayload(victim, source, amount));
	}

	/** 触发"盾挡"：返回反伤强度。 */
	public static float fireShieldBlock(LivingEntity blocker, DamageSource source, float blocked, float base) {
		return ChasmItemEvents.dispatchEntity(blocker, ON_SHIELD_BLOCK, new ShieldBlockPayload(blocker, source, blocked, base));
	}

	/** 触发"挖方块前"：返回 false 表示取消本次破坏。 */
	public static boolean fireBlockBreakBefore(BlockBreakPayload payload) {
		return ChasmItemEvents.dispatchEntity(payload.player(), ON_BLOCK_BREAK_BEFORE, payload);
	}

	/** 触发"挖方块后"：返回附加强度。 */
	public static float fireBlockBreakAfter(BlockBreakPayload payload) {
		return ChasmItemEvents.dispatchEntity(payload.player(), ON_BLOCK_BREAK_AFTER, payload);
	}

	/** 触发 useOn：返回交互结果（按优先级合并）。 */
	public static InteractionResult fireUseOn(UseOnPayload payload) {
		return ChasmItemEvents.dispatchEntity(payload.player(), ON_USE_ON, payload);
	}

	/** 触发"弹射物命中"：返回额外伤害。 */
	public static float fireProjectileHit(ProjectilePayload payload) {
		if (!(payload.shooter() instanceof LivingEntity shooter)) {
			return 0.0F;
		}
		return ChasmItemEvents.dispatchEntity(shooter, ON_PROJECTILE_HIT, payload);
	}

	/** 触发"射击释放"：返回附加强度（供模组自带适配器调用）。 */
	public static float fireShoot(ShootPayload payload) {
		return ChasmItemEvents.dispatchEntity(payload.shooter(), ON_SHOOT, payload);
	}
}
