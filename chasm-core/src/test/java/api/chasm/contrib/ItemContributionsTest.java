package api.chasm.contrib;

import api.chasm.data.ChasmData;
import api.chasm.item.AttributeDeclaration;
import api.chasm.item.event.ChasmItemEventKeys;
import api.chasm.item.event.ChasmItemEvents;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 贡献管线：真源 → 置脏 → 编译快照（事件分桶 + 属性物化 + 无残留 + 幂等）。 */
class ItemContributionsTest {

	private static final ResourceLocation CONTRIBUTOR_ID =
		ResourceLocation.fromNamespaceAndPath("chasmtest", "affix/lifesteal");
	private static final ResourceLocation HANDLER_ID =
		ResourceLocation.fromNamespaceAndPath("chasmtest", "lifesteal_handler");
	private static final ResourceLocation MODIFIER_ID =
		ResourceLocation.fromNamespaceAndPath("chasmtest", "lifesteal_hp");

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		ChasmContributors.register(new ItemContributor() {
			@Override
			public ResourceLocation id() {
				return CONTRIBUTOR_ID;
			}

			@Override
			public void contributeHandlers(HandlerSink sink) {
				sink.add(ChasmItemEventKeys.ON_HIT, HANDLER_ID);
			}

			@Override
			public void contributeAttributes(AttributeSink sink) {
				sink.add(AttributeDeclaration.mainHand(Attributes.MAX_HEALTH.value(), MODIFIER_ID, 4.0F));
			}
		});
	}

	private static ItemStack freshStack() {
		return new ItemStack(Items.STICK);
	}

	private static boolean hasModifier(ItemStack stack, ResourceLocation id) {
		ItemAttributeModifiers mods = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		return mods.modifiers().stream().anyMatch(m -> m.modifier().id().equals(id));
	}

	@Test
	void attachRebuildsBucketsAndAttributes() {
		ItemStack stack = freshStack();
		assertTrue(ItemContributions.attach(stack, CONTRIBUTOR_ID), "首次挂载应生效");
		assertFalse(ItemContributions.attach(stack, CONTRIBUTOR_ID), "重复挂载应幂等");
		assertTrue(ItemContributions.isDirty(stack), "挂载后应置脏等待编译");
		assertTrue(ChasmItemEvents.attached(stack, ChasmItemEventKeys.ON_HIT).isEmpty(), "编译前不应有桶");

		ItemContributions.flush(stack);
		assertFalse(ItemContributions.isDirty(stack), "编译后应清脏");
		assertEquals(1, ItemContributions.revision(stack));
		assertTrue(ChasmItemEvents.attached(stack, ChasmItemEventKeys.ON_HIT).contains(HANDLER_ID),
			"事件应进入 on_hit 桶");
		assertTrue(hasModifier(stack, MODIFIER_ID), "属性应物化进 ATTRIBUTE_MODIFIERS");
		assertEquals(List.of(CONTRIBUTOR_ID), ItemContributions.sources(stack));
	}

	@Test
	void detachClearsBucketAndLeavesNoAttributeResidue() {
		ItemStack stack = freshStack();
		ItemContributions.attach(stack, CONTRIBUTOR_ID);
		ItemContributions.rebuild(stack);
		ItemContributions.detach(stack, CONTRIBUTOR_ID);
		ItemContributions.rebuild(stack);
		assertTrue(ChasmItemEvents.attached(stack, ChasmItemEventKeys.ON_HIT).isEmpty(), "桶应清空");
		assertFalse(hasModifier(stack, MODIFIER_ID), "来源移除后不应残留属性修饰符");
		assertTrue(ItemContributions.sources(stack).isEmpty());
	}

	@Test
	void rebuildIsIdempotentAndPreservesDefaultBuckets() {
		ItemStack stack = freshStack();
		// 模拟 ItemBuilder.eventHandler 写入的"物品默认处理器"
		stack.set(ChasmData.EVENT_HANDLERS, Map.of(
			ChasmItemEventKeys.ON_HIT.id(), List.of(HANDLER_ID)));
		ItemContributions.rebuild(stack);
		ItemContributions.rebuild(stack);
		assertEquals(2, ItemContributions.revision(stack), "每次编译版本 +1");
		assertTrue(ChasmItemEvents.attached(stack, ChasmItemEventKeys.ON_HIT).contains(HANDLER_ID),
			"物品默认处理器在重编译后必须保留");
		assertFalse(hasModifier(stack, MODIFIER_ID), "没有来源时不应凭空产生属性");
	}

	@Test
	void pipelineDoesNotWipeItemOwnModifiersInSameNamespace() {
		// 真实场景：宝石贡献者 mymod:gem/ruby 与物品 mymod:greed_sword 同命名空间，
		// 按命名空间清空会删掉剑自己的攻击力修饰符 —— 必须按精确 id 记账。
		ItemStack stack = freshStack();
		ResourceLocation own = ResourceLocation.fromNamespaceAndPath("chasmtest", "item_own_attack");
		ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();
		builder.add(api.chasm.item.ChasmItemModifiers.holder(
				net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE.value()),
			new net.minecraft.world.entity.ai.attributes.AttributeModifier(own, 5.0,
				net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE),
			net.minecraft.world.entity.EquipmentSlotGroup.MAINHAND);
		stack.set(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());

		ItemContributions.attach(stack, CONTRIBUTOR_ID);
		ItemContributions.rebuild(stack);
		assertTrue(hasModifier(stack, MODIFIER_ID), "贡献者属性应物化");
		assertTrue(hasModifier(stack, own), "物品自带的同命名空间修饰符不得被删除");
		assertFalse(api.chasm.item.ChasmItemModifiers.hasUnregisteredHolder(stack),
			"不得写出无法序列化的 direct Holder（会崩存档）");

		ItemContributions.detach(stack, CONTRIBUTOR_ID);
		ItemContributions.rebuild(stack);
		assertFalse(hasModifier(stack, MODIFIER_ID), "移除来源后贡献者属性应消失");
		assertTrue(hasModifier(stack, own), "移除来源不得误删物品自身属性");
	}

	@Test
	void unknownSourceIsSkippedSafely() {
		ItemStack stack = freshStack();
		ItemContributions.attach(stack, ResourceLocation.fromNamespaceAndPath("chasmtest", "ghost"));
		ItemContributions.rebuild(stack);
		assertTrue(ChasmItemEvents.attached(stack, ChasmItemEventKeys.ON_HIT).isEmpty());
		assertEquals(1, ItemContributions.revision(stack), "坏来源不应中断编译");
	}
}
