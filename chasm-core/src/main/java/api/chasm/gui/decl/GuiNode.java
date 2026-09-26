package api.chasm.gui.decl;

import api.chasm.gui.ChasmMenu;
import api.chasm.gui.GuiRect;
import api.chasm.gui.PlayerInventoryLayout;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * **界面节点**：一次声明、一次遍历就能"画 + 命中"的最小数据。
 *
 * <p>范式：{@code ui = f(state)} —— 界面的唯一真相是"状态 → 节点列表"。
 * 节点没有生命周期、没有隐藏状态：**不在列表里 = 不存在 = 画不出来也点不到**。</p>
 *
 * <h2>三条硬约定</h2>
 * <ol>
 *   <li><b>一个坐标空间</b>：所有坐标都是**面板像素**（面板左上角为原点）。
 *       网格、锚点、居中这些换算要么不在这里发生，要么发生一次并写回像素
 *       （见 {@link #slot(int)} / {@link #inventorySlot(int)}）。</li>
 *   <li><b>一个列表</b>：列表顺序 = 绘制顺序（z 序） = 命中优先级（从后往前找）。
 *       重叠不是错误，是特性。</li>
 *   <li><b>层只是"物品前/物品后"</b>：原版容器会自己把物品画在 {@code renderBg} 之后，
 *       所以需要垫在物品下面的东西（底板、槽框、物品栏底图）标 {@link #behind()}。</li>
 * </ol>
 */
public final class GuiNode {

	/**
	 * 节点绘制层。
	 *
	 * <p>原版 {@code AbstractContainerScreen} 的绘制顺序是固定的：{@code renderBg}（背景）
	 * → 物品槽与物品 → 标签 → tooltip。声明式节点分两趟画，就是为了**和原版共存**：
	 * 垫底的东西在物品下面，其余（图标、文字、高亮）在物品上面。</p>
	 */
	public enum Layer {
		/** 画在物品**下面**（底板、槽框、物品栏底图）。 */
		BEHIND,
		/** 画在物品**上面**（默认；图标、文字、高亮、按钮）。 */
		ABOVE
	}

	private final String key;
	private int x;
	private int y;
	private final int width;
	private final int height;
	private ResourceLocation texture;
	private int u;
	private int v;
	private int texWidth = 256;
	private int texHeight = 256;
	/** 源区域尺寸（0 = 与绘制尺寸相同）。用于把 16×16 字形缩到 8×8 这类"取一块、画成另一块尺寸"。 */
	private int srcWidth;
	private int srcHeight;
	private String text;
	private int color = 0xFFFFFFFF;
	private int hoverColor;
	/** 贴图基色（0 = 不着色）。真实界面里图标就是"字形 + 材料染色"，不是彩色贴图。 */
	private int spriteTint;
	private double delay;
	private float textScale = 1.0F;
	/** 文字阴影（参考实现 mutil 的 {@code GuiString.drawShadow} 默认 true —— 真实界面文字都有阴影）。 */
	private boolean textShadow = true;
	/**
	 * **8 向黑描边**（见 {@link OutlinedText}）。
	 *
	 * <p>真实 mutil 里描边是**另一个类** {@code GuiStringOutline}（而不是 {@code GuiString} 的一个开关），
	 * 用在"叠在图标上的小字"上：工具等级数字 {@code GuiTool.java:34} 就是
	 * {@code new GuiStringOutline(10, 8, "")}。默认 false —— {@link #text} 建出来的节点外观逐字不变。</p>
	 */
	private boolean textOutlined;
	/** 逐元素透明度（乘到最终 alpha 上）。真实界面里"整组淡入"就是给组内每个元素设同一个值。 */
	private float opacity = 1.0F;
	/** **悬停透明度**：鼠标在悬停区域内时用它代替 {@link #opacity}（未声明 → 不使用）。 */
	private float hoverOpacity;
	private boolean hasHoverOpacity;
	/** 悬停判定用的另一个节点的 key（null = 用本节点自己的矩形）。 */
	private String hoverRegionKey;
	/** 一次性脉冲时间轴（tag 变化时由框架触发）：静止值、延迟、各段时长与终点值。 */
	private String alphaTag;
	private double alphaRest;
	private long alphaDelayMs;
	private long[] alphaDurations;
	private double[] alphaTargets;
	/** 入场线性位移（毫秒）：从 (x+dx, y+dy) 滑到 (x, y)。 */
	private int slideX;
	private int slideY;
	private long slideMs = -1L;
	private String action;
	private int value;
	private boolean enabled = true;
	private String tooltip;
	/** disabled 时是否仍可悬停（真机语义；默认 false）。 */
	private boolean hoverWhenDisabled;
	/**
	 * **惰性 tooltip**：渲染/悬停时才求值。
	 *
	 * <p>真机 Tooltip 是"悬停时才 {@code getTooltipLines()}"（mutil {@code GuiElement.getTooltipLines}）；
	 * 而声明式节点是每帧重建的 —— 若把整串文本在构建时算出来，一屏几十条提示就等于每帧几十次
	 * {@code Component.translatable} + 语言表查找（实测属性条面板 ~1.0ms/帧）。用 supplier 把它推到
	 * "真要显示的时候"。</p>
	 */
	private java.util.function.Supplier<String> lazyTooltip;
	/** 惰性的按键条件 tooltip（与 {@link #keyedTooltips} 同义，只是文本推迟到渲染时算）。 */
	private List<LazyKeyedTooltip> lazyKeyedTooltips;

	/** 惰性按键条件 tooltip 的条目。 */
	public record LazyKeyedTooltip(String key, java.util.function.Supplier<String> supplier) {
	}
	/** **按键条件 tooltip**（有序：第一个"已按下"的键命中即用；没有命中的键时回落 {@link #tooltip}）。 */
	private List<KeyedTooltip> keyedTooltips;
	private int fillColor;
	private Layer layer = Layer.ABOVE;
	/** 绑定的存储槽索引（-1 = 不绑定）。 */
	private int slotIndex = -1;
	/** 绑定的玩家背包槽索引（0..35；-1 = 不绑定）。 */
	private int inventoryIndex = -1;

	private GuiNode(String key, int x, int y, int width, int height) {
		this.key = key;
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
	}

	/** 建节点（key 用于动画状态复用：同一 key 跨重建保持动画连续；移动会平滑滑过去）。 */
	public static GuiNode of(String key, int x, int y, int width, int height) {
		return new GuiNode(key, x, y, width, height);
	}

	/** 贴图节点：图集区域 (u,v) 画到 (x,y,w,h)（分母是图集尺寸）。 */
	public static GuiNode sprite(String key, int x, int y, int w, int h,
		ResourceLocation texture, int u, int v, int atlasWidth, int atlasHeight) {
		GuiNode node = new GuiNode(key, x, y, w, h);
		node.texture = texture;
		node.u = u;
		node.v = v;
		node.texWidth = Math.max(atlasWidth, 1);
		node.texHeight = Math.max(atlasHeight, 1);
		return node;
	}

	/**
	 * **带缩放的贴图节点**：从图集的 {@code (u,v,sw,sh)} 取一块，画到 {@code (x,y,w,h)}。
	 *
	 * <p>为什么必须有它：真实界面的图标是"16×16 字形画成 8×8"这种（Tetra 的次模块字形就是这样），
	 * 而 {@code blit} 的宽高同时充当屏幕尺寸和 UV 范围 —— 只写 blit 只能**裁切**，不能缩放。</p>
	 */
	public static GuiNode spriteRegion(String key, int x, int y, int w, int h,
		ResourceLocation texture, int u, int v, int sw, int sh, int atlasWidth, int atlasHeight) {
		GuiNode node = sprite(key, x, y, w, h, texture, u, v, atlasWidth, atlasHeight);
		node.srcWidth = Math.max(1, sw);
		node.srcHeight = Math.max(1, sh);
		return node;
	}

	/** 源区域宽（无缩放时等于绘制宽）。 */
	public int srcWidth() {
		return srcWidth > 0 ? srcWidth : width;
	}

	/** 源区域高（无缩放时等于绘制高）。 */
	public int srcHeight() {
		return srcHeight > 0 ? srcHeight : height;
	}

	/** 是否需要缩放绘制（源区域与目标尺寸不一致）。 */
	public boolean isScaled() {
		return srcWidth > 0 && (srcWidth != width || srcHeight != height);
	}

	/** **整图贴图节点**（分母 = 绘制尺寸，用于"贴图就是这么大"的图，例如物品栏底图）。 */
	public static GuiNode image(String key, int x, int y, int w, int h, ResourceLocation texture) {
		return sprite(key, x, y, w, h, texture, 0, 0, w, h);
	}

	/** 纯色填充节点（高亮、分隔线、按钮底）。 */
	public static GuiNode fill(String key, int x, int y, int w, int h, int color) {
		GuiNode node = new GuiNode(key, x, y, w, h);
		node.fillColor = color;
		return node;
	}

	/**
	 * **槽位绑定节点**：几何不在节点里，而是从界面声明的存储槽**现算**（面板像素）。
	 *
	 * <p>这是"槽位与节点永远对齐"的结构性保证：位置只有一个来源（
	 * {@link api.chasm.gui.ChasmStorageSlot#pixelX()}），谁也没机会写错第二份。
	 * 纯视觉/命中用 —— 物品与点击仍由原版槽位负责。</p>
	 *
	 * @param slotIndex 界面存储槽索引（{@code ChasmGui.storageSlots()} 的下标）
	 */
	public static GuiNode slot(String key, int slotIndex) {
		GuiNode node = new GuiNode(key, 0, 0, 16, 16);
		node.slotIndex = slotIndex;
		return node;
	}

	/**
	 * **玩家背包槽位节点**：几何来自 {@link PlayerInventoryLayout}（服务端建槽用的是同一份）。
	 *
	 * <p>典型用途：真实界面里"这个材料我背包里有"会给背包格子加高亮 —— 高亮框必须落在
	 * 真正的槽位上，而不能靠手抄坐标。</p>
	 *
	 * @param inventoryIndex 玩家背包索引（0..8 快捷栏，9..35 背包）
	 */
	public static GuiNode inventorySlot(String key, int inventoryIndex) {
		GuiNode node = new GuiNode(key, 0, 0, 16, 16);
		node.inventoryIndex = inventoryIndex;
		return node;
	}

	/** 是否纯色节点。 */
	public boolean isFill() {
		return fillColor != 0;
	}

	public int fillColor() {
		return fillColor;
	}

	/**
	 * **给节点上一层色块**（纯色填充）。
	 *
	 * <p>用在绑定节点上就是"给真实槽位加高亮" —— 矩形由槽位现算，颜色由这里给：
	 * {@code GuiNode.inventorySlot("mat:3", 3).tint(0x60FFFFCC)}。</p>
	 */
	public GuiNode tint(int argb) {
		this.fillColor = argb;
		return this;
	}

	/** 标记为垫底层（画在物品下面）。 */
	public GuiNode behind() {
		this.layer = Layer.BEHIND;
		return this;
	}

	public GuiNode text(String text, int color) {
		this.text = text;
		this.color = color;
		return this;
	}

	/**
	 * **8 向描边文字**：正文外面再画一圈黑边，**任何底色上都读得清**。
	 *
	 * <p>逐字复刻真实 mutil 的 {@code GuiStringOutline}（出处与逐行对照见 {@link OutlinedText}），
	 * 真实用它画的第一个地方就是工具等级数字：{@code GuiTool.java:34} 的
	 * {@code new GuiStringOutline(10, 8, "")}。构造时就 {@code drawShadow = false}
	 * （{@code GuiStringOutline.java:11}），所以这里也把 {@code textShadow} 关掉 ——
	 * 黑边 + 阴影叠在一起会让数字糊成一团。</p>
	 *
	 * <p>与 {@link #text} 的关系：这个方法就是 {@code text(...)} 之后多打开一个描边开关，
	 * 颜色/几何/命中/动画语义完全一样。{@link #text} 的行为**一个字都没变**。</p>
	 */
	public GuiNode textOutlined(String text, int color) {
		return text(text, color).outline(true);
	}

	/**
	 * 打开/关闭 8 向描边（给已经有文字的节点用；打开时同时按真实 {@code GuiStringOutline} 关掉阴影）。
	 *
	 * @see #textOutlined(String, int)
	 */
	public GuiNode outline(boolean outlined) {
		this.textOutlined = outlined;
		if (outlined) {
			this.textShadow = false;
		}
		return this;
	}

	/**
	 * **贴图基色**：给字形这类"单色掩码贴图"上材料色（真实 Tetra 的做法 —— 字形本身是灰的，
	 * 铁/金/钻石的颜色靠这里染出来）。悬停色优先级更高。
	 */
	public GuiNode spriteTint(int argb) {
		this.spriteTint = argb;
		return this;
	}

	/** 贴图基色（0 = 不着色）。 */
	public int spriteTint() {
		return spriteTint;
	}

	/** 悬停/选中时的颜色（贴图着色 + 文字色）；不设置则不变色。 */
	public GuiNode hover(int color) {
		this.hoverColor = color;
		return this;
	}

	/**
	 * **文字缩放**（真实界面的"小字"就是这么来的：Tetra 有 5px 高的 {@code GuiStringSmall}）。
	 *
	 * <p>只影响绘制大小，命中矩形仍是节点自身的矩形 —— 小字不该成为点击目标。</p>
	 */
	/**
	 * 放大/缩小文字。
	 *
	 * <p><b>只用 1.0 或 0.5</b>：原版字形是**预栅格化位图**，非整数缩放（0.7/0.8）会把 5×7 的字形
	 * 映到 3.5×4.9 像素，采样时整列/整行被丢掉 → 笔画缺棱缺角。渲染时会吸附到最近的
	 * "整数倍缩小"档位（≥0.75 → 1.0，否则 0.5）。</p>
	 */
	public GuiNode textScale(float scale) {
		if (scale <= 0.0F) {
			throw new IllegalArgumentException("文字缩放必须为正");
		}
		this.textScale = scale;
		return this;
	}

	/** **逐元素透明度**（0~1，乘到最终 alpha）。 */
	public GuiNode opacity(float value) {
		this.opacity = Math.max(0.0F, Math.min(1.0F, value));
		return this;
	}

	/**
	 * **一次性脉冲时间轴**（复刻 mutil 的 {@code KeyframeAnimation} + Tetra 的 flash/高亮错峰）。
	 *
	 * <p>当 {@code tag} 的值发生变化（或该节点首次出现）时，框架自动触发一次：
	 * 从**当前值**线性走到 {@code targets[0]}，再依次走完各段，最后停在最后一段终点。</p>
	 *
	 * <pre>{@code
	 * // 原版 flash：同贴图叠一层纯黑，alpha 0→0.3（40ms）→0（80ms）
	 * GuiNode.fill("flash", 44, 98, 239, 70, 0xFF000000)
	 *     .alphaTimeline(pageTag, 0.0, 0, new long[] { 40, 80 }, new double[] { 0.3, 0.0 });
	 * }</pre>
	 *
	 * <p>纯函数式：{@code ui = f(state)} 不被破坏 —— tag 就是 state 的一部分，
	 * 触发由框架比对前后帧自动完成，port 不需要"记住上一帧"。</p>
	 */
	public GuiNode alphaTimeline(String tag, double rest, long delayMs, long[] durationsMs, double[] targets) {
		this.alphaTag = tag;
		this.alphaRest = rest;
		this.alphaDelayMs = delayMs;
		this.alphaDurations = durationsMs.clone();
		this.alphaTargets = targets.clone();
		return this;
	}

	/** **入场线性位移**：从 (x+dx, y+dy) 滑到 (x, y)，用时 {@code durationMs}（Tetra 全是线性、无缓动）。 */
	public GuiNode slideIn(int dx, int dy, long durationMs) {
		this.slideX = dx;
		this.slideY = dy;
		this.slideMs = durationMs;
		return this;
	}

	/** 入场延迟（秒）：逐节点错开浮现。 */
	public GuiNode delay(double seconds) {
		this.delay = seconds;
		return this;
	}

	/** 点击动作：id 在服务端注册；value 是附带参数（如行号/索引）。 */
	public GuiNode action(String id, int value) {
		this.action = id;
		this.value = value;
		return this;
	}

	/** 禁用态：仍然画，但点击会被忽略（用于"材料不足"这类）。 */
	public GuiNode enabled(boolean enabled) {
		this.enabled = enabled;
		return this;
	}

	/**
	 * **基串 tooltip**（无条件显示的那一份）。
	 *
	 * <p>与按键展开的关系：真机 {@code GuiStatBar.getTooltipLines:221-245} 的分支是
	 * "{@code Screen.hasShiftDown()} ? {@code extendedTooltip} : {@code tooltip}" ——
	 * 这里 {@code tooltip} 就是那个 {@code this.tooltip}，展开版用
	 * {@link #tooltipWhen(String, String)} 声明。**本方法的行为一个字都没变**：
	 * 只调用 {@code tooltip(x)} 的节点，{@link #tooltip()} / {@link #tooltipAt(Predicate)}
	 * 在任何按键状态下都返回同一个 {@code x}。</p>
	 */
	public GuiNode tooltip(String tooltip) {
		this.tooltip = tooltip;
		return this;
	}

	/**
	 * **惰性基串 tooltip**：求值推迟到渲染/悬停那一刻（{@link #tooltipAt}）。
	 *
	 * <p>语义与 {@link #tooltip(String)} 完全相同，只是把"什么时候算"推后；
	 * 两者同时声明时以 {@code tooltip(String)} 为准。</p>
	 */
	public GuiNode tooltipLazy(java.util.function.Supplier<String> supplier) {
		this.lazyTooltip = supplier;
		return this;
	}

	/** 是否声明了惰性 tooltip。 */
	public boolean hasLazyTooltip() {
		return lazyTooltip != null;
	}

	/**
	 * **disabled 时仍然参与悬停**（默认 false）。
	 *
	 * <p>真机 mutil 的 {@code GuiButton} 把 {@code enabled} 只用在点击判定上，
	 * {@code onFocus/onBlur} 与 {@code getTooltipLines} 照常 —— 所以"不能点"和"不能悬停"是两件事。
	 * 端口以前因为框架把两者绑在一起，只能靠"不设 disabled"绕过（见 {@code CraftButtonGui} 那条注释）。</p>
	 *
	 * <p>点击路径不受影响：{@code DeclClient.hitTest} 仍然只认 {@code enabled}。</p>
	 */
	public GuiNode hoverWhenDisabled(boolean value) {
		this.hoverWhenDisabled = value;
		return this;
	}

	/** 是否在 disabled 时仍参与悬停。 */
	public boolean isHoverWhenDisabled() {
		return hoverWhenDisabled;
	}

	/**
	 * **按键条件 tooltip**：某个键按下时，整份 tooltip 换成 {@code text}。
	 *
	 * <p>真实出处（逐条对照）：</p>
	 * <ul>
	 *   <li>按键分支 {@code GuiStatBar.getTooltipLines:221-229}：
	 *       {@code if (Screen.hasShiftDown()) return extendedTooltip; return tooltip;}
	 *       ——<b>按下 shift 时返回的是另一整份</b>，不是追加；</li>
	 *   <li>展开版的构造 {@code GuiStatBar.getCombinedTooltipExtended:262-294}：
	 *       基串 + {@code " "} 行 + {@code Tooltips.expanded}（{@code item.tetra.tooltip_expanded}）
	 *       + 灰色扩展正文；</li>
	 *   <li>工具行同款 {@code TooltipGetterTool.hasExtendedTooltip:69-71} 恒 true →
	 *       {@code getCombinedTooltip:254-257} 必加 {@code " "} + {@code item.tetra.tooltip_expand}。</li>
	 * </ul>
	 *
	 * <p>求值时机是**渲染时**：{@code key} 是否按下由
	 * {@link api.chasm.gui.decl.client.DeclClient#keyState} 在每帧渲染该节点时决定，
	 * 节点本身不缓存任何按键状态 —— {@code ui = f(state)} 不被破坏。</p>
	 *
	 * <p>多个条件按声明顺序匹配，**第一个命中的键胜出**（所以后声明的键优先级更低）；
	 * 都没有命中时回到 {@link #tooltip(String)} 的基串。</p>
	 *
	 * @param key  按键名（真实 shift = {@link #SHIFT_KEY}，见 {@code DeclClient}）
	 * @param text 该键按下时显示的**整份** tooltip
	 */
	/**
	 * **惰性的按键条件 tooltip**：与 {@link #tooltipWhen(String, String)} 语义相同，
	 * 只是文本在渲染/悬停时才由 supplier 求出（真机同样只在 {@code getTooltipLines} 时构造）。
	 */
	public GuiNode tooltipWhenLazy(String key, java.util.function.Supplier<String> supplier) {
		if (key == null || key.isEmpty()) {
			throw new IllegalArgumentException("按键名不能为空");
		}
		if (supplier == null) {
			throw new IllegalArgumentException("按键条件 tooltip 的 supplier 不能为 null");
		}
		if (lazyKeyedTooltips == null) {
			lazyKeyedTooltips = new ArrayList<>(2);
		}
		lazyKeyedTooltips.add(new LazyKeyedTooltip(key, supplier));
		return this;
	}

	public GuiNode tooltipWhen(String key, String text) {
		if (key == null || key.isEmpty()) {
			throw new IllegalArgumentException("按键名不能为空");
		}
		if (text == null) {
			throw new IllegalArgumentException("按键条件 tooltip 文本不能为 null");
		}
		if (keyedTooltips == null) {
			keyedTooltips = new ArrayList<>(2);
		}
		keyedTooltips.add(new KeyedTooltip(key, text));
		return this;
	}

	/** **shift** 的按键名（真机 {@code Screen.hasShiftDown}，见 {@code DeclClient}）。 */
	public static final String SHIFT_KEY = "shift";

	/**
	 * **一条按键条件 tooltip**：键名 + 该键按下时要显示的整份文本。
	 *
	 * @param key  按键名（如 {@link #SHIFT_KEY}）
	 * @param text 该键按下时的整份 tooltip
	 */
	public record KeyedTooltip(String key, String text) {
	}

	/** 已声明的按键条件 tooltip（声明顺序；没有则空列表）。 */
	public List<KeyedTooltip> keyedTooltips() {
		return keyedTooltips == null ? List.of() : List.copyOf(keyedTooltips);
	}

	/** 是否声明过按键条件 tooltip。 */
	public boolean hasKeyedTooltips() {
		return keyedTooltips != null && !keyedTooltips.isEmpty();
	}

	/**
	 * **按当前按键状态取这一帧要显示的 tooltip**（渲染时求值）。
	 *
	 * @param keyDown 判断某个键此刻是否按下；真实实现读 {@code Screen.hasShiftDown()}
	 *                （{@code GuiStatBar.java:223}），无 GUI 单测可注入假实现
	 * @return 第一个按下键对应的整份文本；没有按键条件或都没有按下时 = {@link #tooltip()}
	 */
	public String tooltipAt(Predicate<String> keyDown) {
		if (keyedTooltips != null && keyDown != null) {
			for (KeyedTooltip entry : keyedTooltips) {
				if (keyDown.test(entry.key())) {
					return entry.text();
				}
			}
		}
		if (lazyKeyedTooltips != null && keyDown != null) {
			for (LazyKeyedTooltip entry : lazyKeyedTooltips) {
				if (keyDown.test(entry.key())) {
					String text = entry.supplier().get();
					if (text != null) {
						// supplier 返回 null = "这一条没有展开版" → 继续找下一条/回落到基串，
						// 绝不因为按住 shift 反而把提示弄没了。
						return text;
					}
				}
			}
		}
		return resolveTooltip();
	}

	/** 基串 tooltip：优先显式字符串，其次惰性 supplier（求值推迟到这里）。 */
	private String resolveTooltip() {
		return tooltip != null ? tooltip : (lazyTooltip == null ? null : lazyTooltip.get());
	}

	public String key() {
		return key;
	}

	public int x() {
		return x;
	}

	public int y() {
		return y;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public ResourceLocation texture() {
		return texture;
	}

	public int u() {
		return u;
	}

	public int v() {
		return v;
	}

	public int texWidth() {
		return texWidth;
	}

	public int texHeight() {
		return texHeight;
	}

	public String text() {
		return text;
	}

	public int color() {
		return color;
	}

	/** 悬停色（0 = 未设置）。 */
	public int hoverColor() {
		return hoverColor;
	}

	public double delay() {
		return delay;
	}

	/** 文字缩放（1 = 原尺寸）。 */
	/** 文字阴影开关（默认 true）。 */
	public GuiNode textShadow(boolean shadow) {
		this.textShadow = shadow;
		return this;
	}

	public boolean textShadow() {
		return textShadow;
	}

	/** 文字是否走 8 向描边（{@link #textOutlined(String, int)} / {@link #outline(boolean)}）。 */
	public boolean isTextOutlined() {
		return textOutlined;
	}

	public float textScale() {
		return textScale;
	}

	/** 逐元素透明度。 */
	public float opacity() {
		return opacity;
	}

	/**
	 * **悬停透明度**：鼠标指针落在悬停区域内时，用这个值代替 {@link #opacity()}。
	 *
	 * <p>真实出处（真机页签的 label 与键位提示就是这么出来的）：
	 * {@code VerticalTabButtonGui.java:46,53} 把二者 opacity 初值设为 0，
	 * {@code :95-104} 的 {@code onFocus()}（鼠标进入命中区）启动 {@code labelShow}/{@code keybindShow}
	 * 两条 {@code KeyframeAnimation} 淡入到 1，{@code :106-116} 的 {@code onBlur()}（移出）淡回 0。
	 * 也就是说"悬停"只改**这一处透明度**，不改节点的存在与命中。</p>
	 *
	 * <p><b>在渲染时求值</b>：节点只记住这个声明，本帧 hovered 与否由
	 * {@link api.chasm.gui.decl.client.DeclClient#render} 用当帧鼠标位置现算
	 * （{@code DeclClient.alphaMultiplier(animKey, node, hovered)}）—— 和按键条件 tooltip
	 * 一样，{@code ui = f(state)} 之外没有第二份缓存。hovered 为 false 时仍然用
	 * {@link #opacity()}，所以**不声明本方法的节点行为逐字不变**。</p>
	 *
	 * <p>悬停区域默认是**本节点自己的矩形**（面板像素空间，与绘制用的是同一个
	 * {@link #rect(ChasmMenu)}）。需要"悬停 A 却改 B 的透明度"时用
	 * {@link #opacityWhenHovered(float, String)}。</p>
	 *
	 * @param value 悬停时的透明度（0~1，越界会被夹取）
	 */
	public GuiNode opacityWhenHovered(float value) {
		this.hoverOpacity = Math.max(0.0F, Math.min(1.0F, value));
		this.hasHoverOpacity = true;
		return this;
	}

	/**
	 * **在另一个节点的矩形上悬停时改变本节点透明度**。
	 *
	 * <p>为什么需要它：真机的悬停判定挂在**按钮本体**（{@code GuiClickable} 的命中区）上，
	 * label 与键位提示是它的子元素 —— 鼠标只要进入按钮矩形，子元素就淡入，而不是"鼠标要压在
	 * 文字上"（{@code VerticalTabButtonGui.java:95-104} 的 {@code onFocus} 属于按钮，不属于 label）。
	 * 所以这里允许把悬停区域指向同一次节点列表里的另一个 key（如页签的 {@code tab:hit:N}）。</p>
	 *
	 * <p>区域节点的矩形同样取自面板像素空间；该 key 在本帧列表里不存在时回落到本节点自己的矩形。</p>
	 *
	 * @param value          悬停时的透明度（0~1，越界会被夹取）
	 * @param hoverRegionKey 提供悬停矩形的节点 key（必须在同一份节点列表里）
	 */
	public GuiNode opacityWhenHovered(float value, String hoverRegionKey) {
		if (hoverRegionKey == null || hoverRegionKey.isEmpty()) {
			throw new IllegalArgumentException("悬停区域节点 key 不能为空");
		}
		opacityWhenHovered(value);
		this.hoverRegionKey = hoverRegionKey;
		return this;
	}

	/** 是否声明了悬停透明度（{@link #opacityWhenHovered(float)}）。 */
	public boolean hasHoverOpacity() {
		return hasHoverOpacity;
	}

	/** 声明的悬停透明度（未声明时为 0，无意义）。 */
	public float hoverOpacity() {
		return hoverOpacity;
	}

	/** 悬停判定用的节点 key（null = 用本节点自己的矩形）。 */
	public String hoverRegionKey() {
		return hoverRegionKey;
	}

	/**
	 * **本帧真正生效的透明度**：悬停且声明过 → 悬停值；否则 → {@link #opacity()}。
	 *
	 * <p>求值在渲染时进行，节点不保存"当前是否悬停"。</p>
	 */
	public float opacityAt(boolean hovered) {
		return hovered && hasHoverOpacity ? hoverOpacity : opacity;
	}

	/** 是否有一次性脉冲时间轴。 */
	public boolean hasAlphaTimeline() {
		return alphaTag != null;
	}

	/** 脉冲触发标签（值变化即触发一次）。 */
	public String alphaTag() {
		return alphaTag;
	}

	public double alphaRest() {
		return alphaRest;
	}

	public long alphaDelayMs() {
		return alphaDelayMs;
	}

	public long[] alphaDurations() {
		return alphaDurations;
	}

	public double[] alphaTargets() {
		return alphaTargets;
	}

	/** 是否有入场位移。 */
	public boolean hasSlide() {
		return slideMs > 0;
	}

	public int slideX() {
		return slideX;
	}

	public int slideY() {
		return slideY;
	}

	public long slideMs() {
		return slideMs;
	}

	public String action() {
		return action;
	}

	public int value() {
		return value;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public String tooltip() {
		return resolveTooltip();
	}

	public Layer layer() {
		return layer;
	}

	/** 绑定的存储槽索引（-1 = 不绑定）。 */
	public int slotIndex() {
		return slotIndex;
	}

	/** 绑定的玩家背包槽索引（-1 = 不绑定）。 */
	public int inventoryIndex() {
		return inventoryIndex;
	}

	/** 是否绑定了某个真实槽位（几何要从菜单现算）。 */
	public boolean isBound() {
		return slotIndex >= 0 || inventoryIndex >= 0;
	}

	/**
	 * **几何**：面板像素矩形。绑定节点从菜单现算（唯一来源），其余用自身坐标。
	 *
	 * <p>绘制与命中都走这一个方法 —— 这就是"所见即所点"。</p>
	 */
	public GuiRect rect(ChasmMenu menu) {
		if (slotIndex >= 0 && menu != null && slotIndex < menu.gui().storageSlots().size()) {
			var slot = menu.gui().storageSlots().get(slotIndex);
			// 位置带菜单：声明了 slotPxDynamic 的槽位几何由**当前同步状态**现算 ——
			// 与 ChasmMenu.createStorageSlot 走的是同一个入口，所以"画在哪"与"能点到哪"永远一致
			return new GuiRect(slot.pixelX(menu), slot.pixelY(menu), 16, 16);
		}
		if (inventoryIndex >= 0 && menu != null) {
			PlayerInventoryLayout bag = menu.gui().playerInventory();
			return new GuiRect(bag.slotX(inventoryIndex), bag.slotY(inventoryIndex), 16, 16);
		}
		return new GuiRect(x, y, width, height);
	}

	@Override
	public String toString() {
		return "GuiNode[" + key + " " + x + "," + y + " " + width + "x" + height + "]";
	}
}
