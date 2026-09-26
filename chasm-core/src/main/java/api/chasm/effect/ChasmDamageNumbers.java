package api.chasm.effect;

import api.chasm.item.event.ResultProtocol;

/**
 * **数值型伤害事件的结果协议**（{@link DamageNumber} 的合并规则）。
 *
 * <p>与布尔协议 {@link api.chasm.item.event.ChasmProtocols#ALLOW} 并列存在：布尔回答"能不能打"，
 * 本协议回答"打多少、穿多少护甲"。初始值是 {@link DamageNumber#NEUTRAL}，
 * 合并是 {@link DamageNumber#merge}（加法相加 / 乘算连乘 / 下限取大 / 护甲无视相加），**永不终止**
 * —— 每个监听者都能看到别人改过的累计值，顺序无关。</p>
 *
 * <p>用法见 {@code ChasmItemEventKeys.ON_HURT}：事件源拿到协议结果后，
 * 用 {@link DamageNumber#apply(float)} 求出目标数值、用 {@link DamageNumber#armorPenetration()}
 * 求出应当削减的护甲比例。</p>
 */
public final class ChasmDamageNumbers {

	private ChasmDamageNumbers() {
	}

	/** 数值型伤害修正协议（初始 NEUTRAL；合并 merge；不终止）。 */
	public static final ResultProtocol<DamageNumber> NUMBER = new ResultProtocol<>() {
		@Override
		public DamageNumber initial() {
			return DamageNumber.NEUTRAL;
		}

		@Override
		public DamageNumber merge(DamageNumber current, DamageNumber incoming) {
			if (current == null) {
				return incoming == null ? DamageNumber.NEUTRAL : incoming;
			}
			return current.merge(incoming);
		}
	};
}
