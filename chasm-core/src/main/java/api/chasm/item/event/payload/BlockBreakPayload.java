package api.chasm.item.event.payload;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** 挖方块负载（before/after 共用）。 */
public record BlockBreakPayload(Player player, Level level, BlockPos pos, BlockState state,
								BlockEntity blockEntity) {
}
