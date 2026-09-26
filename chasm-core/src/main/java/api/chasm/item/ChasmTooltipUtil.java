package api.chasm.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

import java.util.List;

/**
 * Chasm 物品工具提示（tooltip）变换工具。
 *
 * <p><b>攻击速度显示修复（第十二步）</b>：原版 1.21 攻击速度的基础值为 4.0，工具提示里渲染的是
 * {@code 实际值 - 4.0} 的<b>修饰符</b>（例如剑的 {@code -2.4}），而非最终数值 {@code 1.6}，
 * 极易让玩家误以为是错误。本工具在物品的 {@code appendHoverText} 中，用「基础值 4.0 + 主手
 * ADD_VALUE 修饰符之和」换算回总攻击速度，并把原版攻击速度行整体替换为易读的
 * {@code 攻击速度: 1.6}。</p>
 *
 * <p>匹配方式不依赖本地化字符串，而是递归比对组件子节点里 Capable 的
 * {@code TranslatableContents#getKey()} 是否等于 {@code attribute.name.generic.attack_speed}
 * 翻译键，因此在英文/中文等任何语言环境下都稳定命中。</p>
 */
public final class ChasmTooltipUtil {

	private ChasmTooltipUtil() {
	}

	/**
	 * 对已由 {@code super.appendHoverText} 填充好的工具提示做 Chasm 化的变换。
	 * 调用时机必须位于 {@code super} 之后（此时攻击速度行已在列表中）。
	 *
	 * @param stack   当前物品栈
	 * @param tooltip 可变的工具提示行列表
	 */
	public static void transform(ItemStack stack, List<Component> tooltip) {
		if (stack.isEmpty()) {
			return;
		}
		replaceAttackSpeedLine(stack, tooltip);
	}

	/** 把原版攻击速度行替换为「总攻击速度」可读行。 */
	private static void replaceAttackSpeedLine(ItemStack stack, List<Component> tooltip) {
		Attribute attackSpeed = Attributes.ATTACK_SPEED.value();
		String attackSpeedKey = attackSpeed.getDescriptionId(); // attribute.name.generic.attack_speed
		float total = totalAttackSpeed(stack);
		Component friendly = Component.translatable("tooltip.chasm.attack_speed_total",
				String.format("%.2f", total))
			.withStyle(ChatFormatting.DARK_GREEN);
		// 先移除所有命中攻击速度的行，再在末尾追加一行易读数值（顺序稳定、不产生重复）
		if (tooltip.removeIf(line -> containsTranslatableKey(line, attackSpeedKey))) {
			tooltip.add(friendly);
		}
	}

	/** 总攻击速度 = 原版基础 4.0 + 主手 ATTACK_SPEED 的 ADD_VALUE 修饰符之和。 */
	private static float totalAttackSpeed(ItemStack stack) {
		float base = (float) Attributes.ATTACK_SPEED.value().getDefaultValue();
		ItemAttributeModifiers modifiers = stack.getOrDefault(
			DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		float sum = 0.0f;
		for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
			if (entry.attribute().value() == Attributes.ATTACK_SPEED.value()
				&& entry.modifier().operation() == AttributeModifier.Operation.ADD_VALUE) {
				sum += entry.modifier().amount();
			}
		}
		return base + sum;
	}

	/**
	 * 递归判断组件（及其子节点、**以及翻译参数**）是否为指定翻译键的文案。
	 *
	 * <p><b>踩过的坑</b>：原来只查 {@code getContents()} 与 {@code getSiblings()}，于是**从来匹配不上** ——
	 * 1.21 的属性行结构是"外层 {@code literal} + 内层 {@code translatable("attribute.modifier.…")}，
	 * 属性名作为**翻译参数**嵌进去"，参数既不是 contents 也不是 sibling，
	 * 结果攻击速度行永远替换不掉，玩家看到的仍是原版那个 {@code -1.11} 修饰符。</p>
	 */
	static boolean containsTranslatableKey(Component component, String key) {
		if (component == null) {
			return false;
		}
		if (component.getContents() instanceof TranslatableContents contents) {
			if (key.equals(contents.getKey())) {
				return true;
			}
			for (Object arg : contents.getArgs()) {
				if (arg instanceof Component nested && containsTranslatableKey(nested, key)) {
					return true;
				}
			}
		}
		for (Component child : component.getSiblings()) {
			if (containsTranslatableKey(child, key)) {
				return true;
			}
		}
		return false;
	}
}