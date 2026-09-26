package api.chasm.gui;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/**
 * 界面主题（**数据模型，纯 common 包，不含任何客户端类**）。
 *
 * <p><b>本类的存在意义：让资源包/整合包能换肤。</b>每个视觉元素都拆成"可选贴图 + 兜底颜色"：</p>
 * <ul>
 *   <li>元素声明了 {@code sprite} 且**该贴图存在** → 客户端用 {@code blitSprite} 画它（支持九宫格，见下）；</li>
 *   <li>否则回退到 {@code color} 平涂 —— 所以"没有贴图也永远能正常显示"，不会出现紫黑方块。</li>
 * </ul>
 *
 * <p><b>资源包怎么换肤（零代码）</b>：把同名 PNG 放进
 * {@code assets/<命名空间>/textures/gui/sprites/<路径>.png} 即可。默认主题用的是约定名：</p>
 * <pre>
 * assets/chasm/textures/gui/sprites/panel.png            ← 面板背景
 * assets/chasm/textures/gui/sprites/slot.png             ← 存储格
 * assets/chasm/textures/gui/sprites/slot_frame.png       ← 存储格外框
 * assets/chasm/textures/gui/sprites/button.png           ← 按钮（普通态）
 * assets/chasm/textures/gui/sprites/button_hover.png     ← 按钮（悬停态）
 * assets/chasm/textures/gui/sprites/button_disabled.png  ← 按钮（禁用态）
 * assets/chasm/textures/gui/sprites/bar_track.png        ← 进度条底槽
 * assets/chasm/textures/gui/sprites/bar_fill.png         ← 进度条填充
 * </pre>
 *
 * <p><b>九宫格</b>：贴图旁边放同名 {@code .mcmeta} 即可声明拉伸方式（原版机制，随贴图一起被资源包替换）：</p>
 * <pre>
 * { "gui": { "scaling": { "type": "nine_slice", "width": 16, "height": 16, "border": 4 } } }
 * </pre>
 *
 * <p>想给某个界面单独换一套皮肤（而不是全局）：用 {@link ChasmGuiBuilder#theme(ResourceLocation)}
 * 指定主题 id，并注册一个自定义 {@code ChasmGuiTheme}（自己模组或整合包专用模组都行）。</p>
 */
public final class ChasmGuiTheme {

	/**
	 * 一个视觉元素：可选贴图 + 兜底颜色。
	 *
	 * @param sprite 贴图 id（{@code null} = 不用贴图，直接平涂 color）
	 * @param color  兜底颜色（ARGB）
	 */
	public record Element(ResourceLocation sprite, int color) {

		/** 纯颜色元素。 */
		public static Element of(int color) {
			return new Element(null, color);
		}

		/** 贴图元素（贴图缺失时回退到 color）。 */
		public static Element of(ResourceLocation sprite, int color) {
			return new Element(Objects.requireNonNull(sprite, "sprite"), color);
		}

		/** 是否有贴图声明。 */
		public boolean hasSprite() {
			return sprite != null;
		}
	}

	private final ResourceLocation id;
	private final Element panel;
	private final Element slotFrame;
	private final Element slot;
	private final Element button;
	private final Element buttonHover;
	private final Element buttonDisabled;
	private final Element buttonInner;
	private final Element buttonHoverInner;
	private final Element barTrack;
	private final Element barFill;
	private final Element sliderTrack;
	private final Element sliderHandle;
	private final Element focusRing;
	private final int titleColor;
	private final int textColor;
	private final int buttonLabelColor;
	private final boolean autoContrastLabel;

