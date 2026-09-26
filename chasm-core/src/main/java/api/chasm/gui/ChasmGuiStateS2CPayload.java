package api.chasm.gui;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 界面**大值批量快照** S2C 包。
 *
 * <p>只承载"整数通道放不下"的数据（字符串/record/列表…），一次包内可含多个键（同 tick 合并）。
 * 整数走原版 {@code ContainerData}，**不经过本包**——两条通道互不干扰，常规进度条几乎零额外流量。</p>
 *
 * @param containerId 菜单同步 id（客户端据此找到对应 {@link ChasmMenu}）
 * @param values      键 → NBT 编码值（仅含本 tick 变化的键）
 */
public record ChasmGuiStateS2CPayload(int containerId, CompoundTag values) implements CustomPacketPayload {

	/** 包类型 id（命名空间统一使用基础库 "chasm"）。 */
	public static final Type<ChasmGuiStateS2CPayload> TYPE =
		new Type<>(ResourceLocation.fromNamespaceAndPath("chasm", "gui_state_sync"));

	/** 编解码器（菜单 id + NBT 复合）。 */
	public static final StreamCodec<RegistryFriendlyByteBuf, ChasmGuiStateS2CPayload> CODEC = StreamCodec.of(
		(RegistryFriendlyByteBuf buf, ChasmGuiStateS2CPayload msg) -> {
			buf.writeVarInt(msg.containerId());
			buf.writeNbt(msg.values());
		},
		(RegistryFriendlyByteBuf buf) -> new ChasmGuiStateS2CPayload(buf.readVarInt(), buf.readNbt()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
