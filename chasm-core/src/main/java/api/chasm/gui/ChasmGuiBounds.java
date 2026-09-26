package api.chasm.gui;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 界面绑定的距离/维度校验（P0.5：堵住"隔着半张地图操作机器"的漏洞）。
 *
 * <p>旧版 {@code ChasmMenu.stillValid} 只判 {@code isAlive}，玩家跑远、换维度都能继续点按钮、拖物品；
 * 对"机器/容器类"界面属于刷物风险。本类把校验做成**纯函数**（不依赖世界对象，可单测）：
 * 同维度且玩家到绑定方块的距离 ≤ 范围才算有效。</p>
 */
public final class ChasmGuiBounds {

	/** 默认绑定范围（方块）——与原版容器交互距离一致（8 格，比较用平方值）。 */
	public static final double DEFAULT_RANGE = 8.0;

	private ChasmGuiBounds() {
	}

	/** 把"格数"换成比较用的平方值。 */
	public static double square(double range) {
		return range * range;
	}

	/**
	 * 是否在有效交互范围内。
	 *
	 * @param playerDimension 玩家所在维度
	 * @param px/py/pz         玩家坐标
	 * @param boundDimension   界面绑定方块所在维度
	 * @param bound            绑定方块坐标（取方块中心比较）
	 * @param rangeSq          范围平方（见 {@link #square(double)}）
	 */
	public static boolean inRange(ResourceKey<Level> playerDimension, double px, double py, double pz,
								  ResourceKey<Level> boundDimension, BlockPos bound, double rangeSq) {
		if (playerDimension == null || boundDimension == null || bound == null) {
			return true;
		}
		if (!playerDimension.equals(boundDimension)) {
			return false;
		}
		double dx = px - (bound.getX() + 0.5);
		double dy = py - (bound.getY() + 0.5);
		double dz = pz - (bound.getZ() + 0.5);
		return dx * dx + dy * dy + dz * dz <= rangeSq;
	}
}
