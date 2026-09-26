package api.chasm.attribute;

import api.chasm.Chasm;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 攻速安全阀回归测试（09-11 事故）：总攻速 ≤ 0 必须被构建期拒绝、运行时夹取修复。
 *
 * <p>事故：{@code .attackSpeed(-2.4f)} 被当成【面板总攻速】→ 修饰符 -6.4 → 总攻速 -2.4 →
 * 蓄力刻度恒 0 → 手上物品永久沉在视野外（只有挥剑露头）。</p>
 */
class ChasmAttackSpeedTest {

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static ItemStack withAttackSpeedModifier(double amount) {
		ItemStack stack = new ItemStack(Items.STICK);
		ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();
		builder.add(api.chasm.item.ChasmItemModifiers.holder(Attributes.ATTACK_SPEED.value()),
			new AttributeModifier(ResourceLocation.fromNamespaceAndPath("test", "speed"), amount,
				AttributeModifier.Operation.ADD_VALUE),
			EquipmentSlotGroup.MAINHAND);
		stack.set(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());
		return stack;
	}

	@Test
	void builderRejectsNonPositiveTotalWithActionableMessage() {
		// 事故写法：把"原版风格的修饰符增量"传进"总攻速"参数
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
			() -> Chasm.item().attackSpeed(-2.4f));
		assertTrue(e.getMessage().contains("总攻击速度"), "错误信息应说明是总攻速语义");
		assertTrue(e.getMessage().contains("1.6"), "应给出正确写法（4.0 - 2.4 = 1.6）");
		assertTrue(e.getMessage().contains("attribute("), "应给出更底层的替代写法");

		assertThrows(IllegalArgumentException.class, () -> Chasm.item().attackSpeed(0.0f));
		assertThrows(IllegalArgumentException.class,
			() -> Chasm.templates().weapon("test", "w").attackSpeed(-2.4f));
	}

	@Test
	void builderAcceptsSaneTotal() {
		assertEquals(1.6f, ChasmAttackSpeed.requireSaneTotal(1.6f, "t"), 0.0001f);
		Chasm.item().attackSpeed(1.6f); // 不得抛错
	}

	@Test
	void totalOfComputesBasePlusModifiers() {
		assertEquals(4.0f, ChasmAttackSpeed.totalOf(ItemAttributeModifiers.EMPTY), 0.0001f);
		assertEquals(-2.4f, ChasmAttackSpeed.totalOf(
			withAttackSpeedModifier(-6.4).get(DataComponents.ATTRIBUTE_MODIFIERS)), 0.0001f);
		assertEquals(1.6f, ChasmAttackSpeed.totalOf(
			withAttackSpeedModifier(-2.4).get(DataComponents.ATTRIBUTE_MODIFIERS)), 0.0001f);
	}

	@Test
	void sanitizeRepairsAlreadySavedBrokenItem() {
		ItemStack broken = withAttackSpeedModifier(-6.4);
		assertEquals(-2.4f, ChasmAttackSpeed.totalOf(broken.get(DataComponents.ATTRIBUTE_MODIFIERS)), 0.0001f);
		assertFalse(ChasmAttackSpeed.isSane(broken.get(DataComponents.ATTRIBUTE_MODIFIERS)));

		assertTrue(ChasmAttackSpeed.sanitize(broken), "坏物品应被修复");
		assertTrue(ChasmAttackSpeed.isSane(broken.get(DataComponents.ATTRIBUTE_MODIFIERS)),
			"修复后总攻速必须回到安全区");
		assertEquals(ChasmAttackSpeed.MIN_TOTAL,
			ChasmAttackSpeed.totalOf(broken.get(DataComponents.ATTRIBUTE_MODIFIERS)), 0.0001f);
	}

	@Test
	void sanitizeLeavesHealthyItemsUntouched() {
		ItemStack healthy = withAttackSpeedModifier(-2.4);
		ItemAttributeModifiers before = healthy.get(DataComponents.ATTRIBUTE_MODIFIERS);
		assertFalse(ChasmAttackSpeed.sanitize(healthy), "正常物品不应被改动");
		assertEquals(before, healthy.get(DataComponents.ATTRIBUTE_MODIFIERS));
	}

	@Test
	void pipelineWritesAreSanitizedAtRuntime() {
		// 外部来源（词缀/宝石）写出致命攻速时，写后安全阀必须把它夹回安全区
		ItemStack stack = withAttackSpeedModifier(-6.4);
		api.chasm.item.ChasmItemModifiers.add(stack, java.util.List.of(
			api.chasm.item.AttributeDeclaration.mainHand(
				Attributes.MAX_HEALTH.value(), ResourceLocation.fromNamespaceAndPath("test", "hp"), 4.0F)));
		assertTrue(ChasmAttackSpeed.isSane(stack.get(DataComponents.ATTRIBUTE_MODIFIERS)),
			"经管线写入后也必须安全");
	}
}
