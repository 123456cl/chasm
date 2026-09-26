package api.chasm.effect;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **效果层框架三件能力**的回归测试（数值 / 触发条件 / 边界）。
 *
 * <p>断言的全部是"真实实现的口径"，每条都带参考实现的 file:line：</p>
 * <ul>
 *   <li>{@link DamageNumber}：quickStrike 的下限（{@code ItemEffectHandler.java:214-223}）、
 *       armorPenetration 的 {@code -level x 0.01}（{@code ArmorPenetrationEffect.java:32}）、
 *       reaching 的乘算（{@code ReachingEffect.java:35}）；</li>
 *   <li>{@link ChasmMobEffects}：真实 17 个效果都靠"类别 + 颜色 + 属性修饰符 + tick 间隔"注册
 *       （{@code TetraRegistries.java:385-401} / {@code StunPotionEffect.java:24-29}）；</li>
 *   <li>{@link ChasmBreakSpeed}：真实 {@code PlayerEvent.BreakSpeed} 的倍率语义
 *       （{@code ReachingEffect.java:39-43}），折算成原版 {@code MINING_EFFICIENCY} 的加法量；</li>
 *   <li>{@link ChasmBlockXp}：经验增量口径（{@code ItemEffectHandler.java:127-134}）。</li>
 * </ul>
 */
class ChasmEffectFrameworkTest {

	private static final float EPS = 1.0E-4F;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	// ================================================================ ① DamageNumber（数值型伤害前置）

	@Test
	void damageNumberAppliesAllFourChannels() {
		// 中性：什么都不改
		assertTrue(DamageNumber.NEUTRAL.isNeutral(), "NEUTRAL 必须是中性");
		assertEquals(10.0F, DamageNumber.NEUTRAL.apply(10.0F), EPS, "中性值不改数值");

		// 加法（skewering 的 setAmount(amount + level)，SkeweringEffect.java:13）
		assertEquals(15.0F, DamageNumber.NEUTRAL.add(5.0F).apply(10.0F), EPS, "+5");

		// 乘算（reaching 的 amount *= multiplier，ReachingEffect.java:35）
		assertEquals(20.0F, DamageNumber.NEUTRAL.times(2.0F).apply(10.0F), EPS, "x2");

		// 下限（quickStrike 抬到 (0.2 + 0.05 x level) x 攻击力，ItemEffectHandler.java:220-222）
		assertEquals(12.0F, DamageNumber.NEUTRAL.atLeast(12.0F).apply(10.0F), EPS, "下限 12 把 10 抬上去");
		assertEquals(10.0F, DamageNumber.NEUTRAL.atLeast(4.0F).apply(10.0F), EPS, "下限低于当前值 -> 不动");
		// 下限是"最终下限"：不被后续负乘算压回去
		assertEquals(12.0F, DamageNumber.NEUTRAL.atLeast(12.0F).times(0.5F).apply(10.0F), EPS,
			"floor 是最终下限（真实两种效果都是直接 set 数值）");

		// 护甲无视（ArmorPenetrationEffect.java:32 的 -level x 0.01）
		assertEquals(0.3F, DamageNumber.NEUTRAL.penetrate(0.2F).penetrate(0.1F).armorPenetration(), EPS,
			"0.2 + 0.1 = 0.3");
		assertEquals(1.0F, DamageNumber.NEUTRAL.penetrate(0.8F).penetrate(0.5F).armorPenetration(), EPS,
			"累加后钳到 1（护甲最多削光，不能变负）");
		assertEquals(0.35F, DamageNumber.NEUTRAL.penetrate(-0.2F).penetrate(0.35F).armorPenetration(), EPS,
			"负值被钳到 0 再加");

		// 组合：先加后乘（(10+2) x 3 = 36）
		assertEquals(36.0F, DamageNumber.NEUTRAL.add(2.0F).times(3.0F).apply(10.0F), EPS, "(10+2) x 3");

		// 边界：非法输入被规范化（绝不产生 NaN / 0 伤害 / 负伤害）
		assertEquals(1.0F, DamageNumber.NEUTRAL.times(0.0F).multiplier(), EPS, "倍率 0 -> 规范化为 1");
		assertEquals(1.0F, DamageNumber.NEUTRAL.times(Float.NaN).multiplier(), EPS, "NaN 倍率 -> 1");
		assertEquals(0.0F, DamageNumber.NEUTRAL.times(Float.NaN).apply(Float.NaN), EPS, "NaN 伤害 -> 0");
		assertEquals(0.0F, DamageNumber.NEUTRAL.add(-100.0F).apply(10.0F), EPS, "减到负 -> 钳 0");

		// delta：正 = 加伤、负 = 减伤（框架用它决定补结算方向）
		assertEquals(5.0F, DamageNumber.NEUTRAL.add(5.0F).delta(10.0F), EPS, "delta = +5");
		assertEquals(-5.0F, DamageNumber.NEUTRAL.times(0.5F).delta(10.0F), EPS, "delta = -5");
	}

