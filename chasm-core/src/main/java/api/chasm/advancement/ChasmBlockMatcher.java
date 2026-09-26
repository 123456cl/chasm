package api.chasm.advancement;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * **方块匹配器**（框架能力）—— 数据驱动的"这个方块状态算不算命中"。
 *
 * <h2>它是什么的 1:1 等价物</h2>
 * <p>真实 Tetra 的 {@code se.mickelus.tetra.blocks.PropertyMatcher}
 * （{@code _ref/Tetra-1.20/.../blocks/PropertyMatcher.java:24-113}）：</p>
 * <ul>
 *   <li>JSON 可以写成**字符串**（{@code "tetra:forged_crate"}）或**对象**
 *       （{@code {"block": ...}} / {@code {"tag": ...}} / {@code {"block": ..., "state": {...}}}）；</li>
 *   <li>{@code block} 解析不到时**静默不匹配**（真实 {@code :39-41} 用
 *       {@code ForgeRegistries.BLOCKS.containsKey} 判存在 —— 1.21.1 换成
 *       {@code BuiltInRegistries.BLOCK.get(id) == null}）；</li>
 *   <li>{@code state} 里的属性名/取值非法时**抛 JsonSyntaxException**（真实 {@code :57-68}），
 *       错误文案逐字照抄；</li>
 *   <li>{@code test}：方块相等 → 标签命中 → 每个属性谓词（真实 {@code :88-104}）。</li>
 * </ul>
 *
 * <p>属性谓词表与真实一样是 {@code Property<?> -> Predicate<?>}（真实 {@code :26} 用
 * {@code Maps.newHashMap()}）：数据包路径写 {@code Predicates.equalTo} 等值，
 * 代码路径可用 {@link #where} 挂任意谓词。</p>
 */
public final class ChasmBlockMatcher implements Predicate<BlockState> {

	/** 空匹配器：任何方块状态都命中（真实 {@code PropertyMatcher.any}）。 */
	public static final ChasmBlockMatcher ANY = new ChasmBlockMatcher();

	private final Map<Property<?>, Predicate<?>> propertyPredicates = new LinkedHashMap<>();
	private Block block;
	private TagKey<Block> tag;

	public ChasmBlockMatcher() {
	}

	/**
	 * 从数据包的 JSON 解析（真实 {@code PropertyMatcher.deserialize:30-85}）。
	 *
	 * @param json 字符串或对象；null / JsonNull → {@link #ANY}
	 * @throws JsonSyntaxException 属性名未知或取值非法（与真实同文案）
	 */
	public static ChasmBlockMatcher fromJson(JsonElement json) {
		if (json == null || json.isJsonNull()) {
			return ANY;
		}
		ChasmBlockMatcher result = new ChasmBlockMatcher();
		if (!json.isJsonObject()) {
			// 真实 :73-81 的字符串分支
			result.block = blockOf(json.getAsString());
			return result;
		}
		JsonObject object = json.getAsJsonObject();
		if (object.has("block")) {
			result.block = blockOf(object.get("block").getAsString());
		}
		if (object.has("tag")) {
			ResourceLocation tagId = ResourceLocation.tryParse(object.get("tag").getAsString());
			if (tagId != null) {
				// 1.21.1 的正规构造点（javap 实证：BlockTags 只有常量 + create(String)，注册表键构造走 TagKey.create）
				result.tag = TagKey.create(Registries.BLOCK, tagId);
			}
		}
		if (result.block != null && object.has("state")) {
			StateDefinition<Block, BlockState> definition = result.block.getStateDefinition();
			for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("state").entrySet()) {
				Property<?> property = definition.getProperty(entry.getKey());
				if (property == null) {
					throw new JsonSyntaxException("Unknown block state property '" + entry.getKey() + "' for block '"
						+ result.block.getDescriptionId() + "'");
				}
				String raw = entry.getValue() instanceof JsonPrimitive primitive
					? primitive.getAsString() : String.valueOf(entry.getValue());
				Optional<?> value = property.getValue(raw);
				if (value.isEmpty()) {
					throw new JsonSyntaxException("Invalid block state value '" + raw + "' for property '"
						+ entry.getKey() + "' on block '" + result.block.getDescriptionId() + "'");
				}
				Object held = value.get();
				result.propertyPredicates.put(property, candidate -> held.equals(candidate));
			}
		}
		return result;
	}

	/** 该 id 对应的方块；未注册 → null（真实"静默不匹配"语义）。 */
	private static Block blockOf(String id) {
		ResourceLocation location = ResourceLocation.tryParse(id);
		return location == null ? null : BuiltInRegistries.BLOCK.get(location);
	}

	/** 代码侧追加一条属性谓词（真实 {@code PropertyMatcher.where:110-113}）。 */
	public <V extends Comparable<V>> ChasmBlockMatcher where(Property<V> property, Predicate<? extends V> is) {
		propertyPredicates.put(property, is);
		return this;
	}

	@Override
	public boolean test(BlockState state) {
		if (state == null) {
			return false;
		}
		if (block != null && block != state.getBlock()) {
			return false;
		}
		if (tag != null && !state.is(tag)) {
			return false;
		}
		for (Map.Entry<Property<?>, Predicate<?>> entry : propertyPredicates.entrySet()) {
			if (!matches(state, entry.getKey(), entry.getValue())) {
				return false;
			}
		}
		return true;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static boolean matches(BlockState state, Property<?> property, Predicate<?> predicate) {
		return state.hasProperty(property) && ((Predicate) predicate).test(state.getValue((Property) property));
	}

	/** 是否有任何约束（空 = 恒真）。 */
	public boolean isEmpty() {
		return block == null && tag == null && propertyPredicates.isEmpty();
	}

	/** 回写数据包形状（仅"只有方块"时用字符串形式，与真实反序列化可逆）。 */
	public JsonElement toJson() {
		if (block != null && tag == null && propertyPredicates.isEmpty()) {
			return new JsonPrimitive(BuiltInRegistries.BLOCK.getKey(block).toString());
		}
		JsonObject object = new JsonObject();
		if (block != null) {
			object.addProperty("block", BuiltInRegistries.BLOCK.getKey(block).toString());
		}
		if (tag != null) {
			object.addProperty("tag", tag.location().toString());
		}
		return object;
	}

	@Override
	public String toString() {
		return "ChasmBlockMatcher" + toJson();
	}
}
