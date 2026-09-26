package api.chasm.gui;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 界面绑定的距离/维度校验（P0.5）：纯函数，可无世界单测。 */
class ChasmGuiBoundsTest {

	private static final ResourceKey<Level> OVERWORLD = ResourceKey.create(
		net.minecraft.core.registries.Registries.DIMENSION,
		net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"));
	private static final ResourceKey<Level> NETHER = ResourceKey.create(
		net.minecraft.core.registries.Registries.DIMENSION,
		net.minecraft.resources.ResourceLocation.withDefaultNamespace("the_nether"));

	private static final double RANGE_SQ = ChasmGuiBounds.square(8.0);

	@Test
	void sameDimensionWithinRangeIsValid() {
		BlockPos machine = new BlockPos(0, 64, 0);
		assertTrue(ChasmGuiBounds.inRange(OVERWORLD, 0.5, 64.5, 0.5, OVERWORLD, machine, RANGE_SQ));
		assertTrue(ChasmGuiBounds.inRange(OVERWORLD, 5.0, 64.0, 5.0, OVERWORLD, machine, RANGE_SQ));
	}

	@Test
	void beyondRangeIsRejected() {
		BlockPos machine = new BlockPos(0, 64, 0);
		assertFalse(ChasmGuiBounds.inRange(OVERWORLD, 9.0, 64.5, 0.5, OVERWORLD, machine, RANGE_SQ),
			"9 格外应失效（旧实现只判 isAlive，是刷物漏洞）");
		assertFalse(ChasmGuiBounds.inRange(OVERWORLD, 0.5, 200.0, 0.5, OVERWORLD, machine, RANGE_SQ),
			"纵向跑远同样应失效");
	}

	@Test
	void differentDimensionIsRejected() {
		BlockPos machine = new BlockPos(0, 64, 0);
		assertFalse(ChasmGuiBounds.inRange(NETHER, 0.5, 64.5, 0.5, OVERWORLD, machine, RANGE_SQ),
			"换维度必须失效，哪怕坐标相同");
	}

	@Test
	void nullBindingIsPermissiveForPortableGuis() {
		assertTrue(ChasmGuiBounds.inRange(OVERWORLD, 1000, 100, 1000, null, null, RANGE_SQ));
	}
}
