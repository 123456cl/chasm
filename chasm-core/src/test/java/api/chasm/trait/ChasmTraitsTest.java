package api.chasm.trait;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * P0 纯 JVM 单测：{@link ChasmTraits} 特质全局注册表。
 *
 * <p>覆盖：USE/ATTACK/INVENTORY_TICK 三类注册与 O(1) 查询、跨事件类型隔离、
 * 未注册 id 返回 null、同 id 重复注册覆盖。</p>
 */
class ChasmTraitsTest {

	@Test
	void registerUseAndLookup() {
		UseTrait trait = ctx -> InteractionResult.SUCCESS;
		ResourceLocation id = ChasmTraits.INSTANCE.registerUse("mymod", "fire_use", trait);
		assertEquals(ResourceLocation.fromNamespaceAndPath("mymod", "fire_use"), id);
		assertSame(trait, ChasmTraits.INSTANCE.get(ItemTraitEvent.USE, id));
	}

	@Test
	void eventsAreIsolatedByType() {
		UseTrait use = ctx -> InteractionResult.SUCCESS;
		AttackTrait attack = ctx -> InteractionResult.SUCCESS;
		InventoryTickTrait tick = ctx -> {
		};

		ResourceLocation useId = ChasmTraits.INSTANCE.registerUse("mymod", "use_only", use);
		ResourceLocation attackId = ChasmTraits.INSTANCE.registerAttack("mymod", "attack_only", attack);
		ResourceLocation tickId = ChasmTraits.INSTANCE.registerInventoryTick("mymod", "tick_only", tick);

		// 各事件桶持有各自注册的特质
		assertSame(use, ChasmTraits.INSTANCE.get(ItemTraitEvent.USE, useId));
		assertSame(attack, ChasmTraits.INSTANCE.get(ItemTraitEvent.ATTACK, attackId));
		assertSame(tick, ChasmTraits.INSTANCE.get(ItemTraitEvent.INVENTORY_TICK, tickId));
		// 跨事件查询不到（id 不同）
		assertNull(ChasmTraits.INSTANCE.get(ItemTraitEvent.ATTACK, useId));
		assertNull(ChasmTraits.INSTANCE.get(ItemTraitEvent.INVENTORY_TICK, useId));
		assertNull(ChasmTraits.INSTANCE.get(ItemTraitEvent.USE, attackId));
		assertNull(ChasmTraits.INSTANCE.get(ItemTraitEvent.USE, tickId));
	}

	@Test
	void unknownIdReturnsNull() {
		assertNull(ChasmTraits.INSTANCE.get(ItemTraitEvent.USE,
			ResourceLocation.fromNamespaceAndPath("nope", "nope")));
	}

	@Test
	void reRegisterSameIdOverwrites() {
		ResourceLocation id = ChasmTraits.INSTANCE.registerUse("mymod", "overwrite",
			ctx -> InteractionResult.SUCCESS);
		UseTrait second = ctx -> InteractionResult.CONSUME;
		ChasmTraits.INSTANCE.registerUse("mymod", "overwrite", second);
		assertSame(second, ChasmTraits.INSTANCE.get(ItemTraitEvent.USE, id));
	}
}
