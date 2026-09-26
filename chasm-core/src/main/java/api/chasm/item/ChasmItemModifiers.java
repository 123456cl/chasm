package api.chasm.item;

import api.chasm.log.ChasmLogger;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 物品"动态属性"工具 —— 解决 Apotheosis 这类"词缀/宝石按来源动态加成"的复杂度。
 *
 * <p>原版物品的 {@code ATTRIBUTE_MODIFIERS} 通常是构建期烘焙死的；本工具允许你在运行时把
 * "一组来源的修饰符"增量/整体写回该组件（服务端），使面板、真实属性、工具提示一起跟对。</p>
 *
 * <p><b>关键正确性约束（曾导致存档崩溃，务必遵守）</b>：写入的 Holder 必须是**注册表 Holder**。</p>
 * <ul>
 *   <li>{@code Holder.direct(attribute)} 拿不到 {@code ResourceKey}，{@code ItemStack.save} 会抛
 *       "Unregistered holder in ResourceKey[minecraft:root / minecraft:attribute]" 直接崩存档；
 *       网络同步同样无法编码它（表现为客户端与服务端栈长期不一致、手部物品渲染卡在某个姿势）。</li>
 *   <li>因此本类统一用 {@link #holder(Attribute)} 解析注册表 Holder；解析不到（未注册属性）只记
 *       WARN 并**跳过**该条声明，绝不写 direct Holder。</li>
 * </ul>
 */
public final class ChasmItemModifiers {

	private ChasmItemModifiers() {
	}

	/** 解析注册表 Holder；未注册的属性返回 null（调用方跳过并告警）。 */
	public static Holder<Attribute> holder(Attribute attribute) {
		if (attribute == null) {
			return null;
		}
		return BuiltInRegistries.ATTRIBUTE.getResourceKey(attribute)
			.flatMap(BuiltInRegistries.ATTRIBUTE::getHolder)
			.orElse(null);
	}

	/**
	 * 追加一组属性修饰符（来源用不同 modifierId 区分），并写回栈组件。
	 *
	 * <p>同 id 的旧修饰符会被**替换**而不是叠加——避免"每次重算多出一条"的累积 bug。</p>
	 */
	public static void add(ItemStack stack, List<AttributeDeclaration> declarations) {
		if (declarations == null || declarations.isEmpty()) {
			return;
		}
		write(stack, declarations, Set.of());
	}

	/** 变参便捷。 */
	public static void add(ItemStack stack, AttributeDeclaration... declarations) {
		if (declarations != null && declarations.length > 0) {
			add(stack, List.of(declarations));
		}
	}

	/**
	 * 按**精确修饰符 id** 移除（只摘自己写过的，绝不误伤物品自带或他人来源的修饰符）。
	 *
	 * @return 是否发生了改动
	 */
	public static boolean removeModifiers(ItemStack stack, Collection<ResourceLocation> modifierIds) {
		if (stack == null || stack.isEmpty() || modifierIds == null || modifierIds.isEmpty()) {
			return false;
		}
		ItemAttributeModifiers current = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		Set<ResourceLocation> remove = new LinkedHashSet<>(modifierIds);
		ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();
		boolean changed = false;
		int kept = 0;
		for (ItemAttributeModifiers.Entry m : current.modifiers()) {
			if (remove.contains(m.modifier().id())) {
				changed = true;
				continue;
			}
			builder.add(m.attribute(), m.modifier(), m.slot());
			kept++;
		}
		if (changed) {
			stack.set(DataComponents.ATTRIBUTE_MODIFIERS, kept == 0 ? ItemAttributeModifiers.EMPTY : builder.build());
		}
		return changed;
	}

	/**
	 * 按来源命名空间整体替换（**粗粒度**：会连同该命名空间下物品自带的修饰符一起清掉）。
	 *
	 * <p>仅在你确定该命名空间只属于自己时使用；贡献管线用 {@link #removeModifiers} 精确记账。</p>
	 */
	public static void replaceModifiersWithNamespace(ItemStack stack, String namespace,
		List<AttributeDeclaration> declarations) {
		ItemAttributeModifiers current = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();
		for (ItemAttributeModifiers.Entry m : current.modifiers()) {
			if (!m.modifier().id().getNamespace().equals(namespace)) {
				builder.add(m.attribute(), m.modifier(), m.slot());
			}
		}
		stack.set(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());
		write(stack, declarations, Set.of());
	}

	/** 写入实现：先按 id 剔除被覆盖的旧项，再追加新项；未注册属性跳过。 */
	private static void write(ItemStack stack, List<AttributeDeclaration> declarations,
							  Set<ResourceLocation> alwaysRemove) {
		Set<ResourceLocation> incoming = new LinkedHashSet<>(alwaysRemove);
		List<ItemAttributeModifiers.Entry> toAdd = new ArrayList<>();
		for (AttributeDeclaration d : declarations) {
			Holder<Attribute> holder = holder(d.attribute());
			if (holder == null) {
				ChasmLogger.warn("chasm", "属性 {} 未注册，已跳过修饰符 {}（避免写出无法序列化的 direct Holder）",
					d.attribute(), d.modifierId());
				continue;
			}
			incoming.add(d.modifierId());
			toAdd.add(new ItemAttributeModifiers.Entry(holder,
				new AttributeModifier(d.modifierId(), d.amount(), d.operation()), d.slot()));
		}
		if (toAdd.isEmpty() && incoming.isEmpty()) {
			return;
		}
		ItemAttributeModifiers current = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();
		int kept = 0;
		for (ItemAttributeModifiers.Entry m : current.modifiers()) {
			if (incoming.contains(m.modifier().id())) {
				continue;
			}
			builder.add(m.attribute(), m.modifier(), m.slot());
			kept++;
		}
		for (ItemAttributeModifiers.Entry entry : toAdd) {
			builder.add(entry.attribute(), entry.modifier(), entry.slot());
			kept++;
		}
		stack.set(DataComponents.ATTRIBUTE_MODIFIERS, kept == 0 ? ItemAttributeModifiers.EMPTY : builder.build());
		// 运行时安全阀：外部来源（词缀/宝石/脚本）也可能写出致命攻速，写后统一夹取修复
		api.chasm.attribute.ChasmAttackSpeed.sanitize(stack);
	}

	/** 自检：该栈上是否存在无法序列化的 direct Holder 修饰符（存在即会崩存档）。 */
	public static boolean hasUnregisteredHolder(ItemStack stack) {
		ItemAttributeModifiers mods = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		return mods.modifiers().stream().anyMatch(m -> m.attribute().unwrapKey().isEmpty());
	}
}
