package api.chasm.block;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;

/**
 * 方块玩法行为上下文：封装一次与方块交互（右键/放置/破坏/踩踏）的完整信息。
 */
public final class BlockUseContext {

	private final Level level;
	private final BlockPos pos;
	private final Player player;
	private final BlockState state;
	private final BlockHitResult hit;
	private final Entity entity;

	public BlockUseContext(Level level, BlockPos pos, Player player, BlockState state, BlockHitResult hit) {
		this(level, pos, player, state, hit, null);
	}

	public BlockUseContext(Level level, BlockPos pos, Player player, BlockState state, BlockHitResult hit, Entity entity) {
		this.level = level;
		this.pos = pos;
		this.player = player;
		this.state = state;
		this.hit = hit;
		this.entity = entity;
	}

	/** 当前所在世界。 */
	public Level level() {
		return level;
	}

	/** 交互的方块坐标。 */
	public BlockPos pos() {
		return pos;
	}

	/** 交互的玩家（踩踏等事件可能为 null）。 */
	public Player player() {
		return player;
	}

	/** 触发事件的实体（踩踏时是踩上来的实体；其他事件为 null 或玩家）。 */
	public Entity entity() {
		return entity != null ? entity : player;
	}

	/** 交互时的方块状态。 */
	public BlockState state() {
		return state;
	}

	/** 点击命中的具体面与位置（右键事件有值，其他事件可能为 null）。 */
	public BlockHitResult hit() {
		return hit;
	}

	/** 挥动手臂（播放使用动画；无玩家时跳过）。 */
	public void swingArm() {
		if (player != null) {
			player.swing(InteractionHand.MAIN_HAND);
		}
	}

	/** 给玩家发送系统消息（无玩家或客户端侧时跳过）。 */
	public void sendMessage(String message) {
		if (player != null && !level.isClientSide) {
			player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
		}
	}
}
