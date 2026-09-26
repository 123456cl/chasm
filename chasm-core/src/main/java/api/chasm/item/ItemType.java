package api.chasm.item;

import api.chasm.enchantment.EnchantmentConfig;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 物品类型（ItemType）：一类物品的公共语义契约。
 *
 * <p>类型不是"继承哪个类"，而是一份模板，声明一次、多处复用：</p>
 * <ul>
 *   <li>{@code factory} —— 原版类映射（普通物品/剑/斧…），决定横扫、挖掘、附魔池等语义</li>
 *   <li>{@code defaultAttributes} —— 默认属性模板（攻击力/攻速/护甲…），每个该类型物品自动携带</li>
 *   <li>{@code tags} —— 自动加入的标签（如 {@code chasm:staffs}、{@code c:swords}），供跨模组识别</li>
 * </ul>
 *
 * <p>通过 {@code Chasm.types()} 注册/查询；物品用 {@code Chasm.item().type(...)} 复用。</p>
 */
public final class ItemType {

	private final ResourceLocation id;
	private final ChasmItemFactory factory;
	private final List<AttributeDeclaration> defaultAttributes;
	private final Set<String> tags;
	private final Map<ResourceKey<Enchantment>, EnchantmentConfig> enchantmentPool;

	ItemType(ResourceLocation id, ChasmItemFactory factory,
		List<AttributeDeclaration> defaultAttributes, Set<String> tags,
		Map<ResourceKey<Enchantment>, EnchantmentConfig> enchantmentPool) {
		this.id = id;
		this.factory = factory;
		this.defaultAttributes = Collections.unmodifiableList(new ArrayList<>(defaultAttributes));
		this.tags = Collections.unmodifiableSet(new LinkedHashSet<>(tags));
		// 附魔池保留可变：物品声明可经 ItemBuilder.enchantable() 在注册期追加成员。
		this.enchantmentPool = new LinkedHashMap<>(enchantmentPool);
	}

	/** 类型全局唯一 id（{@code ns:name}）。 */
	public ResourceLocation id() {
		return id;
	}

	/** 该类型的原版类映射工厂。 */
	public ChasmItemFactory factory() {
		return factory;
	}

	/** 默认属性模板（该类型的每个物品自动携带，物品自身声明可覆盖同 id 修饰符）。 */
	public List<AttributeDeclaration> defaultAttributes() {
		return defaultAttributes;
	}

	/** 该类型自动加入的标签。 */
	public Set<String> tags() {
		return tags;
	}

	/**
	 * 该类型的附魔池：{@code 附魔键 → 附魔池配置}。
	 *
	 * <p>附魔台上的物品若属于本类型，则会按这些配置刷出专属附魔（含自定义附魔）。
	 * 为空集合表示无专属附魔池（沿用原版标签逻辑，如剑类附魔）。</p>
	 */
	public Map<ResourceKey<Enchantment>, EnchantmentConfig> enchantmentPool() {
		return Collections.unmodifiableMap(enchantmentPool);
	}

	/** 追加一条附魔池配置（供 {@code ItemBuilder.enchantable()} 在物品注册期扩展类型附魔池）。 */
	public void addEnchantmentToPool(EnchantmentConfig config) {
		enchantmentPool.put(config.key(), config);
	}

	/** 是否声明了专属附魔池。 */
	public boolean hasEnchantmentPool() {
		return !enchantmentPool.isEmpty();
	}

	/** 创建类型构建器（用 {@code Chasm.types().register(ns,name)} 亦可，返回同一 builder）。 */
	public static Builder builder(ResourceLocation id, ChasmItemFactory factory) {
		return new Builder(id, factory);
	}

	/** 创建类型构建器（便捷重载）。 */
	public static Builder builder(String modId, String name, ChasmItemFactory factory) {
		return builder(ResourceLocation.fromNamespaceAndPath(modId, name), factory);
	}

	/** ItemType 声明式构建器。 */
	public static final class Builder {

