package api.chasm.gui;

import api.chasm.log.ChasmLogger;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

/**
 * 界面大值通道（S2C）：包类型登记 + 服务端发送。
 *
 * <p>发送时机由 {@link ChasmMenu#broadcastChanges()}（服务端每 tick）驱动：只有
 * {@link ChasmGuiState#hasDirty()} 为真才发包，且一次包带走上 tick 内全部变化项
 * ——典型界面每秒最多 20 个小包，且内容随变化量而非界面规模增长。</p>
 *
 * <p>客户端接收器在 client 入口点（{@code ChasmGuiClient}）注册；本类在 core 主入口点
 * {@code ChasmInit} 调用，两侧都登记包类型。</p>
 */
public final class ChasmGuiStateChannel {

	private ChasmGuiStateChannel() {
	}

	/** 注册 S2C 包类型（幂等；初始化早期调用一次）。 */
	public static void init() {
		PayloadTypeRegistry.playS2C().register(ChasmGuiStateS2CPayload.TYPE, ChasmGuiStateS2CPayload.CODEC);
	}

	/**
	 * 把一批已编码的大值发给正在看该界面的玩家（服务端线程调用）。
	 *
	 * @param player      目标玩家
	 * @param containerId 菜单同步 id
	 * @param values      键 → NBT 值（空则不发送）
	 */
	static void send(ServerPlayer player, int containerId, Map<String, Tag> values) {
		if (values == null || values.isEmpty()) {
			return;
		}
		try {
			CompoundTag tag = new CompoundTag();
			values.forEach(tag::put);
			ServerPlayNetworking.send(player, new ChasmGuiStateS2CPayload(containerId, tag));
		} catch (RuntimeException e) {
			ChasmLogger.error("chasm", "界面数据同步发送失败（菜单 {}）: {}", containerId, e.toString());
		}
	}
}
