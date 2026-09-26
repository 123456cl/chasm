package api.chasm.effect;

import api.chasm.item.event.ChasmItemEventKeys;
import api.chasm.item.event.ChasmItemEvents;
import api.chasm.item.event.payload.BlockXpPayload;
import api.chasm.log.ChasmLogger;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * **方块经验掉落钩子**（框架能力）—— 绕开 1.21.1 里 {@code protected} 的
 * {@code Block#tryDropExperience(ServerLevel, BlockPos, ItemStack, IntProvider)}。
 *
 * <h2>为什么不能直接问方块</h2>
 * <p>1.20.1 有公开的 {@code BlockState#getExpDrop(...)}（真实 {@code ItemModularHandheld.java:205} 用的就是它）；
 * 1.21.1 把它收进了 {@code Block.tryDropExperience}（javap 确认是 {@code protected}），
 * 且经验范围是方块自己持有的 {@code IntProvider}（{@code DropExperienceBlock} 的私有字段），
 * 外部**既调不到也读不到**；{@code BlockState} 上也已没有 {@code getExpDrop}（javap 全量方法表确认）。</p>
 *
 * <h2>框架的做法：不问方块，看结果</h2>
 * <ol>
 *   <li>方块被破坏时（服务端 {@code PlayerBlockBreakEvents.AFTER}）记下 {@code (玩家, 坐标, 状态, 工具)}，TTL 默认 4 tick；</li>
 *   <li>经验球被加入世界时（{@code ServerEntityEvents.ENTITY_LOAD}，Fabric 在实体 startTracking 的 TAIL 触发）
 *       按坐标就近匹配，取 {@code ExperienceOrb#getValue()} —— 这就是**这一格真正掉了多少经验**；</li>
 *   <li>用 {@link ChasmItemEventKeys#ON_BLOCK_XP} 分发（协议 {@code ADDITIVE} 增量），
 *       监听者可读取、也可改（改的方式：{@code orb.discard()} + {@code ExperienceOrb.award}）。</li>
 * </ol>
 *
 * <p>比"预估"更准：方块被其它系统压制掉落时不会误判；对模组方块同样成立（不依赖方块的类）。
 * 代价是**没有经验球就没有事件**（经验为 0 的方块不发事件）—— 这与真实
 * {@code ItemModularHandheld.java:207} 的 {@code if (xp > 0)} 判断等价。</p>
 *
 * <h2>复杂度</h2>
 * <p>破坏时 1 次 {@code LinkedHashMap.put}（O(1)）；经验球落地时在有界表（硬上限 {@link #MAX_PENDING}）里
 * 就近匹配 —— 表里通常只有 0~2 条；每 tick 的清理只在表非空时做。**没有无界增长**。</p>
 */
public final class ChasmBlockXp {

	/** 记录存活 tick 数：经验球在同一 tick 内生成（{@code Block#popExperience} 直接 {@code addFreshEntity}）。 */
	public static final int DEFAULT_TTL_TICKS = 4;

	/** 待匹配记录硬上限（超限整表清空，绝不无界）。 */
	public static final int MAX_PENDING = 128;

	/** 匹配半径（经验球生成在方块中心，方形距离 ≤ 1.5 足够，且不会误配隔壁方块）。 */
	private static final double MATCH_DISTANCE_SQR = 2.25D;

	/** 坐标 → 待匹配记录（{@code LinkedHashMap} 保插入序，便于超限时丢最旧）。 */
	private static final Map<Long, Tracked> PENDING = new LinkedHashMap<>();

	/** 一条待匹配记录。 */
	private static final class Tracked {
		private final ServerLevel level;
		private final BlockPos pos;
		private final BlockState state;
		private final Player player;
		private final ItemStack tool;
		private int ttl;

		private Tracked(ServerLevel level, BlockPos pos, BlockState state, Player player, ItemStack tool, int ttl) {
			this.level = level;
			this.pos = pos;
			this.state = state;
			this.player = player;
			this.tool = tool;
			this.ttl = ttl;
		}
	}

	private ChasmBlockXp() {
	}

	// ------------------------------------------------------------------ 纯函数

	/** 经验增量 → 最终值（**纯函数**：NaN 视为 0，结果钳到 ≥ 0，绝不产生负经验）。 */
	public static int applyDelta(int amount, float delta) {
		if (!Float.isFinite(delta)) {
			return Math.max(0, amount);
		}
		int base = Math.max(0, amount);
		long result = base + (long) delta;
		if (result < 0L) {
			return 0;
		}
		return result > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
	}

	// ------------------------------------------------------------------ 事件源

	/** 方块被破坏（服务端 {@code PlayerBlockBreakEvents.AFTER}）→ 记一笔，等经验球来认领。 */
	public static void trackBreak(ServerLevel level, Player player, BlockPos pos, BlockState state) {
		if (level == null || player == null || pos == null || state == null) {
			return;
		}
		if (!ChasmItemEvents.isInteresting(ChasmItemEventKeys.ON_BLOCK_XP)) {
			return;
		}
		try {
			sweepExpired();
			if (PENDING.size() >= MAX_PENDING) {
				PENDING.clear();
			}
			PENDING.put(pos.asLong(),
				new Tracked(level, pos.immutable(), state, player, player.getMainHandItem(), DEFAULT_TTL_TICKS));
		} catch (Throwable t) {
			ChasmLogger.error("chasm", "方块经验记录失败（已隔离）", t);
		}
	}

	/**
	 * 实体进入世界（{@code ServerEntityEvents.ENTITY_LOAD}）→ 若是经验球且能对上最近一次破坏，就分发事件。
	 *
	 * @return 是否认领了该经验球
	 */
	public static boolean onEntityLoad(Entity entity, ServerLevel level) {
		if (!(entity instanceof ExperienceOrb orb) || level == null) {
			return false;
		}
		if (!ChasmItemEvents.isInteresting(ChasmItemEventKeys.ON_BLOCK_XP)) {
			return false;
		}
		Tracked tracked = takeNear(level, orb.position());
		if (tracked == null) {
			return false;
		}
		try {
			int amount = Math.max(0, orb.getValue());
			int result = fire(tracked.player, level, tracked.pos, tracked.state, tracked.tool, amount);
			if (result != amount) {
				// 原版的 ExperienceOrb 只有 getValue()（javap 确认 value 是私有字段、无 setter），
				// 因此"改经验"= 丢弃旧球 + 按新数值重新发一个（重新发球会再次触发本方法，
				// 但那时待匹配记录已移除 → 立即返回，不会递归）。
				Vec3 spawn = orb.position();
				orb.discard();
				if (result > 0) {
					ExperienceOrb.award(level, spawn, result);
				}
			}
		} catch (Throwable t) {
			ChasmLogger.error("chasm", "方块经验事件分发失败（已隔离）", t);
		}
		return true;
	}

	/**
	 * 分发一次"方块经验"事件（公共 API：模组自带事件源也可直接调用）。
	 *
	 * @return 修改后的经验值（无监听者时等于 {@code amount}）
	 */
	public static int fire(Player player, ServerLevel level, BlockPos pos, BlockState state, ItemStack tool, int amount) {
		if (player == null || level == null || pos == null || state == null) {
			return Math.max(0, amount);
		}
		if (!ChasmItemEvents.isInteresting(ChasmItemEventKeys.ON_BLOCK_XP)) {
			return Math.max(0, amount);
		}
		BlockXpPayload payload = new BlockXpPayload(player, level, pos, state, Math.max(0, amount), tool);
		float delta = ChasmItemEventKeys.fireBlockXp(payload);
		return applyDelta(amount, delta);
	}

	/** 服务端 tick：倒计时并清理过期记录。 */
	public static void tick() {
		if (!PENDING.isEmpty()) {
			sweepExpired();
		}
	}

	/** 待匹配记录数（调试/测试）。 */
	public static int pendingCount() {
		return PENDING.size();
	}

	/** 清空待匹配表（**仅供单测/关服清理**）。 */
	public static void clear() {
		PENDING.clear();
	}

	// ------------------------------------------------------------------ 内部

	private static void sweepExpired() {
		Iterator<Map.Entry<Long, Tracked>> iterator = PENDING.entrySet().iterator();
		while (iterator.hasNext()) {
			Tracked tracked = iterator.next().getValue();
			if (tracked == null || --tracked.ttl <= 0) {
				iterator.remove();
			}
		}
	}

	/** 就近取一条匹配记录（表有界，扫描上限即 {@link #MAX_PENDING}）。 */
	private static Tracked takeNear(ServerLevel level, Vec3 position) {
		if (position == null) {
			return null;
		}
		long bestKey = Long.MIN_VALUE;
		double bestDistance = Double.MAX_VALUE;
		for (Map.Entry<Long, Tracked> entry : PENDING.entrySet()) {
			Tracked tracked = entry.getValue();
			if (tracked == null || tracked.level != level) {
				continue;
			}
			double distance = Vec3.atCenterOf(tracked.pos).distanceToSqr(position);
			if (distance <= MATCH_DISTANCE_SQR && distance < bestDistance) {
				bestDistance = distance;
				bestKey = entry.getKey();
			}
		}
		return bestKey == Long.MIN_VALUE ? null : PENDING.remove(bestKey);
	}
}
