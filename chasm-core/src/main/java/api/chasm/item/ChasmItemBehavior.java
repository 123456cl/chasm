package api.chasm.item;

import api.chasm.data.ChasmData;
import api.chasm.enchantment.ChasmEnchantments;
import api.chasm.item.context.AttackContext;
import api.chasm.item.context.InventoryTickContext;
import api.chasm.item.context.KeyContext;
import api.chasm.item.context.UseContext;
import api.chasm.log.ChasmLogger;
import api.chasm.trait.AttackTrait;
import api.chasm.trait.ChasmTraits;
import api.chasm.trait.InventoryTickTrait;
import api.chasm.trait.ItemTraitEvent;
import api.chasm.trait.UseTrait;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Chasm 玩法物品的共享行为载体。
 *
 * <p>把原先散落在 {@link ChasmItem} 内的全部行为数据与触发逻辑集中到这里，使
 * {@code ChasmItem}（普通物品）与 {@code ChasmSwordItem}（剑）等不同原版子类
 * 能复用同一套「特质优先 + 回调兜底 + 默认组件」逻辑，避免千行模板重复。</p>
 *
 * <p>行为列表保持可变：其他模组可通过 {@link ChasmItemController} 在加载后追加/覆写行为
 * （SPI 扩展能力）。</p>
 */
public final class ChasmItemBehavior {

	private final List<Consumer<UseContext>> rightClickHandlers;
	private final List<Consumer<AttackContext>> attackHandlers;
	private final List<DataComponentType<?>> defaultComponentTypes;
	private final List<Object> defaultComponentValues;
	private final List<Class<?>> deferredComponentClasses;
	private final List<Object> deferredComponentValues;
	private final String displayName;
	private final String texture;
	/**
	 * 是否允许 DataGen **自动生成**平面物品模型。
	 *
	 * <p>为什么需要它：真实模组移植时会自带模型（如 Tetra 的 {@code modular_sword} 是带
	 * {@code overrides} 的真实模型）。自动生成的占位模型与自带模型**同路径**，
	 * 同时存在时 {@code processResources} 会直接因"重复条目"失败 —— 这不是配置问题，
	 * 是**两个真相源**，正确做法是声明"我的模型自己带"。</p>
	 */
	private final boolean autoModel;
	/** 声明的基础攻击力（普通攻击伤害），默认 1.0f 与原版一致。 */
	private final float attackDamage;
	/** 延迟绑定已解析的 DataComponentType 缓存（仅存非 null 值）。 */
	private final Map<Class<?>, DataComponentType<?>> deferredResolved = new ConcurrentHashMap<>();
	/** 绑定到的物品类型（null = 未指定 type，普通物品）。 */
	private volatile ItemType type;
	/**
	 * **按栈动态命名**（如宝石的"无瑕的战斗宝石"）：null = 用语言文件里的静态名。
	 *
	 * <p>为什么需要它：原版 {@code Item.getName(ItemStack)} 是可以按栈取名的，但框架此前只支持
	 * {@code .name("静态名")}（写进语言文件），于是"同一物品不同组件 = 不同名字"的玩法
	 * （宝石纯度、附魔等级、词缀前后缀）只能退化成"名字不变、全靠 tooltip"。</p>
	 */
	private volatile java.util.function.Function<ItemStack, net.minecraft.network.chat.Component> nameProvider;

	/** 自带附魔绑定列表（随默认实例写入 ENCHANTMENTS 组件）。 */
	private final List<EnchantmentBinding> enchantmentBindings = new ArrayList<>();
	/** 已解析的附带附魔 Holder 缓存（附魔注册表冻结后填入）。 */
	private final Map<ResourceKey<Enchantment>, Holder.Reference<Enchantment>> enchantResolved = new ConcurrentHashMap<>();
	/** 自定义按键处理器：按键 id → 回调（第十二步，由 {@code onKeyPress} 绑定）。 */
	private final Map<ResourceLocation, Consumer<KeyContext>> keyHandlers = new ConcurrentHashMap<>();
	/** 挖掘委托（可空；由 {@link #installMining} 装入，见 {@link MiningHooks}）。 */
	private volatile MiningHooks mining;

