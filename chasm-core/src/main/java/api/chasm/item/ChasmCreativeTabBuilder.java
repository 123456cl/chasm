package api.chasm.item;

import api.chasm.log.ChasmLogger;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 创造模式标签页构建器（**框架原本完全没有**，本轮为"示例模组能直接拿东西"补上）。
 *
 * <p>最省事的用法是"自动收录"：把该模组命名空间下的**全部物品**（含方块物品）收进一个标签页，
 * 每次打开标签页时实时收集 —— 以后新增物品**不需要回来维护清单**。</p>
 *
 * <pre>{@code
 * Chasm.creativeTab("mymod", "main")
 *     .title("Chasm 示例")
 *     .icon(MANA_SWORD)      // 不设则用收录到的第一个物品
 *     .autoAll()             // 自动收录 mymod: 命名空间下的所有物品
 *     .register();
 * }</pre>
 *
 * <p>注册时机：模组初始化期（注册表冻结前）。注册表已冻结时只记一条 WARN，不中断。</p>
 */
public final class ChasmCreativeTabBuilder {

	private final String modId;
	private final String name;
	private Component title;
	private Supplier<ItemStack> icon;
	private boolean autoAll;
	private final List<Item> extras = new ArrayList<>();
	private final List<Supplier<ItemStack>> variants = new ArrayList<>();

	/** 仅由 {@link api.chasm.Chasm#creativeTab(String, String)} 创建。 */
	public ChasmCreativeTabBuilder(String modId, String name) {
		if (modId == null || modId.isBlank() || name == null || name.isBlank()) {
			throw new IllegalArgumentException("标签页需要非空的 modId 与 name");
		}
		this.modId = modId;
		this.name = name;
	}

	/** 标签页标题（不用则用 name）。 */
	public ChasmCreativeTabBuilder title(String title) {
		this.title = Component.literal(title);
		return this;
	}

	/** 图标（用某个物品）。 */
	public ChasmCreativeTabBuilder icon(Item item) {
		this.icon = () -> item == null ? ItemStack.EMPTY : new ItemStack(item);
		return this;
	}

	/** 图标（自定义物品栈）。 */
	public ChasmCreativeTabBuilder icon(Supplier<ItemStack> supplier) {
		this.icon = supplier;
		return this;
	}

	/** 自动收录该模组命名空间下的全部物品（含方块物品）；实时收集，零维护。 */
	public ChasmCreativeTabBuilder autoAll() {
		this.autoAll = true;
		return this;
	}

	/** 额外显式追加物品（与 autoAll 可叠加，自动去重）。 */
	public ChasmCreativeTabBuilder add(Item... items) {
		for (Item item : items) {
			if (item != null && item != Items.AIR) {
				extras.add(item);
			}
		}
		return this;
	}

	/**
	 * 追加**变体栈**（同一物品的不同组件状态）：宝石 × 各纯度、附魔书 × 各等级这类内容，
	 * 以前只能显示一个"基础栈"。实时求值，每次打开标签页都按最新数据生成。
	 */
	public ChasmCreativeTabBuilder variant(Supplier<ItemStack> supplier) {
		if (supplier != null) {
			variants.add(supplier);
		}
		return this;
	}

	/** 追加一组变体栈。 */
	@SafeVarargs
	public final ChasmCreativeTabBuilder variants(Supplier<ItemStack>... suppliers) {
		for (Supplier<ItemStack> supplier : suppliers) {
			variant(supplier);
		}
		return this;
	}

	/** 该标签页当前会收录的物品栈（按物品 id 排序，稳定可预期）。 */
	public List<ItemStack> collected() {
		Set<Item> items = new LinkedHashSet<>();
		if (autoAll) {
			items.addAll(itemsOfNamespace(modId));
		}
		items.addAll(extras);
		List<ItemStack> out = new ArrayList<>();
		for (Item item : items) {
			out.add(new ItemStack(item));
		}
		// 变体栈（同一物品的不同组件状态：宝石 × 纯度、附魔书 × 等级……）
		// 必须在这里并入：displayItems 与 icon 都走 collected()，否则变体永远不显示。
		for (Supplier<ItemStack> supplier : variants) {
			ItemStack stack = supplier.get();
			if (stack != null && !stack.isEmpty()) {
				out.add(stack);
			}
		}
		return out;
	}

	/**
	 * 注册标签页（模组初始化期调用；注册表已冻结时只记 WARN）。
	 *
	 * @return 构建出的标签页（未注册成功也返回实例，便于测试/调试）
	 */
	public CreativeModeTab register() {
		ResourceLocation id = ResourceLocation.fromNamespaceAndPath(modId, name);
		Supplier<ItemStack> iconSupplier = icon != null ? icon : () -> {
			List<ItemStack> all = collected();
			return all.isEmpty() ? ItemStack.EMPTY : all.get(0);
		};
		CreativeModeTab tab = CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
			.title(title != null ? title : Component.literal(name))
			.icon(iconSupplier)
			.displayItems((params, output) -> {
				for (ItemStack stack : collected()) {
					output.accept(stack);
				}
			})
			.build();
		try {
			Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, id, tab);
			ChasmLogger.info(modId, "注册创造模式标签页 {}（{}）", id,
				autoAll ? "自动收录命名空间 " + modId + " 的全部物品（打开时实时收集）"
					: "显式 " + extras.size() + " 个物品");
		} catch (IllegalStateException frozen) {
			ChasmLogger.warn(modId, "创造模式标签页 {} 注册被跳过（注册表已冻结，DataGen 期属正常）", id);
		}
		return tab;
	}

	/**
	 * 某命名空间下的全部物品（按 id 排序）—— 纯查询，可用于图鉴/聚合，也可单测。
	 *
	 * @param namespace 命名空间（如 {@code mymod}）
	 */
	public static List<Item> itemsOfNamespace(String namespace) {
		List<Item> out = new ArrayList<>();
		for (var entry : BuiltInRegistries.ITEM.entrySet()) {
			if (entry.getKey().location().getNamespace().equals(namespace)) {
				out.add(entry.getValue());
			}
		}
		out.sort(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).getPath()));
		return out;
	}
}