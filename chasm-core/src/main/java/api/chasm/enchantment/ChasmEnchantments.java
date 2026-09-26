package api.chasm.enchantment;

import api.chasm.log.ChasmLogger;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自定义附魔注册表（data-driven）。
 *
 * <p>1.21.1 附魔为动态注册表，任何自定义附魔都必须产生对应的 {@code data/<ns>/enchantment/*.json}
 * 才能被加载。本注册表有两种角色：</p>
 * <ul>
 *   <li><b>声明</b>：{@link #register(String, String)} 得到 {@link EnchantmentBuilder}，链式配置后
 *       {@code .build()} 生成 {@link EnchantmentDeclaration}（静态期安全引用），并登记到本注册表；
 *       DataGen 据此自动输出附魔 JSON。</li>
 *   <li><b>运行时解析</b>：诚征服务端 {@link RegistryAccess} 冻结后的附魔注册表，把
 *       {@link ResourceKey} 解析为 {@code Holder<Enchantment>}，供自带附魔（{@code ItemBuilder.enchant}）
 *       与附魔台注入（{@code EnchantmentMenuMixin}）使用。</li>
 * </ul>
 *
 * <p>用法：</p>
 * <pre>{@code
 * public static final EnchantmentDeclaration MANA_VAMPIRE =
 *     Chasm.enchantments().register("mymod", "mana_vampire")
 *         .weight(10).maxLevel(3)
 *         .slot("mainhand")
 *         .build();
 * }</pre>
 */
public final class ChasmEnchantments {

	/** 全局单例。 */
	public static final ChasmEnchantments INSTANCE = new ChasmEnchantments();

	private final Map<ResourceKey<Enchantment>, EnchantmentDeclaration> declarations = new ConcurrentHashMap<>();
	/** 服务端注册表冻结后可用的附魔注册表引用（null = 尚未冻结，例如静态/DataGen 期）。 */
	private volatile Registry<Enchantment> enchantmentRegistry;

	private ChasmEnchantments() {
		// 注册冻结时机：服务端启动（datapack 已加载、附魔注册表已填充）
		ServerLifecycleEvents.SERVER_STARTING.register(server ->
			capture(server.registryAccess()));
	}

	/** 捕获给定 RegistryAccess 的附魔注册表（供自带附魔/其他场景解析 Holder）。 */
	public void capture(RegistryAccess access) {
		this.enchantmentRegistry = access.registryOrThrow(Registries.ENCHANTMENT);
	}

	/** 已捕获的附魔注册表（可能为 null）。 */
	public Registry<Enchantment> registry() {
		return enchantmentRegistry;
	}

	/**
	 * 开始声明一个自定义附魔，返回构建器。
	 *
	 * @param modId 命名空间（如 "mymod"）
	 * @param name  附魔注册名（如 "mana_vampire"）
	 */
	public EnchantmentBuilder register(String modId, String name) {
		ResourceKey<Enchantment> key = ResourceKey.create(Registries.ENCHANTMENT,
			ResourceLocation.fromNamespaceAndPath(modId, name));
		return new EnchantmentBuilder(key, this);
	}

	/** 声明完成回调（由 Builder.build 调用）：登记并输出日志。 */
	void registerInternal(EnchantmentDeclaration decl) {
		declarations.put(decl.key(), decl);
		ResourceLocation id = decl.key().location();
		ChasmLogger.info(id.getNamespace(), "注册自定义附魔 {} (max_level={}, weight={}, treasure={})",
			id, decl.maxLevel(), decl.weight(), decl.treasure());
	}

	/** 全部已声明附魔（DataGen 遍历用）。 */
	public Collection<EnchantmentDeclaration> allDeclarations() {
		return Collections.unmodifiableCollection(declarations.values());
	}

	/** 按注册键查询声明。 */
	public Optional<EnchantmentDeclaration> get(ResourceKey<Enchantment> key) {
		return Optional.ofNullable(declarations.get(key));
	}

	/** 当前已声明附魔总数。 */
	public int size() {
		return declarations.size();
	}

	/** 是否已声明该附魔。 */
	public boolean isRegistered(ResourceKey<Enchantment> key) {
		return declarations.containsKey(key);
	}

	/**
	 * 把一个附魔注册键解析为 {@code Holder<Enchantment>}。
	 *
	 * <p>需要 {@link RegistryAccess} 已冻结（服务端启动后）；否则返回 empty，
	 * 可用于 DataGen/静态期安全跳过。</p>
	 */
	public Optional<Holder.Reference<Enchantment>> holderFor(ResourceKey<Enchantment> key) {
		Registry<Enchantment> reg = enchantmentRegistry;
		if (reg == null) {
			return Optional.empty();
		}
		return reg.getHolder(key);
	}

	/**
	 * 某个附魔的 supported_items 标签：{@code <ns>:enchantable/<path>}。
	 *
	 * <p>DataGen 会把它引用的注册表条目（同命名空间的物品）聚合进该标签，从而让原版附魔台
	 * 对这类物品能识别该附魔；附魔 JSON 的 supported_items 指向此标签。</p>
	 */
	public static TagKey<Item> supportedItemsTag(ResourceKey<Enchantment> enchantKey) {
		ResourceLocation loc = enchantKey.location();
		return TagKey.create(Registries.ITEM,
			ResourceLocation.fromNamespaceAndPath(loc.getNamespace(), "enchantable/" + loc.getPath()));
	}

	/**
	 * 附魔声明构建器（链式）。
	 */
	public static final class EnchantmentBuilder {

		private final ResourceKey<Enchantment> key;
		private final ChasmEnchantments owner;
		private int weight = 10;
		private int maxLevel = 3;
		private int anvilCost = 1;
		private boolean treasure;
		private int minCostBase = 1;
		private int minCostPerLevel = 10;
		private int maxCostBase = 6;
		private int maxCostPerLevel = 10;
		/** 生效槽位组（按插入序去重，避免重复调用 slot() 产生重复 JSON）。 */
		private final java.util.LinkedHashSet<String> slots = new java.util.LinkedHashSet<>(List.of("mainhand"));
		/** 附魔显示名（未声明时按注册名人性化派生，DataGen 自动生成 lang 条目）。 */
		private String displayName;
		/** 附魔描述（可空；为空时不生成 desc lang 条目）。 */
		private String displayDescription;

		EnchantmentBuilder(ResourceKey<Enchantment> key, ChasmEnchantments owner) {
			this.key = key;
			this.owner = owner;
		}

		/**
		 * 附魔显示名（DataGen 自动写入 {@code enchantment.<ns>.<path>} lang 键）。
		 * 未声明时按注册名人性化派生（如 {@code mana_vampire} → {@code Mana Vampire}）。
		 */
		public EnchantmentBuilder name(String name) {
			this.displayName = name;
			return this;
		}

		/**
		 * 附魔描述（DataGen 自动写入 {@code enchantment.<ns>.<path>.desc} lang 键）。
		 * 可空；空串不生成 desc 条目。
		 */
		public EnchantmentBuilder description(String description) {
			this.displayDescription = description;
			return this;
		}

		/** 出现权重（默认 10）。 */
		public EnchantmentBuilder weight(int weight) {
			this.weight = weight;
			return this;
		}

		/** 最大等级（默认 3）。 */
		public EnchantmentBuilder maxLevel(int maxLevel) {
			this.maxLevel = maxLevel;
			return this;
		}

		/** 铁砧消耗等级系数（默认 1）。 */
		public EnchantmentBuilder anvilCost(int anvilCost) {
			this.anvilCost = anvilCost;
			return this;
		}

		/** 标记为宝藏附魔（仅战利品，默认 false）。 */
		public EnchantmentBuilder treasure() {
			this.treasure = true;
			return this;
		}

		/** 设置 min_cost 成本（base + 每级增量）。 */
		public EnchantmentBuilder minCost(int base, int perLevelAboveFirst) {
			this.minCostBase = base;
			this.minCostPerLevel = perLevelAboveFirst;
			return this;
		}

		/** 设置 max_cost 成本（base + 每级增量）。 */
		public EnchantmentBuilder maxCost(int base, int perLevelAboveFirst) {
			this.maxCostBase = base;
			this.maxCostPerLevel = perLevelAboveFirst;
			return this;
		}

		/** 添加一个生效槽位组（默认 ["mainhand"]）。 */
		public EnchantmentBuilder slot(String slot) {
			this.slots.add(slot);
			return this;
		}

		/** 批量添加生效槽位组。 */
		public EnchantmentBuilder slots(String... slotGroups) {
			for (String s : slotGroups) {
				this.slots.add(s);
			}
			return this;
		}

		/** 构建声明并登记到注册表。 */
		public EnchantmentDeclaration build() {
			String name = displayName != null && !displayName.isEmpty()
				? displayName : humanize(key.location().getPath());
			EnchantmentDeclaration decl = new EnchantmentDeclaration(key, weight, maxLevel, anvilCost,
				treasure, minCostBase, minCostPerLevel, maxCostBase, maxCostPerLevel, new ArrayList<>(slots),
				name, displayDescription);
			owner.registerInternal(decl);
			return decl;
		}

		/** 注册名人性化：{@code mana_vampire} → {@code Mana Vampire}。 */
		private static String humanize(String path) {
			StringBuilder sb = new StringBuilder();
			for (String part : path.split("_")) {
				if (part.isEmpty()) {
					continue;
				}
				if (sb.length() > 0) {
					sb.append(' ');
				}
				sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
			}
			return sb.length() == 0 ? path : sb.toString();
		}
	}
}