		private final ResourceLocation id;
		private final ChasmItemFactory factory;
		private final List<AttributeDeclaration> defaultAttributes = new ArrayList<>();
		private final Set<String> tags = new LinkedHashSet<>();
		private final Map<ResourceKey<Enchantment>, EnchantmentConfig> enchantmentPool = new LinkedHashMap<>();
		/** build() 完成后的登记回调（框架内部用于把类型写入 ChasmTypes 注册表）。 */
		private java.util.function.Consumer<ItemType> onBuild;

		Builder(ResourceLocation id, ChasmItemFactory factory) {
			this.id = id;
			this.factory = factory;
		}

		/** 设定 build() 完成后的回调（供 ChasmTypes.register 自动登记，包内调用）。 */
		Builder onBuild(java.util.function.Consumer<ItemType> consumer) {
			this.onBuild = consumer;
			return this;
		}

		/**
		 * 声明一条默认属性（ADD_VALUE，指定生效槽位组）。
		 *
		 * @param attribute 目标属性
		 * @param amount    修饰符数值
		 * @param slot      生效槽位组
		 */
		public Builder defaultAttribute(Attribute attribute, float amount, EquipmentSlotGroup slot) {
			return defaultAttribute(attribute,
				ResourceLocation.fromNamespaceAndPath("chasm",
					"type_" + attributeId(attribute) + "_" + defaultAttributes.size()),
				amount, AttributeModifier.Operation.ADD_VALUE, slot);
		}

		/** 声明一条默认属性（ADD_VALUE，主手槽位）。 */
		public Builder defaultAttribute(Attribute attribute, float amount) {
			return defaultAttribute(attribute, amount, EquipmentSlotGroup.MAINHAND);
		}

		/** 声明一条默认属性（完整参数：自定义修饰符 id 与运算）。 */
		public Builder defaultAttribute(Attribute attribute, ResourceLocation modifierId, float amount,
			AttributeModifier.Operation operation, EquipmentSlotGroup slot) {
			int idx = existingIndex(modifierId);
			if (idx >= 0) {
				// 同 id 默认属性直接覆盖，避免重复累加
				defaultAttributes.set(idx, new AttributeDeclaration(attribute, modifierId, amount, operation, slot));
			} else {
				defaultAttributes.add(new AttributeDeclaration(attribute, modifierId, amount, operation, slot));
			}
			return this;
		}

		/** 加入一个标签（类型自动打入）。 */
		public Builder tag(String tag) {
			tags.add(tag);
			return this;
		}

		/** 批量加入标签。 */
		public Builder tags(String... additional) {
			for (String t : additional) {
				tags.add(t);
			}
			return this;
		}

		/** 声明该类型专属附魔池的一项（非宝藏，可经附魔台获得）。 */
		public Builder enchantment(api.chasm.enchantment.EnchantmentDeclaration decl,
			int minLevel, int maxLevel, int weight) {
			return enchantment(decl.key(), minLevel, maxLevel, weight, false);
		}

		/** 声明该类型专属附魔池的一项（经注册键）。 */
		public Builder enchantment(ResourceKey<Enchantment> ench, int minLevel, int maxLevel, int weight) {
			return enchantment(ench, minLevel, maxLevel, weight, false);
		}

		/** 声明该类型专属附魔池的一项（宝藏：仅战利品，不进附魔台）。 */
		public Builder enchantment(ResourceKey<Enchantment> ench, int minLevel, int maxLevel,
			int weight, boolean treasureOnly) {
			enchantmentPool.put(ench, new EnchantmentConfig(ench, minLevel, maxLevel, weight, treasureOnly));
			return this;
		}

		/** 构建不可变 ItemType（若设置了 onBuild 回调，会在登记后一并触发）。 */
		public ItemType build() {
			ItemType type = new ItemType(id, factory, defaultAttributes, tags, enchantmentPool);
			if (onBuild != null) {
				onBuild.accept(type);
			}
			return type;
		}

		private int existingIndex(ResourceLocation modifierId) {
			for (int i = 0; i < defaultAttributes.size(); i++) {
				if (defaultAttributes.get(i).modifierId().equals(modifierId)) {
					return i;
				}
			}
			return -1;
		}

		private static String attributeId(Attribute attribute) {
			String s = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.getKey(attribute));
			int idx = s.indexOf(':');
			return idx >= 0 ? s.substring(idx + 1) : s;
		}
	}
}