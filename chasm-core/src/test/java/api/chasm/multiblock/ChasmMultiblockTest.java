package api.chasm.multiblock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChasmMultiblock} 的几何 / 注册 / 成形判定断言。
 *
 * <p>用的是 Tetra 的真实三个规格（stonecutter 3×2 / earthpiercer 2×2 / extractor 3×3，
 * 证据 {@code _ref/.../TetraRegistries.java:250-257}），以及真实 id 格式
 * {@code "%s_%d_%d"} / {@code "%s_ruined_%d_%d"}（{@code MultiblockSchematicBlock.java:187-188}）。</p>
 */
class ChasmMultiblockTest {

	private static final ChasmMultiblock.Spec STONECUTTER = new ChasmMultiblock.Spec("stonecutter", 3, 2);

	@BeforeEach
	void setUp() {
		ChasmMultiblock.clearForTesting();
		Map<String, ChasmMultiblock.Cell> ids = new LinkedHashMap<>();
		for (int x = 0; x < STONECUTTER.width(); x++) {
			for (int y = 0; y < STONECUTTER.height(); y++) {
				ChasmMultiblock.Cell cell = new ChasmMultiblock.Cell(x, y, 0);
				ids.put("tetra:" + STONECUTTER.pieceId(x, y), cell);
				ids.put("tetra:" + STONECUTTER.ruinedId(x, y), cell);
			}
		}
		ChasmMultiblock.mount(STONECUTTER, ids);
	}

	@Test
	void specMatchesRealTetraDimensions() {
		assertEquals(6, STONECUTTER.size(), "stonecutter 3x2 = 6 格");
		assertEquals(3, STONECUTTER.width());
		assertEquals(2, STONECUTTER.height());
		assertEquals(1, STONECUTTER.depth());
		assertEquals(1, STONECUTTER.primaryX(), "真实 x == width/2");
		assertEquals(1, STONECUTTER.primaryY(), "真实 y == height/2");
		assertTrue(STONECUTTER.isPrimary(1, 1));
		assertFalse(STONECUTTER.isPrimary(0, 0));
		assertEquals(6, STONECUTTER.cells().size());
	}

	@Test
	void blockIdFormatAndLookupAreReal() {
		assertEquals("stonecutter_2_0", STONECUTTER.pieceId(2, 0));
		assertEquals("stonecutter_ruined_2_0", STONECUTTER.ruinedId(2, 0));
		assertSame(STONECUTTER, ChasmMultiblock.byIdentifier("stonecutter"));
		ChasmMultiblock.CellMatch match = ChasmMultiblock.byBlockId("tetra:stonecutter_1_1");
		assertNotNull(match);
		assertSame(STONECUTTER, match.spec());
		assertEquals(1, match.x());
		assertEquals(1, match.y());
		assertTrue(match.primary(), "x==width/2 && y==height/2 是主块");
		assertNotNull(ChasmMultiblock.byBlockId("stonecutter_ruined_0_0"), "不带命名空间的写法也要认");
		assertNull(ChasmMultiblock.byIdentifier("nope"), "未知 key 必须 null（挡 NPE）");
		assertNull(ChasmMultiblock.byBlockId(null));
	}

	@Test
	void worldPosFollowsRealRotation() {
		BlockPos origin = new BlockPos(10, 64, 10);
		// 中心格不动（真实 x==width/2, y==height/2）
		assertEquals(origin, ChasmMultiblock.worldPos(origin, Direction.NORTH, 1, 1, 1, 1));
		assertEquals(origin, ChasmMultiblock.worldPos(origin, Direction.SOUTH, 1, 1, 1, 1));
		// 真实 RotationHelper：NORTH = (-x, y, -z)、WEST = (-z, y, x)、EAST = (z, y, -x)、SOUTH 原样
		assertEquals(new BlockPos(11, 64, 10), ChasmMultiblock.worldPos(origin, Direction.NORTH, 1, 1, 0, 1));
		assertEquals(new BlockPos(9, 64, 10), ChasmMultiblock.worldPos(origin, Direction.SOUTH, 1, 1, 0, 1));
		assertEquals(new BlockPos(10, 64, 9), ChasmMultiblock.worldPos(origin, Direction.WEST, 1, 1, 0, 1));
		assertEquals(new BlockPos(10, 64, 11), ChasmMultiblock.worldPos(origin, Direction.EAST, 1, 1, 0, 1));
		assertEquals(new BlockPos(9, 66, 10), ChasmMultiblock.worldPos(origin, Direction.SOUTH, 1, 1, 0, 3));
	}

