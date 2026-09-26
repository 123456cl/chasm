package api.chasm.contrib.socket;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.RandomSource;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 纯度/品质阶梯（**开放注册表**）：宝石、材料、掉落物的"同一种东西的不同档次"。
 *
 * <p>对照 Apotheosis 的 {@code Purity}（cracked/chipped/flawed/normal/flawless/perfect）：
 * 那边是一个**硬编码枚举**；这里改成可注册阶梯——任何模组都能追加自己的档位
 * （"远古"、"神话之上"、材料品质、难度星级……同一套语义）。</p>
 *
 * <p>三个语义被统一：</p>
 * <ul>
 *   <li><b>顺序</b>：注册顺序即阶梯顺序，{@link #atLeast}/{@link #next}/{@link #max} 全部基于它；</li>
 *   <li><b>命名</b>：{@code nameTemplate}（如"碎裂的%s"）→ 宝石名的前缀，与颜色一起决定显示；</li>
 *   <li><b>外观</b>：颜色（或彩虹渐变），由 {@link #name(String)} 直接产出成品 Component。</li>
 * </ul>
 *
 * <p>注意：所有档位必须在模组初始化期注册（注册表只读快照），运行期不再变更。</p>
 */
public final class GemPurity implements Comparable<GemPurity> {

	private static final Map<String, GemPurity> BY_PATH = new ConcurrentHashMap<>();
	private static final List<GemPurity> ORDER = new CopyOnWriteArrayList<>();

	private final String path;
	private final String displayName;
	private final String nameTemplate;
	private final int color;
	private final boolean rainbow;
	private final double weight;
	private final int rank;

	private GemPurity(String path, String displayName, String nameTemplate, int color, boolean rainbow,
		double weight) {
		if (path == null || path.isBlank()) {
			throw new IllegalArgumentException("纯度 path 不可为空");
		}
		if (displayName == null || displayName.isBlank()) {
			throw new IllegalArgumentException("纯度 " + path + " 缺少显示名");
		}
		if (!(weight >= 0)) {
			throw new IllegalArgumentException("纯度 " + path + " 的权重不可为负: " + weight);
		}
		this.path = path;
		this.displayName = displayName;
		this.nameTemplate = nameTemplate == null || nameTemplate.isBlank() ? "%s" : nameTemplate;
		this.color = color;
		this.rainbow = rainbow;
		this.weight = weight;
		this.rank = ORDER.size();
	}

	// ---------------------------------------------------------------- 注册

	/** 注册档位（固定颜色）。 */
	public static GemPurity register(String path, String displayName, String nameTemplate, int color,
		double weight) {
		return register0(path, displayName, nameTemplate, color, false, weight);
	}

	/** 注册档位（彩虹渐变名，对应神化的 {@code GradientColor.RAINBOW}）。 */
	public static GemPurity registerRainbow(String path, String displayName, String nameTemplate, double weight) {
		return register0(path, displayName, nameTemplate, 0xFFFFFFFF, true, weight);
	}

	private static synchronized GemPurity register0(String path, String displayName, String nameTemplate, int color,
		boolean rainbow, double weight) {
		if (BY_PATH.containsKey(path)) {
			throw new IllegalStateException("纯度 " + path + " 已注册");
		}
		GemPurity purity = new GemPurity(path, displayName, nameTemplate, color, rainbow, weight);
		ORDER.add(purity);
		BY_PATH.put(path, purity);
		return purity;
	}

	/** 按 path 取（无则 null）。 */
	public static GemPurity get(String path) {
		return path == null ? null : BY_PATH.get(path);
	}

	/** 全部档位（按阶梯顺序）。 */
	public static List<GemPurity> all() {
		return Collections.unmodifiableList(ORDER);
	}

	public static int size() {
		return ORDER.size();
	}

	/** 最低档（未注册任何档位时 null）。 */
	public static GemPurity lowest() {
		return ORDER.isEmpty() ? null : ORDER.get(0);
	}

	/** 最高档。 */
	public static GemPurity highest() {
		return ORDER.isEmpty() ? null : ORDER.get(ORDER.size() - 1);
	}

	/** 不低于 min 的全部档位（含 min；静态版，注意与实例的 {@link #atLeast(GemPurity)} 区分）。 */
	public static List<GemPurity> startingAt(GemPurity min) {
		if (min == null) {
			return all();
		}
		return ORDER.stream().filter(p -> p.atLeast(min)).toList();
	}

	/** 权重抽取（全零或空表则均匀）。 */
	public static GemPurity roll(RandomSource rand) {
		return roll(rand, ORDER);
	}

	/** 在给定池中权重抽取（池空则均匀）。 */
	public static GemPurity roll(RandomSource rand, List<GemPurity> pool) {
		if (pool == null || pool.isEmpty()) {
			return null;
		}
		double total = pool.stream().mapToDouble(GemPurity::weight).sum();
		if (total <= 0) {
			return pool.get(rand.nextInt(pool.size()));
		}
		double r = rand.nextDouble() * total;
		for (GemPurity purity : pool) {
			r -= purity.weight();
			if (r <= 0) {
				return purity;
			}
		}
		return pool.get(pool.size() - 1);
	}

	/** 不低于 min 的权重抽取。 */
	public static GemPurity rollAtLeast(RandomSource rand, GemPurity min) {
		return roll(rand, startingAt(min));
	}

	public static GemPurity max(GemPurity a, GemPurity b) {
		if (a == null) {
			return b;
		}
		if (b == null) {
			return a;
		}
		return a.rank >= b.rank ? a : b;
	}

	// ---------------------------------------------------------------- 语义

	public String path() {
		return path;
	}

	public String displayName() {
		return displayName;
	}

	public String nameTemplate() {
		return nameTemplate;
	}

	/** RGB（0xRRGGBB）。 */
	public int rgb() {
		return color & 0xFFFFFF;
	}

	public boolean rainbow() {
		return rainbow;
	}

	public double weight() {
		return weight;
	}

	public int rank() {
		return rank;
	}

	/** 本档位是否不低于 other（阶梯比较）。 */
	public boolean atLeast(GemPurity other) {
		return other == null || this.rank >= other.rank;
	}

	/** 上一档（已是最高则返回自身）。 */
	public GemPurity next() {
		return rank + 1 < ORDER.size() ? ORDER.get(rank + 1) : this;
	}

	@Override
	public int compareTo(GemPurity o) {
		return Integer.compare(this.rank, o.rank);
	}

	// ---------------------------------------------------------------- 显示

	/** 用档位命名模板套一个基础名（如 "碎裂的" + "战斗宝石"）。 */
	public String formatName(String baseName) {
		return nameTemplate.contains("%s") ? nameTemplate.replace("%s", baseName) : nameTemplate + baseName;
	}

	/** 档位自身的名字（带颜色）。 */
	public Component component() {
		return colored(displayName);
	}

	/** 成品名：模板 + 颜色（彩虹档逐字渐变）。 */
	public Component name(String baseName) {
		return colored(formatName(baseName));
	}

	/** 给任意文本上本档位的颜色（彩虹则逐字渐变）。 */
	public MutableComponent colored(String text) {
		if (text == null || text.isEmpty()) {
			return Component.empty();
		}
		if (!rainbow) {
			return Component.literal(text).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb())));
		}
		MutableComponent out = Component.empty();
		int n = text.length();
		for (int i = 0; i < n; i++) {
			int rgb = java.awt.Color.HSBtoRGB(i / (float) Math.max(1, n), 1.0F, 1.0F);
			out.append(Component.literal(String.valueOf(text.charAt(i)))
				.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb))));
		}
		return out;
	}

	@Override
	public String toString() {
		return "GemPurity[" + path + "]";
	}
}
