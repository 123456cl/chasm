package api.chasm.model;

import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 包装模型的行为测试（{@link LayeredItemModel} / {@link ChasmItemLayerModels}）。
 *
 * <p>这些用例在纯 JVM 里跑（不碰 GL/贴图集），钉死四件事：</p>
 * <ol>
 *   <li><b>兼容底线</b>：没注册 provider 的物品，按栈取模型返回的必须是**同一个实例** ——
 *       不是"看起来一样"，是压根没换（{@code assertSame}）。</li>
 *   <li><b>绝不 NPE</b>：贴图集拿不到 sprite 时退化为原模型，而不是抛异常（渲染线程上抛异常会崩客户端）。</li>
 *   <li><b>几何来自本层贴图</b>：被包模型自己**没有任何几何**时（真实场景：{@code modular_sword.json}
 *       只有 {@code parent:item/generated}、没有 {@code textures}），也要能靠该层的 sprite 画出叠层 ——
 *       不再去借 {@code minecraft:item/paper} 的几何（那是 2026-09-20 修掉的根因，
 *       见 {@link LayeredItemModel} 的"几何从哪来"）。</li>
 *   <li><b>染色索引</b>：第 i 层 = {@code TINT_BASE + i}；低于 {@code TINT_BASE} 的索引必须返回 -1（白色），
 *       这样这个物品别的 quad 颜色与注册前完全一致。</li>
 * </ol>
 */
class LayeredItemModelTest {

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static ItemLayer layer(String path, int tint) {
		return new ItemLayer(ResourceLocation.fromNamespaceAndPath("chasmtest", path), tint);
	}

	/** 最小可用替身：geometries 为空表（等价于"模板没有几何"）。 */
	private static BakedModel emptyTemplate() {
		return new FakeBakedModel(List.of());
	}

	@Test
	void unregisteredItemGetsTheVerySameModelInstance() {
		LayeredItemModel wrapper = new LayeredItemModel(emptyTemplate(), Items.APPLE, material -> null);
		ItemStack stack = new ItemStack(Items.APPLE);

		assertFalse(ChasmItemLayers.hasProvider(Items.APPLE));
		BakedModel resolved = wrapper.getOverrides().resolve(wrapper, stack, null, null, 0);
		assertSame(wrapper, resolved, "未注册物品：按栈取模型必须原样返回（连包装都不换）");
	}

	@Test
	void emptyOrNullStackStaysOnTheOriginalModel() {
		ChasmItemLayers.register(Items.BREAD, s -> List.of(layer("bread", 0xFFFFFFFF)));
		LayeredItemModel wrapper = new LayeredItemModel(emptyTemplate(), Items.BREAD, material -> null);

		assertSame(wrapper, wrapper.getOverrides().resolve(wrapper, ItemStack.EMPTY, null, null, 0));
		assertSame(wrapper, wrapper.getOverrides().resolve(wrapper, null, null, null, 0));
	}

	@Test
	void providerWithoutLayersStaysOnTheOriginalModel() {
		ChasmItemLayers.register(Items.CARROT, s -> List.of());
		LayeredItemModel wrapper = new LayeredItemModel(emptyTemplate(), Items.CARROT, material -> null);
		assertSame(wrapper, wrapper.getOverrides().resolve(wrapper, new ItemStack(Items.CARROT), null, null, 0));
	}

	@Test
	void missingSpriteAndMissingGeometryDegradeInsteadOfCrashing() {
		ChasmItemLayers.register(Items.COD, s -> List.of(layer("cod_layer", 0xFF00FF00)));
		// spriteGetter 恒返回 null = 贴图集里没有这张图（或资源还没加载完）
		LayeredItemModel wrapper = new LayeredItemModel(emptyTemplate(), Items.COD, material -> null);

		BakedModel resolved = wrapper.getOverrides().resolve(wrapper, new ItemStack(Items.COD), null, null, 0);
		assertSame(wrapper, resolved, "拿不到贴图 → 保留原来的单张贴图，绝不崩");
		assertEquals(1, wrapper.layersOf(new ItemStack(Items.COD)).size(), "图层表本身仍然算出来了");
	}

