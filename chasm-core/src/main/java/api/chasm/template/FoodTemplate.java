package api.chasm.template;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.level.ItemLike;

import java.util.ArrayList;
import java.util.List;

/**
 * 食物模板（第十四步 T-A4，提炼自 Aquamirae FoodItem / SEA_STEW / POSEIDONS_BREAKFAST）。
 *
 * <p>把食物的常见槽位收敛成一次声明：营养 / 饱和 / 可否满腹食用 / 快速食用 / 余物 /
 * 效果列表（效果 + 时长 + 等级 + 概率）。</p>
 *
 * <p>声明后经 {@code ItemBuilder.food(template)} 应用：写入原版 {@code DataComponents.FOOD}
 * 组件，食用动画、营养结算、效果概率、余物归还全部由原版机制接管（含背包满时的余物掉落），
 * 无需手写 {@code finishUsingItem}。</p>
 *
 * <pre>{@code
 * Chasm.templates().food()
 *     .nutrition(6).saturation(0.6f)
 *     .remainder(Items.BOWL)
 *     .effect(MobEffects.REGENERATION, 100, 0, 0.5f)
 *     .build();
 * }</pre>
 */
public final class FoodTemplate {

	private final int nutrition;
	private final float saturationMod;
	private final boolean canAlwaysEat;
	private final boolean fast;
	private final ItemLike remainder;
	private final List<FoodEffect> effects;

	FoodTemplate(int nutrition, float saturationMod, boolean canAlwaysEat, boolean fast,
		ItemLike remainder, List<FoodEffect> effects) {
		this.nutrition = nutrition;
		this.saturationMod = saturationMod;
		this.canAlwaysEat = canAlwaysEat;
		this.fast = fast;
		this.remainder = remainder;
		this.effects = List.copyOf(effects);
	}

	/** 营养值（每吃一口恢复的饥饿值点数）。 */
	public int nutrition() {
		return nutrition;
	}

	/** 饱和修正系数（0.6 ≈ 原版常见值）。 */
	public float saturationMod() {
		return saturationMod;
	}

	/** 饥饿条满时是否仍可食用。 */
	public boolean canAlwaysEat() {
		return canAlwaysEat;
	}

	/** 是否快速食用（食用时长 0.6s，原版 {@code fast()}）。 */
	public boolean fast() {
		return fast;
	}

	/** 余物（吃完后返还的物品，如碗；可为 null）。 */
	public ItemLike remainder() {
		return remainder;
	}

	/** 食用效果列表（只读；概率 + 时长 + 等级）。 */
	public List<FoodEffect> effects() {
		return effects;
	}

	/** 单条食用效果声明。 */
	public record FoodEffect(Holder<MobEffect> effect, int durationTicks, int amplifier, float chance) {

		public FoodEffect {
			if (durationTicks <= 0) {
				throw new IllegalArgumentException("效果时长须 > 0: " + durationTicks);
			}
			if (amplifier < 0) {
				throw new IllegalArgumentException("效果等级须 >= 0: " + amplifier);
			}
			if (chance < 0.0f || chance > 1.0f) {
				throw new IllegalArgumentException("效果概率非法: " + chance + "（须在 [0,1]）");
			}
		}
	}

	/** 食物模板链式构建器（无需注册名：食物本质是数据捆绑，不参与运行时注册）。 */
	public static final class Builder {

		private int nutrition = 1;
		private float saturationMod = 0.6f;
		private boolean canAlwaysEat;
		private boolean fast;
		private ItemLike remainder;
		private final List<FoodEffect> effects = new ArrayList<>();

		Builder() {
		}

		/** 营养值。 */
		public Builder nutrition(int nutrition) {
			this.nutrition = nutrition;
			return this;
		}

		/** 饱和修正系数。 */
		public Builder saturation(float saturationMod) {
			this.saturationMod = saturationMod;
			return this;
		}

		/** 饥饿条满时仍可食用。 */
		public Builder alwaysEat() {
			this.canAlwaysEat = true;
			return this;
		}

		/** 快速食用（0.6s）。 */
		public Builder fast() {
			this.fast = true;
			return this;
		}

		/** 余物物品（如 {@code Items.BOWL}）。 */
		public Builder remainder(ItemLike item) {
			this.remainder = item;
			return this;
		}

		/** 食用效果：效果 + 时长(tick) + 等级(0=I) + 概率 [0,1]。 */
		public Builder effect(Holder<MobEffect> effect, int durationTicks, int amplifier, float chance) {
			effects.add(new FoodEffect(effect, durationTicks, amplifier, chance));
			return this;
		}

		/** 构建食物模板。 */
		public FoodTemplate build() {
			return new FoodTemplate(nutrition, saturationMod, canAlwaysEat, fast, remainder, effects);
		}
	}
}
