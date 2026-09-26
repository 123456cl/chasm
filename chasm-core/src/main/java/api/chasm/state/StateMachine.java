package api.chasm.state;

import api.chasm.safety.ChasmSafety;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 声明式状态机辅助（消除"手写 int 状态机"带来的复杂与易错）。
 *
 * <p>把"放料→炒→出锅→糊"这类机器流程声明成一张表：每个状态有（时长、超时去哪个状态、
 * 每 tick 动作）；tick 自动推进、超时自动切换、全程 try-catch 容错（坏动作只记日志不崩服）。
 * 状态切换统一收口（enter），不会再出现"忘了 setChanged/忘了置 status"这类笔误。</p>
 *
 * 用法：
 * <pre>
 * StateMachine<Phase> sm = StateMachine.of(Phase.PUT);
 * sm.at(Phase.PUT, 1200, Phase.COOK, () -> onPutTick());
 * sm.at(Phase.COOK, 200,  Phase.DONE, () -> onCookTick());
 * sm.at(Phase.DONE, 800,  Phase.BURNT, () -> {});
 * // 每 tick 调用 sm.tick()；外部事件用 sm.to(Phase.X) 手动切。
 * </pre>
 *
 * @param <S> 状态类型（枚举即可）
 */
public final class StateMachine<S> {

	/** 一个状态节点的配置。 */
	public static final class Node<S> {
		final long durationTicks;
		final S timeoutNext;         // 可为 null：到时停在原状态
		final Runnable onTick;       // 每 tick（到点前）调用；可为 null
		final Runnable onEnter;      // 进入本状态时调用；可为 null

		Node(long durationTicks, S timeoutNext, Runnable onTick, Runnable onEnter) {
			this.durationTicks = Math.max(0, durationTicks);
			this.timeoutNext = timeoutNext;
			this.onTick = onTick;
			this.onEnter = onEnter;
		}
	}

	private S state;
	private long remaining;
	private final Map<S, Node<S>> nodes = new HashMap<>();
	private Consumer<S> onStateChanged; // 状态切换回调（如 setChanged/refresh）

	private StateMachine(S initial) {
		this.state = initial;
		this.remaining = 0;
	}

	/** 创建状态机，初始为 initial。 */
	public static <S> StateMachine<S> of(S initial) {
		return new StateMachine<>(initial);
	}

	/** 注册/覆盖一个状态节点。 */
	public StateMachine<S> at(S state, long durationTicks, S timeoutNext, Runnable onTick) {
		return at(state, durationTicks, timeoutNext, onTick, null);
	}

	/** 注册/覆盖一个状态节点（含进入动作）。 */
	public StateMachine<S> at(S state, long durationTicks, S timeoutNext, Runnable onTick, Runnable onEnter) {
		nodes.put(state, new Node<>(durationTicks, timeoutNext, onTick, onEnter));
		return this;
	}

	/** 状态切换监听（可用于 {@code setChanged+refresh}）。 */
	public StateMachine<S> onChanged(Consumer<S> listener) {
		this.onStateChanged = listener;
		return this;
	}

	/** 手动切换到某状态（收口统一入口：重置计时并触发 onEnter/监听）。 */
	public void to(S next) {
		if (next == null) {
			return;
		}
		this.state = next;
		Node<S> node = nodes.get(next);
		this.remaining = node == null ? 0 : node.durationTicks;
		if (node != null && node.onEnter != null) {
			ChasmSafety.safe("state-machine:enter", node.onEnter);
		}
		notifyChanged();
	}

	/** 每 tick 推进一次（外部每 tick 调用；异常已容错）。 */
	public void tick() {
		Node<S> node = nodes.get(state);
		if (node == null) {
			return; // 未注册的状态：什么都不做（防错而非崩溃）
		}
		if (node.durationTicks > 0) {
			if (remaining > 0) {
				remaining--;
				if (node.onTick != null) {
					ChasmSafety.safe("state-machine:tick:" + state, node.onTick);
				}
				return;
			}
			// 到时
			if (node.timeoutNext != null) {
				to(node.timeoutNext);
			}
			return;
		}
		// 无限时长状态：每 tick 都跑动作
		if (node.onTick != null) {
			ChasmSafety.safe("state-machine:tick:" + state, node.onTick);
		}
	}

	/** 距当前状态超时还剩多少 tick（0 = 无时长状态或已超时）。 */
	public long remaining() {
		return remaining;
	}

	/** 当前状态。 */
	public S state() {
		return state;
	}

	private void notifyChanged() {
		if (onStateChanged != null) {
			ChasmSafety.safe("state-machine:changed", () -> onStateChanged.accept(state));
		}
	}
}