	ChasmItemBehavior(List<Consumer<UseContext>> rightClickHandlers,
		List<Consumer<AttackContext>> attackHandlers,
		List<DataComponentType<?>> defaultComponentTypes, List<Object> defaultComponentValues,
		List<Class<?>> deferredComponentClasses, List<Object> deferredComponentValues,
		String displayName, String texture, boolean autoModel, float attackDamage) {
		this.rightClickHandlers = new ArrayList<>(rightClickHandlers);
		this.attackHandlers = new ArrayList<>(attackHandlers);
		this.defaultComponentTypes = new ArrayList<>(defaultComponentTypes);
		this.defaultComponentValues = new ArrayList<>(defaultComponentValues);
		this.deferredComponentClasses = new ArrayList<>(deferredComponentClasses);
		this.deferredComponentValues = new ArrayList<>(deferredComponentValues);
		this.displayName = displayName;
		this.texture = texture;
		this.autoModel = autoModel;
		this.attackDamage = attackDamage;
	}

	/** 声明的基础攻击力（普通攻击伤害）。Trait 可据此计算魔法伤害差额，而非硬编码原版基础值。 */
	public float getAttackDamage() {
		return attackDamage;
	}

	/** 绑定该物品所属的类型（由 {@code ItemBuilder.register} 在构建行为时设置）。 */
	/** 设置按栈动态命名（可空；返回 null 表示回落到静态名）。 */
	public void setNameProvider(java.util.function.Function<ItemStack, net.minecraft.network.chat.Component> provider) {
		this.nameProvider = provider;
	}

	/** 按栈取名（无 provider 时返回 null，由物品回落到 super.getName）。 */
	public net.minecraft.network.chat.Component nameOf(ItemStack stack) {
		java.util.function.Function<ItemStack, net.minecraft.network.chat.Component> provider = this.nameProvider;
		return provider == null || stack == null ? null : provider.apply(stack);
	}

	public void setItemType(ItemType type) {
		this.type = type;
	}

	/** 该物品绑定的类型（未指定返回 null）。 */
	public ItemType chasmType() {
		return type;
	}

	/** 追加一条自带附魔绑定（Holder 已确定时用）。 */
	public void addEnchantment(Holder<Enchantment> holder, int level) {
		enchantmentBindings.add(EnchantmentBinding.of(holder, level));
	}

	/** 追加一条自带附魔绑定（仅知注册键，运行时解析 Holder）。 */
	public void addEnchantment(ResourceKey<Enchantment> key, int level) {
		enchantmentBindings.add(EnchantmentBinding.of(key, level));
	}

	/** 迭代所有自带附魔绑定（供注册扫描器输出结构化日志）。 */
	public List<EnchantmentBinding> enchantmentBindings() {
		return enchantmentBindings;
	}

	/** 绑定一个自定义按键处理器（由 {@link ItemBuilder#onKeyPress} 调用）。 */
	public void addKeyHandler(ResourceLocation keyId, Consumer<KeyContext> handler) {
		keyHandlers.put(keyId, handler);
	}

	/** 反查某按键绑定的处理器（未绑定返回 null；服务端按键通道据此执行）。 */
	public Consumer<KeyContext> keyHandler(ResourceLocation keyId) {
		return keyHandlers.get(keyId);
	}

	/** 该物品绑定的自定义按键 id 集合（日志/调试）。 */
	public java.util.Set<ResourceLocation> keyHandlerIds() {
		return keyHandlers.keySet();
	}

