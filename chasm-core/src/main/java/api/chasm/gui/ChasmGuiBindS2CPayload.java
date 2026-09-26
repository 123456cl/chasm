package api.chasm.gui;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 界面绑定信息 S2C 包：服务端用 {@link ChasmGui#openAt} 打开"绑定到某方块"的界面后，
 * 把绑定位置/维度告诉客户端（客户端据此显示机器信息、绘制钩子定位等）。
 *
 * <p><b>为什么不用 Fabric 的 {@code ExtendedScreenHandlerType}</b>：它的
 * {@code create(int, Inventory)} 直接抛 {@code UnsupportedOperationException}（javap 核实），
 * 一旦把 {@code MenuType} 换成它，所有走 {@code player.openMenu(gui.provider())} 的旧代码
 * 在客户端会直接崩。因此这里保持原版 MenuType 不变，用一个小包补上"打开数据"。</p>
 *
 * @param containerId 菜单同步 id
 * @param pos         绑定方块坐标
 * @param dimension   绑定维度 id（如 minecraft:overworld）
 */
public record ChasmGuiBindS2CPayload(int containerId, BlockPos pos, ResourceLocation dimension)
	implements CustomPacketPayload {

	/** 包类型 id（命名空间统一使用基础库 "chasm"）。 */
	public static final Type<ChasmGuiBindS2CPayload> TYPE =
		new Type<>(ResourceLocation.fromNamespaceAndPath("chasm", "gui_bind"));

	/** 编解码器。 */
	public static final StreamCodec<RegistryFriendlyByteBuf, ChasmGuiBindS2CPayload> CODEC = StreamCodec.of(
		(RegistryFriendlyByteBuf buf, ChasmGuiBindS2CPayload msg) -> {
			buf.writeVarInt(msg.containerId());
			buf.writeBlockPos(msg.pos());
			buf.writeResourceLocation(msg.dimension());
		},
		(RegistryFriendlyByteBuf buf) -> new ChasmGuiBindS2CPayload(
			buf.readVarInt(), buf.readBlockPos(), buf.readResourceLocation()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
