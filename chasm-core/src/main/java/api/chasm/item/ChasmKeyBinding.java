package api.chasm.item;

import net.minecraft.resources.ResourceLocation;

/**
 * Chasm 自定义按键绑定的句柄（第十二步）。
 *
 * <p>一个逻辑按键在共享/服务端环境（{@link api.chasm.key.ChasmKeys#register}）中只是一个"声明"：
 * 记录 id、默认键码与显示名，<b>不触碰任何客户端类</b>（因此可在服务端安全链接）。
 * 真正的原版 {@code KeyMapping} 由客户端初始化器 {@code api.chasm.key.client.ChasmKeyClient}
 * 维护：它会在客户端初始化时为每个声明创建 {@code KeyMapping} 并注册到原版「选项 -&gt;
 * 按键控制」列表（分类 {@code key.category.chasm}），玩家可自由改键、冲突检测由原版自动
 * 处理，无需本框架写任何配置界面。</p>
 *
 * <p>客户端坐标系的类（{@code KeyMapping} 等）一律只出现在客户端初始化器中，
 * 本类不持有其引用，确保服务端／共享环境链接安全。</p>
 *
 * <p><b>包级解耦</b>：本类放在 {@code api.chasm.item} 包（与 {@code ItemBuilder} 同包），
 * 使「物品 ↔ 按键」包级依赖单向化（{@code api.chasm.key → api.chasm.item}），
 * 打破循环依赖。</p>
 */
public final class ChasmKeyBinding {

	private final ResourceLocation id;
	private final String displayName;
	private final int defaultKeyCode;

	public ChasmKeyBinding(ResourceLocation id, String displayName, int defaultKeyCode) {
		this.id = id;
		this.displayName = displayName;
		this.defaultKeyCode = defaultKeyCode;
	}

	/** 逻辑按键 id（{@code modId:name}），用于物品绑定与网络包对应。 */
	public ResourceLocation id() {
		return id;
	}

	/** 显示名（DataGen 自动生成按键可见文案时用）。 */
	public String displayName() {
		return displayName;
	}

	/** 默认键码（GLFW key code，例如 O = 79）。 */
	public int defaultKeyCode() {
		return defaultKeyCode;
	}

	/** 按键显示名的翻译键（{@code key.chasm.<modId>.<name>}）。 */
	public String translationKey() {
		return "key.chasm." + id.getNamespace() + "." + id.getPath();
	}

	@Override
	public String toString() {
		return "ChasmKeyBinding[" + id + ", key=" + defaultKeyCode + "]";
	}
}
