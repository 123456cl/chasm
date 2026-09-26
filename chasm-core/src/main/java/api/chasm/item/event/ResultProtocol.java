package api.chasm.item.event;

/**
 * 结果协议（**开放注册**）：定义"一类事件的结果如何组合"。
 *
 * <p>框架不写死任何协议——模组可自定义自己的协议（例如三态、权重对象、Map 结果、指令队列…），
 * 并把它用于自己注册的事件种类。内置示例见 {@link ChasmProtocols}（仅参考实现）。</p>
 *
 * @param <R> 结果类型
 */
public interface ResultProtocol<R> {

	/** 初始结果（没有任何处理器时的结果）。 */
	R initial();

	/** 合并：把某个处理器返回的结果并入当前结果。 */
	R merge(R current, R incoming);

	/** 当前结果是否应当终止后续处理器（如"已取消/已消费"）。默认不终止。 */
	default boolean terminates(R current) {
		return false;
	}
}
