package api.chasm.tool;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * **工具动作注册表**（框架能力）—— 对应 Forge 的 {@code ToolAction/ToolActions}。
 *
 * <h2>为什么是"驻留表"而不是 enum</h2>
 * <p>真实数据里的 {@code tools} 键<b>就是动作名</b>，而且存在纯靠 JSON 造出来、
 * 从没在 Java 里声明过的动作。做成 enum 或白名单会让这些数据直接失效，
 * 所以这里保持"任意字符串 → 惰性驻留实例"的语义。</p>
 *
 * <h2>键的规范</h2>
 * <p>规范键是<b>裸名</b>（{@code pickaxe_dig}、{@code cut}、{@code till}…）。
 * 带命名空间前缀会得到另一个实例（死键），所以 {@link #get} 会容错剥前缀，
 * 保证数据怎么写都能命中。</p>
 */
public final class ChasmToolActions {

	/** 一个工具动作（值语义：同名即同动作，可安全当 Map 键）。 */
	public record Action(String name) {
		@Override
		public String toString() {
			return name;
		}
	}

	private static final Map<String, Action> ACTIONS = new ConcurrentHashMap<>();

	private ChasmToolActions() {
	}

	/** 取（或创建）一个动作；允许带命名空间（会被剥掉，只认裸名）。 */
	public static Action get(String name) {
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("工具动作名不可为空");
		}
		return ACTIONS.computeIfAbsent(canonical(name), Action::new);
	}

	/** 取（或创建）一个动作（按 id 的 path）。 */
	public static Action of(ResourceLocation id) {
		return get(id.getPath());
	}

	/** 规范化动作名（剥前缀）。 */
	public static String canonical(String name) {
		if (name == null) {
			return null;
		}
		int i = name.indexOf(':');
		return i >= 0 ? name.substring(i + 1) : name;
	}

	public static boolean isKnown(String name) {
		return name != null && ACTIONS.containsKey(canonical(name));
	}

	/** 当前已驻留的动作数（调试用）。 */
	public static int size() {
		return ACTIONS.size();
	}

	// ------------------------------------------------ 常用动作常量（裸名，以数据键为准）

	public static final Action PICKAXE_DIG = get("pickaxe_dig");
	public static final Action AXE_DIG = get("axe_dig");
	public static final Action SHOVEL_DIG = get("shovel_dig");
	public static final Action HOE_DIG = get("hoe_dig");
	public static final Action SHEARS_DIG = get("shears_dig");
	public static final Action SWORD_DIG = get("sword_dig");
	public static final Action AXE_STRIP = get("axe_strip");
	public static final Action AXE_SCRAPE = get("axe_scrape");
	public static final Action AXE_WAX_OFF = get("axe_wax_off");
	public static final Action SHOVEL_FLATTEN = get("shovel_flatten");
	/** ⚠️ 原版动作名就是 {@code till}，不是 {@code hoe_till}。 */
	public static final Action TILL = get("till");
	/** 切割（模块用它提供劈砍效率）。 */
	public static final Action CUT = get("cut");
	/** 锤击（拆除/锻造）。 */
	public static final Action HAMMER_DIG = get("hammer_dig");
}
