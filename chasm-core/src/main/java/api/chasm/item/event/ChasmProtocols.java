package api.chasm.item.event;

import net.minecraft.world.InteractionResult;

/**
 * 结果协议"参考实现"集合 —— 仅作示例，**不是唯一可选**。
 *
 * <p>开发者应优先按自己的玩法语义注册自己的 {@link ResultProtocol}（这正是本层开放的目的）。</p>
 */
public final class ChasmProtocols {

	private ChasmProtocols() {
	}

	/** 允许/否决：初始 TRUE；合并取 AND；FALSE 终止。适合"能否做某事"的钩子。 */
	public static final ResultProtocol<Boolean> ALLOW = new ResultProtocol<>() {
		@Override public Boolean initial() { return Boolean.TRUE; }
		@Override public Boolean merge(Boolean current, Boolean incoming) {
			return incoming == null ? current : Boolean.valueOf(current && incoming);
		}
		@Override public boolean terminates(Boolean current) { return Boolean.FALSE.equals(current); }
	};

	/** 数值累加：初始 0；合并相加；不终止。适合伤害/倍率/概率类修正。 */
	public static final ResultProtocol<Float> ADDITIVE = new ResultProtocol<>() {
		@Override public Float initial() { return 0.0F; }
		@Override public Float merge(Float current, Float incoming) {
			return incoming == null ? current : current + incoming;
		}
	};

	/** 交互结果优先：初始 PASS；合并取"更强者"（SUCCESS > CONSUME > PASS > FAIL 简化序）；不终止。 */
	public static final ResultProtocol<InteractionResult> INTERACTION = new ResultProtocol<>() {
		@Override public InteractionResult initial() { return InteractionResult.PASS; }
		@Override public InteractionResult merge(InteractionResult current, InteractionResult incoming) {
			if (incoming == null) {
				return current;
			}
			return rank(incoming) > rank(current) ? incoming : current;
		}
		private int rank(InteractionResult r) {
			if (r == InteractionResult.SUCCESS) return 3;
			if (r == InteractionResult.CONSUME) return 2;
			if (r == InteractionResult.FAIL) return 1;
			return 0;
		}
	};
}
