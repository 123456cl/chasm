package api.chasm.player;

import api.chasm.log.ChasmLogger;

import com.mojang.serialization.Codec;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * PlayerVar 多人同步的网络通道（S2C）。
 *
 * <p><b>修复 PlayerVar 多人同步缺失</b>：修复前 PlayerVar 只有
 * {@code clientCache}/{@code cacheReadOnly} 这类"客户端只读缓存"雏形，但没有任何数据包真正
 * 把服务端值推给客户端——客户端 HUD/UI 只能显示默认值，服务端每次 {@code set/modify} 的变更
 * 客户端完全看不到。本通道补齐整条同步链路：</p>
 *
 * <ol>
 *   <li><b>增量同步</b>：服务端每次 {@code PlayerVar.set/modify} 值发生变化后，向<b>该玩家</b>
 *       单独发送一个 S2C 包（{@link PlayerVarSyncS2CPayload}，值经 NBT 编码，任意 Codec 通用）。</li>
 *   <li><b>加入全量同步</b>：玩家加入时（{@link ServerPlayConnectionEvents#JOIN}），把<b>所有</b>
 *       已注册 PlayerVar 的当前值全量推送给该玩家，保证开局/重进即拿到正确数值（含存档中
 *       持久化的旧值）。</li>
 * </ol>
 *
 * <p>包体极小（变量 id + 单值 NBT），且仅在值实际变化时发送，不会造成网络压力。</p>
 *
 * <p>客户端接收器在 client 入口点 {@code api.chasm.player.client.PlayerVarClient} 注册；
 * 本类只负责服务端发送与包类型登记（core 主入口点 {@code ChasmInit} 调用，两侧均登记）。</p>
 */
public final class PlayerVarChannel {

	private PlayerVarChannel() {
	}

	/** 注册 S2C 包类型 + 玩家加入全量同步监听（幂等；应在初始化早期调用一次）。 */
	public static void init() {
		PayloadTypeRegistry.playS2C().register(PlayerVarSyncS2CPayload.TYPE, PlayerVarSyncS2CPayload.CODEC);
		// 玩家加入：全量同步所有已注册 PlayerVar（回调在服务端线程执行）
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> syncAll(handler.getPlayer()));
	}

	/**
	 * 把某个变量的最新值编码并发送给指定玩家（服务端线程调用）。
	 *
	 * <p>用 {@code Codec → NbtOps} 编码为通用 NBT，再以 {@code {"v": <值>}} 包装发送，
	 * 兼容任意 Codec 类型（int/string/record 等）。编码失败仅记 error，不中断调用方。</p>
	 *
	 * @param player 目标服务端玩家
	 * @param varId  变量 id（{@code modId:name}）
	 * @param codec  变量编解码器
	 * @param value  待同步的最新值
	 * @param <T>    变量类型
	 */
	static <T> void send(ServerPlayer player, ResourceLocation varId, Codec<T> codec, T value) {
		try {
			Tag encoded = codec.encodeStart(NbtOps.INSTANCE, value).getOrThrow();
			CompoundTag wrapper = new CompoundTag();
			wrapper.put("v", encoded);
			ServerPlayNetworking.send(player, new PlayerVarSyncS2CPayload(varId, wrapper));
		} catch (RuntimeException e) {
			ChasmLogger.error("chasm", "PlayerVar {} 同步编码/发送失败: {}", varId, e.toString());
		}
	}

	/** 玩家加入时全量推送所有已注册 PlayerVar 的当前值（服务端线程调用）。 */
	static void syncAll(ServerPlayer player) {
		for (PlayerVar<?> var : PlayerVar.all()) {
			try {
				var.syncTo(player);
			} catch (RuntimeException e) {
				ChasmLogger.error("chasm", "PlayerVar {} 加入全量同步失败: {}", var.id(), e.toString());
			}
		}
	}
}
