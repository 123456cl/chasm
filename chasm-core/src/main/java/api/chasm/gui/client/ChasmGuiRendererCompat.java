package api.chasm.gui.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * 旧接口兼容：ChasmGuiBuilder.background(ResourceLocation) 声明的是"32×32、8px 边框"的九宫格贴图，
 * 这里保留原有的手动九宫格绘制（不依赖版本具体的 blitNineSliced 签名）。
 *
 * <p>新代码建议改用主题贴图（assets/&lt;ns&gt;/textures/gui/sprites/panel.png + 同名 .mcmeta 九宫格），
 * 那样资源包替换只需覆盖贴图文件本身。</p>
 */
@Environment(EnvType.CLIENT)
final class ChasmGuiRendererCompat {

	private static final int BORDER = 8;
	private static final int TEX = 32;

	private ChasmGuiRendererCompat() {
	}

	static void drawLegacyNineSlice(GuiGraphics g, ResourceLocation texture,
									int left, int top, int width, int height) {
		int b = BORDER;
		int u = TEX - b;
		int innerW = width - 2 * b;
		int innerH = height - 2 * b;
		blit(g, texture, left, top, b, b, 0, 0, b, b);
		if (innerW > 0) {
			blit(g, texture, left + b, top, innerW, b, b, 0, 1, b);
		}
		blit(g, texture, left + width - b, top, b, b, u, 0, b, b);
		if (innerH > 0) {
			blit(g, texture, left, top + b, b, innerH, 0, b, b, 1);
		}
		if (innerW > 0 && innerH > 0) {
			blit(g, texture, left + b, top + b, innerW, innerH, b, b, 1, 1);
		}
		if (innerH > 0) {
			blit(g, texture, left + width - b, top + b, b, innerH, u, b, b, 1);
		}
		blit(g, texture, left, top + height - b, b, b, 0, u, b, b);
		if (innerW > 0) {
			blit(g, texture, left + b, top + height - b, innerW, b, b, u, 1, b);
		}
		blit(g, texture, left + width - b, top + height - b, b, b, u, u, b, b);
	}

	private static void blit(GuiGraphics g, ResourceLocation texture, int x, int y, int w, int h,
							 int u, int v, int sw, int sh) {
		g.blit(texture, x, y, w, h, u, v, sw, sh, TEX, TEX);
	}
}
