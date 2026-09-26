package api.chasm.item;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 物品注释的提示渲染（核心自带，**任何物品都生效**，包括原版物品）。
 *
 * <p>位置刻意放在**物品名下方第一段**（原版注释区的位置），这样玩家一眼就能看到"这件装备被打了什么标识"。</p>
 */
public final class ChasmItemNotesTooltip {

	private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("chasm", "item_notes");

	private ChasmItemNotesTooltip() {
	}

	/** 注册渲染（由核心入口点调用一次；顺带完成 {@link ChasmItemNotes} 组件的早期登记）。 */
	public static void init() {
		java.util.Objects.requireNonNull(ChasmItemNotes.TYPE, "chasm:item_notes 组件类型");
		ChasmItemTooltips.register(ID, ChasmItemNotesTooltip::append);
	}

	private static void append(net.minecraft.world.item.ItemStack stack,
							   net.minecraft.world.item.TooltipFlag flag, List<Component> lines) {
		List<ChasmItemNote> notes = ChasmItemNotes.list(stack);
		if (notes.isEmpty()) {
			return;
		}
		int insertAt = lines.isEmpty() ? 0 : 1;   // 名字下方
		for (ChasmItemNote note : notes) {
			lines.add(Math.min(insertAt, lines.size()), note.render());
			insertAt++;
		}
	}
}
