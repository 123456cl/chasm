package api.chasm.item.context;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 自定义按键触发上下文（第十二步）。
 *
 * <p>服务端在玩家手持绑定该按键的物品并按下按键（经由 {@code KeyPressC2SPayload}）
 * 时构造并交给 {@code ItemBuilder.onKeyPress} 绑定的回调，在<b>服务端主线程</b>执行，
 * 可安全访问服务端世界与实体。</p>
 *
 * <p><b>包级解耦</b>：本类放在 {@code api.chasm.item.context} 子包，供
 * {@code api.chasm.item}（行为载体/Builder）与 {@code api.chasm.key}（按键通道）共同依赖，
 * 打破「物品 ↔ 按键」包级循环依赖。</p>
 *
 * @param level  服务端世界
 * @param player 触发按键的玩家
 * @param stack  玩家手中触发该按键的物品栈（已校验为持有绑定物品）
 * @param keyId  被按下的按键 id（{@code modId:name}）
 */
public record KeyContext(Level level, Player player, ItemStack stack, ResourceLocation keyId) {
}
