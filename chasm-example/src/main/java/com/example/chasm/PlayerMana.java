package com.example.chasm;

import api.chasm.Chasm;
import api.chasm.player.PlayerVar;
import com.mojang.serialization.Codec;

import net.minecraft.world.entity.player.Player;

/**
 * 玩家法力系统（玩法演示）。
 *
 * <p>每个玩家一份独立的法力值，基于 Chasm {@link PlayerVar}（Fabric DataAttachment）
 * 持久化：随世界存档保存、玩家重生保留、类型安全，并自动钳制到 [0, {@link #MAX}]。
 * 开局/上限均为 {@link #MAX}（50）。</p>
 *
 * <p>提供读取、写入、尝试消耗（不足返回 false）、恢复（自动钳制到上限）等操作。
 * 所有写入务必在服务端调用（{@link PlayerVar} 对客户端调用会自动忽略并告警）。</p>
 */
public final class PlayerMana {

	/** 法力上限（同时是开局固定值）。 */
	public static final int MAX = 50;

	/** 每秒自然恢复量（供玛瑙水晶等调用）。 */
	public static final int REGEN_PER_SECOND = 10;

	/** 底层玩家持久化变量（[0, MAX]，开局 MAX）。 */
	private static final PlayerVar<Integer> VAR = Chasm
		.playerVar("mymod", "mana", Codec.INT)
		.defaultValue(MAX)
		.min(0)
		.max(MAX)
		.build();

	private PlayerMana() {
	}

	/** 读取玩家当前法力。 */
	public static int get(Player player) {
		return VAR.get(player);
	}

	/** 写入法力（自动钳制到 [0, MAX]）。 */
	public static void set(Player player, int value) {
		VAR.set(player, value);
	}

	/**
	 * 尝试消耗法力。
	 *
	 * @return 法力充足时消耗成功返回 true；不足返回 false（不做任何修改）
	 */
	public static boolean tryConsume(Player player, int amount) {
		int current = get(player);
		if (current < amount) {
			return false;
		}
		set(player, current - amount);
		return true;
	}

	/** 恢复法力（自动钳制到上限）。 */
	public static void add(Player player, int amount) {
		VAR.modify(player, v -> v + amount);
	}
}