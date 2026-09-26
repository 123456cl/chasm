package api.chasm.contrib.socket;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * **按纯度分档的数值表**：同一条加成，不同纯度给不同数值（神化宝石 JSON 里的 {@code values}）。
 *
 * <p>神化示例（稳定宝石的攻击力）：cracked=1 / chipped=2 / flawed=3.5 / normal=5 / flawless=7 / perfect=10。
 * 抽到哪一档纯度，就用那一档的数值；**缺失档位 = 该纯度不支持这条加成**（神化的 {@code min_purity} 语义）。</p>
 *
 * <p>阶梯顺序由 {@link GemPurity} 注册顺序决定，因此 {@link #minPurity()} 不需要额外声明。</p>
 */
public final class TieredValues {

	private final Map<String, Float> byPurity;
	private final GemPurity minPurity;

	private TieredValues(Map<String, Float> byPurity) {
		this.byPurity = Collections.unmodifiableMap(new LinkedHashMap<>(byPurity));
		GemPurity min = null;
		for (String path : this.byPurity.keySet()) {
			GemPurity purity = GemPurity.get(path);
			if (purity == null) {
				throw new IllegalStateException("分档数值引用了未注册的纯度: " + path);
			}
			if (min == null || purity.rank() < min.rank()) {
				min = purity;
			}
		}
		this.minPurity = min;
	}

	public static Builder builder() {
		return new Builder();
	}

	/** 便捷：单个纯度一个值。 */
	public static TieredValues of(String purityPath, float value) {
		return builder().put(purityPath, value).build();
	}

	/** 指定纯度的数值；该纯度不支持则 null。 */
	public Float get(String purityPath) {
		return purityPath == null ? null : byPurity.get(purityPath);
	}

	/** 指定纯度的数值；不支持则 0。 */
	public float resolve(GemPurity purity) {
		if (purity == null) {
			return 0.0F;
		}
		Float value = byPurity.get(purity.path());
		return value == null ? 0.0F : value;
	}

	/** 该纯度是否吃得到这条加成。 */
	public boolean supports(GemPurity purity) {
		return purity != null && byPurity.containsKey(purity.path());
	}

	/** 全部档位值（只读，保持插入顺序）。 */
	public Map<String, Float> values() {
		return byPurity;
	}

	/** 最低可用档位（空表 null）。 */
	public GemPurity minPurity() {
		return minPurity;
	}

	public boolean isEmpty() {
		return byPurity.isEmpty();
	}

	@Override
	public String toString() {
		return "TieredValues" + byPurity;
	}

	/** 构建器。 */
	public static final class Builder {
		private final Map<String, Float> map = new LinkedHashMap<>();

		public Builder put(String purityPath, float value) {
			if (purityPath == null) {
				throw new IllegalArgumentException("纯度 path 不可为 null");
			}
			map.put(purityPath, value);
			return this;
		}

		public Builder put(String purityPath, double value) {
			return put(purityPath, (float) value);
		}

		/** 神化 JSON 的六档一次性写法（缺档跳过）。 */
		public Builder putAll(float cracked, float chipped, float flawed, float normal, float flawless,
			float perfect) {
			put("cracked", cracked);
			put("chipped", chipped);
			put("flawed", flawed);
			put("normal", normal);
			put("flawless", flawless);
			put("perfect", perfect);
			return this;
		}

		public TieredValues build() {
			if (map.isEmpty()) {
				throw new IllegalStateException("分档数值表为空");
			}
			return new TieredValues(map);
		}
	}
}
