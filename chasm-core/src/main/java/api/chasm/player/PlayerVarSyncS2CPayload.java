package api.chasm.player;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * PlayerVar S2C 同步包（修复 PlayerVar 多人同步缺失）。
 *
 * <p>服务端在某 PlayerVar 值变化（{@code set/modify}）或玩家加入全量同步时发送，携带
 * 「变量 id」+「编码后的值」（NBT 包装 {@code {"v": ...}}）。客户端按变量 id 反查
 * {@link PlayerVar}，用其 Codec 解码后写入只读缓存（{@link PlayerVar#receiveSync}）。</p>
 *
 * @param varId 变量 id（{@code modId:name}）
 * @param value 编码后的值（{@code {"v": <值>}} NBT 包装，任意 Codec 类型通用）
 */
public record PlayerVarSyncS2CPayload(ResourceLocation varId, CompoundTag value)
	implements CustomPacketPayload {

	/** 包类型 id（命名空间统一使用基础库 "chasm"）。 */
	public static final Type<PlayerVarSyncS2CPayload> TYPE =
		new Type<>(ResourceLocation.fromNamespaceAndPath("chasm", "player_var_sync"));

	/** 编解码器（varId + NBT 值）。 */
	public static final StreamCodec<RegistryFriendlyByteBuf, PlayerVarSyncS2CPayload> CODEC = StreamCodec.of(
		(RegistryFriendlyByteBuf buf, PlayerVarSyncS2CPayload msg) -> {
			buf.writeResourceLocation(msg.varId());
			buf.writeNbt(msg.value());
		},
		(RegistryFriendlyByteBuf buf) -> new PlayerVarSyncS2CPayload(buf.readResourceLocation(), buf.readNbt()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
