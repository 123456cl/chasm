package api.chasm.gui.client;

import api.chasm.gui.BackgroundRegion;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * 大图集区域取图绘制。
 *
 * <p>关键点：blit 的 UV 分母必须是**整张图**的尺寸（如 256x384），而不是区域尺寸；
 * 否则会从图外采样，表现为花屏/串图 —— 这就是"搬运大图界面时拉错"的技术原因。</p>
 *
 * <p>另外：外部素材（别人的 ARR 贴图）可能**不存在**（未安装对应资源包）。此时回退为主题面板色，
 * 绝不让玩家看到紫黑错误贴图。</p>
 */
@Environment(EnvType.CLIENT)
final class BlitRegion {

	private BlitRegion() {
	}

	/** 该贴图是否存在于当前资源栈（由资源包/模组提供）。 */
	static boolean textureExists(ResourceLocation texture) {
		ResourceLocation file = ResourceLocation.fromNamespaceAndPath(texture.getNamespace(),
			"textures/" + texture.getPath() + ".png");
		return Minecraft.getInstance().getResourceManager().getResource(file).isPresent();
	}

	/**
	 * 把区域绘制到目标矩形；贴图缺失时返回 false（由调用方回退）。
	 *
	 * @return 是否真的画了（false = 贴图不存在，调用方应用兜底色）
	 */
	static boolean draw(GuiGraphics g, BackgroundRegion region, int x, int y, int targetWidth, int targetHeight) {
		if (!textureExists(region.texture())) {
			return false;
		}
		g.blit(region.texture(), x, y, targetWidth, targetHeight,
			(float) region.u(), (float) region.v(),
			region.width(), region.height(),
			region.sheetWidth(), region.sheetHeight());
		return true;
	}
}
