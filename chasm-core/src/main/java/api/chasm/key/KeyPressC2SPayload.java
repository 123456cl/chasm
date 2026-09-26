package api.chasm.key;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;

/**
 * 自定义按键 C2S 包（第十二步）。
 *
 * <p>客户端在玩家按下一个 Chasm 自定义按键（上升沿，{@code KeyMapping.consumeClick()}）
 * 时发送，携带「手」（主/副手）+「手持物品 id」+「按键 id」。体积极小，
 * 且只在真实按键按下一次性发送，天然防高频发包。服务端据此校验玩家确在该手持有该物品，
 * 再反查物品绑定的按键处理器执行。</p>
 *
 * @param itemId 手持物品的物品 id（{@code modId:itemPath}）
 * @param keyId  被按下的按键 id（{@code modId:keyPath}）
 * @param hand   触发时的手（目前仅主手，保留副手枚举以兼容扩展）
 */
public record KeyPressC2SPayload(ResourceLocation itemId, ResourceLocation keyId, InteractionHand hand)
	implements CustomPacketPayload {

	/** 包类型 id（命名空间统一使用基础库 "chasm"）。 */
	public static final Type<KeyPressC2SPayload> TYPE =
		new Type<>(ResourceLocation.fromNamespaceAndPath("chasm", "key_press"));

	/** 编解码器（写入 itemId + keyId + 手）。 */
	public static final StreamCodec<RegistryFriendlyByteBuf, KeyPressC2SPayload> CODEC = StreamCodec.of(
		(RegistryFriendlyByteBuf buf, KeyPressC2SPayload msg) -> {
			buf.writeResourceLocation(msg.itemId());
			buf.writeResourceLocation(msg.keyId());
			buf.writeEnum(msg.hand());
		},
		(RegistryFriendlyByteBuf buf) -> new KeyPressC2SPayload(
			buf.readResourceLocation(), buf.readResourceLocation(), buf.readEnum(InteractionHand.class)));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}