	@Test
	void damageProtocolMergesInAnyOrder() {
		assertSame(DamageNumber.NEUTRAL, ChasmDamageNumbers.NUMBER.initial(), "初始值必须是 NEUTRAL");
		assertFalse(ChasmDamageNumbers.NUMBER.terminates(DamageNumber.NEUTRAL.times(2.0F)),
			"数值协议不终止：后面的监听者要看得到累计值");

		DamageNumber a = DamageNumber.NEUTRAL.add(2.0F);
		DamageNumber b = DamageNumber.NEUTRAL.penetrate(0.25F);
		assertEquals(ChasmDamageNumbers.NUMBER.merge(a, b).apply(10.0F),
			ChasmDamageNumbers.NUMBER.merge(b, a).apply(10.0F), EPS, "合并顺序无关");
		assertEquals(12.0F, ChasmDamageNumbers.NUMBER.merge(a, b).apply(10.0F), EPS, "+2 -> 12");
		assertEquals(0.25F, ChasmDamageNumbers.NUMBER.merge(a, b).armorPenetration(), EPS, "护甲无视 0.25");

		assertEquals(10.0F, ChasmDamageNumbers.NUMBER.merge(DamageNumber.NEUTRAL, null).apply(10.0F), EPS,
			"null 增量被忽略（绝不 NPE）");
		assertEquals(12.0F, ChasmDamageNumbers.NUMBER.merge(null, a).apply(10.0F), EPS, "current 为 null 也安全");
	}

	// ================================================================ ② 自定义药水效果注册

	@Test
	void mobEffectRegistersWithRateCategoryColor() {
		ResourceLocation id = ResourceLocation.fromNamespaceAndPath("chasmtest", "rate_effect");
		MobEffect effect = ChasmMobEffects.register("chasmtest", "rate_effect")
			.category(MobEffectCategory.HARMFUL)
			.color(0x880000)
			// 真实 BleedingPotionEffect.java:49-51 的 duration % 10 == 0
			.everyTicks(10)
			// 真实 BleedingPotionEffect.java:18 的属性修饰符（1.21.1 的枚举名）
			.attribute(Attributes.MOVEMENT_SPEED,
				ResourceLocation.fromNamespaceAndPath("chasmtest", "rate_effect/speed"),
				-0.3D, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL)
			.onTick((entity, amplifier) -> true)
			.register();

		assertNotNull(effect, "注册必须返回实例");
		assertSame(effect, ChasmMobEffects.get(id).orElse(null), "按 id 查得到");
		assertNotNull(ChasmMobEffects.holderOf(id), "Holder 缓存已建立（应用效果时用）");
		// 单测环境的注册表**已经冻结**（Bootstrap.bootStrap() 之后注册会抛
		// IllegalStateException: Registry is already frozen，实测见 .tmp_probe_reg），
		// 真实游戏里 onInitialize 阶段是冻结前 —— 所以这里断言的是"冻结时的降级行为"：
		// 仍然拿得到 Holder（直接 Holder），且不抛异常。
		if (ChasmMobEffects.isInGameRegistry(id)) {
			assertSame(effect, BuiltInRegistries.MOB_EFFECT.get(id), "进了原版效果注册表");
		} else {
			assertNull(BuiltInRegistries.MOB_EFFECT.get(id), "冻结环境：确实没进原版注册表");
			assertNotNull(ChasmMobEffects.holderOf(id), "冻结环境：仍然有可用 Holder（Holder.direct）");
		}
		assertEquals(MobEffectCategory.HARMFUL, effect.getCategory(), "类别 = HARMFUL");
		assertEquals(0x880000, effect.getColor(), "颜色 = 0x880000（真实 BleedingPotionEffect 的构造参数）");

		// tick 间隔：真实 isDurationEffectTick 的 duration % 10 == 0（1.21.1 叫 shouldApplyEffectTickThisTick）
		assertTrue(effect.shouldApplyEffectTickThisTick(10, 0), "duration=10 生效");
		assertFalse(effect.shouldApplyEffectTickThisTick(9, 0), "duration=9 不生效");
		assertFalse(effect.isInstantenous(), "非瞬时效果");
	}

