package api.chasm.gui.client;

import api.chasm.gui.ChasmButton;
import api.chasm.gui.ChasmButtonClickC2SPayload;
import api.chasm.gui.ChasmGui;
import api.chasm.gui.ChasmGuiTheme;
import api.chasm.gui.ChasmGuiThemes;
import api.chasm.gui.ChasmMenu;
import api.chasm.gui.ChasmWidgetKind;
import api.chasm.gui.ChasmWidgetKinds;
import api.chasm.gui.ChasmWidgetSpec;
import api.chasm.gui.PlayerInventoryLayout;
import api.chasm.gui.decl.GuiDeclarations;
import api.chasm.gui.decl.GuiNode;
import api.chasm.gui.decl.client.DeclClient;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;

/**
 * 声明式界面的客户端绘制界面。
 *
 * <h2>坐标空间（只有一套）</h2>
 * <p>面板左上角 = 屏幕 (leftPos, topPos)；所有元素的坐标都是**面板像素**：
 * 槽位 {@code ChasmStorageSlot.pixelX()}、控件 {@code ChasmWidgetSpec.pixelX()}、
 * 声明式节点 {@code GuiNode.rect(menu)}、玩家背包 {@link PlayerInventoryLayout}。
 * 这里只做一次 {@code leftPos + x} —— 以前散落的 {@code +8 / +18} 已经全部删除，
 * 它们正是"控件和槽位差 8/18 像素"的根源。</p>
 *
 * <h2>声明式界面的绘制顺序</h2>
 * <ol>
 *   <li>声明式 {@link GuiNode.Layer#BEHIND} 节点（底板、槽框、物品栏底图）—— 在物品之前；</li>
 *   <li>原版画的物品与悬停高亮；</li>
 *   <li>声明式 {@link GuiNode.Layer#ABOVE} 节点（图标、文字、高亮、按钮）—— 在物品之后；</li>
 *   <li>物品 tooltip（最后画，保证不被上面的节点盖住）。</li>
 * </ol>
 * <p><b>声明式界面不再有"框架底色"</b>：面板画什么完全由节点列表决定。
 * 真界面（例如 Tetra 加工台）本来就没有底板，硬加一层主题面板只会盖住真实素材 —— 那是历史包袱。</p>
 */
public final class ChasmScreen extends AbstractContainerScreen<ChasmMenu> {

	private static final int CELL = ChasmMenu.CELL;
	private static final int NINE_TEX = 32;                                    // 图标纹理约定尺寸 px（图标仍走旧接口）
	private static final int ICON_SIZE = 16;                                   // 图标绘制尺寸 px

	private final ChasmGui gui;
	/** 是否声明式界面（节点列表说话，框架不插手背景/槽框/标题）。 */
	private final boolean declarative;

	/** 键盘焦点控件索引（-1 = 无焦点）；Tab/Shift+Tab 轮转。 */
	private int focusedWidget = -1;

	/** 正在拖动的滑块控件索引（-1 = 未拖动）。 */
	private int draggingWidget = -1;

	/** 本帧鼠标下的控件索引（-1 = 无），用于 tooltip。 */
	private int hoveredWidget = -1;

	private boolean animationsReset;
	/** 打开后是否已经把布局数字打进日志（一次即可，用来定位"整体偏移"这类只看代码看不出的事）。 */
	private boolean layoutLogged;

	private ChasmScreen(ChasmMenu menu, Inventory playerInventory, Component title, ChasmGui gui) {
		super(menu, playerInventory, title);
		this.gui = gui;
		this.declarative = GuiDeclarations.isDeclarative(gui.id());
		// 面板尺寸：显式 panelSize 优先（移植固定像素 UI），否则按网格列数自适应
		this.imageWidth = gui.panelWidth() > 0 ? gui.panelWidth()
			: Math.max(176, ChasmMenu.GRID_X + gui.cols() * CELL + 8);
		this.imageHeight = gui.panelHeight() > 0 ? gui.panelHeight() : 115 + gui.rows() * CELL;
		this.titleLabelX = 8;
		this.titleLabelY = 6;
		this.inventoryLabelX = 8;
		this.inventoryLabelY = this.imageHeight - 94;
	}

