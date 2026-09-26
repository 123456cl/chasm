package api.chasm.item;

import api.chasm.data.ChasmData;
import api.chasm.item.event.ChasmItemEventKeys;
import api.chasm.item.event.ChasmItemEvents;
import api.chasm.log.ChasmLogger;

import com.mojang.serialization.Codec;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

/**
 * **耐久损耗的统一入口**：所有"给物品扣耐久"的地方都该走这里，而不是直接 setDamageValue。
 *
 * <p>为什么值得单独一个入口：宝石/词缀的"无视 N% 耐久损耗"需要在**扣减点**插一脚。
 * 原版 hurtAndBreak 无法零 mixin 拦截，但框架自己的损耗点（UseContext.damageItem、
 * AttackContext.damageItem、模组自建逻辑）都可以先问一句 {@link ChasmItemEventKeys#ON_DURABILITY_LOSS}。</p>
 *
 * <p>小数进位：无视比例是连续的（比如 12.5%），而耐久是整数。扣 1 点、无视 12.5% 时
 * 不能简单取整成 0（那就变成 100% 无视了），因此把"欠的零头"记在组件里累计，
 * 长期表现精确等于该比例。</p>
 */
public final class ChasmDurability {

	/** 零头累计（小数进位用；不写进物品 = 无零头）。 */
	public static final DataComponentType<Float> CREDIT =
		ChasmData.register("chasm", "durability_credit", Codec.FLOAT);

	private ChasmDurability() {
	}

	/** 本物品当前的"无视损耗"比例（0..1；不可损坏物品为 1）。 */
	public static float ignoredFraction(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return 0.0F;
		}
		if (stack.has(DataComponents.UNBREAKABLE) || !stack.isDamageableItem()) {
			return 1.0F;
		}
		if (!ChasmItemEvents.isInteresting(ChasmItemEventKeys.ON_DURABILITY_LOSS)) {
			return 0.0F;
		}
		try {
			float fraction = ChasmItemEventKeys.fireDurabilityLoss(stack, 1);
			return Math.min(1.0F, Math.max(0.0F, fraction));
		} catch (RuntimeException e) {
			ChasmLogger.warn("chasm", "耐久事件分发异常（已隔离，按不减伤处理）: {}", e.toString());
			return 0.0F;
		}
	}

	/**
	 * 造成耐久损耗。
	 *
	 * @return true = 还能继续用；false = 已损坏（调用方应据此断开物品）
	 */
	public static boolean damage(ItemStack stack, int amount) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		if (amount <= 0) {
			return stack.getDamageValue() < stack.getMaxDamage();
		}
		if (stack.has(DataComponents.UNBREAKABLE) || !stack.isDamageableItem()) {
			return true;
		}
		float ignored = ignoredFraction(stack);
		int applied = amount;
		if (ignored > 0.0F) {
			float credit = stack.getOrDefault(CREDIT, 0.0F) + amount * ignored;
			int skipped = (int) Math.floor(credit);
			credit -= skipped;
			if (credit > 0.0F) {
				stack.set(CREDIT, credit);
			} else {
				stack.remove(CREDIT);
			}
			applied = Math.max(0, amount - skipped);
		}
		if (applied > 0) {
			stack.setDamageValue(stack.getDamageValue() + applied);
		}
		return stack.getDamageValue() < stack.getMaxDamage();
	}
}
