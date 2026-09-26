package api.chasm.multiblock;

import api.chasm.blockentity.ChasmBlockEntity;
import api.chasm.blockentity.ChasmBlockEntityBehaviour;
import api.chasm.log.ChasmLogger;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **多方块：格子布局 + 成形判定 + 方块实体的统一声明入口**（框架能力）。
 *
 * <h2>解决什么问题</h2>
 * <p>真实 Tetra 的 multischematic（{@code _ref/.../blocks/multischematic/}）每个模组自己写一遍：
 * 尺寸、"拼块 id → 局部格子"的换算、旋转后的世界坐标、成形逐格判定、主块广播、拼块粒子。
 * 之前端口把这些抄进了 {@code TetraSchematicSpecs} + {@code TetraMultiblockBlocks} 两个类里
 * （加起来近 700 行，且"格子布局"与"方块注册"互相引用）。本类把这些收成一份实现：</p>
 * <ol>
 *   <li><b>布局</b>：{@link Spec}（identifier + width × height × depth）→ {@link Spec#cells()}、
 *       {@link #worldPos}、{@link #chunkKey}；</li>
 *   <li><b>注册</b>：{@link #mount(Spec, Map)} 把"方块 id → 格子"登记进 O(1) 查表（含废墟块）；</li>
 *   <li><b>成形判定</b>：{@link #check} —— 只查本规格的 {@code width*height*depth} 格，逐格用调用方给的
 *       {@link CellProbe} 反查"这一格是不是同名同格的拼块"，**没有嵌套遍历、没有全表扫描**；</li>
 *   <li><b>方块实体</b>：{@link BlockEntity} —— 需要状态的多人方块（进度/进度条/库存）直接继承它，
 *       规格 id、所在格子、成形标记与成形计时的存读都归框架管。</li>
 * </ol>
 *
 * <h2>真实依据（Tetra 1.20）</h2>
 * <ul>
 *   <li>拼块 id 格式 {@code "%s_%d_%d"} / {@code "%s_ruined_%d_%d"} —— {@code MultiblockSchematicBlock.java:187-188}；</li>
 *   <li>主块格子 {@code x == width/2 && y == height/2} —— 同文件 {@code :226}；</li>
 *   <li>几何：宽 × 高、厚 1 的竖直平面（局部 z 恒为 0），
 *       {@code worldPos = rotate(basePos − (x,y,0), facing) + 本块世界坐标} —— 同文件 {@code :88-96}；</li>
 *   <li>旋转语义（{@code SOUTH} 原样 / {@code WEST}(−z,y,x) / {@code NORTH}(−x,y,−z) / {@code EAST}(z,y,−x)）
 *       —— {@code Mutil RotationHelper.java:51-63}；</li>
 *   <li>成形＝"全部格子都是同名拼块且 x/y 对位" ——
 *       {@code PrimaryMultiblockSchematicBlock.java:67-72,126-128}。</li>
 * </ul>
 *
 * <h2>复杂度</h2>
 * <p>注册 / 查表 {@code O(1)}；成形判定 {@code O(格子数)}（≤ 27，且每格只做一次哈希查表）；
 * {@link #chunkKey} 把三维位置压成一个 int，避免每次哈希 {@link BlockPos} 的三个 int。</p>
 */
public final class ChasmMultiblock {

	/**
	 * **一份多方块规格**（格子布局）。
	 *
	 * @param identifier 规格名（真实 Tetra 里就是原理图 id，如 {@code stonecutter}）
	 * @param width      横向格子数
	 * @param height     纵向格子数
	 * @param depth      厚度格子数（真实 multischematic 恒为 1）
	 */
	public record Spec(String identifier, int width, int height, int depth) {

		public Spec {
			if (identifier == null || identifier.isBlank()) {
				throw new IllegalArgumentException("多方块规格需要非空的 identifier");
			}
			width = Math.max(1, width);
			height = Math.max(1, height);
			depth = Math.max(1, depth);
		}

		/** 二维规格（真实 Tetra 的默认：厚 1）。 */
		public Spec(String identifier, int width, int height) {
			this(identifier, width, height, 1);
		}

		/** 格子总数（真实 {@code int size = block.height * block.width}，{@code MultiblockSchematicProcessor.java:38}）。 */
		public int size() {
			return width * height * depth;
		}

		/** 主块所在格（真实 {@code x == width/2 && y == height/2}）。 */
		public int primaryX() {
			return width / 2;
		}

		public int primaryY() {
			return height / 2;
		}

		public int primaryZ() {
			return depth / 2;
		}

		/** 拼块 id（真实格式 {@code "%s_%d_%d"}；厚 1 时省略 z 段，与真实一致）。 */
		public String pieceId(int x, int y) {
			return pieceId(x, y, 0);
		}

		/** 拼块 id（厚 &gt; 1 时带 z 段 {@code "%s_%d_%d_%d"}）。 */
		public String pieceId(int x, int y, int z) {
			return depth <= 1 ? identifier + "_" + x + "_" + y : identifier + "_" + x + "_" + y + "_" + z;
		}

		/** 废墟拼块 id（真实格式 {@code "%s_ruined_%d_%d"}）。 */
		public String ruinedId(int x, int y) {
			return ruinedId(x, y, 0);
		}

		public String ruinedId(int x, int y, int z) {
			return depth <= 1 ? identifier + "_ruined_" + x + "_" + y : identifier + "_ruined_" + x + "_" + y + "_" + z;
		}

		/** 该格是否主块。 */
		public boolean isPrimary(int x, int y) {
			return isPrimary(x, y, 0);
		}

		public boolean isPrimary(int x, int y, int z) {
			return x == primaryX() && y == primaryY() && z == primaryZ();
		}

		/** 全部格子（局部坐标，读序 = x → y → z，与真实拼块注册顺序一致）。 */
		public List<Cell> cells() {
			List<Cell> out = new ArrayList<>(size());
			for (int x = 0; x < width; x++) {
				for (int y = 0; y < height; y++) {
					for (int z = 0; z < depth; z++) {
						out.add(new Cell(x, y, z));
					}
				}
			}
			return List.copyOf(out);
		}
	}

	/** 规格里的一格（局部坐标）。 */
	public record Cell(int x, int y, int z) {
	}

	/** "这一格是谁"的查询结果（成形判定用；主块标记与方块实体由调用方填）。 */
	public record CellMatch(Spec spec, int x, int y, int z, boolean primary, @Nullable Object holder) {
	}

	/**
	 * 成形判定的逐格探测口（由端口实现：查世界的方块状态 / 查方块实体）。
	 *
	 * <p>实现必须 null 安全：不属于本规格的位置返回 {@code null}。</p>
	 */
	public interface CellProbe {

		/** 世界坐标 → 该位置的拼块归属（不属于任何规格 → null）。 */
		@Nullable
		CellMatch match(BlockPos worldPos);
	}

	/** 成形判定结果。 */
	public record Result(boolean formed, int matched, int total, @Nullable BlockPos primary, @Nullable Object holder) {

		/** 未成形且没有主块的空结果。 */
		public static Result empty(int total) {
			return new Result(false, 0, Math.max(1, total), null, null);
		}
	}

	// ------------------------------------------------------------------ 注册表（O(1) 查表）

	/** identifier → 规格。 */
	private static final Map<String, Spec> BY_IDENTIFIER = new LinkedHashMap<>();

	/** 方块注册名（含与不含命名空间两种写法都登记）→ 格子。 */
	private static final Map<String, CellMatch> BY_BLOCK_ID = new LinkedHashMap<>();

	private ChasmMultiblock() {
	}

	/**
	 * 登记一份规格与它的方块 id 表。
	 *
	 * <pre>{@code
	 * ChasmMultiblock.Spec spec = new ChasmMultiblock.Spec("stonecutter", 3, 2);
	 * Map<String, ChasmMultiblock.Cell> ids = new LinkedHashMap<>();
	 * ids.put("tetra:stonecutter_0_1", new ChasmMultiblock.Cell(0, 1, 0));
	 * ChasmMultiblock.mount(spec, ids);
	 * }</pre>
	 *
	 * <p>重复 mount 同一 identifier 以**最后一次**为准（热重载/开发期改布局用）。</p>
	 */
	public static void mount(Spec spec, Map<String, Cell> blockIds) {
		if (spec == null) {
			return;
		}
		synchronized (BY_IDENTIFIER) {
			BY_IDENTIFIER.put(spec.identifier(), spec);
			if (blockIds != null) {
				for (Map.Entry<String, Cell> entry : blockIds.entrySet()) {
					String id = entry.getKey();
					Cell cell = entry.getValue();
					if (id == null || cell == null) {
						continue;
					}
					putBlockId(id, new CellMatch(spec, cell.x(), cell.y(), cell.z(), spec.isPrimary(cell.x(), cell.y(), cell.z()), null));
					int colon = id.indexOf(':');
					if (colon >= 0) {
						putBlockId(id.substring(colon + 1), new CellMatch(spec, cell.x(), cell.y(), cell.z(),
							spec.isPrimary(cell.x(), cell.y(), cell.z()), null));
					}
				}
			}
		}
		ChasmLogger.info("chasm", "多方块规格已挂载：{}（{}×{}×{}，{} 个方块 id）",
			spec.identifier(), spec.width(), spec.height(), spec.depth(), blockIds == null ? 0 : blockIds.size());
	}

	private static void putBlockId(String id, CellMatch match) {
		BY_BLOCK_ID.put(id, match);
	}

	/** 全部规格（声明顺序）。 */
	public static List<Spec> all() {
		synchronized (BY_IDENTIFIER) {
			return List.copyOf(new ArrayList<>(BY_IDENTIFIER.values()));
		}
	}

	/** 按 identifier 查规格；未知 → null。 */
	@Nullable
	public static Spec byIdentifier(String identifier) {
		if (identifier == null) {
			return null;
		}
		synchronized (BY_IDENTIFIER) {
			return BY_IDENTIFIER.get(identifier);
		}
	}

	/** 按方块注册名查格子（{@code "stonecutter_1_1"} 与 {@code "tetra:stonecutter_1_1"} 都认）；未知 → null。 */
	@Nullable
	public static CellMatch byBlockId(String blockId) {
		if (blockId == null) {
			return null;
		}
		synchronized (BY_IDENTIFIER) {
			return BY_BLOCK_ID.get(blockId);
		}
	}

	/** 已登记的方块 id 数（拼块 + 废墟块；测试做全集比对用）。 */
	public static int blockIdCount() {
		synchronized (BY_IDENTIFIER) {
			return BY_BLOCK_ID.size();
		}
	}

	/** 清空（单测用；运行时不调用）。 */
	public static void clearForTesting() {
		synchronized (BY_IDENTIFIER) {
			BY_IDENTIFIER.clear();
			BY_BLOCK_ID.clear();
		}
	}

	// ------------------------------------------------------------------ 几何

	/**
	 * **局部格 → 世界坐标**（真实 {@code MultiblockSchematicBlock.java:88-96}）。
	 *
	 * <p>{@code rotateDirection} 逐方向内联自 {@code Mutil RotationHelper.java:51-63}：
	 * {@code SOUTH} = 原样、{@code WEST} = (−z, y, x)、{@code NORTH} = (−x, y, −z)、{@code EAST} = (z, y, −x)。
	 * 端口只用了水平朝向（真实 multischematic 只有 facing 属性，竖直方向走 default 分支）。</p>
	 *
	 * @param origin    本块（任意拼块）的世界坐标
	 * @param facing    本块的 facing 属性值（null 视作 SOUTH）
	 * @param originX   本块的局部横坐标
	 * @param originY   本块的局部纵坐标
	 * @param baseX     待换算格子的局部横坐标
	 * @param baseY     待换算格子的局部纵坐标
	 */
	public static BlockPos worldPos(BlockPos origin, Direction facing, int originX, int originY, int baseX, int baseY) {
		if (origin == null) {
			return BlockPos.ZERO;
		}
		int dx = baseX - originX;
		int dy = baseY - originY;
		return switch (facing == null ? Direction.SOUTH : facing) {
			case WEST -> origin.offset(0, dy, dx);
			case NORTH -> origin.offset(-dx, dy, 0);
			case EAST -> origin.offset(0, dy, -dx);
			default -> origin.offset(dx, dy, 0);
		};
	}

	/** 三维版本（厚 &gt; 1 的规格用；真实 z 恒为 0，所以 {@code originZ} 直接相加）。 */
	public static BlockPos worldPos(BlockPos origin, Direction facing, int originX, int originY, int originZ,
									int baseX, int baseY, int baseZ) {
		return worldPos(origin, facing, originX, originY, baseX, baseY).offset(0, 0, baseZ - originZ);
	}

	/** 规格的全部局部格子（BlockPos 形式，y 向上）。 */
	public static List<BlockPos> cells(Spec spec) {
		if (spec == null) {
			return Collections.emptyList();
		}
		List<BlockPos> out = new ArrayList<>(spec.size());
		for (int x = 0; x < spec.width(); x++) {
			for (int y = 0; y < spec.height(); y++) {
				for (int z = 0; z < spec.depth(); z++) {
					out.add(new BlockPos(x, y, z));
				}
			}
		}
		return out;
	}

	/** 某拼块在世界里的全部格子（真实 {@code getSchematicParts}）。 */
	public static List<BlockPos> partsOf(Spec spec, BlockPos origin, Direction facing, int originX, int originY) {
		if (spec == null || origin == null) {
			return Collections.emptyList();
		}
		List<BlockPos> out = new ArrayList<>(spec.size());
		for (Cell cell : spec.cells()) {
			out.add(worldPos(origin, facing, originX, originY, cell.x(), cell.y()));
		}
		return out;
	}

	/**
	 * 区块内位置的紧凑 key（{@code x & 15 | (z & 15) << 4 | y << 8}）。
	 *
	 * <p>用途：成形判定/多方块索引的键。{@link BlockPos} 当 Map key 每次要哈希三个 int，
	 * 这里压成一个 int，查表是纯哈希。</p>
	 */
	public static int chunkKey(int x, int y, int z) {
		return (x & 15) | ((z & 15) << 4) | ((y & 255) << 8);
	}

	public static int chunkKey(BlockPos pos) {
		return pos == null ? -1 : chunkKey(pos.getX(), pos.getY(), pos.getZ());
	}

	// ------------------------------------------------------------------ 成形判定

	/**
	 * **成形逐格判定**：{@code width*height*depth} 次探测，全部对上 → 成形。
	 *
	 * <p>与真实 {@code PrimaryMultiblockSchematicBlock.isCorrectPart:126-128} 同义：
	 * 同一规格 + 局部格对位。真实只要求 {@code instanceof MultiblockSchematicBlock}
	 * （废墟块不是它的子类，自然不算），这一点由 {@link CellProbe} 的实现负责。</p>
	 *
	 * @param spec     目标规格
	 * @param origin   判定基准块（任意拼块）
	 * @param facing   基准块的朝向
	 * @param originX  基准块的局部横坐标
	 * @param originY  基准块的局部纵坐标
	 * @param probe    逐格探测口（null → 直接不成形，绝不 NPE）
	 * @return 成形结果（含匹配格数、主块位置、主块的 holder）
	 */
	public static Result check(Spec spec, BlockPos origin, Direction facing, int originX, int originY, @Nullable CellProbe probe) {
		if (spec == null || origin == null || probe == null) {
			return Result.empty(spec == null ? 1 : spec.size());
		}
		int matched = 0;
		BlockPos primaryPos = null;
		Object primaryHolder = null;
		for (int x = 0; x < spec.width(); x++) {
			for (int y = 0; y < spec.height(); y++) {
				BlockPos target = worldPos(origin, facing, originX, originY, x, y);
				CellMatch match = probe.match(target);
				if (match == null || match.spec() != spec || match.x() != x || match.y() != y) {
					continue;
				}
				matched++;
				if (spec.isPrimary(x, y)) {
					primaryPos = target;
					primaryHolder = match.holder();
				}
			}
		}
		boolean formed = matched == spec.size();
		return new Result(formed, matched, spec.size(), formed ? primaryPos : null, formed ? primaryHolder : null);
	}

	// ------------------------------------------------------------------ 方块实体（统一声明入口）

	/**
	 * **多方块方块实体的模板**：规格 id + 所在格子 + 成形标记，存读与成形刷新都归框架。
	 *
	 * <p>用法（需要状态的多人方块才需要它；真实 Tetra 的 multischematic 把 {@code complete}
	 * 放在方块状态里、没有 BE —— 那种情况直接用 {@link #check} 即可）：</p>
	 * <pre>{@code
	 * public class MyForgeBE extends ChasmMultiblock.BlockEntity {
	 *     public MyForgeBE(BlockPos pos, BlockState state) { super(MY_TYPE, pos, state); }
	 *     {@literal @}Override protected CellProbe probe() { return MyMultiblocks::matchAt; }
	 *     {@literal @}Override protected void onFormedChanged(boolean formed) { ... }
	 * }
	 * }</pre>
	 */
	public static class BlockEntity extends ChasmBlockEntity {

		private static final String KEY_SPEC = "multiblock";
		private static final String KEY_CELL = "cell";
		private static final String KEY_FORMED = "formed";

		@Nullable
		private Spec spec;
		private int cellX;
		private int cellY;
		private int cellZ;
		private boolean formed;

		protected BlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
			super(type, pos, state);
			setLazyTickRate(20);
		}

		// —— 声明 ——

		/** 本 BE 所属规格（子类在 {@code addBehaviours} 之后、或读档后设置；null = 还没声明）。 */
		public void setSpec(@Nullable Spec spec, int x, int y, int z) {
			this.spec = spec;
			this.cellX = x;
			this.cellY = y;
			this.cellZ = z;
		}

		@Nullable
		public Spec spec() {
			return spec;
		}

		public int cellX() {
			return cellX;
		}

		public int cellY() {
			return cellY;
		}

		public int cellZ() {
			return cellZ;
		}

		/** 是否成形（最近一次 {@link #refreshFormation()} 的结果）。 */
		public boolean isFormed() {
			return formed;
		}

		/** 逐格探测口（子类实现；默认 null → 永不判定成形，**绝不 NPE**）。 */
		@Nullable
		protected CellProbe probe() {
			return null;
		}

		/** 成形状态变化时的回调（子类可在此发粒子/解锁内容）。 */
		protected void onFormedChanged(boolean nowFormed) {
		}

		// —— 判定 ——

		/**
		 * 重算成形状态并写回（状态没变则**不写**、不回调 —— 避免方块更新风暴）。
		 *
		 * @return 是否发生了变化
		 */
		public boolean refreshFormation() {
			if (spec == null || level == null || level.isClientSide()) {
				return false;
			}
			Direction facing = facingOf();
			Result result = check(spec, worldPosition, facing, cellX, cellY, probe());
			if (result.formed() == formed) {
				return false;
			}
			formed = result.formed();
			sendData();
			refresh();
			try {
				onFormedChanged(formed);
			} catch (RuntimeException e) {
				ChasmLogger.warn("chasm", "多方块 {} 的成形回调失败: {}", spec.identifier(), String.valueOf(e));
			}
			return true;
		}

		/** 基准朝向（子类若用属性存朝向就重写它；默认 SOUTH）。 */
		protected Direction facingOf() {
			return Direction.SOUTH;
		}

		// —— 序列化 ——

		@Override
		protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
			super.saveAdditional(tag, registries);
			if (spec != null) {
				tag.putString(KEY_SPEC, spec.identifier());
			}
			tag.putIntArray(KEY_CELL, new int[] { cellX, cellY, cellZ });
			tag.putBoolean(KEY_FORMED, formed);
		}

		@Override
		public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
			super.loadAdditional(tag, registries);
			if (tag.contains(KEY_SPEC)) {
				spec = byIdentifier(tag.getString(KEY_SPEC));
			}
			int[] cell = tag.getIntArray(KEY_CELL);
			if (cell.length >= 3) {
				cellX = cell[0];
				cellY = cell[1];
				cellZ = cell[2];
			}
			formed = tag.getBoolean(KEY_FORMED);
		}

		/** 该位置是否被加载（{@code level} 存在）；成形判定/广播都要求它是真世界。 */
		public boolean hasWorld() {
			return level != null && !level.isClientSide();
		}

		/** 便捷：从任意世界方块取本类型的 BE（取不到 → null）。 */
		@Nullable
		public static BlockEntity at(Level level, BlockPos pos) {
			if (level == null || pos == null) {
				return null;
			}
			net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
			return be instanceof BlockEntity multi ? multi : null;
		}

		/** 行为容器必须实现：多方块模板没有自带行为。 */
		@Override
		public void addBehaviours(List<ChasmBlockEntityBehaviour<?>> behaviours) {
			// 无自带行为（需要库存/热源的多人方块在自己的子类里追加）
		}
	}

	/** 注册名（含命名空间）→ 规格名，供数据包/调试按名字对账。 */
	public static java.util.Set<String> registeredIdentifiers() {
		synchronized (BY_IDENTIFIER) {
			return java.util.Collections.unmodifiableSet(new java.util.LinkedHashMap<>(BY_IDENTIFIER).keySet());
		}
	}

	/** id → {@link ResourceLocation} 的容错解析（数据里两者写法都出现过）。 */
	@Nullable
	public static ResourceLocation tryParse(String id) {
		return id == null ? null : ResourceLocation.tryParse(id);
	}
}
