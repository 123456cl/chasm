package api.chasm.template;

import api.chasm.log.ChasmLogger;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 护甲套装模板（第十四步 T-A2，提炼自 Aquamirae AbyssalArmorItem 半套/全套机制）。
 *
 * <p>一套护甲的完整声明收敛为一次 {@code armorSet}：</p>
 * <ul>
 *   <li>材质：各部位防御值、附魔等级、装备音效、修复材料、纹理层、韧性、击退抗性</li>
 *   <li>耐久系数：{@code armorSet(...).durabilityFactor(15)} → 各部位自动派生耐久</li>
 *   <li>半套/全套效果：{@code halfSet(ctx -> ...)} / {@code fullSet(ctx -> ...)}
 *       （每 tick 服务端触发，阈值默认 2 / 4 件）</li>
 * </ul>
 *
 * <p>套装件经 {@code ItemBuilder.armor(set, ArmorItem.Type)} 声明，物品栈携带
 * {@code ARMOR_SET_ID} 组件；{@code ChasmArmorItem} 每 tick 统计穿戴件数并按阈值分发。</p>
 *
 * <p>材质使用 {@link Holder#direct(Object)} 直接持有（1.21.1 材质为内置注册表，
 * 直接值在属性/修复/渲染各路径均可用），免去 DataGen JSON 与注册表冻结时序。</p>
 */
public final class ArmorSetSpec {

	private final ResourceLocation id;
	private final Holder<ArmorMaterial> material;
	private final int durabilityFactor;
	private final int halfCount;
	private final int fullCount;
	private final Consumer<ArmorTickContext> halfSet;
	private final Consumer<ArmorTickContext> fullSet;

	ArmorSetSpec(ResourceLocation id, Holder<ArmorMaterial> material, int durabilityFactor,
		int halfCount, int fullCount, Consumer<ArmorTickContext> halfSet,
		Consumer<ArmorTickContext> fullSet) {
		this.id = id;
		this.material = material;
		this.durabilityFactor = durabilityFactor;
		this.halfCount = halfCount;
		this.fullCount = fullCount;
		this.halfSet = halfSet;
		this.fullSet = fullSet;
	}

	/** 套装注册 id（{@code modId:name}）。 */
	public ResourceLocation id() {
		return id;
	}

	/** 套装材质（防御/附魔/音效/修复/纹理层）。 */
	public Holder<ArmorMaterial> material() {
		return material;
	}

	/** 耐久系数（部位耐久 = {@code Type.getDurability(factor)}）。 */
	public int durabilityFactor() {
		return durabilityFactor;
	}

	/** 半套生效件数阈值（默认 2）。 */
	public int halfCount() {
		return halfCount;
	}

	/** 全套生效件数阈值（默认 4）。 */
	public int fullCount() {
		return fullCount;
	}

	/** 半套效果回调（每 tick 服务端；可为 null）。 */
	public Consumer<ArmorTickContext> halfSet() {
		return halfSet;
	}

	/** 全套效果回调（每 tick 服务端；可为 null）。 */
	public Consumer<ArmorTickContext> fullSet() {
		return fullSet;
	}

	/**
	 * 按穿戴件数分发套装效果（由 {@code ChasmArmorItem} 每 tick 调用，服务端）。
	 *
	 * <p>半套效果在 {@code [halfCount, fullCount)} 区间触发，全套效果在 {@code >= fullCount} 触发。
	 * 整体 try-catch 兜底，异常不影响游戏主线程。</p>
	 *
	 * @param ctx 套装 tick 上下文
	 */
	public void tick(ArmorTickContext ctx) {
		try {
			int count = ctx.pieceCount();
			if (fullSet != null && count >= fullCount) {
				fullSet.accept(ctx);
			} else if (halfSet != null && count >= halfCount) {
				halfSet.accept(ctx);
			}
		} catch (Exception e) {
			ChasmLogger.error(id.getNamespace(), "护甲套装 {} 效果执行异常（件数={}）", id, ctx.pieceCount(), e);
		}
	}

	/** 护甲套装链式构建器：材质槽位 + 半套/全套效果槽位，{@code build()} 后注册到 {@link ChasmTemplates}。 */
	public static final class Builder {

		private final String modId;
		private final String name;
		private final Map<ArmorItem.Type, Integer> defense = new EnumMap<>(ArmorItem.Type.class);
		private int enchantmentValue = 15;
		private Holder<SoundEvent> equipSound = net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_IRON;
		private Ingredient repairIngredient = Ingredient.EMPTY;
		private final List<ArmorMaterial.Layer> layers = new ArrayList<>();
		private float toughness;
		private float knockbackResistance;
		private int durabilityFactor = 15;
		private int halfCount = 2;
		private int fullCount = 4;
		private Consumer<ArmorTickContext> halfSet;
		private Consumer<ArmorTickContext> fullSet;

		Builder(String modId, String name) {
			this.modId = modId;
			this.name = name;
		}

		/** 该部位的护甲值（如头盔 3）。 */
		public Builder defense(ArmorItem.Type type, int amount) {
			defense.put(type, amount);
			return this;
		}

		/** 附魔等级（影响附魔台可用附魔质量，默认 15）。 */
		public Builder enchantmentValue(int value) {
			this.enchantmentValue = value;
			return this;
		}

		/** 装备音效（默认铁质）。 */
		public Builder equipSound(Holder<SoundEvent> sound) {
			this.equipSound = sound;
			return this;
		}

		/** 修复材料（如 {@code Ingredient.of(Items.IRON_INGOT)}）。 */
		public Builder repair(Ingredient ingredient) {
			this.repairIngredient = ingredient;
			return this;
		}

		/** 纹理层（如 {@code ResourceLocation.fromNamespaceAndPath("mymod","abyssal")}，对应
		 *  {@code assets/mymod/textures/models/armor/abyssal_layer_1.png}）。 */
		public Builder layer(ResourceLocation texture) {
			layers.add(new ArmorMaterial.Layer(texture));
			return this;
		}

		/** 韧性（默认 0）。 */
		public Builder toughness(float value) {
			this.toughness = value;
			return this;
		}

		/** 击退抗性（默认 0）。 */
		public Builder knockbackResistance(float value) {
			this.knockbackResistance = value;
			return this;
		}

		/** 耐久系数（部位耐久 = {@code Type.getDurability(factor)}，默认 15）。 */
		public Builder durabilityFactor(int factor) {
			if (factor <= 0) {
				throw new IllegalArgumentException("耐久系数须 > 0: " + factor);
			}
			this.durabilityFactor = factor;
			return this;
		}

		/** 半套生效件数阈值（默认 2）。 */
		public Builder halfCount(int count) {
			this.halfCount = count;
			return this;
		}

		/** 全套生效件数阈值（默认 4）。 */
		public Builder fullCount(int count) {
			this.fullCount = count;
			return this;
		}

		/** 半套效果：穿戴 {@code >= halfCount} 件时每 tick 触发（服务端）。 */
		public Builder halfSet(Consumer<ArmorTickContext> handler) {
			this.halfSet = handler;
			return this;
		}

		/** 全套效果：穿戴 {@code >= fullCount} 件时每 tick 触发（服务端）。 */
		public Builder fullSet(Consumer<ArmorTickContext> handler) {
			this.fullSet = handler;
			return this;
		}

		/** 构建并注册护甲套装，返回可复用的套装规格。 */
		public ArmorSetSpec build() {
			if (defense.isEmpty()) {
				ChasmLogger.warn(modId, "护甲套装 {} 未声明任何部位防御值（材质防御为空）", name);
			}
			Ingredient repair = repairIngredient;
			ArmorMaterial material = new ArmorMaterial(
				Map.copyOf(defense),
				enchantmentValue,
				equipSound,
				() -> repair,
				layers.isEmpty() ? List.of(new ArmorMaterial.Layer(
					ResourceLocation.fromNamespaceAndPath(modId, name))) : List.copyOf(layers),
				toughness,
				knockbackResistance);
			ResourceLocation id = ResourceLocation.fromNamespaceAndPath(modId, name);
			ArmorSetSpec spec = new ArmorSetSpec(id, Holder.direct(material),
				durabilityFactor, halfCount, fullCount, halfSet, fullSet);
			ChasmTemplates.INSTANCE.registerArmorSet(spec);
			ChasmLogger.info(modId, "注册护甲套装 {}（半套 {} 件/全套 {} 件，耐久系数 {}）",
				id, halfCount, fullCount, durabilityFactor);
			return spec;
		}
	}
}
