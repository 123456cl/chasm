package api.chasm.model;

import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **图层几何：每一层必须是"按该层贴图自己的轮廓生成的、有厚度的板"**。
 *
 * <p>钉死四件事（对应 2026-09-20 修掉的"借 paper 几何"根因，见 {@link ChasmItemGeometry} 类注释）：</p>
 * <ol>
 *   <li><b>不是只有一个面</b>：正面 {@code SOUTH} 与背面 {@code NORTH} 必须同时存在；</li>
 *   <li><b>不是零厚度平面</b>：所有顶点的 z 落在原版物品层的 z 区间 {@code [7.5/16, 8.5/16]}
 *       且确有跨度（{@code ItemModelGenerator} 字节码 {@code ldc 7.5f} / {@code ldc 8.5f}）；</li>
 *   <li><b>轮廓来自本层贴图</b>：侧壁四边形必须落在该贴图不透明像素的范围内 ——
 *       借来的 paper 几何会整片铺满 16×16，这条会直接失败；</li>
 *   <li><b>朝向正确</b>：正面/背面的 UV 沿 +x 递增（{@code FaceBakery} 对背面靠顶点绕序翻转，
 *       不靠镜像 UV）；不是"竖竖的"侧向片。</li>
 * </ol>
 */
class ChasmItemGeometryTest {

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/** 一条水平的、**不贴左右边**的不透明段（x∈[4,12)、y∈[6,10)）—— 原版会为它补出上下两个侧壁。 */
	private static TestSprites.OpaqueMask middleBar() {
		return (x, y) -> x >= 4 && x < 12 && y >= 6 && y < 10;
	}

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath("chasmtest", path);
	}

	private static boolean hasDirection(List<BakedQuad> quads, Direction direction) {
		for (BakedQuad quad : quads) {
			if (quad.getDirection() == direction) {
				return true;
			}
		}
		return false;
	}

	/** 顶点在方块空间里的坐标（顶点数组 = DefaultVertexFormat.BLOCK，每顶点 8 个 int）。 */
	private static float[] vertex(BakedQuad quad, int index) {
		int[] data = quad.getVertices();
		int base = index * 8;
		return new float[]{
			Float.intBitsToFloat(data[base]),
			Float.intBitsToFloat(data[base + 1]),
			Float.intBitsToFloat(data[base + 2]),
			Float.intBitsToFloat(data[base + 4]),
			Float.intBitsToFloat(data[base + 5])};
	}

	private static int vertexCount(BakedQuad quad) {
		return quad.getVertices().length / 8;
	}

	@Test
	void layerGeometryHasBothAFrontAndABackFace() {
		TextureAtlasSprite sprite = TestSprites.of(id("layer"), middleBar());
		List<BakedQuad> quads = ChasmItemGeometry.quadsFor(sprite, 7);

		assertFalse(quads.isEmpty(), "一层必须至少产出 quad");
		assertTrue(hasDirection(quads, Direction.SOUTH), "缺正面（SOUTH）：用户看到的『只有一个面』");
		assertTrue(hasDirection(quads, Direction.NORTH), "缺背面（NORTH）：用户看到的『后面是空的』");
	}

	@Test
	void layerGeometryHasRealThicknessInsideTheVanillaItemZRange() {
		TextureAtlasSprite sprite = TestSprites.of(id("thickness"), middleBar());
		List<BakedQuad> quads = ChasmItemGeometry.quadsFor(sprite, 0);

		float low = Float.MAX_VALUE;
		float high = -Float.MAX_VALUE;
		for (BakedQuad quad : quads) {
			for (int i = 0; i < vertexCount(quad); i++) {
				float z = vertex(quad, i)[2];
				low = Math.min(low, z);
				high = Math.max(high, z);
			}
		}
		// 方块空间 = 元素空间 / 16
		assertEquals(ChasmItemGeometry.MIN_Z / 16.0F, low, 1.0E-5F, "z 下界必须是原版 MIN_Z");
		assertEquals(ChasmItemGeometry.MAX_Z / 16.0F, high, 1.0E-5F, "z 上界必须是原版 MAX_Z");
		assertTrue(high - low > 0.0F, "厚度为 0 = 一片零厚度的平面（用户看到的『没有实体』）");
		// 所有层共用同一段 z（原版不给层做偏移）→ 层间不会因为错开而露边
		assertEquals(1.0F / 16.0F, high - low, 1.0E-5F, "厚度必须恰好是原版的 1 像素");
	}

	@Test
	void sideQuadsFollowThisLayersOwnSilhouette() {
		TextureAtlasSprite sprite = TestSprites.of(id("silhouette"), middleBar());
		List<BakedQuad> quads = ChasmItemGeometry.quadsFor(sprite, 0);

		// 原版会为这条中间横条补出侧壁（不是只有正背面两张）
		int sides = 0;
		for (BakedQuad quad : quads) {
			Direction direction = quad.getDirection();
			if (direction == Direction.SOUTH || direction == Direction.NORTH) {
				continue;
			}
			sides++;
			for (int i = 0; i < vertexCount(quad); i++) {
				float[] v = vertex(quad, i);
				// 方块空间 → 元素空间（×16）；不透明段是 x∈[4,12)、y∈[6,10)
				float x = v[0] * 16.0F;
				float y = v[1] * 16.0F;
				assertTrue(x >= 4.0F - 1.0E-3F && x <= 12.0F + 1.0E-3F,
					"侧壁 x=" + x + " 超出本层贴图的不透明范围 [4,12]（借来的几何会铺满 16×16）");
				assertTrue(y >= 6.0F - 1.0E-3F && y <= 10.0F + 1.0E-3F,
					"侧壁 y=" + y + " 超出本层贴图的不透明范围 [6,10]");
			}
		}
		assertTrue(sides > 0, "这条贴图的上下两边应当各补出一个侧壁（原版 createSideElements）");
	}

	@Test
	void frontAndBackFacesCarryTheLayersOwnTintIndexAndUvRegion() {
		ResourceLocation texture = id("tintuv");
		TextureAtlasSprite sprite = TestSprites.of(texture, middleBar());
		int tintIndex = 1000 + 3;
		List<BakedQuad> quads = ChasmItemGeometry.quadsFor(sprite, tintIndex);

		for (BakedQuad quad : quads) {
			assertEquals(tintIndex, quad.getTintIndex(), "每一层的所有面都必须带这一层的 tint index");
			for (int i = 0; i < vertexCount(quad); i++) {
				float[] v = vertex(quad, i);
				assertTrue(v[3] >= sprite.getU0() - 1.0E-6F && v[3] <= sprite.getU1() + 1.0E-6F,
					"u=" + v[3] + " 落在本层 sprite 的图集区域外 → 会采到别的贴图（『图片歪了』）");
				assertTrue(v[4] >= sprite.getV0() - 1.0E-6F && v[4] <= sprite.getV1() + 1.0E-6F,
					"v=" + v[4] + " 落在本层 sprite 的图集区域外");
			}
		}

		// 朝向：正面/背面的 u 都沿 +x 递增（背面靠绕序翻转，不靠镜像 UV —— 实测原版行为）
		assertTrue(uAscendsWithX(quads, Direction.SOUTH), "正面 UV 方向不对（会画成镜像/歪的）");
		assertTrue(uAscendsWithX(quads, Direction.NORTH), "背面 UV 方向不对");
	}

	/** 该方向的第一个 quad：x 最小的顶点其 u 也必须最小。 */
	private static boolean uAscendsWithX(List<BakedQuad> quads, Direction direction) {
		for (BakedQuad quad : quads) {
			if (quad.getDirection() != direction) {
				continue;
			}
			float minX = Float.MAX_VALUE;
			float uAtMinX = Float.NaN;
			float maxX = -Float.MAX_VALUE;
			float uAtMaxX = Float.NaN;
			for (int i = 0; i < vertexCount(quad); i++) {
				float[] v = vertex(quad, i);
				if (v[0] < minX) {
					minX = v[0];
					uAtMinX = v[3];
				}
				if (v[0] > maxX) {
					maxX = v[0];
					uAtMaxX = v[3];
				}
			}
			return uAtMaxX > uAtMinX;
		}
		return false;
	}

	@Test
	void geometryShapeFollowsThisLayersOwnPixelsNotAFixedBorrowedOutline() {
		// 实测（1.21.1 原版 ItemModelGenerator）：
		//   矩形轮廓（满张/横条/方块/单像素）→ 1 块板 + 外圈四壁 = 5 个元素 / 6 张 quad
		//   两个分开的方块 → 9 个元素 / 10 张 quad
		//   全透明 → 1 个元素 / 2 张 quad（只有板，没有侧壁）
		// 也就是说**几何随本层像素变化**；旧实现无论哪一层都套同一份借来的 paper 轮廓。
		List<BakedQuad> full = ChasmItemGeometry.quadsFor(TestSprites.of(id("full"), (x, y) -> true), 0);
		assertEquals(6, full.size(), "满张不透明 = 正背面 2 张 + 外圈四壁 4 张");
		assertTrue(hasDirection(full, Direction.SOUTH));
		assertTrue(hasDirection(full, Direction.NORTH));

		List<BakedQuad> rect = ChasmItemGeometry.quadsFor(
			TestSprites.of(id("rect"), (x, y) -> x >= 4 && x < 12 && y >= 6 && y < 10), 0);
		assertEquals(6, rect.size(), "矩形轮廓同样只补外圈四壁");

		List<BakedQuad> two = ChasmItemGeometry.quadsFor(TestSprites.of(id("two"),
			(x, y) -> (x >= 2 && x < 5 && y >= 2 && y < 5) || (x >= 9 && x < 14 && y >= 8 && y < 13)), 0);
		assertTrue(two.size() > full.size(),
			"两个分开的方块必须比满张多出侧壁（实际 " + two.size() + " vs " + full.size() + "）");

		List<BakedQuad> empty = ChasmItemGeometry.quadsFor(TestSprites.of(id("empty"), (x, y) -> false), 0);
		assertEquals(2, empty.size(), "全透明贴图只有那块板（正 + 背），没有任何侧壁");
	}

	@Test
	void nullSpriteOrNullOutputsAreIgnoredInsteadOfCrashing() {
		assertTrue(ChasmItemGeometry.quadsFor(null, 0).isEmpty());
		assertEquals(0, ChasmItemGeometry.elementCount(null));
		java.util.List<BakedQuad> unculled = new java.util.ArrayList<>();
		ChasmItemGeometry.bake(null, 0, unculled, new java.util.EnumMap<>(Direction.class));
		assertTrue(unculled.isEmpty());
	}
}
