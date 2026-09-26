package api.chasm.data;

import com.mojang.serialization.Codec;

/**
 * 常用数据组件 Codec 辅助，避免手写 {@code RecordCodecBuilder} 样板。
 *
 * <p>提供原版常用标量 Codec 的统一定义引用，以及一个「透传」入口
 * {@link #record(Codec)}（语义上标记『这是一个 record 的 Codec』，便于阅读；
 * 后续可在此扩展自动 record Codec 生成）。</p>
 *
 * <pre>{@code
 * // 注册一个 int 型数据组件
 * DataComponentType<Integer> souls =
 *     ChasmData.register("mymod", "souls", ChasmCodecs.INT);
 * }</pre>
 */
public final class ChasmCodecs {

	private ChasmCodecs() {
	}

	/** int 型 Codec。 */
	public static final Codec<Integer> INT = Codec.INT;
	/** String 型 Codec。 */
	public static final Codec<String> STRING = Codec.STRING;
	/** boolean 型 Codec。 */
	public static final Codec<Boolean> BOOL = Codec.BOOL;
	/** float 型 Codec。 */
	public static final Codec<Float> FLOAT = Codec.FLOAT;
	/** double 型 Codec。 */
	public static final Codec<Double> DOUBLE = Codec.DOUBLE;

	/**
	 * record Codec 透传入口（语义标记，原样返回）。
	 *
	 * <p>复杂 record 可结合 {@link ChasmCodec#codecFor(Class)} 自动生成，
	 * 这里保留一个统一入口，便于阅读与后续扩展。</p>
	 *
	 * @param codec 已生成的 record Codec
	 */
	public static <T> Codec<T> record(Codec<T> codec) {
		return codec;
	}
}