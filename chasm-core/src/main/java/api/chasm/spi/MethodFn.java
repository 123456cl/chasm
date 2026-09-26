package api.chasm.spi;

/**
 * 一个"方法点"的可执行实现（可被装饰/替换）。
 *
 * @param <C> 输入上下文
 * @param <R> 返回值
 */
@FunctionalInterface
public interface MethodFn<C, R> {
	/** 执行并返回结果。 */
	R apply(C context);
}
