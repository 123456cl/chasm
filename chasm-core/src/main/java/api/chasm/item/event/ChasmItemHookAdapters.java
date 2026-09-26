package api.chasm.item.event;

import api.chasm.effect.ChasmBlockXp;
import api.chasm.effect.ChasmBreakSpeed;
import api.chasm.effect.DamageNumber;
import api.chasm.item.event.payload.BlockBreakPayload;
import api.chasm.item.event.payload.ProjectilePayload;
import api.chasm.item.event.payload.UseOnPayload;
import api.chasm.log.ChasmLogger;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 内置事件源适配器：把 Fabric / 原版可用的事件接到 {@link ChasmItemEvents} 分发总线上。
 *
 * <p>覆盖面与实现依据（1.21.1 + Fabric API 0.116，零 mixin）：</p>
 * <ul>
 *   <li><b>受击</b>：{@code ServerLivingEntityEvents.ALLOW_DAMAGE} → {@link ChasmItemEventKeys#ON_INCOMING_DAMAGE}
 *       （可完全取消）；{@code AFTER_DAMAGE} → {@link ChasmItemEventKeys#ON_POST_HURT}（反伤）。</li>
 *   <li><b>命中</b>：{@code AFTER_DAMAGE} → {@link ChasmItemEventKeys#ON_HIT}（补结算额外伤害）。</li>
 *   <li><b>盾挡</b>：{@code AFTER_DAMAGE} 的 {@code blocked} 标记 → {@link ChasmItemEventKeys#ON_SHIELD_BLOCK}。</li>
 *   <li><b>挖方块</b>：{@code PlayerBlockBreakEvents.BEFORE/AFTER} → {@link ChasmItemEventKeys#ON_BLOCK_BREAK_BEFORE}
 *       （可取消）/ {@link ChasmItemEventKeys#ON_BLOCK_BREAK_AFTER}。</li>
 *   <li><b>useOn</b>：{@code UseBlockCallback} → {@link ChasmItemEventKeys#ON_USE_ON}。</li>
 *   <li><b>射箭</b>：命中时刻由 {@code AFTER_DAMAGE}（直伤实体是弹射物）→
 *       {@link ChasmItemEventKeys#ON_PROJECTILE_HIT}；"释放瞬间"原版无事件，走
 *       {@link ChasmItemEventKeys#fireShoot} 由模组自带适配器触发（开放事件源的意义）。</li>
 * </ul>
 *
 * <p>第三方可调用 {@link #registerAdapter} 注册自己的事件源（如自定义法术系统、
 * 能量武器、载具撞击），无需改框架。</p>
 */
public final class ChasmItemHookAdapters {

	/** 再入保护：由本适配器补结算的伤害不再触发同一批事件，避免递归/自伤循环。 */
	private static final ThreadLocal<Boolean> REENTRANT = ThreadLocal.withInitial(() -> Boolean.FALSE);

	/**
	 * 待结算的数值修正（{@code ON_HURT} 的结果）：受击者 → 修正。
	 *
	 * <p>为什么需要跨事件保存：护甲无视必须在 {@code ALLOW_DAMAGE}（护甲换算之前）挂上，
	 * 而撤销与差额补偿只能在 {@code AFTER_DAMAGE}（伤害已落地）做。
	 * 表用 {@code WeakHashMap} —— 受击者被卸载后条目自然回收，不会长期持有实体。</p>
	 */
	private static final Map<LivingEntity, DamageNumber> PENDING_HURTS =
		Collections.synchronizedMap(new WeakHashMap<>());

	/** 削护甲的临时修饰符 id（确定性：同 id 覆盖，绝不叠加）。 */
	private static final ResourceLocation ARMOR_PEN_ID =
		ResourceLocation.fromNamespaceAndPath("chasm", "armor_penetration");

	private static final List<String> ADAPTER_NAMES = new ArrayList<>();

	private ChasmItemHookAdapters() {
	}

	/** 注册一个自定义事件源（开放钩子）；{@code init} 内自行挂接任意外部事件并调用 fire* 方法。 */
	public static void registerAdapter(String name, Runnable init) {
		try {
			init.run();
			ADAPTER_NAMES.add(name);
			ChasmLogger.call("chasm", "ChasmItemHookAdapters", "registerAdapter", "已注册事件源适配器 {}", name);
		} catch (Throwable t) {
			ChasmLogger.error("chasm", "事件源适配器 {} 注册失败（已隔离）", name, t);
		}
	}

	/** 已注册适配器名（调试）。 */
	public static List<String> adapterNames() {
		return List.copyOf(ADAPTER_NAMES);
	}

	/** 安装内置适配器（由 {@code ChasmInit} 调用一次）。 */
	public static void init() {
		ServerLivingEntityEvents.ALLOW_DAMAGE.register(ChasmItemHookAdapters::allowDamage);
		ServerLivingEntityEvents.AFTER_DAMAGE.register(ChasmItemHookAdapters::afterDamage);
		PlayerBlockBreakEvents.BEFORE.register(ChasmItemHookAdapters::beforeBlockBreak);
		PlayerBlockBreakEvents.AFTER.register(ChasmItemHookAdapters::afterBlockBreak);
		UseBlockCallback.EVENT.register(ChasmItemHookAdapters::useOn);
		// 破坏速度钩子：服务端的"开始破坏方块"（Fabric 只在 START_DESTROY_BLOCK 触发，
		// 见 ServerPlayerInteractionManagerMixin:58-61）+ 每 tick 清理过期记录
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
			if (!level.isClientSide() && pos != null) {
				ChasmBreakSpeed.onStartBreaking(player, pos, level.getBlockState(pos));
			}
			return InteractionResult.PASS;
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			ChasmBreakSpeed.tick();
			ChasmBlockXp.tick();
		});
		// 方块经验钩子：1.21.1 没有公开的 getExpDrop，框架捕捉经验球的真实数值
		ServerEntityEvents.ENTITY_LOAD.register(ChasmBlockXp::onEntityLoad);
		ChasmLogger.call("chasm", "ChasmItemHookAdapters", "init",
			"内置事件源适配器已注册（受击/命中/盾挡/挖方块/useOn/弹射物命中/伤害数值/破坏速度/方块经验）");
	}

	// ------------------------------------------------------------ 受击 / 命中 / 盾挡

	private static boolean allowDamage(LivingEntity victim, DamageSource source, float amount) {
		if (victim.level().isClientSide() || REENTRANT.get()) {
			// 再入的这次 hurt 是本适配器自己补结算出来的，不再过一遍事件（否则会递归）
			return true;
		}
		boolean allowed = true;
		if (ChasmItemEvents.isInteresting(ChasmItemEventKeys.ON_INCOMING_DAMAGE)) {
			try {
				allowed = ChasmItemEventKeys.fireIncomingDamage(victim, source, amount);
			} catch (Throwable t) {
				ChasmLogger.error("chasm", "受击事件分发异常（已隔离，默认放行）", t);
				allowed = true;
			}
		}
		// 数值前置（改数值 / 削护甲）：必须在护甲换算之前挂上，所以就在这一步做
		prepareHurt(victim, source, amount, allowed);
		return allowed;
	}

	/**
	 * {@code ON_HURT} 的**应用**：挂临时护甲削减 + 记下待补的数值差额。
	 *
	 * <p>三步：① 自愈 —— 上一次若没走到 {@code AFTER_DAMAGE}（被别的模组拦下、伤害被吞），
	 * 先把残留的护甲修饰符清掉；② 状态权威 —— 本次被取消（{@code allowed == false}）就什么都不挂；
	 * ③ 挂削减并记一笔，等 {@code AFTER_DAMAGE} 收尾。</p>
	 */
	private static void prepareHurt(LivingEntity victim, DamageSource source, float amount, boolean allowed) {
		DamageNumber stale = PENDING_HURTS.remove(victim);
		if (stale != null) {
			removeArmorPenetration(victim);
		}
		if (!allowed || !ChasmItemEvents.isInteresting(ChasmItemEventKeys.ON_HURT)) {
			return;
		}
		try {
			DamageNumber number = ChasmItemEventKeys.fireHurt(victim, source, amount);
			if (number == null || number.isNeutral()) {
				return;
			}
			applyArmorPenetration(victim, number.armorPenetration());
			PENDING_HURTS.put(victim, number);
		} catch (Throwable t) {
			ChasmLogger.error("chasm", "伤害数值事件分发异常（已隔离）", t);
			removeArmorPenetration(victim);
		}
	}

	/**
	 * 把"护甲无视比例"落成目标身上的**临时** ARMOR 修饰符。
	 *
	 * <p>与真实 {@code ArmorPenetrationEffect.java:25-33} 逐条对应：
	 * {@code MULTIPLY_TOTAL} 且值为 {@code -level x 0.01}（{@code MULTIPLY_TOTAL} 的语义就是
	 * {@code (base + add) x (1 + sum)}，所以 −0.2 恰好等于"护甲 x 0.8"）；
	 * 已存在同 id 修饰符时**不重复挂**（真实用 {@code instance.getModifier(uuid) == null} 判断）。</p>
	 */
	private static void applyArmorPenetration(LivingEntity victim, float penetration) {
		if (!(penetration > 0.0F)) {
			return;
		}
		AttributeInstance armor = victim.getAttribute(Attributes.ARMOR);
		if (armor == null || armor.getModifier(ARMOR_PEN_ID) != null) {
			return;
		}
		// 1.21.1 的枚举名是 ADD_MULTIPLIED_TOTAL（1.20 的 MULTIPLY_TOTAL 已改名，语义不变）
		armor.addTransientModifier(new AttributeModifier(ARMOR_PEN_ID, -Math.min(1.0F, penetration),
			AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
	}

	/** 撤销护甲削减（幂等；目标没有该属性时静默）。 */
	private static void removeArmorPenetration(LivingEntity victim) {
		try {
			AttributeInstance armor = victim.getAttribute(Attributes.ARMOR);
			if (armor != null && armor.getModifier(ARMOR_PEN_ID) != null) {
				armor.removeModifier(ARMOR_PEN_ID);
			}
		} catch (Throwable t) {
			ChasmLogger.warn("chasm", "撤销护甲削减失败: {}", String.valueOf(t));
		}
	}

	/** 当前是否有待结算的数值修正（调试/测试）。 */
	public static int pendingHurtCount() {
		return PENDING_HURTS.size();
	}

	private static void afterDamage(LivingEntity victim, DamageSource source, float base, float taken, boolean blocked) {
		if (victim.level().isClientSide() || REENTRANT.get()) {
			return;
		}
		// 先摘走数值前置留下的待结算项：**无论有没有其它监听者都要收尾**
		// （否则临时护甲削减会永久留在目标身上）
		DamageNumber pending = PENDING_HURTS.remove(victim);
		// 兴趣掩码早退：没有模组监听本批事件时零分配、零收集
		boolean interested = ChasmItemEvents.isInteresting(ChasmItemEventKeys.ON_HIT)
			|| ChasmItemEvents.isInteresting(ChasmItemEventKeys.ON_POST_HURT)
			|| ChasmItemEvents.isInteresting(ChasmItemEventKeys.ON_SHIELD_BLOCK)
			|| ChasmItemEvents.isInteresting(ChasmItemEventKeys.ON_PROJECTILE_HIT);
		if (pending == null && !interested) {
			return;
		}
		REENTRANT.set(Boolean.TRUE);
		try {
			if (pending != null) {
				// 护甲削减用完即撤（真实 ArmorPenetrationEffect.java:35-41 的 onLivingDamage）
				removeArmorPenetration(victim);
				// 差额补结算：1.21.1 的 Fabric 事件无法改 hurt 的入参，只能事后补；
				// 减伤方向无法用"再打一次"表达，因此只补正值（已在 DamageNumber/事件键文档写明边界）
				applyExtra(victim, source, pending.delta(base));
			}
			Entity direct = source.getDirectEntity();
			if (direct instanceof Projectile projectile) {
				float extra = ChasmItemEventKeys.fireProjectileHit(
					new ProjectilePayload(source.getEntity(), projectile, source, victim, taken));
				applyExtra(victim, source, extra);
			}
			if (blocked) {
				float reflect = ChasmItemEventKeys.fireShieldBlock(victim, source, base - taken, base);
				Entity attacker = source.getEntity();
				if (reflect > 0.0F && attacker instanceof LivingEntity livingAttacker && livingAttacker != victim) {
					livingAttacker.hurt(victim.damageSources().thorns(victim), reflect);
				}
			}
			float hitExtra = ChasmItemEventKeys.fireHit(source.getEntity(), victim, source, base, taken);
			applyExtra(victim, source, hitExtra);
			float postExtra = ChasmItemEventKeys.firePostHurt(victim, source, taken);
			Entity attacker = source.getEntity();
			if (postExtra > 0.0F && attacker instanceof LivingEntity livingAttacker && livingAttacker != victim) {
				livingAttacker.hurt(victim.damageSources().thorns(victim), postExtra);
			}
		} catch (Throwable t) {
			ChasmLogger.error("chasm", "命中/盾挡事件分发异常（已隔离）", t);
		} finally {
			REENTRANT.set(Boolean.FALSE);
		}
	}

	private static void applyExtra(LivingEntity victim, DamageSource source, float extra) {
		if (extra > 0.0F && victim.isAlive()) {
			victim.hurt(source, extra);
		}
	}

	// ------------------------------------------------------------------ 挖方块

	private static boolean beforeBlockBreak(Level level, Player player, BlockPos pos, BlockState state,
											BlockEntity blockEntity) {
		if (level.isClientSide() || !ChasmItemEvents.isInteresting(ChasmItemEventKeys.ON_BLOCK_BREAK_BEFORE)) {
			return true;
		}
		try {
			return ChasmItemEventKeys.fireBlockBreakBefore(new BlockBreakPayload(player, level, pos, state, blockEntity));
		} catch (Throwable t) {
			ChasmLogger.error("chasm", "挖方块前事件分发异常（已隔离，默认放行）", t);
			return true;
		}
	}

	private static void afterBlockBreak(Level level, Player player, BlockPos pos, BlockState state,
										BlockEntity blockEntity) {
		if (level.isClientSide()) {
			return;
		}
		// ① 破坏速度钩子收尾：方块真没了 → 撤掉临时修正（真实 Tetra 的临界一击缓存同理：
		//    CritEffect.critBlock 破块后立刻失效）
		ChasmBreakSpeed.onBroken(player, pos);
		// ② 方块经验钩子：记一笔，等经验球进入世界时认领（Block#tryDropExperience 是 protected）
		if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
			ChasmBlockXp.trackBreak(serverLevel, player, pos, state);
		}
		if (!ChasmItemEvents.isInteresting(ChasmItemEventKeys.ON_BLOCK_BREAK_AFTER)) {
			return;
		}
		try {
			ChasmItemEventKeys.fireBlockBreakAfter(new BlockBreakPayload(player, level, pos, state, blockEntity));
		} catch (Throwable t) {
			ChasmLogger.error("chasm", "挖方块后事件分发异常（已隔离）", t);
		}
	}

	// ------------------------------------------------------------------- useOn

	private static InteractionResult useOn(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (level.isClientSide() || !ChasmItemEvents.isInteresting(ChasmItemEventKeys.ON_USE_ON)) {
			return InteractionResult.PASS;
		}
		try {
			BlockPos pos = hit.getBlockPos();
			UseOnPayload payload = new UseOnPayload(player, level, hand, pos, level.getBlockState(pos), hit);
			InteractionResult result = ChasmItemEventKeys.fireUseOn(payload);
			return result == null ? InteractionResult.PASS : result;
		} catch (Throwable t) {
			ChasmLogger.error("chasm", "useOn 事件分发异常（已隔离，默认放行）", t);
			return InteractionResult.PASS;
		}
	}
}
