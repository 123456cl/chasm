package api.chasm.contrib.socket;

import api.chasm.contrib.ItemContributions;
import api.chasm.data.ChasmData;
import api.chasm.log.ChasmLogger;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.ArrayList;
import java.util.List;

/**
 * 镶嵌插槽（**复用贡献管线**）：装备带 N 个插槽，宝石插入即"挂贡献者"，拔出即"摘贡献者"。
 *
 * <p>与 Apotheosis 的 socket/gem 对照：那边宝石加成要再写一套"读取/生效/属性重算"；这边只需
 * 把宝石做成 {@link api.chasm.contrib.ItemContributor}，插入 = {@link ItemContributions#attach}，
 * 属性与事件行为由统一管线一次性物化，拔出无残留（见 {@code ItemContributionsTest}）。</p>
 *
 * <p>嵌套保护：宝石自身的插槽（如果有）不参与递归求值——宝石只贡献一层，避免无限展开。</p>
 */
public final class ChasmSockets {

	private ChasmSockets() {
	}

	/** 设置插槽数量（0 = 不可镶嵌，会清空已有宝石并摘除其贡献者）。 */
	public static void setCapacity(ItemStack stack, int capacity) {
		if (stack == null || stack.isEmpty()) {
			return;
		}
		if (capacity <= 0) {
			unsocketAll(stack);
			stack.remove(ChasmData.SOCKET_CAPACITY);
			return;
		}
		stack.set(ChasmData.SOCKET_CAPACITY, capacity);
	}

	/** 插槽数量（无组件 = 0）。 */
	public static int capacity(ItemStack stack) {
		return stack == null ? 0 : stack.getOrDefault(ChasmData.SOCKET_CAPACITY, 0);
	}

	/** 已镶嵌宝石（只读副本，按槽位顺序）。 */
	public static List<ItemStack> gems(ItemStack stack) {
		if (stack == null) {
			return List.of();
		}
		ItemContainerContents contents = stack.get(ChasmData.SOCKET_GEMS);
		if (contents == null) {
			return List.of();
		}
		List<ItemStack> out = new ArrayList<>();
		contents.nonEmptyStream().forEach(out::add);
		return List.copyOf(out);
	}

	/** 剩余空槽。 */
	public static int freeSlots(ItemStack stack) {
		return Math.max(0, capacity(stack) - gems(stack).size());
	}

	public static boolean hasFreeSlot(ItemStack stack) {
		return capacity(stack) > 0 && freeSlots(stack) > 0;
	}

	/**
	 * 镶嵌一枚宝石：占用一个空槽、挂上其贡献者并重编译该装备。
	 *
	 * @return 成功返回 true（满槽 / 无插槽 / 空宝石 / 已镶嵌同一贡献者 均返回 false）
	 */
	public static boolean socket(ItemStack stack, ItemStack gem) {
		if (stack == null || stack.isEmpty() || gem == null || gem.isEmpty() || !hasFreeSlot(stack)) {
			return false;
		}
		ResourceLocation contributorId = ChasmGems.contributorOf(gem);
		List<ItemStack> next = new ArrayList<>(gems(stack));
		next.add(gem.copyWithCount(1));
		stack.set(ChasmData.SOCKET_GEMS, ItemContainerContents.fromItems(next));
		if (contributorId != null) {
			ItemContributions.attach(stack, contributorId);
			fireHook(contributorId, stack, gem, true);
		}
		ItemContributions.rebuild(stack);
		ChasmLogger.call("chasm", "ChasmSockets", "socket", "镶嵌 {} → 贡献者 {}",
			gem.getItem(), contributorId);
		return true;
	}

	/**
	 * 拔出第 index 个槽位的宝石，并摘除其贡献者（无残留）。
	 *
	 * @return 取出的宝石栈；越界返回 {@link ItemStack#EMPTY}
	 */
	public static ItemStack unsocket(ItemStack stack, int index) {
		List<ItemStack> current = gems(stack);
		if (index < 0 || index >= current.size()) {
			return ItemStack.EMPTY;
		}
		List<ItemStack> next = new ArrayList<>(current);
		ItemStack removed = next.remove(index);
		if (next.isEmpty()) {
			stack.remove(ChasmData.SOCKET_GEMS);
		} else {
			stack.set(ChasmData.SOCKET_GEMS, ItemContainerContents.fromItems(next));
		}
		ResourceLocation contributorId = ChasmGems.contributorOf(removed);
		if (contributorId != null) {
			ItemContributions.detach(stack, contributorId);
			fireHook(contributorId, stack, removed, false);
		}
		ItemContributions.rebuild(stack);
		return removed;
	}

	/** 拔出全部宝石（摘除全部贡献者）。 */
	public static List<ItemStack> unsocketAll(ItemStack stack) {
		List<ItemStack> current = new ArrayList<>(gems(stack));
		if (current.isEmpty()) {
			return List.of();
		}
		stack.remove(ChasmData.SOCKET_GEMS);
		for (ItemStack gem : current) {
			ResourceLocation contributorId = ChasmGems.contributorOf(gem);
			if (contributorId != null) {
				ItemContributions.detach(stack, contributorId);
				fireHook(contributorId, stack, gem, false);
			}
		}
		ItemContributions.rebuild(stack);
		return current;
	}

	/** 与宝石列表对齐贡献者（存档加载/外部改过组件后调用；幂等）。 */
	public static void resync(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return;
		}
		boolean changed = false;
		for (ItemStack gem : gems(stack)) {
			ResourceLocation contributorId = ChasmGems.contributorOf(gem);
			if (contributorId != null) {
				changed |= ItemContributions.attach(stack, contributorId);
			}
		}
		if (changed) {
			ItemContributions.rebuild(stack);
		}
	}

	/** 触发贡献者的镶嵌/拔出钩子（贡献者不存在则静默跳过）。 */
	private static void fireHook(ResourceLocation contributorId, ItemStack host, ItemStack source,
		boolean socketed) {
		api.chasm.contrib.ItemContributor contributor =
			api.chasm.contrib.ChasmContributors.get(contributorId);
		if (contributor == null) {
			return;
		}
		try {
			if (socketed) {
				contributor.onSocket(host, source);
			} else {
				contributor.onUnsocket(host, source);
			}
		} catch (RuntimeException e) {
			ChasmLogger.warn("chasm", "贡献者 {} 的镶嵌钩子抛错: {}", contributorId, e.toString());
		}
	}

	/** 调试：插槽状态一行描述。 */
	public static String describe(ItemStack stack) {
		return "sockets=" + gems(stack).size() + "/" + capacity(stack)
			+ " rev=" + ItemContributions.revision(stack);
	}
}
