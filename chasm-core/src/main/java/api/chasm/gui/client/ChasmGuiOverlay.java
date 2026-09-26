package api.chasm.gui.client;

import api.chasm.gui.ChasmMenu;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 界面自定义绘制钩子（第三步的雏形，先随数据同步一起落地，让数值"看得见"）。
 *
 * <p>在声明式屏幕每帧绘制完背景/槽位/按钮之后调用，用于画进度条、能量条、状态文字、
 * 图标等任意内容。配合 {@link ChasmMenu#data(String)}（整数）与
 * {@link ChasmMenu#smoothData(String)}（平滑值）读取服务端同步过来的数据。</p>
 *
 * <pre>{@code
 * ChasmGuiClient.overlay(id("mymod", "magic_box"), (g, menu, left, top, mx, my, partial) -> {
 *     int filled = (int) menu.smoothData("mana");
 *     g.fill(left + 20, top + 40, left + 20 + filled, top + 46, 0xFF3AC0FF);
 * });
 * }</pre>
 */
@Environment(EnvType.CLIENT)
@FunctionalInterface
public interface ChasmGuiOverlay {

	/**
	 * 绘制回调（客户端渲染线程）。
	 *
	 * @param graphics  绘制工具
	 * @param menu      当前菜单（可读同步数据）
	 * @param left      界面面板左上角 x（屏幕坐标）
	 * @param top       界面面板左上角 y（屏幕坐标）
	 * @param mouseX    鼠标 x
	 * @param mouseY    鼠标 y
	 * @param partialTick 帧插值（0~1）
	 */
	void render(GuiGraphics graphics, ChasmMenu menu, int left, int top, int mouseX, int mouseY, float partialTick);
}
