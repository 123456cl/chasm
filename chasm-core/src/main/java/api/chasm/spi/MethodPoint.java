package api.chasm.spi;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 方法点（MethodPoint）—— chasm 接入点 SPI 的最小单元。
 *
 * <p>把「一个可以被人覆盖/装饰的行为」抽象成：一个<b>默认实现</b> + 若干<b>后置装饰</b> +
 * 可选<b>显式全局替换</b>。对齐的需求模型：</p>
 * <ul>
 *   <li><b>普通模组改动（默认）</b>：用 {@link #after(String, MethodPost)} 追加装饰 ——
 *       不改动默认实现本身，因此<b>不影响其它模组</b>；装饰按注册顺序叠加（多模组调度友好）。</li>
 *   <li><b>拉一份功能备份再本地改</b>：用 {@link #backup()} 拿到当前默认实现，在其基础上写自己的
 *       实现，再作为 {@link #after} 装饰追加（或作为一个新的全局实现候选）。</li>
 *   <li><b>特殊标注才改 API 本体</b>：只有调用方明确走 {@link #replaceGlobal(String, MethodFn, boolean)}
 *       并传 {@code allowGlobal = true} 时，才会把默认实现整体换掉（留给"对 API 本体做优化"的少数
 *       场景；框架提供方在文档里对真正允许本体替换的点标注 {@code @MutablePoint}）。</li>
 * </ul>
 *
 * <p>线程安全：默认实现用 {@code volatile}，装饰用 {@link CopyOnWriteArrayList}，
 * 可被游戏/网络多线程安全调用。</p>
 *
 * @param <C> 输入上下文
 * @param <R> 返回值
 */
public final class MethodPoint<C, R> {

	/** 当前生效的默认/全局实现。 */
	private volatile MethodFn<C, R> root;
	/** 后置装饰（按注册顺序执行）。 */
	private final List<MethodPost<C, R>> after = new CopyOnWriteArrayList<>();

	/** 是否允许"本体全局替换"（框架作者对该点放行的开关）。 */
	private final boolean mutableGlobal;

	MethodPoint(MethodFn<C, R> root, boolean mutableGlobal) {
		this.root = root;
		this.mutableGlobal = mutableGlobal;
	}

	/** 构建一个默认<b>不允许</b>全局替换的方法点（推荐给多数接入点）。 */
	public static <C, R> MethodPoint<C, R> of(MethodFn<C, R> defaultImpl) {
		return new MethodPoint<>(defaultImpl, false);
	}

	/** 构建一个<b>允许</b>显式全局替换的方法点（仅框架作者对特殊点使用）。 */
	public static <C, R> MethodPoint<C, R> mutable(MethodFn<C, R> defaultImpl) {
		return new MethodPoint<>(defaultImpl, true);
	}

	/**
	 * 拉一份当前默认实现的副本（供"取备份再本地改"）。注意：这是一次性快照，
	 * 若之后别人 replaceGlobal，本备份仍是旧实现——本地改请用不可变思路封装。
	 */
	public MethodFn<C, R> backup() {
		return root;
	}

	/** 追加一个后置装饰（默认实现结果之后执行，不修改默认本身，互不影响）。 */
	public MethodPoint<C, R> after(String ownerId, MethodPost<C, R> post) {
		this.after.add(post);
		return this;
	}

	/**
	 * <b>显式全局替换 API 本体</b>：仅当该点允许（构造为 mutable 或框架作者放行）且调用方
	 * 显式传 {@code allowGlobal=true} 才生效；否则抛 {@link IllegalStateException}。
	 */
	public MethodPoint<C, R> replaceGlobal(String ownerId, MethodFn<C, R> impl, boolean allowGlobal) {
		if (!allowGlobal) {
			throw new IllegalStateException("replaceGlobal 需要 allowGlobal=true（特殊标注才允许改本体）: " + ownerId);
		}
		if (!mutableGlobal) {
			throw new IllegalStateException("该方法点不允许全局替换（非 mutable）: " + ownerId);
		}
		this.root = impl;
		return this;
	}

	/** 执行：默认实现 → 逐条后置装饰。 */
	public R apply(C context) {
		R result = root.apply(context);
		for (MethodPost<C, R> post : after) {
			result = post.after(context, result);
		}
		return result;
	}
}