	@Test
	void formationCheckCountsEveryCell() {
		BlockPos origin = BlockPos.ZERO;
		// 全对位 → 成形
		ChasmMultiblock.Result formed = ChasmMultiblock.check(STONECUTTER, origin, Direction.SOUTH, 0, 0,
			pos -> new ChasmMultiblock.CellMatch(STONECUTTER, pos.getX(), pos.getY(), 0,
				STONECUTTER.isPrimary(pos.getX(), pos.getY()), null));
		assertTrue(formed.formed());
		assertEquals(6, formed.matched());
		assertEquals(new BlockPos(1, 1, 0), formed.primary());

		// 缺一格 → 不成形（真实：count != width*height）
		ChasmMultiblock.Result missing = ChasmMultiblock.check(STONECUTTER, origin, Direction.SOUTH, 0, 0,
			pos -> pos.getX() == 2 && pos.getY() == 1 ? null
				: new ChasmMultiblock.CellMatch(STONECUTTER, pos.getX(), pos.getY(), 0, false, null));
		assertFalse(missing.formed());
		assertEquals(5, missing.matched());
		assertNull(missing.primary(), "不成形就没有主块");

		// 位错（同名拼块出现在错误的格子）→ 不成形
		ChasmMultiblock.Result misaligned = ChasmMultiblock.check(STONECUTTER, origin, Direction.SOUTH, 0, 0,
			pos -> new ChasmMultiblock.CellMatch(STONECUTTER, pos.getX(), 0, 0, false, null));
		assertFalse(misaligned.formed());
	}

	@Test
	void checkIsNullSafe() {
		assertFalse(ChasmMultiblock.check(null, BlockPos.ZERO, Direction.SOUTH, 0, 0, pos -> null).formed());
		assertFalse(ChasmMultiblock.check(STONECUTTER, null, Direction.SOUTH, 0, 0, pos -> null).formed());
		assertFalse(ChasmMultiblock.check(STONECUTTER, BlockPos.ZERO, Direction.SOUTH, 0, 0, null).formed());
	}

	@Test
	void chunkKeyIsCompactAndNullSafe() {
		assertEquals(ChasmMultiblock.chunkKey(3, 70, 9), ChasmMultiblock.chunkKey(new BlockPos(3, 70, 9)));
		assertEquals(-1, ChasmMultiblock.chunkKey((BlockPos) null));
		// x/z 取低 4 位、y 取低 8 位（与 TetraSchematicSpecs 的旧实现逐位一致）
		assertEquals((3) | (9 << 4) | (70 << 8), ChasmMultiblock.chunkKey(3, 70, 9));
	}

	@Test
	void partsOfExpandsWholeLayout() {
		BlockPos origin = new BlockPos(0, 64, 0);
		assertEquals(6, ChasmMultiblock.partsOf(STONECUTTER, origin, Direction.SOUTH, 0, 0).size());
		assertEquals(6, ChasmMultiblock.cells(STONECUTTER).size());
		assertEquals(0, ChasmMultiblock.cells(null).size());
	}

	@Test
	void duplicateMountKeepsLastDefinition() {
		ChasmMultiblock.Spec replacement = new ChasmMultiblock.Spec("stonecutter", 2, 2);
		ChasmMultiblock.mount(replacement, Map.of("tetra:stonecutter_0_0", new ChasmMultiblock.Cell(0, 0, 0)));
		assertSame(replacement, ChasmMultiblock.byIdentifier("stonecutter"));
		assertEquals(1, ChasmMultiblock.all().size(), "重复 mount 同一 identifier 不增加条目");
		assertNotNull(ChasmMultiblock.byBlockId("stonecutter_0_0"), "带命名空间登记时裸写法也要能查到");
		assertNotNull(ResourceLocation.tryParse("tetra:stonecutter_0_0"));
	}
}
