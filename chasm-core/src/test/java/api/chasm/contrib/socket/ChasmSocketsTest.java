package api.chasm.contrib.socket;

import api.chasm.contrib.ChasmContributors;
import api.chasm.contrib.ItemContributions;
import api.chasm.contrib.ItemContributor;
import api.chasm.item.AttributeDeclaration;
import api.chasm.item.event.ChasmItemEventKeys;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 镶嵌：宝石=贡献者，插入挂载/拔出无残留/满槽拒绝/容量语义。 */
class ChasmSocketsTest {

	private static final ResourceLocation RUBY_ID = ResourceLocation.fromNamespaceAndPath("chasmtest", "gem/ruby");
	private static final ResourceLocation RUBY_MOD = ResourceLocation.fromNamespaceAndPath("chasmtest", "ruby_hp");
	private static final ResourceLocation SAPPHIRE_ID = ResourceLocation.fromNamespaceAndPath("chasmtest", "gem/sapphire");
	private static final ResourceLocation SAPPHIRE_MOD = ResourceLocation.fromNamespaceAndPath("chasmtest", "sapphire_atk");
	private static final ResourceLocation SAPPHIRE_HANDLER =
		ResourceLocation.fromNamespaceAndPath("chasmtest", "sapphire_hit");

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		ChasmContributors.register(new ItemContributor() {
			@Override public ResourceLocation id() { return RUBY_ID; }
			@Override public void contributeAttributes(AttributeSink sink) {
				sink.add(AttributeDeclaration.mainHand(Attributes.MAX_HEALTH.value(), RUBY_MOD, 2.0F));
			}
		});
		ChasmContributors.register(new ItemContributor() {
			@Override public ResourceLocation id() { return SAPPHIRE_ID; }
			@Override public void contributeAttributes(AttributeSink sink) {
				sink.add(AttributeDeclaration.mainHand(Attributes.ATTACK_DAMAGE.value(), SAPPHIRE_MOD, 1.5F));
			}
			@Override public void contributeHandlers(HandlerSink sink) {
				sink.add(ChasmItemEventKeys.ON_HIT, SAPPHIRE_HANDLER);
			}
		});
		ChasmGems.register(Items.EMERALD, RUBY_ID);
		ChasmGems.register(Items.DIAMOND, SAPPHIRE_ID);
	}

	private static boolean hasModifier(ItemStack stack, ResourceLocation id) {
		ItemAttributeModifiers mods = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		return mods.modifiers().stream().anyMatch(m -> m.modifier().id().equals(id));
	}

	@Test
	void socketAttachesGemContributions() {
		ItemStack gear = new ItemStack(Items.STICK);
		ChasmSockets.setCapacity(gear, 2);
		assertEquals(2, ChasmSockets.freeSlots(gear));

		assertTrue(ChasmSockets.socket(gear, new ItemStack(Items.EMERALD)));
		assertTrue(ChasmSockets.socket(gear, new ItemStack(Items.DIAMOND)));
		assertEquals(0, ChasmSockets.freeSlots(gear), "容量 2 已插满");
		assertEquals(2, ChasmSockets.gems(gear).size());

		assertTrue(ItemContributions.sources(gear).contains(RUBY_ID), "宝石应挂上其贡献者");
		assertTrue(ItemContributions.sources(gear).contains(SAPPHIRE_ID));
		assertTrue(hasModifier(gear, RUBY_MOD), "红宝石属性应物化");
		assertTrue(hasModifier(gear, SAPPHIRE_MOD), "蓝宝石属性应物化");
		assertTrue(ChasmItemEventKeys.ON_HIT != null);
		assertTrue(!api.chasm.item.event.ChasmItemEvents.attached(gear, ChasmItemEventKeys.ON_HIT).isEmpty(),
			"蓝宝石的事件处理器应进入 on_hit 桶");
		assertFalse(ChasmSockets.socket(gear, new ItemStack(Items.EMERALD, 3)), "满槽应拒绝（第三颗）");
	}

	@Test
	void unsocketRemovesContributionsWithoutResidue() {
		ItemStack gear = new ItemStack(Items.STICK);
		ChasmSockets.setCapacity(gear, 1);
		assertTrue(ChasmSockets.socket(gear, new ItemStack(Items.DIAMOND)));
		ItemStack removed = ChasmSockets.unsocket(gear, 0);
		assertEquals(Items.DIAMOND, removed.getItem(), "应取回原宝石");
		assertTrue(ItemContributions.sources(gear).isEmpty(), "贡献者应被摘除");
		assertFalse(hasModifier(gear, SAPPHIRE_MOD), "属性不应残留");
		assertTrue(api.chasm.item.event.ChasmItemEvents.attached(gear, ChasmItemEventKeys.ON_HIT).isEmpty(),
			"事件桶应清空");
		assertTrue(ChasmSockets.gems(gear).isEmpty());
	}

	@Test
	void capacityZeroDisablesAndClears() {
		ItemStack gear = new ItemStack(Items.STICK);
		ChasmSockets.setCapacity(gear, 1);
		ChasmSockets.socket(gear, new ItemStack(Items.EMERALD));
		ChasmSockets.setCapacity(gear, 0);
		assertEquals(0, ChasmSockets.capacity(gear));
		assertTrue(ChasmSockets.gems(gear).isEmpty(), "取消插槽应清空宝石");
		assertFalse(hasModifier(gear, RUBY_MOD), "取消插槽后属性不应残留");
		assertFalse(ChasmSockets.socket(gear, new ItemStack(Items.EMERALD)), "无插槽不可镶嵌");
	}

	@Test
	void socketComponentIsContentComparable() {
		// 修复前 chasm:socket_gems 是 List<ItemStack>，而 ItemStack 没有 equals/hashCode，
		// 于是"内容相同的两个组件"判定为不相等 → NBT 往返/网络同步后 ItemStack.matches 认为物品变了。
		net.minecraft.world.item.component.ItemContainerContents a =
			net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND)));
		net.minecraft.world.item.component.ItemContainerContents b =
			net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND)));
		assertEquals(a, b, "内容相同的宝石容器必须相等");
		assertEquals(a.hashCode(), b.hashCode(), "相等容器的哈希也必须一致");

		ItemStack gear = new ItemStack(Items.STICK);
		ChasmSockets.setCapacity(gear, 1);
		ChasmSockets.socket(gear, new ItemStack(Items.COAL));
		assertEquals(net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(new ItemStack(Items.COAL))),
			gear.get(api.chasm.data.ChasmData.SOCKET_GEMS), "栈上组件应与等价容器内容相等");
	}

	@Test
	void unknownGemStillOccupiesSlotWithoutCrash() {
		ItemStack gear = new ItemStack(Items.STICK);
		ChasmSockets.setCapacity(gear, 1);
		assertTrue(ChasmSockets.socket(gear, new ItemStack(Items.COAL)), "未登记宝石仅无加成，仍可占槽");
		assertEquals(1, ChasmSockets.gems(gear).size());
		assertTrue(ItemContributions.sources(gear).isEmpty());
	}
}