	/** {@link net.minecraft.client.gui.screens.MenuScreens} 工厂：从菜单反解所属界面。 */
	public static ChasmScreen from(ChasmMenu menu, Inventory playerInventory, Component title) {
		return new ChasmScreen(menu, playerInventory, title, menu.gui());
	}

	/** 当前界面的主题（未指定 → chasm:default）。资源包换肤在此生效。 */
	private ChasmGuiTheme theme() {
		return ChasmGuiThemes.resolve(gui.themeId());
	}

	/**
	 * 把**框架自己以为的**布局数字打进日志（每个界面打开一次）。
	 *
	 * <p>为什么需要它：面板/槽位/节点的坐标全是"面板像素 + 面板原点"，只要有一处算错，
	 * 屏幕上的表现就是"整体偏了很远"，而光看代码根本看不出是哪一环。
	 * 打出来之后，"谁偏了多少"就是一个可核对的事实，不需要猜。</p>
	 */
	private void logLayoutOnce() {
		if (layoutLogged) {
			return;
		}
		layoutLogged = true;
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		String slots = gui.storageSlots().stream()
			.map(s -> s.index() + ":(" + s.pixelX() + "," + s.pixelY() + ")"
				+ (s.isActive(menu) ? "" : "[隐]"))
			.collect(java.util.stream.Collectors.joining(" "));
		api.chasm.log.ChasmLogger.info(gui.id().getNamespace(),
			"布局 {}｜面板 {}x{}｜面板原点 ({},{})｜缩放窗口 {}x{}｜GUI缩放 {}｜存储槽 {}｜背包起点 ({},{}) 间距 {} 快捷栏y={}｜声明式 {}",
			gui.id(), imageWidth, imageHeight, this.leftPos, this.topPos,
			mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(),
			mc.getWindow().getGuiScale(), slots,
			gui.playerInventory().x(), gui.playerInventory().bagY(), gui.playerInventory().gap(),
			gui.playerInventory().hotbarY(), declarative);
	}

	@Override
	protected void renderBg(GuiGraphics guiGraphics, float partialTicks, int mouseX, int mouseY) {
		logLayoutOnce();
		int left = this.leftPos;
		int top = this.topPos;
		ChasmGuiTheme theme = theme();

		if (declarative) {
			// 声明式界面：垫底的一切都来自节点列表（没有节点 = 面板透明，世界透出来）
			DeclClient.render(guiGraphics, gui.id(), menu, left, top, mouseX, mouseY, GuiNode.Layer.BEHIND);
			// **框架不画任何槽框**：声明式界面的视觉全部由节点列表决定。
			// （曾经加过"框架给活跃槽位补框"的兜底，那是错的：真实界面里槽位长什么样是**素材说了算**，
			//   框架硬塞一个通用槽框只会多出一排看起来像快捷栏的东西。删掉，不做任何"检测"式补救。）
			return;
		}

		// —— 以下是"框架自带布局"（网格/像素槽位 + 主题底板）的老界面路径 ——
		boolean regionDrawn = gui.backgroundRegion() != null
			&& BlitRegion.draw(guiGraphics, gui.backgroundRegion(), left, top, imageWidth, imageHeight);
		// 底板**只在明确声明时**才画：不声明背景 = 不要底板（世界透出来）
		boolean wantsPanel = !gui.panelSuppressed();
		if (!regionDrawn && wantsPanel) {
			ChasmGuiRenderer.drawPanel(guiGraphics, theme, gui.background(), left, top, imageWidth, imageHeight);
		}
		// 存储格：贴图/颜色都由主题决定 → 资源包可直接换皮肤
		for (api.chasm.gui.ChasmStorageSlot s : gui.storageSlots()) {
			ChasmGuiRenderer.drawSlot(guiGraphics, theme, left + s.pixelX(), top + s.pixelY(), CELL);
		}
		// 玩家物品栏底图（与 ChasmMenu 建槽用的是同一份布局）
		PlayerInventoryLayout bag = gui.playerInventory();
		for (int row = 0; row < 3; row++) {
			for (int c = 0; c < 9; c++) {
				ChasmGuiRenderer.drawSlot(guiGraphics, theme,
					left + bag.slotX(9 + row * 9 + c), top + bag.slotY(9 + row * 9 + c), CELL);
			}
		}
		for (int c = 0; c < 9; c++) {
			ChasmGuiRenderer.drawSlot(guiGraphics, theme, left + bag.slotX(c), top + bag.slotY(c), CELL);
		}
	}

