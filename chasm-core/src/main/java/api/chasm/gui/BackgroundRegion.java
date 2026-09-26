package api.chasm.gui;

import net.minecraft.resources.ResourceLocation;

/**
 * **大图集区域取图**（本轮新增：防止"把整张 GUI 大图当九宫格拉伸"的经典错误）。
 *
 * <p>很多模组的界面贴图是一整张大图（例：神化重铸台 reforge.png 为 <b>256×384</b>），
 * 主面板只是其中一块矩形区域，副框是另一块。绘制时必须：**写全整张图的尺寸**（作为 UV 分母），
 * 再给出要取的区域 (u,v,w,h) —— 否则就会把整图拉到面板里，边缘串到别的区域，看起来就是"拉错/花屏"。</p>
 *
 * <p>取自神化源码的事实（可核对 {@code ReforgingScreen.renderBg}）：</p>
 * <pre>
 * gfx.blit(TEXTURE, x, y, 0, 0, 176, 266, 256, 384);            // 主底板区域
 * gfx.blit(TEXTURE, x2, y2, 20 + 46 * i, 273, 46, 35, 256, 384); // 副框区域
 * </pre>
 *
 * @param texture     贴图（如 apotheosis:textures/gui/reforge.png）
 * @param sheetWidth  整张图的宽（UV 分母，必须与图片实际尺寸一致）
 * @param sheetHeight 整张图的高（UV 分母）
 * @param u           区域左上角 u
 * @param v           区域左上角 v
 * @param width       区域宽（像素）
 * @param height      区域高（像素）
 */
public record BackgroundRegion(ResourceLocation texture, int sheetWidth, int sheetHeight,
							   int u, int v, int width, int height) {

	public BackgroundRegion {
		if (texture == null) {
			throw new IllegalArgumentException("背景贴图不可为 null");
		}
		if (sheetWidth <= 0 || sheetHeight <= 0 || width <= 0 || height <= 0) {
			throw new IllegalArgumentException("图集尺寸与区域尺寸必须为正数");
		}
		if (u < 0 || v < 0 || u + width > sheetWidth || v + height > sheetHeight) {
			throw new IllegalArgumentException("区域 (" + u + "," + v + " " + width + "x" + height
				+ ") 超出图集 " + sheetWidth + "x" + sheetHeight + "（这会导致取到图外、渲染花屏）");
		}
	}
}
