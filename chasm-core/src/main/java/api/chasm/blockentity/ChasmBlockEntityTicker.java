package api.chasm.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Chasm 方块实体 ticker 驱动：把原版 {@link BlockEntityTicker} 统一转发到
 * {@link ChasmBlockEntity#tick()}，让模板壳接管生命周期编排（懒初始化 / lazy tick / 行为 tick）。
 *
 * <p>由 {@link ChasmBlockEntityTypeBuilder} 关联的方块在 {@code Block#getTicker} 中返回
 * {@link #instance()} 即可驱动本类方块实体。单例无状态，可被所有 Chasm BE 方块复用。</p>
 *
 * <p>设计来源：Create-Fly 的 {@code SmartBlockEntityTicker}（第十六步分析），森罗物语的
 * {@code PotBlockEntity#tick(Level)} 在 {@code Block#getTicker} 中注册同一模式。</p>
 *
 * @param <T> 方块实体类型（必须继承 {@link ChasmBlockEntity}）
 */
public final class ChasmBlockEntityTicker<T extends ChasmBlockEntity> implements BlockEntityTicker<T> {

	private static final ChasmBlockEntityTicker<?> INSTANCE = new ChasmBlockEntityTicker<>();

	private ChasmBlockEntityTicker() {
	}

	/** 获取共享 ticker 实例（类型擦除安全，仅驱动 {@link ChasmBlockEntity}）。 */
	@SuppressWarnings("unchecked")
	public static <T extends ChasmBlockEntity> BlockEntityTicker<T> instance() {
		return (BlockEntityTicker<T>) INSTANCE;
	}

	@Override
	public void tick(Level level, BlockPos pos, BlockState state, T blockEntity) {
		if (!blockEntity.isRemoved()) {
			blockEntity.tick();
		}
	}
}