	/**
	 * 本物品通过 {@code ItemBuilder.component()} 显式绑定的默认组件（类型→默认值）。
	 *
	 * <p>供 {@link api.chasm.registry.ChasmRegistrar} 在注册时输出结构化日志；
	 * 不含原版内置默认组件，仅含创作者显式声明项。</p>
	 */
	public Map<DataComponentType<?>, Object> chasmDefaultComponents() {
		Map<DataComponentType<?>, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < defaultComponentTypes.size(); i++) {
			map.put(defaultComponentTypes.get(i), defaultComponentValues.get(i));
		}
		return Collections.unmodifiableMap(map);
	}

	/** 声明式显示名（DataGen 生成语言文件用），可为 null。 */
	public String chasmDisplayName() {
		return displayName;
	}

	/** 声明式贴图路径（DataGen 生成模型用），可为 null。 */
	/** 是否允许 DataGen 自动生成物品模型（默认 true）。 */
	public boolean chasmAutoModel() {
		return autoModel;
	}

	public String chasmTexture() {
		return texture;
	}

	/** 追加右键行为（供控制台调用）。 */
	public void addRightClickHandler(Consumer<UseContext> handler) {
		rightClickHandlers.add(handler);
	}

	/** 清空全部右键行为（供控制台覆写调用）。 */
	public void clearRightClickHandlers() {
		rightClickHandlers.clear();
	}

	/** 追加攻击行为（供控制台调用）。 */
	public void addAttackHandler(Consumer<AttackContext> handler) {
		attackHandlers.add(handler);
	}

	/** 清空全部攻击行为（供控制台覆写调用）。 */
	public void clearAttackHandlers() {
		attackHandlers.clear();
	}

	/** 当前右键行为数量（调试/检测用）。 */
	public int rightClickHandlerCount() {
		return rightClickHandlers.size();
	}

	/** 当前攻击行为数量（调试/检测用）。 */
	public int attackHandlerCount() {
		return attackHandlers.size();
	}

	/**
	 * 右键使用触发器（由物品子类的 {@code use()} 委托调用）。
	 *
	 * <p>逻辑与旧 {@code ChasmItem#use} 一致：特质优先、回调兜底。</p>
	 *
	 * @return 本次使用的结果（成功/消耗/失败/跳过）
	 */
	public InteractionResultHolder<ItemStack> handleUse(Level level, Player player,
		InteractionHand hand, ItemStack stack) {
		// 1) 特质优先：若物品栈绑定了 USE 特质，则 O(1) 查回并执行，零遍历
		ResourceLocation traitId = stack.get(ChasmData.USE_TRAIT_ID);
		if (traitId != null) {
			UseTrait trait = ChasmTraits.INSTANCE.get(ItemTraitEvent.USE, traitId);
			if (trait != null) {
				ChasmLogger.call(traitId.getNamespace(), "UseTrait", traitId.getPath(),
					"触发于物品 {}", stack.getItem());
				try {
					InteractionResult result = trait.trigger(new UseContext(level, player, hand, stack));
					if (result == InteractionResult.SUCCESS) {
						return InteractionResultHolder.success(stack);
					}
					if (result == InteractionResult.CONSUME) {
						return InteractionResultHolder.consume(stack);
					}
					if (result == InteractionResult.FAIL) {
						return InteractionResultHolder.fail(stack);
					}
					return InteractionResultHolder.pass(stack);
				} catch (Exception e) {
					ChasmLogger.error(traitId.getNamespace(), "UseTrait {} 执行异常，回退原版行为", traitId, e);
				}
			} else {
				ChasmLogger.error(traitId.getNamespace(), "特质 {} 未注册，回退原版行为", traitId);
			}
		}
		// 2) 兼容旧回调列表（未绑定特质时）
		UseContext ctx = new UseContext(level, player, hand, stack);
		for (Consumer<UseContext> handler : rightClickHandlers) {
			handler.accept(ctx);
		}
		// 1.21 起 use() 返回 InteractionResultHolder，携带使用后的物品栈
		return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
	}

	/**
	 * 攻击实体触发器（由物品子类的 {@code hurtEnemy()} 委托调用）。
	 *
	 * @return 是否已被特质完全消费（true 时调用方应短路，不再走原版伤害逻辑）
	 */
	public boolean handleAttack(ItemStack stack, LivingEntity target, LivingEntity attacker) {
		// 1) 特质优先：若物品栈绑定了 ATTACK 特质，则 O(1) 查回并执行
		ResourceLocation traitId = stack.get(ChasmData.ATTACK_TRAIT_ID);
		if (traitId != null) {
			AttackTrait trait = ChasmTraits.INSTANCE.get(ItemTraitEvent.ATTACK, traitId);
			if (trait != null) {
				ChasmLogger.call(traitId.getNamespace(), "AttackTrait", traitId.getPath(),
					"触发于目标 {}", target);
				try {
					InteractionResult result = trait.trigger(new AttackContext(stack, target, attacker));
					if (result == InteractionResult.SUCCESS || result == InteractionResult.CONSUME) {
						return true; // 已处理，不再走原版伤害逻辑
					}
				} catch (Exception e) {
					ChasmLogger.error(traitId.getNamespace(), "AttackTrait {} 执行异常，回退原版行为", traitId, e);
				}
			} else {
				ChasmLogger.error(traitId.getNamespace(), "特质 {} 未注册，回退原版行为", traitId);
			}
		}
		// 2) 兼容旧回调列表（未绑定特质时）
		if (!attackHandlers.isEmpty()) {
			AttackContext ctx = new AttackContext(stack, target, attacker);
			for (Consumer<AttackContext> handler : attackHandlers) {
				handler.accept(ctx);
			}
		}
		return false;
	}

	/**
	 * 物品栏 tick 触发器（由物品子类的 {@code inventoryTick()} 委托调用，不含 super）。
	 */
	public void handleInventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean selected) {
		if (selected) {
			// 只对"主手选中槽"做一次廉价体检：修好已写进存档的致命攻速
			//（总攻速 ≤ 0 会让蓄力刻度恒 0 → 手上物品永久沉在视野外）。健康物品零写入。
			api.chasm.attribute.ChasmAttackSpeed.sanitize(stack);
		}
		ResourceLocation traitId = stack.get(ChasmData.INVENTORY_TICK_TRAIT_ID);
		if (traitId != null) {
			InventoryTickTrait trait = ChasmTraits.INSTANCE.get(ItemTraitEvent.INVENTORY_TICK, traitId);
			if (trait != null) {
				if (entity instanceof Player player) {
					ChasmLogger.call(traitId.getNamespace(), "InventoryTickTrait", traitId.getPath(),
						"槽位 {} 选中 {}", slotId, selected);
					try {
						trait.trigger(new InventoryTickContext(level, player, stack, slotId, selected));
					} catch (Exception e) {
						ChasmLogger.error(traitId.getNamespace(), "InventoryTickTrait {} 执行异常，回退原版行为", traitId, e);
					}
				}
			} else {
				ChasmLogger.error(traitId.getNamespace(), "特质 {} 未注册，回退原版行为", traitId);
			}
		}
	}

	// ------------------------------------------------------------ 挖掘与提示（原版钩子的开放口）

	/**
	 * **挖掘委托**（框架能力）—— 把原版那四个"只能靠子类覆写"的挖掘钩子（破坏速度 / 采集资格 /
	 * 接管挖掘 / 挖完回调）变成**可插拔的行为**。
	 *
	 * <p>为什么必须补这一层：{@link ChasmItemBehavior} 是 {@code final}，外部系统（例如模块化工具的
	 * 挖掘链）没法继承它；而这四个钩子都在 {@code Item} 上，靠 {@link api.chasm.item.ChasmItemController}
	 * 那种"追加回调"也够不着。有了它，任意物品都能像 {@code ChasmItem} 一样表态"我的挖掘怎么算"，
	 * 且**不装委托时行为与从前逐字一致**（默认方法返回"不表态"）。</p>
	 */
	public interface MiningHooks {

		/** 破坏速度（原版 {@code Item#getDestroySpeed}）。 */
		float destroySpeed(ItemStack stack, BlockState state);

		/** 是否为该方块的正确工具（原版 {@code Item#isCorrectToolForDrops}）。 */
		boolean isCorrectToolForDrops(ItemStack stack, BlockState state);

		/** 是否接管 {@code mineBlock}（true 时耐久/进度由委托自己算）。 */
		boolean handlesMining();

		/** 挖掘完成回调（原版 {@code Item#mineBlock}）。 */
		void onMineBlock(ItemStack stack, Level level, BlockState state, BlockPos pos, LivingEntity miner);
	}

	/**
	 * **装入挖掘委托**（幂等：重复装同一个会原样返回；装完返回 {@code this} 便于链式）。
	 *
	 * <p>位置是安全的：只在物品注册之后（行为已构造完）由贡献者/控制台调用，
	 * 且字段是 {@code volatile}，渲染线程读到的要么是 null 要么是完整对象。</p>
	 */
	public ChasmItemBehavior installMining(MiningHooks hooks) {
		if (hooks != null) {
			this.mining = hooks;
		}
		return this;
	}

	/** 当前挖掘委托（未装 → null）。 */
	public MiningHooks mining() {
		return mining;
	}

	/**
	 * **破坏速度**（原版 {@code Item#getDestroySpeed(ItemStack, BlockState)} 的挂钩）。
	 *
	 * <p>为什么要有它：模块化工具的速度是"攻速 × 效率"算出来的（真实 Tetra 的公式，
	 * 见 M1 报告），以前要支持它就得为一个特殊物品写一个 {@code Item} 子类并覆写原版方法；
	 * 现在只需在行为里表态，任意物品都能复用。</p>
	 *
	 * @return 负数表示**不表态**（物品回落到原版逻辑）
	 */
	public float destroySpeed(ItemStack stack, BlockState state) {
		MiningHooks hooks = this.mining;
		return hooks == null ? -1.0F : hooks.destroySpeed(stack, state);
	}

	/**
	 * **是否为该方块的正确工具**（原版 {@code Item#isCorrectToolForDrops}）。
	 *
	 * @return {@code null} = 不表态（走原版）；否则用返回值
	 */
	public Boolean isCorrectToolForDrops(ItemStack stack, BlockState state) {
		MiningHooks hooks = this.mining;
		return hooks == null ? null : hooks.isCorrectToolForDrops(stack, state);
	}

	/** 是否自己接管挖掘（true 时 {@code mineBlock} 不再走原版，耐久/进度由行为自己算）。 */
	public boolean handlesMining() {
		MiningHooks hooks = this.mining;
		return hooks != null && hooks.handlesMining();
	}

	/**
	 * **挖掘完成回调**（原版 {@code Item#mineBlock}）。
	 *
	 * <p>⚠️ 1.21.1 的掉落时序：先判 {@code hasCorrectToolForDrops} → 再 {@code mineBlock} 扣耐久 →
	 * 最后用**调用前 copy 的栈**做掉落。所以想改掉落不能在这里改栈（改了也不生效），
	 * 要走 {@code handleUse} 或方块破坏事件。</p>
	 */
	public void onMineBlock(ItemStack stack, Level level, BlockState state, BlockPos pos, LivingEntity miner) {
		MiningHooks hooks = this.mining;
		if (hooks != null) {
			hooks.onMineBlock(stack, level, state, pos, miner);
		}
	}

	/** tooltip 的插入位置。 */
	public enum TooltipSlot {
		/** 原版基础行（含属性行）**之前** —— 真实 Tetra 的模块/附魔/磨砺清单就插在这里。 */
		BEFORE_BASE,
		/** 原版基础行之后。 */
		AFTER_BASE
	}

	/**
	 * **自定义 tooltip 行**（分段插入）。
	 *
	 * <p>为什么要分位置：Fabric 的 {@code ItemTooltipCallback} 注入在 {@code ItemStack.getTooltip} 的
	 * RETURN 处，追加的行只能落在**最底部**；而真实 Tetra 的模块清单必须出现在原版附魔行**之前**
	 * （J 报告的施工图）。只有走行为钩子才能在正确位置插入。</p>
	 */
	public List<Component> tooltipLines(ItemStack stack, TooltipFlag flag, TooltipSlot slot) {
		return List.of();
	}

	/** 是否仍要执行"攻速行易读化"变换（自己画属性的物品可以关掉）。 */
	public boolean transformsVanillaTooltip() {
		return true;
	}

	/**
	 * 默认实例装饰：运行时解析延迟绑定的 Record 组件类型并绑定默认值。
	 *
	 * <p>时机：getDefaultInstance 首次调用发生在 onInitialize 扫描注册完成之后，
	 * 此时 componentType(recordClass) 必定已注册，规避静态初始化时序死穴。</p>
	 *
	 * @param stack 已由 {@code super.getDefaultInstance()} 生成的默认栈
	 * @return 补全延迟组件后的默认栈
	 */
	@SuppressWarnings("unchecked")
	public ItemStack applyDefaultInstance(ItemStack stack) {
		for (int i = 0; i < deferredComponentClasses.size(); i++) {
			Class<?> recordClass = deferredComponentClasses.get(i);
			Object value = deferredComponentValues.get(i);
			DataComponentType<?> type = deferredResolved.computeIfAbsent(recordClass, cls -> {
				try {
					return ChasmData.INSTANCE.componentType(cls);
				} catch (IllegalArgumentException e) {
					ChasmLogger.error("chasm", "Record 组件未注册，延迟绑定跳过: {}", cls.getName());
					return null;
				}
			});
			if (type != null) {
				// 泛型擦除：type 声明为 DataComponentType<?>、value 为 Object，
				// 此处以 raw type 调用以绕过编译期类型检查（运行时类型已由 ChasmData 保证一致）。
				@SuppressWarnings({"unchecked", "rawtypes"})
				DataComponentType rawType = (DataComponentType) type;
				stack.set(rawType, value);
			}
		}
		applyEnchantments(stack);
		return stack;
	}

	/**
	 * 把自带附魔写入 {@link DataComponents#ENCHANTMENTS}。
	 *
	 * <p>附魔为 1.21.1 动态注册表，静态期无法解析 Holder；此处在运行时（注册表捕获后）解析并写入。
	 * 对于已直接给定 Holder 的绑定则立即生效；仅知注册键的会解析并缓存，注册表尚未冻结时跳过（下次调用重试）。</p>
	 */
	private void applyEnchantments(ItemStack stack) {
		if (enchantmentBindings.isEmpty()) {
			return;
		}
		ItemEnchantments.Mutable out = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
		boolean touched = false;
		for (EnchantmentBinding binding : enchantmentBindings) {
			Holder<Enchantment> holder = binding.resolve(enchantResolved);
			if (holder != null) {
				// 附带附魔不覆盖物品栈上已有的同名附魔（如附魔台之后的应用）
				if (out.getLevel(holder) == 0) {
					out.set(holder, binding.level());
					touched = true;
				}
			} else {
				// 注册表尚未冻结或有无法解析的键 → 延迟到下一次 getDefaultInstance 再试
				continue;
			}
		}
		if (touched) {
			stack.set(DataComponents.ENCHANTMENTS, out.toImmutable());
		}
	}

	/**
	 * 自带附魔绑定：要么持有确定的 {@link Holder}，要么仅知 {@link ResourceKey} 运行时解析。
	 */
	public static final class EnchantmentBinding {
		private final Holder<Enchantment> holder;
		private final ResourceKey<Enchantment> key;
		private final int level;

		private EnchantmentBinding(Holder<Enchantment> holder, ResourceKey<Enchantment> key, int level) {
			this.holder = holder;
			this.key = key;
			this.level = level;
		}

		static EnchantmentBinding of(Holder<Enchantment> holder, int level) {
			return new EnchantmentBinding(holder, holder == null ? null : holder.unwrapKey().orElse(null), level);
		}

		static EnchantmentBinding of(ResourceKey<Enchantment> key, int level) {
			return new EnchantmentBinding(null, key, level);
		}

		/** 解析出 Holder（优先用内置 holder；其次用注册键从注册表反查并缓存）。 */
		Holder<Enchantment> resolve(Map<ResourceKey<Enchantment>, Holder.Reference<Enchantment>> cache) {
			if (holder != null) {
				return holder;
			}
			if (key == null) {
				return null;
			}
			return cache.computeIfAbsent(key, k ->
				ChasmEnchantments.INSTANCE.holderFor(k).orElse(null));
		}

		/** 附魔注册键（可能为 null）。 */
		public ResourceKey<Enchantment> key() {
			return key;
		}

		/** 附魔等级。 */
		public int level() {
			return level;
		}

		@Override
		public String toString() {
			return "EnchantmentBinding[" + (key != null ? key.location() : holder) + " -> " + level + "]";
		}
	}
}