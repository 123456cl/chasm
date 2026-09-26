package api.chasm.blockentity.template;

import api.chasm.blockentity.BehaviourType;
import api.chasm.blockentity.ChasmBlockEntity;
import api.chasm.blockentity.ChasmBlockEntityBehaviour;
import api.chasm.spi.Spis;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * 热源行为模板（HeatSourceBehaviour）—— 判定方块周围是否存在「热源」。
 *
 * <p>设计来源：森罗物语 {@code PotBlockEntity#hasHeatSource}（第十七步分析）。原版写法：</p>
 * <pre>{@code
 * BlockState below = level.getBlockState(worldPosition.below());
 * if (below.hasProperty(BlockStateProperties.LIT)) return below.getValue(BlockStateProperties.LIT);
 * return below.is(HEAT_SOURCE_BLOCKS_WITHOUT_LIT);
 * }</pre>
 *
 * <p>本模板将其参数化为「检查位置集合 + 热源标签集合 + 自定义谓词」，并在邻居/方块状态变化时
 * 自动重算。判断规则（任一位置满足即热）：</p>
 * <ol>
 *   <li>自定义谓词命中（{@link #predicate}）</li>
 *   <li>方块含 {@code lit} 属性且为 true（营火/熔炉等）</li>
 *   <li>方块位于任一热源标签（{@link #tag}，如自定义 {@code mymod:heat_sources}）</li>
 * </ol>
 *
 * <p>与 {@link CookingProcessBehaviour} 配合：{@code CookingProcessBehaviour.builder(be)
 * .requireHeat()} 自动取用本行为作为加工闸门。</p>
 *
 * @param <T> 方块实体类型
 */
public class HeatSourceBehaviour<T extends ChasmBlockEntity> extends ChasmBlockEntityBehaviour<T> {

	/** 类型键（跨 BE 查询用 {@code blockEntity.getBehaviour(HeatSourceBehaviour.TYPE)}）。 */
	public static final BehaviourType<HeatSourceBehaviour<?>> TYPE = new BehaviourType<>("chasm:heat_source");

	private final List<BlockPos> offsets = new ArrayList<>();
	private final List<TagKey<Block>> heatTags = new ArrayList<>();
	private Predicate<BlockState> customPredicate;
	private boolean hot;

	public HeatSourceBehaviour(T be) {
		super(be);
		// 默认检查正下方（对齐原版锅/熔炉语义）
		offsets.add(new BlockPos(0, -1, 0));
	}

	// —— 参数槽位 ——

	/** 追加一个相对检查位置（相对 BE 坐标）。 */
	public HeatSourceBehaviour<T> check(BlockPos offset) {
		offsets.add(Objects.requireNonNull(offset, "offset"));
		return this;
	}

	/** 检查正下方（默认已含）。 */
	public HeatSourceBehaviour<T> checkBelow() {
		return check(new BlockPos(0, -1, 0));
	}

	/** 追加一个热源标签（方块位于该标签即视为热源，不依赖 lit 属性）。 */
	public HeatSourceBehaviour<T> tag(TagKey<Block> tag) {
		heatTags.add(Objects.requireNonNull(tag, "tag"));
		return this;
	}

	/** 自定义热源谓词（优先级最高，命中即热）。 */
	public HeatSourceBehaviour<T> predicate(Predicate<BlockState> predicate) {
		this.customPredicate = Objects.requireNonNull(predicate, "predicate");
		return this;
	}

	// —— 查询 ——

	/** 当前是否有热源。 */
	public boolean isHot() {
		return hot;
	}

	// —— 生命周期 ——

	@Override
	public BehaviourType<?> getType() {
		return TYPE;
	}

	@Override
	public void initialize() {
		recompute();
	}

	@Override
	public void onBlockChanged(BlockState oldState) {
		recompute();
	}

	@Override
	public void onNeighborChanged(BlockPos neighborPos) {
		recompute();
	}

	private void recompute() {
		Level level = getLevel();
		if (level == null) {
			hot = false;
			return;
		}
		boolean found = false;
		BlockPos pos = getPos();
		for (BlockPos offset : offsets) {
			BlockState state = level.getBlockState(pos.offset(offset));
			if (customPredicate != null && customPredicate.test(state)) {
				found = true;
				break;
			}
			if (state.hasProperty(BlockStateProperties.LIT)) {
				if (state.getValue(BlockStateProperties.LIT)) {
					found = true;
					break;
				}
			} else {
				boolean inTag = false;
				for (TagKey<Block> tag : heatTags) {
					if (state.is(tag)) {
						inTag = true;
						break;
					}
				}
				if (inTag) {
					found = true;
					break;
				}
			}
		}
		if (found != hot) {
			hot = found;
			blockEntity.refresh();
			// SPI 接入点：热源状态翻转事件（第三方可 Spis.point("chasm:heat.changed",...).after(...) 监听）
			Spis.<ChasmBlockEntity, Object>point("chasm:heat.changed", ctx -> null).apply(blockEntity);
		}
	}
}