	@Test
	void instantEffectMatchesVanillaInstantenousSemantics() {
		MobEffect instant = ChasmMobEffects.register("chasmtest", "instant_effect")
			.category(MobEffectCategory.BENEFICIAL)
			.color(0xEEEEEE)
			.instant()
			.register();
		assertNotNull(instant, "注册必须返回实例");
		assertTrue(instant.isInstantenous(), "瞬时标记");
		// 与原版 InstantenousMobEffect 逐字一致：shouldApplyEffectTickThisTick = duration >= 1
		assertTrue(instant.shouldApplyEffectTickThisTick(1, 0), "duration 1 -> 生效");
		assertFalse(instant.shouldApplyEffectTickThisTick(0, 0), "duration 0 -> 不生效");
	}

	@Test
	void dataDrivenEffectRegistrationReadsParameters() {
		// 与真实效果同构的一份数据（bleeding 的类别/颜色、earthbound 的属性、severed 的运算名）
		JsonObject json = JsonParser.parseString("""
			{
			  "id": "data_effect",
			  "category": "harmful",
			  "color": "0x006600",
			  "instant": false,
			  "interval": 4,
			  "attributes": [
			    { "attribute": "minecraft:generic.movement_speed",
			      "modifier": "chasmtest:data_effect/speed",
			      "amount": -0.3, "operation": "add_multiplied_total" },
			    { "attribute": "minecraft:generic.knockback_resistance",
			      "amount": 1.0, "operation": "multiply_total" }
			  ]
			}
			""").getAsJsonObject();
		MobEffect effect = ChasmMobEffects.fromJson("chasmtest", json);
		assertNotNull(effect, "数据驱动注册必须成功");
		assertEquals(MobEffectCategory.HARMFUL, effect.getCategory(), "category=harmful");
		assertEquals(0x006600, effect.getColor(), "color=0x006600");
		assertTrue(effect.shouldApplyEffectTickThisTick(4, 0), "interval=4 生效");
		assertFalse(effect.shouldApplyEffectTickThisTick(3, 0), "interval=4 时 duration=3 不生效");
		assertFalse(effect.isInstantenous(), "instant=false");

		// 运算名映射（两套名字都认：1.21 的新名与 1.20 的旧名）
		assertEquals(AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
			ChasmMobEffects.operationByName("add_multiplied_total"), "1.21 新名");
		assertEquals(AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
			ChasmMobEffects.operationByName("multiply_total"), "1.20 旧名等价");
		assertEquals(AttributeModifier.Operation.ADD_MULTIPLIED_BASE,
			ChasmMobEffects.operationByName("multiply_base"), "1.20 旧名等价");
		assertEquals(AttributeModifier.Operation.ADD_VALUE,
			ChasmMobEffects.operationByName(null), "缺省 = 加法");
		assertEquals(MobEffectCategory.BENEFICIAL, ChasmMobEffects.categoryByName("beneficial"), "类别名");
		assertEquals(MobEffectCategory.NEUTRAL, ChasmMobEffects.categoryByName("???"), "未知类别 = NEUTRAL");

		// 边界：坏数据只跳过、不抛异常、不返回半个效果
		assertNull(ChasmMobEffects.fromJson("chasmtest", null), "null JSON -> null");
		assertNull(ChasmMobEffects.fromJson("chasmtest", new JsonObject()), "缺 id -> null");
		JsonObject empty = new JsonObject();
		empty.addProperty("id", "minimal_effect");
		MobEffect minimal = ChasmMobEffects.fromJson("chasmtest", empty);
		assertNotNull(minimal, "只有 id 也要能注册（其余走默认）");
		assertEquals(MobEffectCategory.NEUTRAL, minimal.getCategory(), "默认类别 NEUTRAL");
		assertEquals(0xFFFFFF, minimal.getColor(), "默认颜色白");

		JsonObject badAttrs = new JsonObject();
		badAttrs.addProperty("id", "bad_attrs_effect");
		JsonArray attrs = new JsonArray();
		attrs.add(JsonParser.parseString("{ \"amount\": 1 }").getAsJsonObject());
		badAttrs.add("attributes", attrs);
		assertNotNull(ChasmMobEffects.fromJson("chasmtest", badAttrs), "属性项缺 attribute -> 跳过该项但仍注册");
	}

