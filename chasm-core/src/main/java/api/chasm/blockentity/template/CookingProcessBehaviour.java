package api.chasm.blockentity.template;

import api.chasm.blockentity.BehaviourType;
import api.chasm.blockentity.ChasmBlockEntity;
import api.chasm.blockentity.ChasmBlockEntityBehaviour;
import api.chasm.spi.Spis;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 加工流程行为模板（CookingProcessBehaviour）—— 通用「输入 → 配方匹配 → 计时 → 产出」状态机。
 *
 * <p>设计来源：森罗物语（第十七步分析）{@code PotBlockEntity}（放料→炒菜→出锅→炒糊）与
 * {@code MillstoneBlockEntity}（配方匹配 + 进度倒数 + 输出闸门 + 定时刷新）共有的加工骨架。
 * 提炼为四个参数槽位：</p>
 * <ul>
 *   <li><b>输入</b>：{@link #input(Supplier)} 提供输入容器（BE 把自己的库存包成 {@link SimpleContainer}）</li>
 *   <li><b>配方</b>：{@link #match(RecipeMatcher)} 匹配输入并给出产物 + 耗时</li>
 *   <li><b>消费</b>：{@link #consume(Consumer)} 加工完成时移除配方用掉的输入（必须设置，否则同一批
 *       食材会被反复匹配 → 无限产出）</li>
 *   <li><b>闸门</b>：{@link #gate(BooleanSupplier)} 加工条件（热源/驱动/转速），不满足则暂停；
 *       {@link #requireHeat()} 自动取用 {@link HeatSourceBehaviour}；{@link #blocked(Supplier)} 输出占位闸门</li>
 * </ul>
 *
 * <p>状态机：{@link Status#IDLE}（等待匹配）→ {@link Status#PROCESSING}（计时推进，可暂停）→
 * {@link Status#DONE}（待取出，{@link #takeOutput()}）。可选 {@link #overbake(int, Consumer)}：
 * 出锅后长时间不取则进入 {@link Status#BURNT} 并触发烧糊回调（对齐炒糊模板）。</p>
 *
 * @param <T> 方块实体类型
 */
public class CookingProcessBehaviour<T extends ChasmBlockEntity> extends ChasmBlockEntityBehaviour<T> {

	/** 类型键（跨 BE 查询用 {@code blockEntity.getBehaviour(CookingProcessBehaviour.TYPE)}）。 */
	public static final BehaviourType<CookingProcessBehaviour<?>> TYPE = new BehaviourType<>("chasm:cooking_process");

	/** 加工阶段状态机。 */
	public enum Status {
		/** 空闲（等待放入食材/匹配配方）。 */
		IDLE,
		/** 加工中（计时推进）。 */
		PROCESSING,
		/** 完成待取出。 */
		DONE,
		/** 炒糊/烧焦（可选：完成后超时未取出）。 */
		BURNT
	}

	/** 配方匹配结果。 */
	public record Match(ItemStack result, int timeTicks) {
		public Match {
			Objects.requireNonNull(result, "result");
			timeTicks = Math.max(1, timeTicks);
		}
	}

	/** 配方匹配器：给定世界与输入容器，返回匹配到的产物（空 = 不匹配）。 */
	@FunctionalInterface
	public interface RecipeMatcher {
		Optional<Match> match(Level level, Container input);
	}

	// —— 参数槽位 ——

	private final Supplier<SimpleContainer> inputProvider;
	private final RecipeMatcher matcher;
	private final Supplier<Boolean> outputBlocked;
	private final BooleanSupplier gate;
	private final Consumer<SimpleContainer> inputConsumer;
	private final Consumer<Level> onComplete;
	private final int refreshEvery;
	private final int overbakeWindow;
	private final Consumer<Level> onBurned;
	/** P0-2：是否需对客户端展示进度（true=周期广播进度；false=仅在完成/状态翻转时广播，省网络）。 */
	private final boolean progressVisible;

	// —— 运行状态 ——

	private Status status = Status.IDLE;
	private int progress;
	private int totalTime;
	private int doneTicks;
	private ItemStack result = ItemStack.EMPTY;

	private CookingProcessBehaviour(Builder<T> b, T be) {
		super(be);
		this.inputProvider = b.inputProvider;
		this.matcher = b.matcher;
		this.outputBlocked = b.outputBlocked;
		this.gate = b.gate;
		this.inputConsumer = b.inputConsumer;
		this.onComplete = b.onComplete;
		this.refreshEvery = b.refreshEvery;
		this.overbakeWindow = b.overbakeWindow;
		this.onBurned = b.onBurned;
		this.progressVisible = b.progressVisible;
	}

	/** 创建构建器（行为参数较多，用 Builder 收敛）。 */
	public static <T extends ChasmBlockEntity> Builder<T> builder(T be) {
		return new Builder<>(be);
	}

	// —— 查询 ——

	/** 当前阶段。 */
	public Status getStatus() {
		return status;
	}

	/** 是否加工中。 */
	public boolean isProcessing() {
		return status == Status.PROCESSING;
	}

	/** 是否完成待取出。 */
	public boolean isDone() {
		return status == Status.DONE;
	}

	/** 是否已烧糊。 */
	public boolean isBurnt() {
		return status == Status.BURNT;
	}

	/** 当前进度（已消耗 tick）。 */
	public int getProgress() {
		return progress;
	}

	/** 总耗时（tick）。 */
	public int getTotalTime() {
		return totalTime;
	}

	/** 进度百分比（0~1，未开始为 0）。 */
	public float getProgressF() {
		return totalTime <= 0 ? 0f : Math.min(1f, progress / (float) totalTime);
	}

	/** 产物（完成/烧糊阶段非空）。 */
	public ItemStack getResult() {
		return result;
	}

	/** 取出产物（返回拷贝并复位到 IDLE；未完成返回 EMPTY）。 */
	public ItemStack takeOutput() {
		if (status != Status.DONE && status != Status.BURNT) {
			return ItemStack.EMPTY;
		}
		ItemStack out = result.copy();
		reset();
		blockEntity.refresh();
		return out;
	}

	/**
	 * 中断进行中的加工（如玩家取出生食材时调用，避免空输入重复产出）；
	 * IDLE / DONE / BURNT 状态下无操作。
	 */
	public void cancel() {
		if (status == Status.PROCESSING) {
			reset();
			blockEntity.refresh();
		}
	}

	// —— 生命周期 ——

	@Override
	public BehaviourType<?> getType() {
		return TYPE;
	}

	@Override
	public void tick() {
		Level level = getLevel();
		if (level == null || level.isClientSide) {
			return;
		}
		switch (status) {
			case IDLE -> tryStart(level);
			case PROCESSING -> advance(level);
			case DONE -> overbakeTick(level);
			case BURNT -> {
			}
		}
	}

	/** 手动触发一次配方匹配（如玩家放入食材后调用；空闲期外的调用被忽略）。 */
	public void start() {
		Level level = getLevel();
		if (level != null && status == Status.IDLE) {
			tryStart(level);
		}
	}

	// —— 内部 ——

	private void tryStart(Level level) {
		if (outputBlocked.get()) {
			return;
		}
		// SPI 决策点：第三方可批准/否决本次加工开始（返回 FALSE 即禁止；默认 TRUE）
		if (Boolean.FALSE.equals(Spis.<Level, Boolean>point("chasm:cooking.can_start", lvl -> Boolean.TRUE).apply(level))) {
			return;
		}
		if (!gate.getAsBoolean()) {
			return;
		}
		SimpleContainer container = inputProvider.get();
		if (container == null) {
			return;
		}
		Optional<Match> matched = matcher.match(level, container);
		if (matched.isEmpty()) {
			return;
		}
		Match match = matched.get();
		this.result = match.result().copy();
		this.totalTime = match.timeTicks();
		this.progress = 0;
		this.doneTicks = 0;
		this.status = Status.PROCESSING;
		blockEntity.refresh();
		// SPI 接入点：开始加工事件
		Spis.<Level, Object>point("chasm:cooking.started", ctx -> null).apply(level);
	}

	private void advance(Level level) {
		// 热源/驱动消失则暂停（对齐森罗物语：无热源不推进）
		if (!gate.getAsBoolean()) {
			return;
		}
		this.progress++;
		// SPI 决策点：进度是否可广播（本地默认 false 时，全局加装可强制开启）
		boolean progressVisible = this.progressVisible
			&& Boolean.TRUE.equals(Spis.<Level, Boolean>point("chasm:cooking.progress_visible", lvl -> Boolean.TRUE).apply(level));
		if (progressVisible && this.progress % refreshEvery == 0) {
			blockEntity.refresh(); // P0-2：仅需展示进度时才周期广播
		}
		if (this.progress >= this.totalTime) {
			// 加工完成：先消费配方用掉的输入（避免同一批食材被反复匹配 → 无限产出）
			SimpleContainer container = inputProvider.get();
			if (container != null) {
				inputConsumer.accept(container);
			}
			this.status = Status.DONE;
			this.doneTicks = 0;
			blockEntity.refresh();
			onComplete.accept(level);
			// SPI 接入点：任意 chasm 加工完成事件（第三方可 Spis.point("chasm:cooking.completed",...).after(...) 监听/装饰，不影响本机默认行为）
			Spis.<Level, Object>point("chasm:cooking.completed", ctx -> null).apply(level);
		}
	}

	private void overbakeTick(Level level) {
		if (overbakeWindow <= 0) {
			return;
		}
		this.doneTicks++;
		if (this.doneTicks >= overbakeWindow) {
			this.status = Status.BURNT;
			blockEntity.refresh();
			onBurned.accept(level);
		}
	}

	private void reset() {
		this.status = Status.IDLE;
		this.progress = 0;
		this.totalTime = 0;
		this.doneTicks = 0;
		this.result = ItemStack.EMPTY;
	}

	// —— Builder ——

	/** {@link CookingProcessBehaviour} 构建器。 */
	public static final class Builder<T extends ChasmBlockEntity> {

		private final T be;
		private Supplier<SimpleContainer> inputProvider = SimpleContainer::new;
		private RecipeMatcher matcher = (level, input) -> Optional.empty();
		private Supplier<Boolean> outputBlocked = () -> false;
		private BooleanSupplier gate = () -> true;
		private Consumer<SimpleContainer> inputConsumer = container -> {
		};
		private Consumer<Level> onComplete = level -> {
		};
		private int refreshEvery = 5;
		private boolean progressVisible = true;
		private int overbakeWindow;
		private Consumer<Level> onBurned = level -> {
		};

		private Builder(T be) {
			this.be = Objects.requireNonNull(be, "be");
		}

		/** 输入容器提供者（把 BE 库存包成 {@link SimpleContainer}；可为 null 视为空容器）。 */
		public Builder<T> input(Supplier<SimpleContainer> inputProvider) {
			this.inputProvider = Objects.requireNonNull(inputProvider, "inputProvider");
			return this;
		}

		/** 配方匹配器（必须设置，否则永远不开始）。 */
		public Builder<T> match(RecipeMatcher matcher) {
			this.matcher = Objects.requireNonNull(matcher, "matcher");
			return this;
		}

		/** 输出占位闸门：返回 true 时暂停加工（如输出槽已满）。 */
		public Builder<T> blocked(Supplier<Boolean> outputBlocked) {
			this.outputBlocked = Objects.requireNonNull(outputBlocked, "outputBlocked");
			return this;
		}

		/** 加工闸门：返回 false 时暂停（如热源/驱动/转速不足）。 */
		public Builder<T> gate(BooleanSupplier gate) {
			this.gate = Objects.requireNonNull(gate, "gate");
			return this;
		}

		/** 自动以 {@link HeatSourceBehaviour#isHot()} 作为加工闸门（找不到热源行为则始终允许）。 */
		public Builder<T> requireHeat() {
			this.gate = () -> {
				HeatSourceBehaviour<?> heat = be.getBehaviour(HeatSourceBehaviour.TYPE);
				return heat == null || heat.isHot();
			};
			return this;
		}

		/** 完成回调（服务端；可把产物放入输出槽 / 弹出物品 / 触发音效）。 */
		public Builder<T> onComplete(Consumer<Level> onComplete) {
			this.onComplete = Objects.requireNonNull(onComplete, "onComplete");
			return this;
		}

		/** 加工完成时消费输入容器（把配方用掉的食材从库存移除；防止同一批食材被反复匹配无限产出）。 */
		public Builder<T> consume(Consumer<SimpleContainer> inputConsumer) {
			this.inputConsumer = Objects.requireNonNull(inputConsumer, "inputConsumer");
			return this;
		}

		/** 是否向客户端展示进度（默认 true；自动弹出类机器设 false 可省网络）。 */
		public Builder<T> progressVisible(boolean visible) {
			this.progressVisible = visible;
			return this;
		}

		/** 进度刷新频率（每 N tick 调一次 {@code refresh()}，默认 5；对齐森罗物语节流）。 */
		public Builder<T> refreshEvery(int ticks) {
			this.refreshEvery = Math.max(1, ticks);
			return this;
		}

		/** 完成后超时烧糊：{@code ticks} 内未取出则进入 {@link Status#BURNT} 并触发 {@code onBurned}。 */
		public Builder<T> overbake(int ticks, Consumer<Level> onBurned) {
			this.overbakeWindow = Math.max(1, ticks);
			this.onBurned = Objects.requireNonNull(onBurned, "onBurned");
			return this;
		}

		/** 构建行为。 */
		public CookingProcessBehaviour<T> build() {
			return new CookingProcessBehaviour<>(this, be);
		}
	}

	// —— 便捷查询 ——

	/** 从任意方块实体上按类型键取加工行为（非 Chasm BE 返回 null）。 */
	@Nullable
	@SuppressWarnings("unchecked")
	public static <B extends ChasmBlockEntity> CookingProcessBehaviour<B> get(
		@Nullable net.minecraft.world.level.block.entity.BlockEntity be) {
		if (be instanceof ChasmBlockEntity cbe) {
			return (CookingProcessBehaviour<B>) cbe.getBehaviour(TYPE);
		}
		return null;
	}
}