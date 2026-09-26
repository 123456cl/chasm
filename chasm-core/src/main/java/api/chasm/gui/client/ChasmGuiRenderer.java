package api.chasm.gui.client;

import api.chasm.gui.ChasmGuiTheme;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.HashSet;
import java.util.Set;

/**
 * 主题渲染器：把 {@link ChasmGuiTheme} 的元素画出来（这里是"资源包换肤"真正生效的地方）。
 *
 * <p>绘制规则（元素级）：</p>
 * <ol>
 *   <li>元素声明了贴图，且资源包里确实存在该贴图 → blitSprite 画贴图
 *       （九宫格由贴图旁的 .mcmeta 决定，随资源包一起替换）；</li>
 *   <li>否则平涂兜底色 —— 没装资源包时观感与旧版一致，也永远不会出现紫黑方块。</li>
 * </ol>
 *
 * <p>贴图存在性判定：直接查资源管理器里的 assets/&lt;ns&gt;/textures/gui/sprites/&lt;path&gt;.png
 * （GUI 图集 minecraft:gui 会自动收录所有命名空间下 textures/gui/sprites/ 的贴图）。
 * 结果按"当前 ResourceManager 实例"缓存，玩家重载/切换资源包时自动失效。</p>
 */
@Environment(EnvType.CLIENT)
public final class ChasmGuiRenderer {

	/** 已确认存在的贴图缓存；ResourceManager 换实例（资源重载）即整体清空。 */
	private static final Set<ResourceLocation> EXISTING = new HashSet<>();
	private static ResourceManager cachedManager;

	private ChasmGuiRenderer() {
	}

	/** 该 GUI 贴图是否存在（资源包/模组是否提供了这张图）。 */
	public static boolean spriteExists(ResourceLocation spriteId) {
		if (spriteId == null) {
			return false;
		}
		ResourceManager manager = Minecraft.getInstance().getResourceManager();
		if (manager != cachedManager) {
			EXISTING.clear();
			cachedManager = manager;
		}
		if (EXISTING.contains(spriteId)) {
			return true;
		}
		ResourceLocation texture = ResourceLocation.fromNamespaceAndPath(spriteId.getNamespace(),
			"textures/gui/sprites/" + spriteId.getPath() + ".png");
		boolean exists = manager.getResource(texture).isPresent();
		if (exists) {
			EXISTING.add(spriteId);
		}
		return exists;
	}

	/** 该元素当前是否会**用贴图**绘制（资源包提供了贴图）。 */ 
	public static boolean usesSprite(ChasmGuiTheme.Element element) {
		return element.hasSprite() && spriteExists(element.sprite());
	}

	/** 画一个主题元素（贴图优先，颜色兜底）。 */
	public static void draw(GuiGraphics g, ChasmGuiTheme.Element element, int x, int y, int w, int h) {
		if (element.hasSprite() && spriteExists(element.sprite())) {
			g.blitSprite(element.sprite(), x, y, w, h);
			return;
		}
		if (w > 0 && h > 0) {
			g.fill(x, y, x + w, y + h, element.color());
		}
	}

	/** 面板背景：主题贴图优先（九宫格），其次旧的 background() 九宫格贴图，最后兜底平涂。 */
	public static void drawPanel(GuiGraphics g, ChasmGuiTheme theme, ResourceLocation legacyBackground,
								 int left, int top, int width, int height) {
		ChasmGuiTheme.Element panel = theme.panel();
		if (panel.hasSprite() && spriteExists(panel.sprite())) {
			g.blitSprite(panel.sprite(), left, top, width, height);
			return;
		}
		if (legacyBackground != null) {
			ChasmGuiRendererCompat.drawLegacyNineSlice(g, legacyBackground, left, top, width, height);
			return;
		}
		g.fill(left, top, left + width, top + height, panel.color());
	}

	/** 存储格：外框 + 内芯（都支持贴图）。 */
	public static void drawSlot(GuiGraphics g, ChasmGuiTheme theme, int x, int y, int cell) {
		draw(g, theme.slotFrame(), x, y, cell, cell);
		draw(g, theme.slot(), x + 1, y + 1, cell - 2, cell - 2);
	}

	/** 按钮：状态贴图/颜色 + 内部高光（正方形，兼容旧调用）。 */
	public static void drawButton(GuiGraphics g, ChasmGuiTheme theme, int x, int y, int cell,
								  boolean hovered, boolean disabled) {
		drawButton(g, theme, x, y, cell, cell, hovered, disabled);
	}

	/** 按钮：状态贴图/颜色 + 内部高光（**支持自定义宽高**，长标签按钮用这个）。 */
	public static void drawButton(GuiGraphics g, ChasmGuiTheme theme, int x, int y, int width, int height,
								  boolean hovered, boolean disabled) {
		draw(g, theme.buttonFor(hovered, disabled), x, y, width, height);
		if (width > 2 && height > 2) {
			draw(g, theme.buttonInnerFor(hovered, disabled), x + 1, y + 1, width - 2, height - 2);
		}
	}

	/** 按钮底色（用于自动对比文字色）。 */ 
	public static int buttonBackground(ChasmGuiTheme theme, boolean hovered, boolean disabled) {
		return theme.buttonFor(hovered, disabled).color();
	}

	/**
	 * 进度条/能量条（供 ChasmGuiOverlay 使用）：底槽 + 按比例填充，两者都可被资源包换图。
	 *
	 * @param ratio 0~1（自动夹取）
	 */
	public static void drawBar(GuiGraphics g, ChasmGuiTheme theme, int x, int y, int width, int height, double ratio) {
		draw(g, theme.barTrack(), x, y, width, height);
		double clamped = Math.max(0.0, Math.min(1.0, ratio));
		int filled = (int) Math.round(width * clamped);
		if (filled > 0) {
			draw(g, theme.barFill(), x, y, filled, height);
		}
	}
}
