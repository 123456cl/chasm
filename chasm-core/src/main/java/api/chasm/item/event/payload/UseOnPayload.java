package api.chasm.item.event.payload;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** 对方块右键（useOn）负载。 */
public record UseOnPayload(Player player, Level level, InteractionHand hand, BlockPos pos,
						   BlockState state, BlockHitResult hit) {
}
