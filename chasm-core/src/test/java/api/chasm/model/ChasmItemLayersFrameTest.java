package api.chasm.model;

import net.minecraft.SharedConstants;
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
 * **可选动画帧参数的契约测试**（{@link ItemLayerProvider#frameOf} /
 * {@link ItemLayerProvider#layersOf(ItemStack, String)} / {@link ChasmItemLayerCache} 的帧键）。
 *
 * <p>钉死三件事：</p>
 * <ol>
 *   <li><b>兼容底线</b>：只实现无帧 {@code layersOf(stack)} 的 provider（lambda）在任何帧下都走默认方法，
 *       结果与无帧调用**同一个实例** —— 没有帧概念的一切物品行为逐字不变。</li>
 *   <li><b>帧真的进键</b>：同一个栈在两个帧下各得一张图层表（真实场景：Tetra 弓的
 *       {@code undrawn / draw_0 / draw_1 / draw_2} 各一张贴图，见
 *       {@code _ref/Tetra-1.20/.../ModularBowItem.java:534-536} 的 filterModel），且第二次查命中缓存。</li>
 *   <li><b>绝不 NPE / 绝不崩</b>：{@code frameOf} 抛异常一律按无帧处理。</li>
 * </ol>
 */
class ChasmItemLayersFrameTest {

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static ItemLayer layer(String path) {
		return new ItemLayer(ResourceLocation.fromNamespaceAndPath("chasmtest", path), 0xFFFFFFFF);
	}

	@Test
	void plainProviderIgnoresTheFrameEntirely() {
		AtomicInteger calls = new AtomicInteger();
		// 同一个 List 实例：断言"帧被忽略"时能直接用身份比较，不受 List.of 每次新建的影响
		List<ItemLayer> fixed = List.of(layer("plain"));
		ChasmItemLayers.register(Items.IRON_HOE, stack -> {
			calls.incrementAndGet();
			return fixed;
		});
		ItemStack stack = new ItemStack(Items.IRON_HOE);

		assertEquals("", ChasmItemLayers.frameOf(stack, null), "没实现帧的物品必须报\"无帧\"");
		assertSame(fixed, ChasmItemLayers.layersOf(stack),
			"无帧求值必须原样返回 provider 给的那张表");
		assertSame(fixed, ChasmItemLayers.layersOf(stack, "draw_1"),
			"默认方法必须直接转发给无帧版本：帧对没实现帧语义的物品**零影响**");
		assertEquals(2, calls.get(), "无帧 + draw_1 各问一次，两次都命中同一个 provider 调用");
	}

	@Test
	void frameIsPartOfTheCacheKeyAndNeverCrossContaminates() {
		AtomicInteger calls = new AtomicInteger();
		ChasmItemLayers.register(Items.BOW, new ItemLayerProvider() {
			@Override
			public List<ItemLayer> layersOf(ItemStack stack) {
				return layersOf(stack, "undrawn");
			}

			@Override
			public String frameOf(ItemStack stack, net.minecraft.world.entity.LivingEntity entity) {
				return "draw_1";
			}

			@Override
			public List<ItemLayer> layersOf(ItemStack stack, String frame) {
				calls.incrementAndGet();
				return List.of(layer("string/" + frame));
			}
		});
		ItemStack stack = new ItemStack(Items.BOW);
		ChasmItemLayerCache cache = new ChasmItemLayerCache(Items.BOW);

		assertEquals("draw_1", ChasmItemLayers.frameOf(stack, null), "帧由 provider 自己从栈/持有者算");
		List<ItemLayer> drawn = cache.layersOf(stack, "draw_1");
		List<ItemLayer> resting = cache.layersOf(stack, "undrawn");

		assertEquals("string/draw_1", drawn.get(0).texture().getPath());
		assertEquals("string/undrawn", resting.get(0).texture().getPath(), "两个帧必须各归其位");
		assertNotSame(drawn, resting);
		assertSame(drawn, cache.layersOf(stack, "draw_1"), "同帧再查必须命中缓存（O(1)）");
		assertEquals(2, calls.get(), "两个帧各算一次，重复查不再惊动 provider");
		assertEquals("draw_1", cache.frameFor(stack), "染色路径沿用的就是最近一次求值的帧");
	}

	@Test
	void brokenFrameProviderDegradesToNoFrame() {
		ChasmItemLayers.register(Items.TRIDENT, new ItemLayerProvider() {
			@Override
			public List<ItemLayer> layersOf(ItemStack stack) {
				return List.of(layer("trident"));
			}

			@Override
			public String frameOf(ItemStack stack, net.minecraft.world.entity.LivingEntity entity) {
				throw new IllegalStateException("帧算不出来");
			}
		});

		assertEquals("", ChasmItemLayers.frameOf(new ItemStack(Items.TRIDENT), null),
			"帧求值抛异常 → 无帧（渲染线程上抛异常会崩客户端）");
		assertTrue(ChasmItemLayers.layersOf(new ItemStack(Items.TRIDENT), null).size() == 1,
			"null 帧按无帧处理，仍然拿得到图层");
	}
}
