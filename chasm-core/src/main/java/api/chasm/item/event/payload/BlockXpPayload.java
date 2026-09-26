package api.chasm.item.event.payload;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * **方块经验掉落负载**：方块被破坏、经验球真正落地时由框架发出。
 *
 * <p>为什么需要这一层：1.21.1 把方块经验收进了 {@code Block.tryDropExperience(ServerLevel, BlockPos, ItemStack, IntProvider)}
 * —— 它是 <b>protected</b> 且方块自己持有的 {@code IntProvider} 不可读，也没有公开的 {@code getExpDrop}；
 * 框架的做法是**不去问方块，而是看结果**：监听经验球的生成
 * （{@code ServerEntityEvents.ENTITY_LOAD}），把球上的真实数值当作该方块的经验掉落。
 * 这比"预估"更准（掉落被其它系统压制时不会误判），且对模组方块同样成立。</p>
 *
 * <p>真实依据：{@code ItemModularHandheld.java:201-210} 的 {@code applyBlockBreakEffects}
 * 用 {@code state.getExpDrop(...)} 取经验再按 {@code intuit} 折算打磨进度；
 * {@code ItemEffectHandler.java:138-151} 的 {@code onBreakBlock} 用 {@code event.getExpToDrop()}
 * 让 {@code satiating} 抽取经验。两者都需要"这一格方块掉了多少经验"。</p>
 *
 * @param player 破坏方块的玩家
 * @param level  所在服务端世界
 * @param pos    方块坐标
 * @param state  被破坏的方块状态（破坏后仍是快照，可安全读取）
 * @param amount 本次真实掉落的经验值（≥ 0）
 * @param tool   当时手持的物品栈
 */
public record BlockXpPayload(Player player, ServerLevel level, BlockPos pos, BlockState state, int amount,
							 net.minecraft.world.item.ItemStack tool) {
}
