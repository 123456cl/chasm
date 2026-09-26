package api.chasm.blockentity;

/**
 * 方块实体行为的类型键（按「类型身份」而非字符串寻址）。
 *
 * <p>每个行为类持有自己的静态单例 {@code TYPE}，例如
 * {@code CookingProcessBehaviour.TYPE}；{@link ChasmBlockEntity} 内部以本键为
 * {@code BehaviourType} 存储/取出行为，配合 {@link #hashCode()} 的引用哈希增强分布，
 * 避免命名冲突与遍历开销。</p>
 *
 * <p>设计来源：Create-Fly 的 {@code BehaviourType}（第十六步分析），Chasm 原样采用
 * 「空对象 + 唯一身份」语义，构造参数 {@code name} 仅用于调试输出。</p>
 *
 * @param <T> 行为类型
 */
public final class BehaviourType<T> {

	private final String name;

	public BehaviourType(String name) {
		this.name = name;
	}

	public BehaviourType() {
		this("");
	}

	/** 调试用名称（不影响语义）。 */
	public String getName() {
		return name;
	}

	/** 引用身份哈希（同 {@code BehaviourType} 实例必然同桶），乘以素数改善分布。 */
	@Override
	public int hashCode() {
		return super.hashCode() * 31 * 493286711;
	}
}
