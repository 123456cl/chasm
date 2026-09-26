package api.chasm.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Chasm 食物物品：{@link ChasmItem} 的食物子类（第十四步 T-A4）。
 *
 * <p>把食用行为接入原版机制：物品栈携带 {@code DataComponents.FOOD} 组件时，
 * {@code use()} 开始食用、{@code finishUsingItem()} 委托 {@code LivingEntity.eat}
 * 结算营养/效果/余物归还（背包满自动掉落由原版接管）；无 FOOD 组件时回退
 * {@link ChasmItem} 的普通行为（特质/回调），因此「既是食物又能带玩法」。
 *
 * <p>工具提示补充效果行（效果名 + 时长 + 概率），模仿 Aquamirae 的
 * {@code PotionUtils.addPotionTooltip} 展示。</p>
 */
public class ChasmFoodItem extends ChasmItem {

	public ChasmFoodItem(ChasmItemBehavior behavior, Properties properties) {
		super(behavior, properties);
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		FoodProperties food = stack.get(DataComponents.FOOD);
		if (food != null) {
			if (player.canEat(food.canAlwaysEat())) {
				player.startUsingItem(hand);
				return InteractionResultHolder.consume(stack);
			}
			return InteractionResultHolder.fail(stack);
		}
		return super.use(level, player, hand); // 非食物回退 Chasm 行为（特质/回调）
	}

	@Override
	public int getUseDuration(ItemStack stack, LivingEntity entity) {
		FoodProperties food = stack.get(DataComponents.FOOD);
		if (food != null) {
			return food.eatDurationTicks();
		}
		return super.getUseDuration(stack, entity);
	}

	@Override
	public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
		FoodProperties food = stack.get(DataComponents.FOOD);
		if (food != null) {
			return entity.eat(level, stack, food);
		}
		return super.finishUsingItem(stack, level, entity);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
		super.appendHoverText(stack, context, tooltip, flag);
		FoodProperties food = stack.get(DataComponents.FOOD);
		if (food == null || food.effects().isEmpty()) {
			return;
		}
		// 效果工具提示：效果名（时长）[概率%]，紫色系贴近原版药水提示
		for (FoodProperties.PossibleEffect possible : food.effects()) {
			MobEffectInstance inst = possible.effect();
			StringBuilder line = new StringBuilder();
			line.append(Component.translatable(inst.getDescriptionId()).getString());
			line.append(" (").append(formatTicks(inst.getDuration())).append(')');
			if (inst.getAmplifier() > 0) {
				line.append(' ').append(roman(inst.getAmplifier() + 1));
			}
			if (possible.probability() < 1.0f) {
				line.append(' ').append(Math.round(possible.probability() * 100.0f)).append('%');
			}
			tooltip.add(Component.literal(line.toString()).withStyle(ChatFormatting.DARK_PURPLE));
		}
	}

	/** tick → "m:ss"。 */
	private static String formatTicks(int ticks) {
		int seconds = Math.max(1, ticks) / 20;
		return (seconds / 60) + ":" + String.format("%02d", seconds % 60);
	}

	/** 等级 1..n → I/II/III...（超 10 用十进制回退）。 */
	private static String roman(int level) {
		String[] numerals = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
		return level >= 1 && level <= numerals.length ? numerals[level - 1] : String.valueOf(level);
	}
}
