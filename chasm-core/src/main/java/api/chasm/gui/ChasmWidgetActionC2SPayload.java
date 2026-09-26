package api.chasm.gui;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 控件动作 C2S 包（滑块拖动 / 开关切换 / 自定义控件）。
 *
 * <p>携带信息刻意最小：界面 id + 控件索引 + 控件种类 + 值（+ 可选 NBT 扩展）。
 * 服务端会逐项验证：界面存在 → 玩家确实开着 → 索引有效 → **种类一致**（防篡改）→ 可交互 → 限流 →
 * 值**夹取到声明值域**后才交给处理器。</p>
 *
 * @param guiId 界面 id
 * @param index 控件索引（{@link ChasmGui#widgets()} 中的下标）
 * @param kind  控件种类 id（与服务端声明不一致即拒绝）
 * @param value 值（滑块/开关系数由两端同一套 {@link ChasmWidgetSpec#valueAt(double)} 计算）
 * @param extra 可选扩展数据（自定义控件用；可为 null）
 */
public record ChasmWidgetActionC2SPayload(ResourceLocation guiId, int index, ResourceLocation kind,
										  int value, CompoundTag extra) implements CustomPacketPayload {

	/** 包类型 id。 */
	public static final Type<ChasmWidgetActionC2SPayload> TYPE =
		new Type<>(ResourceLocation.fromNamespaceAndPath("chasm", "gui_widget_action"));

	/** 编解码器。 */
	public static final StreamCodec<RegistryFriendlyByteBuf, ChasmWidgetActionC2SPayload> CODEC = StreamCodec.of(
		(RegistryFriendlyByteBuf buf, ChasmWidgetActionC2SPayload msg) -> {
			buf.writeResourceLocation(msg.guiId());
			buf.writeVarInt(msg.index());
			buf.writeResourceLocation(msg.kind());
			buf.writeVarInt(msg.value());
			buf.writeNbt(msg.extra());
		},
		(RegistryFriendlyByteBuf buf) -> new ChasmWidgetActionC2SPayload(
			buf.readResourceLocation(), buf.readVarInt(), buf.readResourceLocation(),
			buf.readVarInt(), buf.readNbt()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
