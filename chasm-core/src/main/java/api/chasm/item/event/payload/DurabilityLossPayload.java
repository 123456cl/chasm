package api.chasm.item.event.payload;

import net.minecraft.world.item.ItemStack;

/**
 * 耐久损耗的事实包：这次要给这件物品扣多少耐久。
 *
 * <p>结果协议 ADDITIVE：各来源返回"要无视的比例"（0..1），求和后由
 * {@link api.chasm.item.ChasmDurability} 折算成实际扣减（带小数进位，避免小额损耗被整数抹掉）。</p>
 *
 * @param stack  正在损耗耐久的物品
 * @param amount 本次原始损耗点数
 */
public record DurabilityLossPayload(ItemStack stack, int amount) {
}
