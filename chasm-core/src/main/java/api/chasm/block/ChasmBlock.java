package api.chasm.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Chasm 玩法方块基类：持有行为列表，在对应时机触发玩法回调。
 *
 * <p>创作者通过 {@link api.chasm.Chasm#block()} 的 Builder 声明行为，无需继承本类。
 * 支持事件：右键（use）/ 放置（place）/ 破坏（break）/ 踩踏（step）。</p>
 *
 * <p>行为列表保持可变：其他模组可通过 {@link ChasmBlockController} 追加/覆写（SPI 扩展）。</p>
 *
 * <p>当通过 {@link BlockBuilder#blockEntity} 关联了 {@link api.chasm.blockentity.ChasmBlockEntity}
 * 类型时，本类实现 {@link EntityBlock}：放置/区块加载创建 BE，并由统一 ticker 驱动；
 * 方块被替换时向 BE 转发 {@code destroy()}，邻居更新时转发 {@code onNeighborChanged()}。</p>
 */
public class ChasmBlock extends Block implements EntityBlock {

	private final List<Consumer<BlockUseContext>> useHandlers;
	private final List<Consumer<BlockUseContext>> placeHandlers;
	private final List<Consumer<BlockUseContext>> breakHandlers;
	private final List<Consumer<BlockUseContext>> stepHandlers;
	private final String displayName;
	private final String texture;
	private final String requiredTool;
	private final LootDeclaration loot;
	/** 关联的方块实体类型（惰性解析，可为 null = 无 BE；由 {@link BlockBuilder#blockEntity} 接线）。 */
	@Nullable
	private final Supplier<BlockEntityType<?>> blockEntityType;

	public ChasmBlock(List<Consumer<BlockUseContext>> useHandlers,
		List<Consumer<BlockUseContext>> placeHandlers,
		List<Consumer<BlockUseContext>> breakHandlers,
		List<Consumer<BlockUseContext>> stepHandlers,
		BlockBehaviour.Properties properties,
		String displayName, String texture, String requiredTool, LootDeclaration loot) {
		this(useHandlers, placeHandlers, breakHandlers, stepHandlers, properties,
			displayName, texture, requiredTool, loot, null);
	}

	public ChasmBlock(List<Consumer<BlockUseContext>> useHandlers,
		List<Consumer<BlockUseContext>> placeHandlers,
		List<Consumer<BlockUseContext>> breakHandlers,
		List<Consumer<BlockUseContext>> stepHandlers,
		BlockBehaviour.Properties properties,
		String displayName, String texture, String requiredTool, LootDeclaration loot,
		@Nullable Supplier<BlockEntityType<?>> blockEntityType) {
		super(properties);
		this.useHandlers = new ArrayList<>(useHandlers);
		this.placeHandlers = new ArrayList<>(placeHandlers);
		this.breakHandlers = new ArrayList<>(breakHandlers);
		this.stepHandlers = new ArrayList<>(stepHandlers);
		this.displayName = displayName;
		this.texture = texture;
		this.requiredTool = requiredTool;
		this.loot = loot;
		this.blockEntityType = blockEntityType;
	}

	// —— 方块实体 ticker ——

	/**
	 * 若本方块关联了 {@link ChasmBlockEntity} 类型（{@link BlockBuilder#blockEntity} 接线），
	 * 则为该类型返回统一 ticker 驱动；否则返回 null（原版无 ticker）。
	 */
	@Override
	@Nullable
	@SuppressWarnings("unchecked")
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
		Level level, BlockState state, BlockEntityType<T> type) {
		if (blockEntityType != null) {
			BlockEntityType<?> mine = blockEntityType.get();
			if (mine != null && mine == type) {
				// 类型已在本方块注册，且经 ChasmBlockEntityTypeBuilder 保证为 ChasmBlockEntity
				return (BlockEntityTicker<T>) (BlockEntityTicker<?>) api.chasm.blockentity.ChasmBlockEntityTicker.instance();
			}
		}
		return null;
	}

	/**
	 * 放置/区块加载时创建关联的 {@link ChasmBlockEntity}（原版仅在 {@code instanceof EntityBlock}
	 * 时调用；无关联类型返回 null = 本方块无 BE）。
	 */
	@Override
	@Nullable
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		if (blockEntityType == null) {
			return null;
		}
		BlockEntityType<?> mine = blockEntityType.get();
		return mine == null ? null : mine.create(pos, state);
	}

	/**
	 * 方块被移除/替换：向关联 BE 转发 {@code destroy()}（断开连接等收尾），
	 * 对齐 Create 的 {@code SmartBlockEntity#destroy()} 语义。
	 */
	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
		if (!state.is(newState.getBlock())) {
			BlockEntity be = level.getBlockEntity(pos);
			if (be instanceof api.chasm.blockentity.ChasmBlockEntity cbe) {
				cbe.destroy();
			}
		}
		super.onRemove(state, level, pos, newState, movedByPiston);
	}

	/**
	 * 邻居方块更新：向关联 BE 转发 {@code onNeighborChanged()}（供热源等行为重算）。
	 */
	@Override
	protected void neighborChanged(BlockState state, Level level, BlockPos pos,
		Block block, BlockPos fromPos, boolean isMoving) {
		BlockEntity be = level.getBlockEntity(pos);
		if (be instanceof api.chasm.blockentity.ChasmBlockEntity cbe) {
			cbe.onNeighborChanged(fromPos);
		}
		super.neighborChanged(state, level, pos, block, fromPos, isMoving);
	}

	// —— 事件触发 ——

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
		Player player, BlockHitResult hitResult) {
		BlockUseContext ctx = new BlockUseContext(level, pos, player, state, hitResult);
		for (Consumer<BlockUseContext> handler : useHandlers) {
			handler.accept(ctx);
		}
		return InteractionResult.sidedSuccess(level.isClientSide);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity placer,
		net.minecraft.world.item.ItemStack stack) {
		if (!placeHandlers.isEmpty()) {
			Player player = placer instanceof Player p ? p : null;
			BlockUseContext ctx = new BlockUseContext(level, pos, player, state, null, placer);
			for (Consumer<BlockUseContext> handler : placeHandlers) {
				handler.accept(ctx);
			}
		}
	}

	@Override
	public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
		if (!breakHandlers.isEmpty()) {
			BlockUseContext ctx = new BlockUseContext(level, pos, player, state, null);
			for (Consumer<BlockUseContext> handler : breakHandlers) {
				handler.accept(ctx);
			}
		}
		return state;
	}

	@Override
	public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
		if (!stepHandlers.isEmpty()) {
			Player player = entity instanceof Player p ? p : null;
			BlockUseContext ctx = new BlockUseContext(level, pos, player, state, null, entity);
			for (Consumer<BlockUseContext> handler : stepHandlers) {
				handler.accept(ctx);
			}
		}
	}

	// —— SPI 追加/覆写 ——

	/** 追加右键行为（供控制台调用）。 */
	public void addUseHandler(Consumer<BlockUseContext> handler) {
		useHandlers.add(handler);
	}

	/** 清空全部右键行为（供控制台覆写调用）。 */
	public void clearUseHandlers() {
		useHandlers.clear();
	}

	/** 追加放置行为（供控制台调用）。 */
	public void addPlaceHandler(Consumer<BlockUseContext> handler) {
		placeHandlers.add(handler);
	}

	/** 追加破坏行为（供控制台调用）。 */
	public void addBreakHandler(Consumer<BlockUseContext> handler) {
		breakHandlers.add(handler);
	}

	/** 追加踩踏行为（供控制台调用）。 */
	public void addStepHandler(Consumer<BlockUseContext> handler) {
		stepHandlers.add(handler);
	}

	// —— DataGen 元数据 ——

	/** 声明式显示名（DataGen 生成语言文件用）。 */
	public String chasmDisplayName() {
		return displayName;
	}

	/** 声明式贴图路径（DataGen 生成方块模型用）。 */
	public String chasmTexture() {
		return texture;
	}

	/** 需要的挖掘工具标签（完整路径如 "minecraft:mineable/pickaxe"），DataGen 生成标签用。 */
	public String chasmRequiredTool() {
		return requiredTool;
	}

	/** 掉落声明（DataGen 生成 loot table 用）。 */
	public LootDeclaration chasmLoot() {
		return loot;
	}
}
