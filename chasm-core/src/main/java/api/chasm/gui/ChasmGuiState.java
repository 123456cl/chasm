package api.chasm.gui;

import api.chasm.log.ChasmLogger;

import com.mojang.serialization.Codec;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.inventory.ContainerData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 声明式界面的**数据状态**（服务端权威 + 双通道同步）。
 *
 * <p>解决"机器/进度类界面没有数值通道"这一结构性缺口。两条通道按数据特征自动分流：</p>
 *
 * <ol>
 *   <li><b>整数通道</b>（{@link IntSlot}）：走原版 {@code ContainerData}（{@link #containerData()} →
 *       {@code AbstractContainerMenu.addDataSlots}）。原版每 tick 只对**变化的槽**发
 *       {@code ClientboundContainerSetDataPacket}，零自定义包、天然增量。</li>
 *   <li><b>大值通道</b>（{@link ValueKey}）：任意 codec 类型（字符串、record、列表…）走一条
 *       **批量快照包**（{@link ChasmGuiStateS2CPayload}）。采用"脏标记 + 每 tick 合并"：
 *       同一 tick 内改 N 次只发一次，且只发变化项。</li>
 * </ol>
 *
 * <p><b>为什么必须分两条</b>：javap 核实过 1.21.1 的 {@code ClientboundContainerSetDataPacket}
 * 的值用 {@code writeShort} 编码——整数通道是 <b>16 位</b>（-32768~32767）。能量 10 万、
 * 经验 200 万这类大数会被截断，必须走大值通道。{@link IntSlot} 在构建期就拒绝越界范围并把
 * 开发者引导到 {@code .state(...)}，从源头避免"悄悄截断"。</p>
 *
 * <p><b>客户端平滑</b>（{@link #smooth(String)} / {@link #tickSmooth(double)}）：服务端 20Hz
 * 更新，客户端按帧绘制；直接画原值会看到一格一格跳。平滑值以客户端 tick 推进、渲染帧读取，
 * 纯表现层（不影响服务端权威值）。</p>
 */
public final class ChasmGuiState {

	/** 整数通道的线上下限（原版用 short 编码）。 */
	public static final int WIRE_MIN = Short.MIN_VALUE;

	/** 整数通道的线上上限（原版用 short 编码）。 */
	public static final int WIRE_MAX = Short.MAX_VALUE;

	/**
	 * 整数通道的键声明。
	 *
	 * @param key          键名（界面内唯一）
	 * @param defaultValue 初始值
	 * @param min          最小值（含）
	 * @param max          最大值（含）
	 */
	public record IntSlot(String key, int defaultValue, int min, int max) {
		public IntSlot {
			Objects.requireNonNull(key, "key");
			if (key.isBlank()) {
				throw new IllegalArgumentException("整数键名不可为空");
			}
			if (min > max) {
				throw new IllegalArgumentException("键 " + key + " 的 min(" + min + ") > max(" + max + ")");
			}
			if (defaultValue < min || defaultValue > max) {
				throw new IllegalArgumentException(
					"键 " + key + " 的默认值 " + defaultValue + " 不在 [" + min + ", " + max + "] 内");
			}
			if (min < WIRE_MIN || max > WIRE_MAX) {
				throw new IllegalArgumentException("键 " + key + " 的范围 [" + min + ", " + max
					+ "] 超出整数通道的 16 位线上范围 [" + WIRE_MIN + ", " + WIRE_MAX + "]；"
					+ "大数值请改用 .state(key, codec, default) 大值通道");
			}
		}
	}

	/**
	 * 大值通道的键声明（任意 codec 类型）。
	 *
	 * @param key          键名（界面内唯一）
	 * @param codec        编解码器（NBT 编码传输）
	 * @param defaultValue 初始值（服务端权威初值）
	 * @param <T>          值类型
	 */
	public record ValueKey<T>(String key, Codec<T> codec, T defaultValue) {
		public ValueKey {
			Objects.requireNonNull(key, "key");
			Objects.requireNonNull(codec, "codec");
			Objects.requireNonNull(defaultValue, "defaultValue");
			if (key.isBlank()) {
				throw new IllegalArgumentException("大值键名不可为空");
			}
		}
	}

	private final List<IntSlot> intSlots;
	private final Map<String, Integer> intIndex;
	private final int[] ints;

	private final List<ValueKey<?>> valueKeys;
	private final Map<String, Integer> valueIndex;
	private final Object[] values;

	/** 大值脏项（索引）；{@code fullDirty} = 打开时全量推送。 */
	private final Set<Integer> dirty = new LinkedHashSet<>();
	private boolean fullDirty = true;

	/** 客户端平滑用的显示值（纯表现层）。 */
	private final double[] smooth;

	public ChasmGuiState(List<IntSlot> intSlots, List<ValueKey<?>> valueKeys) {
		this.intSlots = List.copyOf(intSlots);
		this.valueKeys = List.copyOf(valueKeys);
		this.ints = new int[this.intSlots.size()];
		this.intIndex = new LinkedHashMap<>();
		for (int i = 0; i < this.intSlots.size(); i++) {
			IntSlot slot = this.intSlots.get(i);
			if (intIndex.put(slot.key(), i) != null) {
				throw new IllegalArgumentException("整数键重复: " + slot.key());
			}
			ints[i] = slot.defaultValue();
		}
		this.values = new Object[this.valueKeys.size()];
		this.valueIndex = new LinkedHashMap<>();
		for (int i = 0; i < this.valueKeys.size(); i++) {
			ValueKey<?> key = this.valueKeys.get(i);
			if (valueIndex.put(key.key(), i) != null) {
				throw new IllegalArgumentException("大值键重复: " + key.key());
			}
			values[i] = key.defaultValue();
		}
		this.smooth = new double[this.ints.length];
		resetSmooth();
	}

	// ------------------------------------------------------- 整数通道

	/** 整数键列表（声明顺序 = 数据槽索引顺序）。 */
	public List<IntSlot> intSlots() {
		return intSlots;
	}

	/** 大值键列表。 */
	public List<ValueKey<?>> valueKeys() {
		return valueKeys;
	}

	public boolean hasIntData() {
		return ints.length > 0;
	}

	public boolean hasValueData() {
		return values.length > 0;
	}

	/** 是否声明了该整数键（控件回写判定用，不抛异常）。 */
	public boolean hasIntKey(String key) {
		return intIndex.containsKey(key);
	}

	/** 是否声明了该大值键。 */
	public boolean hasValueKey(String key) {
		return valueIndex.containsKey(key);
	}

	/** 读取整数（服务端读权威值；客户端读已同步值）。未知键抛异常（早暴露拼写错误）。 */
	public int intValue(String key) {
		return ints[intIndexOf(key)];
	}

	/**
	 * 写入整数（服务端）。自动夹取到声明的 [min, max]。
	 *
	 * <p>写入后无需手动同步：原版 {@code AbstractContainerMenu.broadcastChanges()} 会在本 tick
	 * 发现该槽变化并只把这一个槽发给客户端。</p>
	 *
	 * @return 实际写入（夹取后）的值
	 */
	public int setInt(String key, int value) {
		int index = intIndexOf(key);
		IntSlot slot = intSlots.get(index);
		int clamped = Math.max(slot.min(), Math.min(slot.max(), value));
		ints[index] = clamped;
		return clamped;
	}

	/** 原版数据槽视图（供 {@code AbstractContainerMenu.addDataSlots} 使用）。 */
	public ContainerData containerData() {
		return new ContainerData() {
			@Override
			public int get(int index) {
				return index >= 0 && index < ints.length ? ints[index] : 0;
			}

			@Override
			public void set(int index, int value) {
				// 服务端：本地写入；客户端：接收原版数据包时由原版调用本方法写入
				if (index >= 0 && index < ints.length) {
					ints[index] = value;
				}
			}

			@Override
			public int getCount() {
				return ints.length;
			}
		};
	}

	// ------------------------------------------------------- 大值通道

	/** 读取大值（服务端权威值 / 客户端已同步值）。 */
	@SuppressWarnings("unchecked")
	public <T> T value(String key) {
		return (T) values[valueIndexOf(key)];
	}

	/**
	 * 写入大值（服务端）。**值未变化时不置脏、不发送**。
	 */
	public <T> void setValue(String key, T value) {
		Objects.requireNonNull(value, "value");
		int index = valueIndexOf(key);
		if (Objects.equals(values[index], value)) {
			return;
		}
		values[index] = value;
		dirty.add(index);
	}

	/** 是否有待发送的大值（服务端 tick 时调用）。 */
	public boolean hasDirty() {
		return fullDirty || !dirty.isEmpty();
	}

	/** 强制全量重推（打开界面时用，客户端无需额外请求）。 */
	public void markAllDirty() {
		fullDirty = true;
	}

	/**
	 * 编码并**清空**脏项（服务端调用）。
	 *
	 * <p>同一 tick 内多次 {@link #setValue} 只会在这里合并成一次发送；编码失败的项只记日志，
	 * 不影响其他项与主流程。</p>
	 *
	 * @return 键 → NBT 值（无脏项时为空 Map）
	 */
	public Map<String, Tag> encodeDirty() {
		Map<String, Tag> out = new LinkedHashMap<>();
		if (!hasDirty()) {
			return out;
		}
		for (int i = 0; i < valueKeys.size(); i++) {
			if (!fullDirty && !dirty.contains(i)) {
				continue;
			}
			ValueKey<?> key = valueKeys.get(i);
			try {
				Tag encoded = encode(key, values[i]);
				if (encoded != null) {
					out.put(key.key(), encoded);
				}
			} catch (RuntimeException e) {
				ChasmLogger.error("chasm", "界面大值 {} 编码失败（已跳过）: {}", key.key(), e.toString());
			}
		}
		dirty.clear();
		fullDirty = false;
		return out;
	}

	/** 客户端：接收并解码一个大值（解码失败只记日志，保留旧值）。 */
	public void receiveValue(String key, Tag tag) {
		Integer index = valueIndex.get(key);
		if (index == null) {
			ChasmLogger.debug("chasm", "收到未知界面大值键 {}（已忽略，可能是旧客户端/旧服务端）", key);
			return;
		}
		ValueKey<?> valueKey = valueKeys.get(index);
		try {
			values[index] = valueKey.codec().parse(NbtOps.INSTANCE, tag).getOrThrow();
		} catch (RuntimeException e) {
			ChasmLogger.error("chasm", "界面大值 {} 解码失败（保留旧值）: {}", key, e.toString());
		}
	}

	/** 客户端：批量接收（一次包里的多个键；未知键忽略并 debug 记录）。 */
	public void receiveAll(CompoundTag tag) {
		if (tag == null) {
			return;
		}
		for (String key : tag.getAllKeys()) {
			Tag value = tag.get(key);
			if (value != null) {
				receiveValue(key, value);
			}
		}
	}

	// ------------------------------------------------------- 客户端平滑

	/** 当前平滑显示值（渲染帧读取；未调用过 tickSmooth 时等于原值）。 */
	public double smooth(String key) {
		return smooth[intIndexOf(key)];
	}

	/**
	 * 推进平滑（每客户端 tick 调一次）。
	 *
	 * @param factor 收敛系数（0~1），越大越快追上目标；0.25~0.4 观感自然
	 */
	public void tickSmooth(double factor) {
		double f = Math.max(0.0, Math.min(1.0, factor));
		for (int i = 0; i < ints.length; i++) {
			double target = ints[i];
			smooth[i] += (target - smooth[i]) * f;
			if (Math.abs(target - smooth[i]) < 0.01) {
				smooth[i] = target;
			}
		}
	}

	/** 把平滑值直接对齐到当前值（打开界面/重置时用，避免从 0 飞过去）。 */
	public void resetSmooth() {
		for (int i = 0; i < ints.length; i++) {
			smooth[i] = ints[i];
		}
	}

	// ------------------------------------------------------- 内部

	private int intIndexOf(String key) {
		Integer index = intIndex.get(key);
		if (index == null) {
			throw new IllegalArgumentException("未知整数键 " + key + "（已声明: " + intIndex.keySet() + "）");
		}
		return index;
	}

	private int valueIndexOf(String key) {
		Integer index = valueIndex.get(key);
		if (index == null) {
			throw new IllegalArgumentException("未知大值键 " + key + "（已声明: " + valueIndex.keySet() + "）");
		}
		return index;
	}

	@SuppressWarnings("unchecked")
	private static <T> Tag encode(ValueKey<T> key, Object value) {
		return key.codec().encodeStart(NbtOps.INSTANCE, (T) value).getOrThrow();
	}

	/** 调试：一行概览。 */
	public String describe() {
		StringBuilder sb = new StringBuilder("state[ints=");
		for (int i = 0; i < intSlots.size(); i++) {
			sb.append(i > 0 ? "," : "").append(intSlots.get(i).key()).append('=').append(ints[i]);
		}
		sb.append(" values=");
		for (int i = 0; i < valueKeys.size(); i++) {
			sb.append(i > 0 ? "," : "").append(valueKeys.get(i).key()).append('=').append(values[i]);
		}
		sb.append(" dirty=").append(hasDirty()).append("]");
		return sb.toString();
	}

	/** 调试：把状态转成 NBT 复合（不含脏语义，仅供观测）。 */
	public CompoundTag snapshot() {
		CompoundTag tag = new CompoundTag();
		for (int i = 0; i < intSlots.size(); i++) {
			tag.putInt(intSlots.get(i).key(), ints[i]);
		}
		for (int i = 0; i < valueKeys.size(); i++) {
			try {
				tag.put(valueKeys.get(i).key(), encode((ValueKey<Object>) valueKeys.get(i), values[i]));
			} catch (RuntimeException ignored) {
				// 观测用，编码失败跳过
			}
		}
		return tag;
	}

	/** 便捷：列出整数与大值键名。 */
	public List<String> keys() {
		List<String> out = new ArrayList<>(intIndex.keySet());
		out.addAll(valueIndex.keySet());
		return out;
	}
}
