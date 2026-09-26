package api.chasm.gui;

import net.minecraft.resources.ResourceLocation;

/**
 * 一次按钮点击的不可变上下文（服务端回调时传入）。
 *
 * @param guiId      所属界面 id（{@code modId:name}）
 * @param name       按钮名（= 声明时传入的 label）
 * @param index      按钮在界面内的索引（声明顺序）
 */
public record ChasmGuiClick(ResourceLocation guiId, String name, int index) {
}