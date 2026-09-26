package api.chasm.model;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外观分层注册表的契约测试（{@link ChasmItemLayers}）。
 *
 * <p>兼容底线在这里被钉死：**没注册的物品 providerOf 必须为 null、layersOf 必须为空表**，
 * 且任何 provider 的坏行为（返回 null / 抛异常）都不能让渲染线程崩。</p>
 */
class ChasmItemLayersTest {

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static ItemLayer layer(String path) {
		return new ItemLayer(ResourceLocation.fromNamespaceAndPath("chasmtest", path), 0xFFFFFFFF);
	}

	@Test
	void unregisteredItemHasNoProviderAndNoLayers() {
		// 没注册过的物品：providerOf = null（契约），layersOf = 空表（契约）
		assertNull(ChasmItemLayers.providerOf(Items.DIAMOND_PICKAXE), "未注册物品必须返回 null");
		assertFalse(ChasmItemLayers.hasProvider(Items.DIAMOND_PICKAXE));
		assertTrue(ChasmItemLayers.layersOf(new ItemStack(Items.DIAMOND_PICKAXE)).isEmpty(),
			"未注册物品必须得到空表 → 模型子系统回落到原来那张平面贴图");
	}

	@Test
	void registerThenQueryReturnsSameProvider() {
		ItemLayerProvider provider = stack -> List.of(layer("a"), layer("b"));
		ChasmItemLayers.register(Items.DIAMOND_SWORD, provider);
		assertSame(provider, ChasmItemLayers.providerOf(Items.DIAMOND_SWORD));
		assertTrue(ChasmItemLayers.hasProvider(Items.DIAMOND_SWORD));
		assertEquals(2, ChasmItemLayers.layersOf(new ItemStack(Items.DIAMOND_SWORD)).size());
	}

	@Test
	void reRegisterKeepsTheLastOne() {
		ItemLayerProvider first = stack -> List.of(layer("first"));
		ItemLayerProvider second = stack -> List.of(layer("second"));
		ChasmItemLayers.register(Items.GOLDEN_SWORD, first);
		ChasmItemLayers.register(Items.GOLDEN_SWORD, second);
		assertSame(second, ChasmItemLayers.providerOf(Items.GOLDEN_SWORD), "重复注册以最后一次为准");
	}

	@Test
	void nullArgumentsAreIgnored() {
		ChasmItemLayers.register(null, stack -> List.of());
		ChasmItemLayers.register(Items.NETHERITE_SWORD, null);
		assertNull(ChasmItemLayers.providerOf(null), "查 null 物品必须返回 null 而不是 NPE");
		assertNull(ChasmItemLayers.providerOf(Items.NETHERITE_SWORD));
	}

	@Test
	void nullOrEmptyStackIsEmptyAndNeverCallsProvider() {
		List<ItemStack> seen = new ArrayList<>();
		ChasmItemLayers.register(Items.IRON_SWORD, stack -> {
			seen.add(stack);
			return List.of(layer("never"));
		});
		assertTrue(ChasmItemLayers.layersOf(null).isEmpty(), "null 栈 → 空表，不能 NPE");
		assertTrue(ChasmItemLayers.layersOf(ItemStack.EMPTY).isEmpty(), "空栈 → 空表");
		assertTrue(seen.isEmpty(), "空栈不该惊动 provider");
	}

	@Test
	void providerReturningNullBecomesEmptyList() {
		ChasmItemLayers.register(Items.STONE_SWORD, stack -> null);
		// 契约：layersOf 永不返回 null
		assertNotNull(ChasmItemLayers.layersOf(new ItemStack(Items.STONE_SWORD)));
		assertTrue(ChasmItemLayers.layersOf(new ItemStack(Items.STONE_SWORD)).isEmpty());
	}

	@Test
	void providerThrowingIsSwallowed() {
		ChasmItemLayers.register(Items.WOODEN_SWORD, stack -> {
			throw new IllegalStateException("模块数据坏了");
		});
		assertTrue(ChasmItemLayers.layersOf(new ItemStack(Items.WOODEN_SWORD)).isEmpty(),
			"provider 抛 RuntimeException → 空表（渲染线程上抛异常会崩客户端）");
		// 注意分工：注册表这一层只兜 RuntimeException；
		// Error（NoSuchMethodError 之类）由渲染侧的缓存层兜（ChasmItemLayerCacheTest#providerThrowingDegradesToNoLayers），
		// 直接调注册表 API 的调用方仍需自己保证不抛 Error。
	}

	@Test
	void sizeCountsRegisteredItems() {
		int before = ChasmItemLayers.size();
		ChasmItemLayers.register(Items.SHEARS, stack -> List.of(layer("shears")));
		assertEquals(before + 1, ChasmItemLayers.size());
		ChasmItemLayers.register(Items.SHEARS, stack -> List.of(layer("shears2")));
		assertEquals(before + 1, ChasmItemLayers.size(), "同一物品重复注册不该增加条数");
	}

	@Test
	void itemLayerFactoryDefaultsToNoTint() {
		ItemLayer plain = ItemLayer.of(ResourceLocation.fromNamespaceAndPath("chasmtest", "plain"));
		assertEquals(0xFFFFFFFF, plain.tint(), "默认不染色（真实 Tetra：ModularOverrideList.java:130 的 0xffffffff）");
	}
}
