package api.chasm.item.event.payload;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * 射击（拉弓/弩释放）负载。
 *
 * <p><b>注意</b>：1.21.1 原版与 Fabric API 均无"弓释放"事件，本负载不绑定内置事件源；
 * 框架提供 {@link api.chasm.item.event.ChasmItemEventKeys#fireShoot} 供模组自带适配器
 * （含 mixin 或其它钩子）调用——这正是开放事件源的意义。</p>
 */
public record ShootPayload(LivingEntity shooter, ItemStack weapon, float power) {
}