	/**
	 * **几何不再借别人的**：被包模型零几何时，只要本层拿得到 sprite，就必须画出叠层模型。
	 *
	 * <p>旧实现在这种情况下会去 {@code baker.bake(minecraft:item/paper)} 借纸的 quad 当模板 ——
	 * 纸的轮廓、纸的侧壁、再把 UV 从纸的图集区域重映射过来。现在几何由
	 * {@link ChasmItemGeometry} 按该层 sprite 现生成（真实 Tetra 的
	 * {@code ItemLayerModel.java:55-63} 同一条路）。</p>
	 */
	@Test
	void geometryComesFromTheLayersOwnSpriteEvenWhenTheBaseModelHasNone() {
		ResourceLocation texture = ResourceLocation.fromNamespaceAndPath("chasmtest", "own_geometry");
		// 一条不贴边的横条：原版会为它补出上下两个侧壁 —— 证明几何确实按这张贴图算出来的
		TextureAtlasSprite sprite = TestSprites.of(texture, (x, y) -> x >= 4 && x < 12 && y >= 6 && y < 10);
		ChasmItemLayers.register(Items.NAME_TAG, s -> List.of(new ItemLayer(texture, 0xFFFFFFFF)));
		LayeredItemModel wrapper = new LayeredItemModel(emptyTemplate(), Items.NAME_TAG, material -> sprite);

		BakedModel resolved = wrapper.getOverrides().resolve(wrapper, new ItemStack(Items.NAME_TAG), null, null, 0);
		assertNotSame(wrapper, resolved, "本层有 sprite 就必须画出叠层模型（不再依赖借来的几何）");

		List<BakedQuad> quads = resolved.getQuads(null, null, RandomSource.create(42L));
		assertFalse(quads.isEmpty(), "叠层模型必须至少有一张 quad");
		boolean south = false;
		boolean north = false;
		for (BakedQuad quad : quads) {
			assertEquals(LayeredItemModel.TINT_BASE, quad.getTintIndex(), "第 0 层的 tint index 必须是 TINT_BASE");
			south |= quad.getDirection() == Direction.SOUTH;
			north |= quad.getDirection() == Direction.NORTH;
		}
		assertTrue(south, "缺正面：用户看到的『只有一个面』");
		assertTrue(north, "缺背面：用户看到的『后面是空的』");
	}

	@Test
	void overridesInstanceIsReusedAcrossFrames() {
		LayeredItemModel wrapper = new LayeredItemModel(emptyTemplate(), Items.SALMON, material -> null);
		// ItemRenderer 每帧都会调 getOverrides()：不能每帧新建对象
		assertSame(wrapper.getOverrides(), wrapper.getOverrides());
	}

	@Test
	void wrapperStaysOnTheVanillaAdapterPath() {
		LayeredItemModel wrapper = new LayeredItemModel(emptyTemplate(), Items.SALMON, material -> null);
		// Indigo 的 ItemRendererMixin 只在 !isVanillaAdapter() 时接管渲染；
		// 走原版路径才会调用 ItemColors.getColor(...)，图层染色才有意义
		assertTrue(wrapper.isVanillaAdapter(), "必须保持原版渲染路径（ForwardingBakedModel 转发被包模型）");
	}

	@Test
	void layerTintIndicesAreOffsetAndNonLayerIndicesStayWhite() {
		int[] tints = {0x00FF0000, 0x80123456}; // 第一层故意 alpha=0（真实 ColorQuadTransformer.java:10-12 视为不透明）
		ChasmItemLayers.register(Items.CLOCK, s -> List.of(layer("clock0", tints[0]), layer("clock1", tints[1])));
		LayeredItemModel wrapper = new LayeredItemModel(emptyTemplate(), Items.CLOCK, material -> null);
		ChasmItemLayerModels.ACTIVE.put(Items.CLOCK, wrapper);
		ItemStack stack = new ItemStack(Items.CLOCK);

		assertEquals(-1, ChasmItemLayerModels.tintOf(stack, 0), "原版 tint index（< 1000）必须返回白色");
		assertEquals(-1, ChasmItemLayerModels.tintOf(stack, 999));
		assertEquals(0xFFFF0000, ChasmItemLayerModels.tintOf(stack, LayeredItemModel.TINT_BASE),
			"alpha 位为 0 时按不透明处理");
		assertEquals(0x80123456, ChasmItemLayerModels.tintOf(stack, LayeredItemModel.TINT_BASE + 1));
		assertEquals(-1, ChasmItemLayerModels.tintOf(stack, LayeredItemModel.TINT_BASE + 2), "越界的层 → 白色");
		assertEquals(-1, ChasmItemLayerModels.layerTintOf(null, 0));
		assertEquals(-1, ChasmItemLayerModels.layerTintOf(stack, -1));
	}

	@Test
	void tintOfAnItemWithoutActiveWrapperIsWhite() {
		assertEquals(-1, ChasmItemLayerModels.tintOf(new ItemStack(Items.PUFFERFISH), LayeredItemModel.TINT_BASE));
	}

	@Test
	void opaqueOnlyFillsAZeroAlpha() {
		assertEquals(0xFFFF0000, ChasmItemLayerModels.opaque(0x00FF0000));
		assertEquals(0x80123456, ChasmItemLayerModels.opaque(0x80123456));
		assertEquals(0xFFFFFFFF, ChasmItemLayerModels.opaque(0xFFFFFFFF));
	}

	/** 最小 {@link BakedModel} 替身：只有"模板 quad 表"是有意义的字段。 */
	private static final class FakeBakedModel implements BakedModel {

		private final List<BakedQuad> quads;

		FakeBakedModel(List<BakedQuad> quads) {
			this.quads = quads;
		}

		@Override
		public List<BakedQuad> getQuads(BlockState state, Direction direction, RandomSource random) {
			return quads;
		}

		@Override
		public boolean useAmbientOcclusion() {
			return true;
		}

		@Override
		public boolean isGui3d() {
			return false;
		}

		@Override
		public boolean usesBlockLight() {
			return false;
		}

		@Override
		public boolean isCustomRenderer() {
			return false;
		}

		@Override
		public TextureAtlasSprite getParticleIcon() {
			return null;
		}

		@Override
		public ItemTransforms getTransforms() {
			return null;
		}

		@Override
		public ItemOverrides getOverrides() {
			return ItemOverrides.EMPTY;
		}
	}
}
