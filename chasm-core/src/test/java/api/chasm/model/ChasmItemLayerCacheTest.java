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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记忆化缓存的回归测试（{@link ChasmItemLayerCache}）。
 *
 * <p>要钉死的两件事：</p>
 * <ol>
 *   <li><b>同一栈反复渲染不能每次重建</b>：provider 只该被问一次（真实 Tetra 也用缓存兜这个，
 *       {@code ModularOverrideList.java:45-48}）。</li>
 *   <li><b>数据组件一变就要重算</b>：换模块/换材料/掉耐久都会改数据组件，
 *       缓存键是组件内容哈希，所以自动失效。</li>
 * </ol>
 */
class ChasmItemLayerCacheTest {

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static ItemLayer layer(String path) {
		return new ItemLayer(ResourceLocation.fromNamespaceAndPath("chasmtest", path), 0xFFFFFFFF);
	}

	@Test
	void sameStackIsEvaluatedOnlyOnce() {
		AtomicInteger calls = new AtomicInteger();
		ChasmItemLayers.register(Items.IRON_PICKAXE, stack -> {
			calls.incrementAndGet();
			return List.of(layer("blade"), layer("hilt"));
		});
		ChasmItemLayerCache cache = new ChasmItemLayerCache(Items.IRON_PICKAXE);
		ItemStack stack = new ItemStack(Items.IRON_PICKAXE);

		List<ItemLayer> first = cache.layersOf(stack);
		List<ItemLayer> second = cache.layersOf(stack);
		List<ItemLayer> third = cache.layersOf(stack);

		assertEquals(2, first.size());
		assertSame(first, second, "同一内容必须命中同一张表（O(1) 查表）");
		assertSame(first, third);
		assertEquals(1, calls.get(), "provider 只该被问一次：每帧重算 = 卡顿");
		assertEquals(1, cache.size());
	}

	@Test
	void componentChangeInvalidatesTheEntry() {
		AtomicInteger calls = new AtomicInteger();
		ChasmItemLayers.register(Items.DIAMOND_AXE, stack -> {
			calls.incrementAndGet();
			return List.of(layer("head"), layer("handle"), layer("binding"));
		});
		ChasmItemLayerCache cache = new ChasmItemLayerCache(Items.DIAMOND_AXE);
		ItemStack stack = new ItemStack(Items.DIAMOND_AXE);

		List<ItemLayer> before = cache.layersOf(stack);
		assertEquals(1, calls.get());

		// 换材料 = 改数据组件（模块化物品的真实做法：把模块写进组件）
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("换了个材料"));
		List<ItemLayer> after = cache.layersOf(stack);

		assertEquals(2, calls.get(), "组件内容变了必须重算（不能拿着旧外观不放）");
		assertNotSame(before, after);
		assertEquals(2, cache.size(), "旧条目可以留着（键不同），但绝不能命中错的那条");
	}

	@Test
	void epochInvalidationClearsEverything() {
		AtomicInteger calls = new AtomicInteger();
		ChasmItemLayers.register(Items.GOLDEN_AXE, stack -> {
			calls.incrementAndGet();
			return List.of(layer("gold"));
		});
		ChasmItemLayerCache cache = new ChasmItemLayerCache(Items.GOLDEN_AXE);
		ItemStack stack = new ItemStack(Items.GOLDEN_AXE);

		cache.layersOf(stack);
		assertEquals(1, calls.get());

		ChasmItemLayerCache.invalidateAll(); // 等价于 ChasmItemLayerModels.invalidateCaches()
		cache.layersOf(stack);
		assertEquals(2, calls.get(), "脏标记 +1 后下一次查表必须整表清空重算");
	}

	@Test
	void unregisteredItemNeverTouchesAProvider() {
		ChasmItemLayerCache cache = new ChasmItemLayerCache(Items.ELYTRA);
		assertTrue(cache.layersOf(new ItemStack(Items.ELYTRA)).isEmpty());
		// 空结果也记忆化（1 条）：未注册 provider 的栈每帧都要查一次外观，
		// 记住"没有图层"才不会每帧白跑一趟 provider 查表
		assertEquals(1, cache.size());
		assertTrue(cache.layersOf(new ItemStack(Items.ELYTRA)).isEmpty());
		assertEquals(1, cache.size(), "重复查询不该新增条目");
	}

	@Test
	void nullAndEmptyStacksAreSafe() {
		ChasmItemLayerCache cache = new ChasmItemLayerCache(Items.SHIELD);
		assertTrue(cache.layersOf(null).isEmpty());
		assertTrue(cache.layersOf(ItemStack.EMPTY).isEmpty());
	}

	@Test
	void brokenLayersAreFilteredOut() {
		ChasmItemLayers.register(Items.TRIDENT, stack -> {
			List<ItemLayer> layers = new java.util.ArrayList<>();
			layers.add(layer("ok1"));
			layers.add(null);                                  // 坏层：null 元素
			layers.add(new ItemLayer(null, 0xFFFFFFFF));       // 坏层：null 贴图（渲染线程上取 sprite 会 NPE）
			layers.add(layer("ok2"));
			return layers;
		});
		ChasmItemLayerCache cache = new ChasmItemLayerCache(Items.TRIDENT);
		List<ItemLayer> layers = cache.layersOf(new ItemStack(Items.TRIDENT));

		assertEquals(2, layers.size(), "坏层必须被丢掉，好层顺序不变");
		assertEquals("ok1", layers.get(0).texture().getPath());
		assertEquals("ok2", layers.get(1).texture().getPath());
	}

	@Test
	void providerThrowingDegradesToNoLayers() {
		ChasmItemLayers.register(Items.CROSSBOW, stack -> {
			throw new AssertionError("连 Error 都不许漏出去");
		});
		ChasmItemLayerCache cache = new ChasmItemLayerCache(Items.CROSSBOW);
		assertTrue(cache.layersOf(new ItemStack(Items.CROSSBOW)).isEmpty());
	}

	@Test
	void tableIsBounded() {
		AtomicInteger calls = new AtomicInteger();
		ChasmItemLayers.register(Items.FISHING_ROD, stack -> {
			calls.incrementAndGet();
			return List.of(layer("rod"));
		});
		ChasmItemLayerCache cache = new ChasmItemLayerCache(Items.FISHING_ROD);
		ItemStack stack = new ItemStack(Items.FISHING_ROD);

		for (int i = 0; i < ChasmItemLayerCache.MAX_ENTRIES + 8; i++) {
			stack.set(DataComponents.CUSTOM_NAME, Component.literal("名字" + i));
			cache.layersOf(stack);
		}
		assertTrue(cache.size() <= ChasmItemLayerCache.MAX_ENTRIES,
			"条目数必须有上限（超出整表清空），否则刷名字能撑爆内存");
		assertTrue(calls.get() > ChasmItemLayerCache.MAX_ENTRIES);
	}
}