	/** 声明式界面不要标题/物品栏标签（真界面里没有这些字）。 */
	@Override
	protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		if (declarative) {
			return;
		}
		super.renderLabels(guiGraphics, mouseX, mouseY);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
		// R8：一帧只构建一次节点列表（渲染要分两层取列表，以前会构建两次）
		DeclClient.startRenderFrame(gui.id());
		super.render(g, mouseX, mouseY, partialTicks); // 背景（含 BEHIND 节点）+槽位+物品+tooltip
		if (declarative) {
			DeclClient.render(g, gui.id(), menu, this.leftPos, this.topPos, mouseX, mouseY, GuiNode.Layer.ABOVE);
		}
		renderButtons(g, mouseX, mouseY);
		renderWidgets(g, mouseX, mouseY, partialTicks);
		renderOverlays(g, mouseX, mouseY, partialTicks);
		// **最后再画一次物品提示**：原版提示在 super.render 里就画了，而我们的控件/绘制钩子画在它之后
		// 会把提示盖住（实际表现：界面里悬停物品看不到任何信息）。神化也是这么处理的，这里照做。
		this.renderTooltip(g, mouseX, mouseY);
		DeclClient.endRenderFrame(gui.id());
	}

	/**
	 * 绘制控件（开放注册的画法）：滑块 / 进度条 / 开关 / 第三方自定义种类。
	 * 悬停或键盘聚焦时显示 tooltip，聚焦时画焦点框。
	 */
	private void renderWidgets(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
		if (!animationsReset) {
			animationsReset = true;
			ChasmGuiAnimations.onOpen(gui.id().toString());
		}
		hoveredWidget = -1;
		List<ChasmWidgetSpec> widgets = gui.widgets();
		for (int i = 0; i < widgets.size(); i++) {
			ChasmWidgetSpec spec = widgets.get(i);
			// 统一坐标：控件自己就是面板像素，这里只加一次面板原点
			int x = this.leftPos + spec.pixelX();
			int y = this.topPos + spec.pixelY();
			int w = spec.pixelWidth();
			int h = spec.pixelHeight();
			boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
			if (hovered) {
				hoveredWidget = i;
			}
			ChasmWidgetRenderers.Renderer renderer = ChasmWidgetRenderers.get(spec.kind());
			try {
				if (renderer != null) {
					renderer.render(g, menu, spec, x, y, w, h, hovered, focusedWidget == i, partialTicks,
						mouseX, mouseY);
				} else {
					ChasmWidgetRenderers.renderFallback(g, menu, spec, x, y, w, h, hovered, focusedWidget == i,
						partialTicks, mouseX, mouseY);
				}
			} catch (RuntimeException e) {
				api.chasm.log.ChasmLogger.error(gui.id().getNamespace(), "控件 {} 绘制异常（已隔离）: {}",
					spec.key(), e.toString());
			}
			if (focusedWidget == i && ChasmGuiClient.showFocusRings()) {
				ChasmGuiRenderer.draw(g, theme().focusRing(), x - 1, y - 1, w + 2, h + 2);
			}
		}
		int tipIndex = hoveredWidget >= 0 ? hoveredWidget : focusedWidget;
		if (tipIndex >= 0 && tipIndex < widgets.size()) {
			ChasmWidgetSpec spec = widgets.get(tipIndex);
			if (spec.tooltipKey() != null && !spec.tooltipKey().isBlank()) {
				g.renderTooltip(this.font, Component.translatable(spec.tooltipKey()), mouseX, mouseY);
			}
		}
	}

	/**
	 * 自定义绘制钩子（{@link ChasmGuiClient#overlay}）：在背景/槽位/按钮之后绘制，
	 * 常用于进度条、能量条、状态文字。可读 {@link ChasmMenu#data(String)}（同步值）与
	 * {@link ChasmMenu#smoothData(String)}（平滑显示值）。
	 */
	private void renderOverlays(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
		for (ChasmGuiOverlay overlay : ChasmGuiClient.overlaysFor(gui.id())) {
			try {
				overlay.render(g, menu, this.leftPos, this.topPos, mouseX, mouseY, partialTicks);
			} catch (RuntimeException e) {
				api.chasm.log.ChasmLogger.error(gui.id().getNamespace(),
					"界面绘制钩子异常（已隔离）: {}", e.toString());
			}
		}
	}

	/**
	 * 列表滚动（**客户端本地视图状态**，不惊动服务端）。
	 *
	 * <p>约定：GUI 声明一个 {@code <控件键>_scroll} 的整数数据键，滚轮就在该键上增减；
	 * 没声明就当作不可滚动。渲染器用 {@code ChasmWidgetRenderers.scrollOf(...)} 读取。</p>
	 */
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int hit = widgetAt(mouseX, mouseY);
		if (hit >= 0 && scrollY != 0.0) {
			ChasmWidgetSpec spec = gui.widgets().get(hit);
			String key = spec.key() + "_scroll";
			if (menu.hasData(key)) {
				int visible = Math.max(1, spec.pixelHeight() / 12);
				int maxScroll = Math.max(0, spec.max() - visible + 1);
				int next = Math.max(0, Math.min(maxScroll, menu.data(key) - (int) Math.signum(scrollY)));
				menu.setData(key, next);
				return true;
			}
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		// **槽位优先**：鼠标压在真实物品槽上时，一切交给原版（放/取/Shift 搬运/右键分堆）
		// 以前节点控件会把整屏的点击吞掉，表现为"物品根本放不进去"
		if (this.hoveredSlot != null) {
			return super.mouseClicked(mouseX, mouseY, button);
		}
		// 控件优先：命中可交互控件即处理（并让滑块进入拖动状态）
		int hit = widgetAt(mouseX, mouseY);
		if (hit >= 0) {
			ChasmWidgetSpec spec = gui.widgets().get(hit);
			ChasmWidgetKind kind = ChasmWidgetKinds.get(spec.kind());
			if (kind != null && kind.interactive()) {
				if (kind.clickAxis() == ChasmWidgetKind.ClickAxis.NODE) {
					// 声明式界面：客户端命中节点序号 → 服务端按序号重建并派发（不传坐标，不会错位）
					int nodeIndex = DeclClient.hitTest(gui.id(), menu, mouseX, mouseY, this.leftPos, this.topPos);
					if (nodeIndex < 0) {
						// 没点到节点就不要吞掉点击
						return super.mouseClicked(mouseX, mouseY, button);
					}
					setFocus(hit);
					sendValue(spec, hit, nodeIndex);
					return true;
				}
				setFocus(hit);
				if (kind.clickAxis() == ChasmWidgetKind.ClickAxis.Y_ROW) {
					// 列表/选项卡：按**行几何**换算（渲染器声明行高，点击用同一套几何 → 不会错位）
					int widgetTop = this.topPos + spec.pixelY();
					int row = ChasmWidgetRenderers.rowIndexAt(spec, (int) (mouseY - widgetTop));
					if (row < 0) {
						return true; // 点到了标题/空白：吞掉，不误触发
					}
					int span = Math.max(1, spec.max() - spec.min());
					sendWidgetValue(spec, hit, (double) (row - spec.min()) / span);
				} else if (kind.clickAxis() == ChasmWidgetKind.ClickAxis.CLICK) {
					sendWidgetValue(spec, hit, 1.0);
				} else if (ChasmWidgetKinds.SLIDER.id().equals(spec.kind())) {
					draggingWidget = hit;
					sendWidgetValue(spec, hit, widgetFraction(spec, mouseX));
				} else if (ChasmWidgetKinds.TOGGLE.id().equals(spec.kind())) {
					int current = ChasmWidgetRenderers.valueOf(menu, spec);
					sendWidgetValue(spec, hit, current > 0 ? 0.0 : 1.0);
				} else {
					sendWidgetValue(spec, hit, 1.0);
				}
				return true;
			}
		}
		for (ChasmButton b : gui.buttons()) {
			if (inside(b, mouseX, mouseY)) {
				int index = indexOf(b);
				ClientPlayNetworking.send(new ChasmButtonClickC2SPayload(gui.id(), b.label(), index));
				return true;
			}
		}
		return super.mouseClicked(mouseX, mouseY, button);
	}

	private void renderButtons(GuiGraphics guiGraphics, int mouseX, int mouseY) {
		Font font = this.font;
		ChasmGuiTheme theme = theme();
		for (ChasmButton b : gui.buttons()) {
			int[] xy = buttonRect(b);
			int x = xy[0];
			int y = xy[1];
			boolean hover = inside(b, mouseX, mouseY);
			int bw = b.pixelWidth();
			int bh = b.pixelHeight();
			ChasmGuiRenderer.drawButton(guiGraphics, theme, x, y, bw, bh, hover, false);
			if (b.icon() != null) {
				int iconX = x + (bw - ICON_SIZE) / 2;
				int iconY = y + (bh - ICON_SIZE) / 2;
				int srcV = hover ? b.iconV() + ICON_SIZE : b.iconV();
				guiGraphics.blit(b.icon(), iconX, iconY,
					ICON_SIZE, ICON_SIZE, b.iconU(), srcV, ICON_SIZE, ICON_SIZE, NINE_TEX, NINE_TEX);
			} else {
				drawFittedLabel(guiGraphics, b.label(), x, y, bw, bh, labelColorFor(theme, hover, false));
			}
		}
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		if (draggingWidget >= 0) {
			ChasmWidgetSpec spec = gui.widgets().get(draggingWidget);
			sendWidgetValue(spec, draggingWidget, widgetFraction(spec, mouseX));
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		draggingWidget = -1;
		return super.mouseReleased(mouseX, mouseY, button);
	}

	/**
	 * 键盘操作（无障碍）：
	 * Tab / Shift+Tab 轮转焦点；方向键调整滑块（Shift 步长 ×10）；Enter / 空格 激活开关/按钮。
	 * 没有可聚焦控件时不拦截任何按键（交还原版）。
	 */
	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		List<Integer> focusable = gui.focusableWidgetIndexes();
		if (!focusable.isEmpty()) {
			if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_TAB) {
				setFocus(api.chasm.gui.ChasmWidgetFocus.next(focusable, focusedWidget,
					net.minecraft.client.gui.screens.Screen.hasShiftDown()));
				return true;
			}
			if (focusedWidget >= 0 && focusedWidget < gui.widgets().size()) {
				ChasmWidgetSpec spec = gui.widgets().get(focusedWidget);
				boolean slider = ChasmWidgetKinds.SLIDER.id().equals(spec.kind());
				if (slider && (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT
					|| keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT)) {
					int step = (net.minecraft.client.gui.screens.Screen.hasShiftDown() ? 10 : 1)
						* (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT ? 1 : -1);
					int current = ChasmWidgetRenderers.valueOf(menu, spec);
					sendValue(spec, focusedWidget, current + step);
					return true;
				}
				if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
					|| keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER
					|| keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE) {
					if (ChasmWidgetKinds.TOGGLE.id().equals(spec.kind())) {
						int current = ChasmWidgetRenderers.valueOf(menu, spec);
						sendValue(spec, focusedWidget, current > 0 ? 0 : 1);
						return true;
					}
					if (!slider) {
						sendValue(spec, focusedWidget, 1);
						return true;
					}
				}
			}
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	// ------------------------------------------------------------ 控件工具

	/** 屏幕坐标 → 控件索引（-1 = 未命中；只命中可交互控件用于点击）。 */
	private int widgetAt(double mouseX, double mouseY) {
		double relX = mouseX - this.leftPos;
		double relY = mouseY - this.topPos;
		List<ChasmWidgetSpec> widgets = gui.widgets();
		for (int i = 0; i < widgets.size(); i++) {
			if (widgets.get(i).contains(relX, relY)) {
				return i;
			}
		}
		return -1;
	}

	/** 鼠标横坐标 → 滑块比例（0~1）。 */
	private double widgetFraction(ChasmWidgetSpec spec, double mouseX) {
		double x = this.leftPos + spec.pixelX();
		double w = spec.pixelWidth();
		return w <= 0 ? 0.0 : (mouseX - x) / w;
	}

	/** 按比例发值（滑块拖动）。 */
	private void sendWidgetValue(ChasmWidgetSpec spec, int index, double fraction) {
		sendValue(spec, index, spec.valueAt(fraction));
	}

	/** 发绝对数值（服务端还会夹取一次；两端共用 spec 的换算，结果一致）。 */
	private void sendValue(ChasmWidgetSpec spec, int index, int value) {
		ClientPlayNetworking.send(new api.chasm.gui.ChasmWidgetActionC2SPayload(gui.id(), index, spec.kind(),
			spec.clamp(value), null));
	}

	/** 设置键盘焦点并朗读（无障碍）。 */
	private void setFocus(int index) {
		if (focusedWidget == index) {
			return;
		}
		focusedWidget = index;
		if (index >= 0 && index < gui.widgets().size()) {
			try {
				net.minecraft.client.Minecraft.getInstance().getNarrator()
					.say(ChasmWidgetRenderers.narrationFor(gui.widgets().get(index)));
			} catch (RuntimeException ignored) {
				// 旁白不可用（未启用/初始化早期）不应影响界面
			}
		}
	}

	private boolean inside(ChasmButton b, double mx, double my) {
		int[] xy = buttonRect(b);
		return mx >= xy[0] && mx < xy[0] + b.pixelWidth() && my >= xy[1] && my < xy[1] + b.pixelHeight();
	}

	/** 按钮位置（面板像素 + 面板原点）。 */
	private int[] buttonRect(ChasmButton b) {
		return new int[] { this.leftPos + b.pixelX(), this.topPos + b.pixelY() };
	}

	/**
	 * 按钮文字色。
	 *
	 * <p>关键：**只有"用颜色平涂"时才能推断对比度**。如果按钮用的是资源包贴图，
	 * 我们不知道像素亮度 —— 此时必须用主题的显式文字色（配合四向描边保证可读）。</p>
	 */
	private int labelColorFor(ChasmGuiTheme theme, boolean hovered, boolean disabled) {
		boolean textured = ChasmGuiRenderer.usesSprite(theme.buttonFor(hovered, disabled));
		if (!theme.autoContrastLabel() || textured) {
			return theme.buttonLabelColor();
		}
		return ChasmGuiTheme.readableTextOn(ChasmGuiRenderer.buttonBackground(theme, hovered, disabled));
	}

	/**
	 * 四向描边文字（学习神化的 {@code drawBorderedString}）：**任何背景都可读**。
	 */
	private void drawOutlinedString(GuiGraphics g, String text, int x, int y, int color) {
		if (text == null || text.isEmpty()) {
			return;
		}
		int outline = ChasmGuiTheme.readableTextOn(color);
		g.drawString(this.font, text, x - 1, y, outline, false);
		g.drawString(this.font, text, x + 1, y, outline, false);
		g.drawString(this.font, text, x, y - 1, outline, false);
		g.drawString(this.font, text, x, y + 1, outline, false);
		g.drawString(this.font, text, x, y, color, false);
	}

	/** 把文字居中画进给定矩形；文字比矩形宽时**整体缩放**而不是溢出重叠。 */
	private void drawFittedLabel(GuiGraphics g, String text, int x, int y, int width, int height, int color) {
		if (text == null || text.isEmpty() || width <= 2) {
			return;
		}
		int textWidth = this.font.width(text);
		float scale = Math.min(1.0F, (width - 4.0F) / Math.max(1, textWidth));
		g.pose().pushPose();
		g.pose().translate(x + width / 2.0F, y + (height - 8.0F * scale) / 2.0F, 0.0F);
		g.pose().scale(scale, scale, 1.0F);
		drawOutlinedString(g, text, -textWidth / 2, 0, color);
		g.pose().popPose();
	}

	private int indexOf(ChasmButton b) {
		List<ChasmButton> list = gui.buttons();
		for (int i = 0; i < list.size(); i++) {
			if (list.get(i) == b) {
				return i;
			}
		}
		return -1;
	}
}
