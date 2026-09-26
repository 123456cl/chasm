package api.chasm.enchantment;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 附魔声明：一个自定义附魔的静态期安全引用 + DataGen 元数据。
 *
 * <p>1.21.1 的 {@link Enchantment} 是数据驱动的动态注册表 record，静态期无法直接拿到
 * {@code Holder<Enchantment>}（注册表在 datapack 加载后才填充）。因此本声明持有
 * {@link ResourceKey} 作为"类型安全引用"，其中还携带生成附魔 JSON 所需的全部元数据
 * （weight/maxLevel/min_max 成本/slots/宝藏标记）。运行时经 {@link ChasmEnchantments} 解析为
 * {@code Holder<Enchantment>}。</p>
 *
 * <p>DataGen 自动把每个声明输出成 {@code data/<ns>/enchantment/<path>.json}，从而让自定义附魔
 * 真正进入动态注册表，可被附魔/应用/保存。</p>
 */
public final class EnchantmentDeclaration {

	private final ResourceKey<Enchantment> key;
	private final int weight;
	private final int maxLevel;
	private final int anvilCost;
	private final boolean treasure;
	private final int minCostBase;
	private final int minCostPerLevel;
	private final int maxCostBase;
	private final int maxCostPerLevel;
	private final List<String> slots;
	/** 附魔显示名（DataGen 自动生成 lang 条目；未声明时按注册名人性化派生）。 */
	private final String displayName;
	/** 附魔描述（DataGen 自动生成 {@code ...desc} lang 条目；可空）。 */
	private final String displayDescription;

	EnchantmentDeclaration(ResourceKey<Enchantment> key, int weight, int maxLevel, int anvilCost,
		boolean treasure, int minCostBase, int minCostPerLevel, int maxCostBase, int maxCostPerLevel,
		List<String> slots, String displayName, String displayDescription) {
		this.key = key;
		this.weight = weight;
		this.maxLevel = maxLevel;
		this.anvilCost = anvilCost;
		this.treasure = treasure;
		this.minCostBase = minCostBase;
		this.minCostPerLevel = minCostPerLevel;
		this.maxCostBase = maxCostBase;
		this.maxCostPerLevel = maxCostPerLevel;
		this.slots = Collections.unmodifiableList(new ArrayList<>(slots));
		this.displayName = displayName;
		this.displayDescription = displayDescription;
	}

	/** 附魔注册键（静态期安全引用）。 */
	public ResourceKey<Enchantment> key() {
		return key;
	}

	/** 最大等级。 */
	public int maxLevel() {
		return maxLevel;
	}

	/** 出现权重。 */
	public int weight() {
		return weight;
	}

	/** 是否宝藏附魔（默认 false）。 */
	public boolean treasure() {
		return treasure;
	}

	/** 该附魔的 supported_items 标签（{@code ns:enchantable/<path>}），DataGen 自动聚合绑定它的类型物品。 */
	public TagKey<Item> supportedTag() {
		return ChasmEnchantments.supportedItemsTag(key);
	}

	/** 便捷：注册键的 location（ns:name）。 */
	public ResourceLocation location() {
		return key.location();
	}

	/** 附魔名翻译键（{@code enchantment.<ns>.<path>}，DataGen 自动生成 lang 条目）。 */
	public String translationKey() {
		return "enchantment." + key.location().toLanguageKey();
	}

	/** 附魔描述翻译键（{@code enchantment.<ns>.<path>.desc}，DataGen 自动生成 lang 条目）。 */
	public String descTranslationKey() {
		return translationKey() + ".desc";
	}

	/** 附魔显示名（未声明时由注册名人性化派生）。 */
	public String displayName() {
		return displayName;
	}

	/** 附魔描述（可为空；为空时不生成 desc lang 条目）。 */
	public String displayDescription() {
		return displayDescription;
	}

	/**
	 * 序列化为 1.21.1 数据驱动 {@code Enchantment} JSON（datapack 格式）。
	 *
	 * <p>字段：description / supported_items / weight / max_level / min_cost / max_cost / anvil_cost / slots。</p>
	 */
	public JsonObject toJson() {
		JsonObject obj = new JsonObject();
		obj.add("description", translate(translationKey()));
		obj.addProperty("supported_items", "#" + supportedTag().location());
		obj.addProperty("weight", weight);
		obj.addProperty("max_level", maxLevel);
		obj.add("min_cost", cost(minCostBase, minCostPerLevel));
		obj.add("max_cost", cost(maxCostBase, maxCostPerLevel));
		obj.addProperty("anvil_cost", anvilCost);
		obj.add("slots", slots(slots));
		return obj;
	}

	private static JsonObject translate(String key) {
		JsonObject o = new JsonObject();
		o.addProperty("translate", key);
		return o;
	}

	private static JsonObject cost(int base, int perLevel) {
		JsonObject o = new JsonObject();
		o.addProperty("base", base);
		o.addProperty("per_level_above_first", perLevel);
		return o;
	}

	private static JsonArray slots(List<String> slotGroups) {
		JsonArray arr = new JsonArray();
		for (String s : slotGroups) {
			arr.add(s);
		}
		return arr;
	}

	@Override
	public String toString() {
		return "EnchantmentDeclaration[" + key.location() + ", lvl<= " + maxLevel + ", weight=" + weight + "]";
	}
}