	ChasmGuiTheme(ResourceLocation id, Element panel, Element slotFrame, Element slot,
				  Element button, Element buttonHover, Element buttonDisabled,
				  Element buttonInner, Element buttonHoverInner,
				  Element barTrack, Element barFill,
				  Element sliderTrack, Element sliderHandle, Element focusRing,
				  int titleColor, int textColor, int buttonLabelColor, boolean autoContrastLabel) {
		this.id = Objects.requireNonNull(id, "id");
		this.panel = panel;
		this.slotFrame = slotFrame;
		this.slot = slot;
		this.button = button;
		this.buttonHover = buttonHover;
		this.buttonDisabled = buttonDisabled;
		this.buttonInner = buttonInner;
		this.buttonHoverInner = buttonHoverInner;
		this.barTrack = barTrack;
		this.barFill = barFill;
		this.sliderTrack = sliderTrack;
		this.sliderHandle = sliderHandle;
		this.focusRing = focusRing;
		this.titleColor = titleColor;
		this.textColor = textColor;
		this.buttonLabelColor = buttonLabelColor;
		this.autoContrastLabel = autoContrastLabel;
	}

	/**
	 * 依据背景亮度返回**可读的文字色**（深底给白字，浅底给黑字）。
	 *
	 * <p>这是"黑底黑字看不见"的根本修复：按钮/条形的底色可能来自资源包贴图或主题，
	 * 文字色不该写死。默认开启自动对比，主题也可显式关掉。</p>
	 */
	public static int readableTextOn(int backgroundArgb) {
		int r = (backgroundArgb >> 16) & 0xFF;
		int g = (backgroundArgb >> 8) & 0xFF;
		int b = backgroundArgb & 0xFF;
		double luminance = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0;
		return luminance > 0.55 ? 0xFF1A1A1A : 0xFFFFFFFF;
	}

	/** 是否按背景自动选择可读文字色（默认 true）。 */
	public boolean autoContrastLabel() {
		return autoContrastLabel;
	}

	/** 默认主题 id（{@code chasm:default}）。 */
	public static final ResourceLocation DEFAULT_ID = ResourceLocation.fromNamespaceAndPath("chasm", "default");

	// 约定贴图名（**资源包换肤契约**：这些名字改了等于破坏整合包皮肤）与兜底颜色。
	// 说明：这些常量必须先于 DEFAULT 初始化，且 Builder 的默认值直接引用它们
	//（不能引用 DEFAULT 自身，否则类初始化期自引用会 NPE）。
	private static ResourceLocation sprite(String path) {
		return ResourceLocation.fromNamespaceAndPath("chasm", path);
	}

	static final Element DEFAULT_PANEL = Element.of(sprite("panel"), 0xFF8B8B8B);
	static final Element DEFAULT_SLOT_FRAME = Element.of(sprite("slot_frame"), 0xFF6E6E6E);
	static final Element DEFAULT_SLOT = Element.of(sprite("slot"), 0xFF5A5A5A);
	static final Element DEFAULT_BUTTON = Element.of(sprite("button"), 0xFFC6C6C6);
	static final Element DEFAULT_BUTTON_INNER = Element.of(0xFFE8E8E8);
	static final Element DEFAULT_BUTTON_HOVER = Element.of(sprite("button_hover"), 0xFF79C6FF);
	static final Element DEFAULT_BUTTON_HOVER_INNER = Element.of(0xFF9ED4FF);
	static final Element DEFAULT_BUTTON_DISABLED = Element.of(sprite("button_disabled"), 0xFF8A8A8A);
	static final Element DEFAULT_BAR_TRACK = Element.of(sprite("bar_track"), 0xFF4A4A4A);
	static final Element DEFAULT_BAR_FILL = Element.of(sprite("bar_fill"), 0xFF3AC0FF);
	static final Element DEFAULT_SLIDER_TRACK = Element.of(sprite("slider_track"), 0xFF3A3A3A);
	static final Element DEFAULT_SLIDER_HANDLE = Element.of(sprite("slider_handle"), 0xFFE0E0E0);
	static final Element DEFAULT_FOCUS_RING = Element.of(sprite("focus_ring"), 0xFFFFFFFF);

