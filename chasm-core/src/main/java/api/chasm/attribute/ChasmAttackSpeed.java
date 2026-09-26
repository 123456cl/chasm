package api.chasm.attribute;

import api.chasm.log.ChasmLogger;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

import java.util.ArrayList;
import java.util.List;

/**
 * 攻速安全阀（**修复"武器永久沉底/无法攻击"事故**）。
 *
 * <p><b>事故经过（09-11 实测存档定位）</b>：示例模板用 {@code .attackSpeed(-2.4f)} 传了"原版风格的
 * 修饰符增量"，而 {@code ItemBuilder.attackSpeed(float)} 的语义是"**面板总攻速**"（内部 {@code total - 4.0}），
 * 于是写出修饰符 {@code -6.4}，玩家总攻速 = 4.0 - 6.4 = <b>-2.4</b>。后果不是"攻击慢"，而是：</p>
 * <ol>
 *   <li>{@code Player.getAttackStrengthScale()} 恒为 0（延迟为负）；</li>
 *   <li>原版 {@code ItemInHandRenderer.tick()} 用 {@code f³} 作为手部高度目标 → 手高度永远趋向 0，
 *       <b>手上物品永久沉到视野下方/后面，只有挥剑动画时才露头</b>；</li>
 *   <li>该数值能被存档保存，于是"越用越坏"且重进无效。</li>
 * </ol>
 *
 * <p>本类提供两道防线：<b>构建期</b>拒绝非法总攻速（{@link #requireSaneTotal}），
 * <b>运行时</b>把已存在的坏数据夹取回安全区（{@link #sanitize}），避免"坏存档里的物品永久残废"。</p>
 */
public final class ChasmAttackSpeed {

	/** 允许的最小总攻速：低于它蓄力几乎打不动，且手部渲染会沉底（0 或负数直接禁用攻击）。 */
	public static final float MIN_TOTAL = 0.1F;

	/** 原版攻速基础值（4.0）。 */
	public static final float BASE = (float) Attributes.ATTACK_SPEED.value().getDefaultValue();

	/** 浮点容差：夹取修复后 total 可能因浮点舍入落在 MIN_TOTAL 下方 1e-7 级别，判定需容忍。 */
	private static final float EPSILON = 1.0E-4F;

	private ChasmAttackSpeed() {
	}

	/**
	 * 构建期校验：总攻速必须 &gt; {@link #MIN_TOTAL}。
	 *
	 * @throws IllegalArgumentException 非法时抛出，并在消息里同时给出两种写法的正确形式
	 */
	public static float requireSaneTotal(float total, String where) {
		if (total >= MIN_TOTAL) {
			return total;
		}
		throw new IllegalArgumentException(where + " 的总攻击速度必须 > " + MIN_TOTAL + "，收到 " + total
			+ "。注意 attackSpeed(x) 传的是【面板总攻速】（基础 " + BASE + " + 修饰符），"
			+ "若你想写原版风格的修饰符增量 -2.4（即总攻速 1.6），请写 .attackSpeed(" + (BASE - 2.4F) + "F) "
			+ "或改用 .attribute(Attributes.ATTACK_SPEED.value(), -2.4F, EquipmentSlotGroup.MAINHAND)。"
			+ "（总攻速 ≤ 0 会让攻击蓄力刻度恒为 0，手上物品会永久沉到视野外，属于致命配置。）");
	}

	/** 由修饰符列表算出总攻速（ADD_VALUE 求和后依次乘算乘法运算）。 */
	public static float totalOf(ItemAttributeModifiers modifiers) {
		double add = 0.0;
		double mulBase = 0.0;
		double mulTotal = 1.0;
		if (modifiers != null) {
			for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
				if (entry.attribute().value() != Attributes.ATTACK_SPEED.value()) {
					continue;
				}
				AttributeModifier modifier = entry.modifier();
				switch (modifier.operation()) {
					case ADD_VALUE -> add += modifier.amount();
					case ADD_MULTIPLIED_BASE -> mulBase += modifier.amount();
					case ADD_MULTIPLIED_TOTAL -> mulTotal *= 1.0 + modifier.amount();
				}
			}
		}
		return (float) ((BASE + add) * (1.0 + mulBase) * mulTotal);
	}

	/**
	 * 运行时救援：若该栈的总攻速 ≤ {@link #MIN_TOTAL}，夹取到安全值并记 WARN。
	 *
	 * <p>用于修好"已经写进存档的坏物品"（重进世界也依然会沉底的那种）。夹取方式是缩小 ADD_VALUE
	 * 修饰符的绝对值，保持其它属性与来源不变。</p>
	 *
	 * @return 是否发生了修复
	 */
	public static boolean sanitize(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		ItemAttributeModifiers current = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		float total = totalOf(current);
		if (total >= MIN_TOTAL - EPSILON) {
			return false;
		}
		List<ItemAttributeModifiers.Entry> entries = new ArrayList<>(current.modifiers());
		boolean touched = false;
		for (int i = 0; i < entries.size(); i++) {
			ItemAttributeModifiers.Entry entry = entries.get(i);
			if (entry.attribute().value() != Attributes.ATTACK_SPEED.value()
				|| entry.modifier().operation() != AttributeModifier.Operation.ADD_VALUE) {
				continue;
			}
			// 把该条 ADD_VALUE 夹取到"使总攻速恰好为 MIN_TOTAL"的数值
			float others = 0.0F;
			for (ItemAttributeModifiers.Entry other : entries) {
				if (other != entry && other.attribute().value() == Attributes.ATTACK_SPEED.value()
					&& other.modifier().operation() == AttributeModifier.Operation.ADD_VALUE) {
					others += other.modifier().amount();
				}
			}
			float fixed = MIN_TOTAL - BASE - others;
			entries.set(i, new ItemAttributeModifiers.Entry(entry.attribute(),
				new AttributeModifier(entry.modifier().id(), fixed, entry.modifier().operation()),
				entry.slot()));
			touched = true;
			break;
		}
		if (!touched) {
			return false;
		}
		ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();
		for (ItemAttributeModifiers.Entry entry : entries) {
			builder.add(entry.attribute(), entry.modifier(), entry.slot());
		}
		stack.set(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());
		ChasmLogger.warn("chasm", "物品 {} 的总攻速 {} ≤ {}（会导致攻击无法蓄力、手上物品永久沉底），"
			+ "已夹取修复为 {}。请在物品声明处修正 attackSpeed 的总值语义。",
			stack.getItem(), total, MIN_TOTAL,
			totalOf(stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)));
		return true;
	}

	/** 便捷：对一组修饰符条目做安全检查（供构建期调用，返回是否安全）。 */
	public static boolean isSane(ItemAttributeModifiers modifiers) {
		return totalOf(modifiers) >= MIN_TOTAL - EPSILON;
	}

	/** 供文档/调试：主手槽位常量。 */
	public static final EquipmentSlotGroup MAIN_HAND = EquipmentSlotGroup.MAINHAND;
}