	// ================================================================ ③ 破坏速度钩子

	@Test
	void breakSpeedMultiplierToAddendIsExact() {
		// 连乘：多个来源各给一个倍率
		assertEquals(6.0F, ChasmBreakSpeed.combineMultipliers(2.0F, 3.0F), EPS, "2 x 3");
		assertEquals(1.0F, ChasmBreakSpeed.combineMultipliers(), EPS, "没有来源 -> 1");
		assertEquals(1.0F, ChasmBreakSpeed.combineMultipliers(0.0F), EPS, "0/负/NaN 一律按 1（扩展点写错不该把速度变成 0）");
		assertEquals(1.0F, ChasmBreakSpeed.combineMultipliers(Float.NaN, -2.0F), EPS, "NaN 与负数被忽略");

		// 倍率 -> MINING_EFFICIENCY 追加量：base x (m - 1)，与原版"f += 属性值"合起来正好是 base x m
		assertEquals(10.0D, ChasmBreakSpeed.addendFor(10.0F, 2.0F), 1.0E-6D, "10 x (2-1) = 10");
		assertEquals(0.0D, ChasmBreakSpeed.addendFor(10.0F, 1.0F), 1.0E-6D, "倍率 1 -> 不加");
		assertEquals(0.0D, ChasmBreakSpeed.addendFor(0.0F, 3.0F), 1.0E-6D, "基础速度 0 -> 不加");
		assertEquals(20.0F, ChasmBreakSpeed.applyMultiplier(10.0F, 2.0F), EPS, "原版读到的结果 = base x m");
		assertEquals(60.0F, ChasmBreakSpeed.applyProviders(10.0F, 2.0F, 3.0F), EPS, "多来源连乘后再作用");

		// 注册表：可插拔、按名字唯一、可注销
		int before = ChasmBreakSpeed.providerCount();
		ChasmBreakSpeed.registerProvider("chasmtest:probe", (player, stack, state, pos, base) -> 1.5F);
		assertEquals(before + 1, ChasmBreakSpeed.providerCount(), "注册后 +1");
		assertTrue(ChasmBreakSpeed.isActive(), "有人注册 -> 事件源不会早退");
		ChasmBreakSpeed.registerProvider("chasmtest:probe", (player, stack, state, pos, base) -> 2.0F);
		assertEquals(before + 1, ChasmBreakSpeed.providerCount(), "同名覆盖，不重复计数");
		ChasmBreakSpeed.registerProvider(null, null);
		assertEquals(before + 1, ChasmBreakSpeed.providerCount(), "null 参数被忽略（绝不 NPE）");
		ChasmBreakSpeed.unregisterProvider("chasmtest:probe");
		assertEquals(before, ChasmBreakSpeed.providerCount(), "注销后还原");
	}

	// ================================================================ ④ 方块经验钩子

	@Test
	void blockXpDeltaIsClampedAndSafe() {
		// 真实 satiating 就是"从掉落经验里抽走一段"（ItemEffectHandler.java:131-134 的 setDroppedExperience(原值 - 抽取)）
		assertEquals(15, ChasmBlockXp.applyDelta(10, 5.0F), "10 + 5 = 15");
		assertEquals(0, ChasmBlockXp.applyDelta(3, -10.0F), "抽多了 -> 0（绝不产生负经验）");
		assertEquals(10, ChasmBlockXp.applyDelta(10, Float.NaN), "NaN 增量 -> 原值");
		assertEquals(0, ChasmBlockXp.applyDelta(-5, 0.0F), "负输入 -> 0");
		assertEquals(Integer.MAX_VALUE, ChasmBlockXp.applyDelta(Integer.MAX_VALUE, 100.0F), "上溢钳到 MAX_VALUE");
		assertEquals(0, ChasmBlockXp.pendingCount(), "初始没有待匹配记录（有界表）");
	}
}