	/** 默认主题：约定贴图名 + 与旧版一致的兜底颜色（没装任何资源包时观感不变）。 */
	public static final ChasmGuiTheme DEFAULT = builder(DEFAULT_ID)
		.panel(DEFAULT_PANEL)
		.slotFrame(DEFAULT_SLOT_FRAME)
		.slot(DEFAULT_SLOT)
		.button(DEFAULT_BUTTON, DEFAULT_BUTTON_INNER)
		.buttonHover(DEFAULT_BUTTON_HOVER, DEFAULT_BUTTON_HOVER_INNER)
		.buttonDisabled(DEFAULT_BUTTON_DISABLED, DEFAULT_BUTTON_DISABLED)
		.bar(DEFAULT_BAR_TRACK, DEFAULT_BAR_FILL)
		.slider(DEFAULT_SLIDER_TRACK, DEFAULT_SLIDER_HANDLE)
		.focusRing(DEFAULT_FOCUS_RING)
		.titleColor(0xFF404040)
		.textColor(0xFFFFFFFF)
		.buttonLabelColor(0xFF404040)
		.build();

	/** 深色主题 id（配深色 UI 贴图用；文字自动转亮色）。 */ 
	public static final ResourceLocation DARK_ID = ResourceLocation.fromNamespaceAndPath("chasm", "dark");

	/** 深色主题预设：深色面板 + 亮色文字（资源包把面板换成暗色贴图时用它，避免黑底黑字）。 */ 
	public static final ChasmGuiTheme DARK = builder(DARK_ID)
		.panel(Element.of(sprite("panel"), 0xFF1B2430))
		.slotFrame(Element.of(sprite("slot_frame"), 0xFF14202C))
		.slot(Element.of(sprite("slot"), 0xFF24384A))
		.button(Element.of(sprite("button"), 0xFF2E4A63), Element.of(0xFF3A6EA5))
		.buttonHover(Element.of(sprite("button_hover"), 0xFF54A0E8), Element.of(0xFF7FC4FF))
		.buttonDisabled(Element.of(sprite("button_disabled"), 0xFF444444), Element.of(0xFF555555))
		.bar(DEFAULT_BAR_TRACK, DEFAULT_BAR_FILL)
		.slider(DEFAULT_SLIDER_TRACK, DEFAULT_SLIDER_HANDLE)
		.focusRing(DEFAULT_FOCUS_RING)
		.titleColor(0xFFE8E8E8)
		.textColor(0xFFE8E8E8)
		.build();

	public static Builder builder(ResourceLocation id) {
		return new Builder(id);
	}

	public ResourceLocation id() {
		return id;
	}

	public Element panel() {
		return panel;
	}

	public Element slotFrame() {
		return slotFrame;
	}

	public Element slot() {
		return slot;
	}

	public Element button() {
		return button;
	}

	public Element buttonHover() {
		return buttonHover;
	}

	public Element buttonDisabled() {
		return buttonDisabled;
	}

	public Element buttonInner() {
		return buttonInner;
	}

	public Element buttonHoverInner() {
		return buttonHoverInner;
	}

	public Element barTrack() {
		return barTrack;
	}

	public Element barFill() {
		return barFill;
	}

	/** 滑块底槽。 */
	public Element sliderTrack() {
		return sliderTrack;
	}

	/** 滑块把手。 */
	public Element sliderHandle() {
		return sliderHandle;
	}

	/** 键盘焦点框（Tab 聚焦时绘制）。 */
	public Element focusRing() {
		return focusRing;
	}

	public int titleColor() {
		return titleColor;
	}

	public int textColor() {
		return textColor;
	}

	public int buttonLabelColor() {
		return buttonLabelColor;
	}

	/** 按状态取按钮元素。 */
	public Element buttonFor(boolean hovered, boolean disabled) {
		if (disabled) {
			return buttonDisabled;
		}
		return hovered ? buttonHover : button;
	}

	/** 按状态取按钮内部元素。 */
	public Element buttonInnerFor(boolean hovered, boolean disabled) {
		if (disabled) {
			return buttonDisabled;
		}
		return hovered ? buttonHoverInner : buttonInner;
	}

