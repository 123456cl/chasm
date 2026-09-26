package api.chasm.item;

import api.chasm.data.ChasmData;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 物品注释工具（**本轮新增**：以前玩家/整合包完全没法给物品"写注解"）。
 *
 * <p>注释存在物品栈的组件 {@code chasm:item_notes} 里（可序列化、可联网、可精确移除），
 * 显示时由核心注册的提示行贡献者插到物品名下方 —— 任何物品都能写，包括**原版物品**。</p>
 *
 * <pre>{@code
 * // 整合包示例：某劫难之后，这把武器"退役"
 * ChasmItemNotes.add(stack, ChasmItemNote.of(id("mypack", "retired"), "已退役：XX 劫难后失效", 0xFFFF7043, 0));
 * // 之后想摘掉：
 * ChasmItemNotes.remove(stack, id("mypack", "retired"));
 * }</pre>
 *
 * <p>与**改名**的区别：改名占用唯一的名字行且会覆盖铁砧名；注释是可叠加、可撤销、可程序化管理的注解。</p>
 */
public final class ChasmItemNotes {

	/** 注释组件类型（命名空间 chasm）。 */
	public static final DataComponentType<List<ChasmItemNote>> TYPE =
		ChasmData.register("chasm", "item_notes", ChasmItemNote.CODEC.listOf());

	private ChasmItemNotes() {
	}

	/** 全部注释（按 order + id 稳定排序）。 */
	public static List<ChasmItemNote> list(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return List.of();
		}
		List<ChasmItemNote> notes = stack.get(TYPE);
		if (notes == null || notes.isEmpty()) {
			return List.of();
		}
		List<ChasmItemNote> sorted = new ArrayList<>(notes);
		sorted.sort(Comparator.comparingInt(ChasmItemNote::order)
			.thenComparing(n -> n.id().toString()));
		return List.copyOf(sorted);
	}

	/** 是否带某条注释。 */
	public static boolean has(ItemStack stack, ResourceLocation noteId) {
		return list(stack).stream().anyMatch(n -> n.id().equals(noteId));
	}

	/**
	 * 追加/覆盖一条注释（同 id 幂等覆盖；不同 id 叠加）。
	 *
	 * @return 是否发生了变化
	 */
	public static boolean add(ItemStack stack, ChasmItemNote note) {
		if (stack == null || stack.isEmpty() || note == null) {
			return false;
		}
		List<ChasmItemNote> current = new ArrayList<>(listRaw(stack));
		ChasmItemNote existing = current.stream().filter(n -> n.id().equals(note.id())).findFirst().orElse(null);
		if (note.equals(existing)) {
			return false;   // 完全相同 → 不写组件（避免无谓的物品同步）
		}
		current.removeIf(n -> n.id().equals(note.id()));
		current.add(note);
		stack.set(TYPE, List.copyOf(current));
		return true;
	}

	/** 按 id 精确移除；不存在则返回 false。 */
	public static boolean remove(ItemStack stack, ResourceLocation noteId) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		List<ChasmItemNote> current = new ArrayList<>(listRaw(stack));
		if (!current.removeIf(n -> n.id().equals(noteId))) {
			return false;
		}
		if (current.isEmpty()) {
			stack.remove(TYPE);
		} else {
			stack.set(TYPE, List.copyOf(current));
		}
		return true;
	}

	/** 清空全部注释。 */
	public static boolean clear(ItemStack stack) {
		if (stack == null || stack.isEmpty() || !stack.has(TYPE)) {
			return false;
		}
		stack.remove(TYPE);
		return true;
	}

	/** 原始（未排序）列表，供内部增删使用。 */
	private static List<ChasmItemNote> listRaw(ItemStack stack) {
		List<ChasmItemNote> notes = stack.get(TYPE);
		return notes == null ? List.of() : notes;
	}
}
