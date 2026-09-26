package com.example.chasm;

import api.chasm.gui.client.ChasmGuiClient;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * 示例模组的客户端入口：演示第一步「界面数据同步 + 客户端平滑 + 绘制钩子」。
 *
 * <p>打开魔法盒后：按「充能」增加法力/热量（服务端权威值，走原版数据槽 + 大值快照包），
 * 本钩子在客户端把两个数值画成**平滑推进**的进度条，并把大值通道同步过来的状态字符串
 * 显示在标题行——服务端 20Hz 更新，客户端按帧平滑显示，不跳格。</p>
 */
public final class ExampleClient implements ClientModInitializer {

	/** 本界面用的主题：默认主题即可（资源包换肤不需要改这里）。 */
	private static final api.chasm.gui.ChasmGuiTheme THEME =
		api.chasm.gui.ChasmGuiThemes.resolve(
			net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mymod", "magic_box"));

	@Override
	public void onInitializeClient() {
		ChasmGuiClient.overlay(ResourceLocation.fromNamespaceAndPath("mymod", "magic_box"),
			(g, menu, left, top, mouseX, mouseY, partialTick) -> {
				int cell = 18;

				// 状态文字（大值通道：任意 codec，变化才同步）
				String status = menu.<String>state("status");
				g.drawString(Minecraft.getInstance().font,
					"状态: " + status, left + 8 + 2 * cell, top + 18 + 5, THEME.textColor());

				// 法力条/热量条现在由 P1 控件（滑块 / 只读进度条）绘制 —— 见 ExampleMod 的 .slider/.bar/.toggle
			});
	}

}