	/** 主题构建器（链式；未设置的项取默认主题的值）。 */
	public static final class Builder {
		private final ResourceLocation id;
		private Element panel = DEFAULT_PANEL;
		private Element slotFrame = DEFAULT_SLOT_FRAME;
		private Element slot = DEFAULT_SLOT;
		private Element button = DEFAULT_BUTTON;
		private Element buttonHover = DEFAULT_BUTTON_HOVER;
		private Element buttonDisabled = DEFAULT_BUTTON_DISABLED;
		private Element buttonInner = DEFAULT_BUTTON_INNER;
		private Element buttonHoverInner = DEFAULT_BUTTON_HOVER_INNER;
		private Element barTrack = DEFAULT_BAR_TRACK;
		private Element barFill = DEFAULT_BAR_FILL;
		private Element sliderTrack = DEFAULT_SLIDER_TRACK;
		private Element sliderHandle = DEFAULT_SLIDER_HANDLE;
		private Element focusRing = DEFAULT_FOCUS_RING;
		private int titleColor = 0xFF404040;
		private int textColor = 0xFFFFFFFF;
		private int buttonLabelColor = 0xFF404040;
		private boolean autoContrastLabel = true;

		Builder(ResourceLocation id) {
			this.id = Objects.requireNonNull(id, "id");
		}

		public Builder panel(Element element) {
			this.panel = Objects.requireNonNull(element, "panel");
			return this;
		}

		public Builder slotFrame(Element element) {
			this.slotFrame = Objects.requireNonNull(element, "slotFrame");
			return this;
		}

		public Builder slot(Element element) {
			this.slot = Objects.requireNonNull(element, "slot");
			return this;
		}

		/** 按钮普通态：外框 + 内部（内部不填则用默认）。 */
		public Builder button(Element outer, Element inner) {
			this.button = Objects.requireNonNull(outer, "button");
			this.buttonInner = Objects.requireNonNull(inner, "buttonInner");
			return this;
		}

		public Builder buttonHover(Element outer, Element inner) {
			this.buttonHover = Objects.requireNonNull(outer, "buttonHover");
			this.buttonHoverInner = Objects.requireNonNull(inner, "buttonHoverInner");
			return this;
		}

		public Builder buttonDisabled(Element outer, Element inner) {
			this.buttonDisabled = Objects.requireNonNull(outer, "buttonDisabled");
			return this;
		}

		public Builder bar(Element track, Element fill) {
			this.barTrack = Objects.requireNonNull(track, "barTrack");
			this.barFill = Objects.requireNonNull(fill, "barFill");
			return this;
		}

		/** 滑块底槽 + 把手。 */
		public Builder slider(Element track, Element handle) {
			this.sliderTrack = Objects.requireNonNull(track, "sliderTrack");
			this.sliderHandle = Objects.requireNonNull(handle, "sliderHandle");
			return this;
		}

		/** 键盘焦点框。 */
		public Builder focusRing(Element element) {
			this.focusRing = Objects.requireNonNull(element, "focusRing");
			return this;
		}

		public Builder titleColor(int argb) {
			this.titleColor = argb;
			return this;
		}

		public Builder textColor(int argb) {
			this.textColor = argb;
			return this;
		}

		public Builder buttonLabelColor(int argb) {
			this.buttonLabelColor = argb;
			return this;
		}

		/** 关闭"按背景自动选文字色"（会强制使用 {@link #buttonLabelColor(int)}）。 */
		public Builder fixedLabelColor(boolean autoContrastLabel) {
			this.autoContrastLabel = autoContrastLabel;
			return this;
		}

		public ChasmGuiTheme build() {
			return new ChasmGuiTheme(id, panel, slotFrame, slot, button, buttonHover, buttonDisabled,
				buttonInner, buttonHoverInner, barTrack, barFill, sliderTrack, sliderHandle, focusRing,
				titleColor, textColor, buttonLabelColor, autoContrastLabel);
		}
	}
}
