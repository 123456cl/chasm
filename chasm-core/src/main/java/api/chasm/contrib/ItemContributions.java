package api.chasm.contrib;

import api.chasm.data.ChasmData;
import api.chasm.item.AttributeDeclaration;
import api.chasm.item.ChasmItemModifiers;
import api.chasm.item.event.ChasmItemEvents;
import api.chasm.log.ChasmLogger;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 贡献管线执行器：把"来源列表"**编译成**栈上的实际行为与属性。
 *
 * <p>三步模型（对应目标里的 ②"分桶 + 变更时编译快照"）：</p>
 * <ol>
 *   <li><b>真源</b>：{@link ChasmData#CONTRIBUTORS}（栈组件，有序来源 id 列表）。词缀/宝石/套装
 *       等系统只改这里，不直接碰事件桶与属性组件。</li>
 *   <li><b>置脏</b>：任何变更调 {@link #markDirty(ItemStack)}；物品在背包批量刷新时用
 *       {@link #flush(ItemStack)} / {@link #rebuildAll(Iterable)}，一次编译覆盖多次变更
 *       （避免"每加一个词缀重算一次属性"的抖动）。</li>
 *   <li><b>编译快照</b>：{@link #rebuild(ItemStack)} 产出——事件处理器**按事件分桶**写回
 *       {@link ChasmData#EVENT_HANDLERS}（含物品默认处理器 + 各来源声明），属性按来源命名空间
 *       物化进 ATTRIBUTE_MODIFIERS（用 {@link ChasmItemModifiers#replaceModifiersWithNamespace}
 *       保证"来源移除后无残留"），并把版本号 +1 写入 {@link ChasmData#CONTRIB_REV}。</li>
 * </ol>
 *
 * <p>幂等 / 可复现：{@code rebuild} 从"真源 + 物品默认"重新算出全部结果，不累加历史状态，
 * 因此重复调用结果一致（存档、复制物品、联机同步都安全）。</p>
 */
public final class ItemContributions {

	private ItemContributions() {
	}

	// ------------------------------------------------------------ 真源读写

	/** 当前来源列表（只读，有序）。 */
	public static List<ResourceLocation> sources(ItemStack stack) {
		List<ResourceLocation> list = stack.get(ChasmData.CONTRIBUTORS);
		return list == null ? List.of() : list;
	}

	/** 是否已挂载该来源。 */
	public static boolean has(ItemStack stack, ResourceLocation contributorId) {
		return sources(stack).contains(contributorId);
	}

	/** 挂载来源（幂等）；成功追加会置脏，等待编译。 */
	public static boolean attach(ItemStack stack, ResourceLocation contributorId) {
		if (contributorId == null || has(stack, contributorId)) {
			return false;
		}
		List<ResourceLocation> next = new ArrayList<>(sources(stack));
		next.add(contributorId);
		stack.set(ChasmData.CONTRIBUTORS, List.copyOf(next));
		markDirty(stack);
		return true;
	}

	/** 移除来源（幂等）；移除后置脏，编译时会清掉其属性与事件桶。 */
	public static boolean detach(ItemStack stack, ResourceLocation contributorId) {
		List<ResourceLocation> current = sources(stack);
		if (contributorId == null || !current.contains(contributorId)) {
			return false;
		}
		List<ResourceLocation> next = new ArrayList<>(current);
		next.remove(contributorId);
		if (next.isEmpty()) {
			stack.remove(ChasmData.CONTRIBUTORS);
		} else {
			stack.set(ChasmData.CONTRIBUTORS, List.copyOf(next));
		}
		markDirty(stack);
		return true;
	}

	/** 清空全部来源（置脏）。 */
	public static void clear(ItemStack stack) {
		if (!sources(stack).isEmpty()) {
			stack.remove(ChasmData.CONTRIBUTORS);
			markDirty(stack);
		}
	}

	// ------------------------------------------------------------ 脏标记

	/** 标记为脏（变更时调用；编译前多次标记只编译一次）。 */
	public static void markDirty(ItemStack stack) {
		if (stack != null && !stack.isEmpty()) {
			stack.set(ChasmData.CONTRIB_DIRTY, Boolean.TRUE);
		}
	}

	public static boolean isDirty(ItemStack stack) {
		return Boolean.TRUE.equals(stack.get(ChasmData.CONTRIB_DIRTY));
	}

	/** 贡献快照版本（每次编译 +1；0 表示从未编译）。 */
	public static int revision(ItemStack stack) {
		return stack.getOrDefault(ChasmData.CONTRIB_REV, 0);
	}

	// ------------------------------------------------------------ 编译

	/** 脏则编译（背包/容器批量刷新入口）。 */
	public static void flush(ItemStack stack) {
		if (isDirty(stack)) {
			rebuild(stack);
		}
	}

	/** 批量刷新（返回实际编译的栈数量）。 */
	public static int rebuildAll(Iterable<ItemStack> stacks) {
		int count = 0;
		if (stacks == null) {
			return 0;
		}
		for (ItemStack stack : stacks) {
			if (stack != null && !stack.isEmpty() && isDirty(stack)) {
				rebuild(stack);
				count++;
			}
		}
		return count;
	}

	/**
	 * 编译快照：从真源 + 物品默认重新算出事件桶与属性物化结果。
	 *
	 * <p>任何异常都只记日志并保留旧状态（不让一次坏贡献者崩溃存档/背包刷新）。</p>
	 */
	public static void rebuild(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return;
		}
		try {
			List<ResourceLocation> sourceIds = sources(stack);

			// 1) 事件桶：先摘掉"上一次编译写入的"处理器（精确所有权），保留手工挂载 + 物品默认，
			//    再叠加本次各来源声明 —— 因此来源移除不会残留，手工挂载也永不被冲掉。
			Map<ResourceLocation, List<ResourceLocation>> buckets = existingBuckets(stack);
			List<ResourceLocation> previouslyWritten = stack.get(ChasmData.CONTRIB_HANDLER_IDS);
			if (previouslyWritten != null) {
				for (List<ResourceLocation> handlers : buckets.values()) {
					handlers.removeAll(previouslyWritten);
				}
				buckets.values().removeIf(List::isEmpty);
			}
			mergeInto(buckets, defaultBuckets(stack));
			List<ResourceLocation> written = new ArrayList<>();
			Map<ResourceLocation, AttributeDeclaration> attributesById = new LinkedHashMap<>();
			List<String> unknown = new ArrayList<>();
			for (ResourceLocation sourceId : sourceIds) {
				ItemContributor contributor = ChasmContributors.get(sourceId);
				if (contributor == null) {
					unknown.add(sourceId.toString());
					continue;
				}
				contributor.contributeHandlers(stack, (event, handlerId) -> {
					buckets.computeIfAbsent(event.id(), k -> new ArrayList<>()).add(handlerId);
					written.add(handlerId);
				});
				// 属性按**精确修饰符 id** 记账（不用命名空间：宝石贡献者常与物品同命名空间，
				// 按命名空间清空会误删物品自带的攻击力/攻速修饰符）
				contributor.contributeAttributes(stack, declaration ->
					attributesById.put(declaration.modifierId(), declaration));
			}
			if (written.isEmpty()) {
				stack.remove(ChasmData.CONTRIB_HANDLER_IDS);
			} else {
				stack.set(ChasmData.CONTRIB_HANDLER_IDS, List.copyOf(written));
			}
			if (buckets.isEmpty()) {
				stack.remove(ChasmData.EVENT_HANDLERS);
			} else {
				Map<ResourceLocation, List<ResourceLocation>> copy = new LinkedHashMap<>();
				buckets.forEach((eventId, handlers) -> copy.put(eventId, List.copyOf(handlers)));
				stack.set(ChasmData.EVENT_HANDLERS, java.util.Collections.unmodifiableMap(copy));
			}

			// 2) 属性物化（精确记账）：先摘掉上次管线写过的 id，再写入这次的声明。
			//    —— 只动自己写过的，物品自带 / 其他来源的修饰符一律保留。
			List<ResourceLocation> previousModifiers = stack.get(ChasmData.CONTRIB_MODIFIER_IDS);
			if (previousModifiers != null) {
				List<ResourceLocation> stale = new ArrayList<>(previousModifiers);
				stale.removeAll(attributesById.keySet());
				ChasmItemModifiers.removeModifiers(stack, stale);
			}
			if (!attributesById.isEmpty()) {
				ChasmItemModifiers.add(stack, new ArrayList<>(attributesById.values()));
				stack.set(ChasmData.CONTRIB_MODIFIER_IDS, List.copyOf(attributesById.keySet()));
			} else {
				stack.remove(ChasmData.CONTRIB_MODIFIER_IDS);
			}

			// 3) 版本 + 清脏。
			stack.set(ChasmData.CONTRIB_REV, revision(stack) + 1);
			stack.remove(ChasmData.CONTRIB_DIRTY);

			if (!unknown.isEmpty()) {
				ChasmLogger.warn("chasm", "栈上存在未注册的贡献来源（已跳过）: {}", unknown);
			}
		} catch (Throwable t) {
			ChasmLogger.error("chasm", "贡献编译失败（保留旧状态）: {}", String.valueOf(t));
		}
	}

	/** 当前栈上的事件桶（可变的深拷贝；含上次编译结果与手工挂载）。 */
	private static Map<ResourceLocation, List<ResourceLocation>> existingBuckets(ItemStack stack) {
		Map<ResourceLocation, List<ResourceLocation>> out = new LinkedHashMap<>();
		Map<ResourceLocation, List<ResourceLocation>> current = stack.get(ChasmData.EVENT_HANDLERS);
		if (current != null) {
			current.forEach((eventId, handlers) -> out.put(eventId, new ArrayList<>(handlers)));
		}
		return out;
	}

	/** 把来源桶并入目标桶（去重、保序）。 */
	private static void mergeInto(Map<ResourceLocation, List<ResourceLocation>> target,
								  Map<ResourceLocation, List<ResourceLocation>> incoming) {
		incoming.forEach((eventId, handlers) -> {
			List<ResourceLocation> bucket = target.computeIfAbsent(eventId, k -> new ArrayList<>());
			for (ResourceLocation handlerId : handlers) {
				if (!bucket.contains(handlerId)) {
					bucket.add(handlerId);
				}
			}
		});
	}

	/** 物品默认事件桶（ItemBuilder.eventHandler 的默认组件）。 */
	private static Map<ResourceLocation, List<ResourceLocation>> defaultBuckets(ItemStack stack) {
		Map<ResourceLocation, List<ResourceLocation>> out = new LinkedHashMap<>();
		ItemStack template = stack.getItem().getDefaultInstance();
		Map<ResourceLocation, List<ResourceLocation>> defaults = template.get(ChasmData.EVENT_HANDLERS);
		if (defaults != null) {
			defaults.forEach((eventId, handlers) -> out.put(eventId, new ArrayList<>(handlers)));
		}
		return out;
	}

	/** 调试：当前栈编译出来的事件桶概要。 */
	public static String describe(ItemStack stack) {
		StringBuilder sb = new StringBuilder();
		sb.append("sources=").append(sources(stack));
		sb.append(" rev=").append(revision(stack));
		sb.append(" dirty=").append(isDirty(stack));
		Map<ResourceLocation, List<ResourceLocation>> buckets = stack.get(ChasmData.EVENT_HANDLERS);
		sb.append(" buckets=").append(buckets == null ? "{}" : buckets.toString());
		// 事件工具只暴露按事件查询，这里直接读组件以展示全部桶
		return sb.toString();
	}

	/** 直接查询某栈是否已挂某事件的处理器（便捷转发）。 */
	public static boolean hasHandler(ItemStack stack, api.chasm.item.event.ItemEventKey<?> event) {
		return !ChasmItemEvents.attached(stack, event).isEmpty();
	}
}
