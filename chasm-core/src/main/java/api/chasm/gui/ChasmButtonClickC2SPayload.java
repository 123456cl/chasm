package api.chasm.gui;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 按钮点击 C2S 包：客户端在玩家点击虚拟按钮时发送，服务端据此执行回调。
 *
 * <p>不直接携带布局/回调——布局在两侧通过同一份 {@link ChasmGui} 描述共享；
 * 本包只携带"哪个界面、哪个按钮"，体积极小，天然适配 Not Enough Bandwidth 等
 * 压缩优化，也便于 {@code onOpen} 之外的轻量高频更新走 Chasm 数据同步而非发包。</p>
 *
 * @param guiId   所属界面 id（{@code modId:name}）
 * @param name    按钮名（label）
 * @param index   按钮索引
 */
public record ChasmButtonClickC2SPayload(ResourceLocation guiId, String name, int index)
	implements CustomPacketPayload {

	/** 包类型 id（命名空间统一使用基础库 "chasm"）。 */
	public static final Type<ChasmButtonClickC2SPayload> TYPE =
		new Type<>(ResourceLocation.fromNamespaceAndPath("chasm", "gui_button_click"));

	/** 编解码器（写入 guiId + 按钮名 + 索引）。 */
	public static final StreamCodec<RegistryFriendlyByteBuf, ChasmButtonClickC2SPayload> CODEC =
		StreamCodec.of(
			(RegistryFriendlyByteBuf buf, ChasmButtonClickC2SPayload msg) -> {
				buf.writeResourceLocation(msg.guiId());
				buf.writeUtf(msg.name());
				buf.writeVarInt(msg.index());
			},
			(RegistryFriendlyByteBuf buf) -> new ChasmButtonClickC2SPayload(
				buf.readResourceLocation(), buf.readUtf(), buf.readVarInt()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}