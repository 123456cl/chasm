package api.chasm.spi;

/**
 * 方法点"后处理"装饰：在默认/全局实现之后追加，可读取并改写结果（不影响其它调用方）。
 *
 * @param <C> 输入上下文
 * @param <R> 返回值
 */
@FunctionalInterface
public interface MethodPost<C, R> {
	/** 对默认结果做后置装饰/改写。 */
	R after(C context, R result);
}
