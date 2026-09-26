package api.chasm.item;

import api.chasm.data.ChasmData;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归测试：属性修饰符写出必须是**注册表 Holder**。
 *
 * <p>背景：真实存档曾在保存玩家时崩溃——
 * {@code IllegalStateException: Unregistered holder in ResourceKey[...attribute]: Direct{...}}
 * 由 {@code Holder.direct(...)} 写进 ATTRIBUTE_MODIFIERS 引起（direct Holder 无 ResourceKey，
 * NBT/网络都无法编码）。本测试锁定该不变量。</p>
 */
class ChasmItemModifiersTest {

	private static final ResourceLocation OWN = ResourceLocation.fromNamespaceAndPath("chasmtest", "own_mod");
	private static final ResourceLocation FOREIGN = ResourceLocation.fromNamespaceAndPath("chasmtest", "foreign_mod");

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static ItemStack stackWithForeignModifier() {
		ItemStack stack = new ItemStack(Items.STICK);
		ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();
		builder.add(ChasmItemModifiers.holder(Attributes.ATTACK_DAMAGE.value()),
			new AttributeModifier(FOREIGN, 3.0, AttributeModifier.Operation.ADD_VALUE),
			EquipmentSlotGroup.MAINHAND);
		stack.set(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());
		return stack;
	}

	private static boolean has(ItemStack stack, ResourceLocation id) {
		return stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
			.modifiers().stream().anyMatch(m -> m.modifier().id().equals(id));
	}

	@Test
	void directHolderWouldBreakSaving() {
		// 记录"错误写法"的后果：direct Holder 没有 ResourceKey → 存读档/网络同步会失败
		ItemStack stack = new ItemStack(Items.STICK);
		ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();
		builder.add(Holder.direct(Attributes.MAX_HEALTH.value()),
			new AttributeModifier(OWN, 4.0, AttributeModifier.Operation.ADD_VALUE),
			EquipmentSlotGroup.MAINHAND);
		stack.set(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());
		assertTrue(ChasmItemModifiers.hasUnregisteredHolder(stack), "direct Holder 正是崩溃原因");
		ItemAttributeModifiers mods = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
		assertTrue(mods.modifiers().get(0).attribute().unwrapKey().isEmpty());
	}

	@Test
	void apiNeverWritesDirectHolders() {
		ItemStack stack = new ItemStack(Items.STICK);
		ChasmItemModifiers.add(stack, List.of(
			AttributeDeclaration.mainHand(Attributes.MAX_HEALTH.value(), OWN, 4.0F)));
		assertTrue(has(stack, OWN));
		assertFalse(ChasmItemModifiers.hasUnregisteredHolder(stack),
			"经 API 写入的修饰符必须全部是注册表 Holder（否则崩存档）");
		assertTrue(stack.get(DataComponents.ATTRIBUTE_MODIFIERS).modifiers().stream()
			.allMatch(m -> m.attribute().unwrapKey().isPresent()));
	}

	@Test
	void unregisteredAttributeIsSkippedNotWrittenAsDirectHolder() {
		Attribute unregistered = new RangedAttribute("chasmtest.unregistered", 1.0, 0.0, 100.0);
		ItemStack stack = new ItemStack(Items.STICK);
		ChasmItemModifiers.add(stack, List.of(
			AttributeDeclaration.mainHand(unregistered, OWN, 5.0F)));
		assertFalse(has(stack, OWN), "未注册属性应被跳过");
		assertFalse(ChasmItemModifiers.hasUnregisteredHolder(stack), "更不允许退化成 direct Holder");
	}

	@Test
	void removeModifiersOnlyRemovesOwnedIds() {
		ItemStack stack = stackWithForeignModifier();
		ChasmItemModifiers.add(stack, List.of(
			AttributeDeclaration.mainHand(Attributes.MAX_HEALTH.value(), OWN, 4.0F)));
		assertTrue(has(stack, OWN));
		assertTrue(has(stack, FOREIGN));

		assertTrue(ChasmItemModifiers.removeModifiers(stack, List.of(OWN)));
		assertFalse(has(stack, OWN), "自己的修饰符应被移除");
		assertTrue(has(stack, FOREIGN), "他人/物品自带的修饰符必须保留");
		assertFalse(ChasmItemModifiers.removeModifiers(stack, List.of(ResourceLocation.fromNamespaceAndPath("chasmtest", "nope"))));
	}

	@Test
	void sameNamespaceForeignModifierSurvivesPipelineStyleRewrite() {
		// 旧实现按"命名空间"清空，会连 chasmtest:foreign_mod 一起删掉；精确记账不应发生
		ItemStack stack = stackWithForeignModifier();
		ChasmItemModifiers.add(stack, List.of(
			AttributeDeclaration.mainHand(Attributes.MAX_HEALTH.value(), OWN, 4.0F)));
		// 模拟"来源移除"：只摘管线自己写过的 id
		ChasmItemModifiers.removeModifiers(stack, List.of(OWN));
		assertTrue(has(stack, FOREIGN), "同命名空间的外来修饰符不得被误删");
		assertEquals(1, stack.get(DataComponents.ATTRIBUTE_MODIFIERS).modifiers().size());
	}

	@Test
	void addingSameIdTwiceDoesNotDuplicate() {
		ItemStack stack = new ItemStack(Items.STICK);
		ChasmItemModifiers.add(stack, List.of(AttributeDeclaration.mainHand(Attributes.MAX_HEALTH.value(), OWN, 4.0F)));
		ChasmItemModifiers.add(stack, List.of(AttributeDeclaration.mainHand(Attributes.MAX_HEALTH.value(), OWN, 6.0F)));
		assertEquals(1, stack.get(DataComponents.ATTRIBUTE_MODIFIERS).modifiers().size(), "同 id 应替换而非叠加");
		assertEquals(6.0, stack.get(DataComponents.ATTRIBUTE_MODIFIERS).modifiers().get(0).modifier().amount());
	}
}
