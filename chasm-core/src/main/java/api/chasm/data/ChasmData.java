package api.chasm.data;

import api.chasm.context.ModContext;
import api.chasm.log.ChasmLogger;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import com.mojang.serialization.Codec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据组件注册与类型安全访问器（Chasm 数据空间）。
 *
 * <p>每个 {@code @DataComponent} 声明被扫描后：</p>
 * <ol>
 *   <li>自动生成 Codec（{@link ChasmCodec}）</li>
 *   <li>注册 {@code DataComponentType} 到 {@code modId} 命名空间（与注册表 id 隔离一致）</li>
 *   <li>按类注册到本地索引，暴露到 {@code Chasm.mod(modId).data(id)}（模组数据空间隔离）</li>
 * </ol>
 *
 * <p>玩法中使用（类型安全，无手写 NBT）：</p>
 * <pre>{@code
 * Chasm.data().get(stack, ManaData.class);        // 读取
 * Chasm.data().set(stack, ManaData.class, mana);  // 写入
 * }</pre>
 */
public final class ChasmData {

	/** 门面实例（配合 {@code Chasm.data()} 使用）。 */
	public static final ChasmData INSTANCE = new ChasmData();

	/**
	 * record 类 → (modId → DataComponentType)（类型安全索引，按 (modId, recordClass) 隔离）。
	 *
	 * <p><b>修复跨模组同名 record 覆盖</b>：同一 record 类可能被不同模组各自注册（如两个模组都声明
	 * {@code ManaData}，同名同类加载到同一 ClassLoader 即同一 {@code Class} 对象）。若只按 Class
	 * 建索引，后注册的模组会覆盖先注册者的组件绑定，导致 A 模组的物品栈被 B 模组的组件类型读取。
	 * 改为按 {@code modId} 二级隔离后，各模组的绑定互不覆盖；无 modId 的旧式访问仅在该类被
	 * <b>唯一</b>模组注册时可用，跨模组歧义时抛出引导错误并指示使用
	 * {@code componentType(modId, class)} 或 {@code Chasm.mod(modId).data()}。</p>
	 */
	private static final Map<Class<?>, Map<String, DataComponentType<?>>> BY_MOD_CLASS =
		new ConcurrentHashMap<>();

	/** 冻结后才尝试注册的组件 id（运行期出现即表示注册时序写错，见 {@link #registerInto}）。 */
	private static final java.util.Set<ResourceLocation> LATE_REGISTRATIONS =
		java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** 是否处于 DataGen 环境（Fabric datagen 标记，与 ChasmItemLoader 判断一致）。 */
	private static boolean inDatagen() {
		return System.getProperty("fabric-api.datagen") != null;
	}

	/**
	 * 幂等 + 注册表冻结防护地写入内置注册表。
	 *
	 * <p><b>修复 DataGen 静态初始化注册风险</b>：{@code ChasmData} 的标量静态字段（如
	 * {@code USE_TRAIT_ID}）在类加载即触发注册，DataGen 扫描模组静态字段时若内置注册表已冻结
	 * 会抛 {@code IllegalStateException} 崩溃。此处双重防护：①已存在同 id 直接复用（幂等，避免
	 * 重复注册/覆盖）；②真实注册被冻结注册表拒绝时仅记 WARN、保留内存侧组件声明，不中断初始化。</p>
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void registerInto(ResourceLocation registryId, DataComponentType componentType) {
		try {
			Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, registryId, (DataComponentType) componentType);
		} catch (IllegalStateException frozen) {
			LATE_REGISTRATIONS.add(registryId);
			if (inDatagen()) {
				// DataGen 期注册表本来就已冻结，这是预期行为
				ChasmLogger.warn("chasm", "数据组件 {} 因注册表已冻结（DataGen 期）跳过真实注册，仅保留内存声明",
					registryId);
			} else {
				// 游戏运行期冻结 = 组件注册写晚了：组件没进注册表，携带它的物品无法存档/联网
				ChasmLogger.error("chasm",
					"数据组件 {} 在注册表冻结后才注册（组件未真正登记）——携带它的物品会存档失败/联网断线。"
						+ "修复：在 onInitialize 里提前触碰该组件类（例如调用 XXX.ensureRegistered()）使其在扫描期完成注册。",
					registryId);
			}
		}
	}

	/** 冻结后才尝试注册的组件（正常运行应为空；非空即某个模组把组件注册写晚了）。 */
	public static java.util.Set<ResourceLocation> lateRegistrations() {
		return java.util.Set.copyOf(LATE_REGISTRATIONS);
	}

	/** 是否在 DataGen 环境（供模组判断"冻结是否属预期"）。 */
	public static boolean isDatagenEnvironment() {
		return inDatagen();
	}

	/**
	 * 内部组件：物品栈上存储「右键使用特质 id」（框架自动注册，namespace 为 chasm，不与用户组件冲突）。
	 *
	 * <p>采用标量 Codec 静态注册模式（非 Record 类索引），随 {@code ChasmData} 类加载即完成注册，
	 * 时序由类初始化顺序保证，无需延迟绑定。</p>
	 */
	public static final DataComponentType<ResourceLocation> USE_TRAIT_ID =
		register("chasm", "use_trait_id", ResourceLocation.CODEC);

	/**
	 * 内部组件：物品栈上存储「攻击实体特质 id」（框架自动注册，namespace 为 chasm，不与用户组件冲突）。
	 */
	public static final DataComponentType<ResourceLocation> ATTACK_TRAIT_ID =
		register("chasm", "attack_trait_id", ResourceLocation.CODEC);

	/**
	 * 内部组件：物品栈上存储「物品栏 tick 特质 id」（框架自动注册，namespace 为 chasm，不与用户组件冲突）。
	 */
	public static final DataComponentType<ResourceLocation> INVENTORY_TICK_TRAIT_ID =
		register("chasm", "inventory_tick_trait_id", ResourceLocation.CODEC);

	/**
	 * 内部组件：物品栈上存储「击杀奖励 id」（第十四步模板层，namespace 为 chasm，不与用户组件冲突）。
	 *
	 * <p>由 {@code ItemBuilder.killReward(...)} 写入；服务端 {@code AFTER_DEATH} 监听器
	 * 从击杀者主手物品栈读取该 id，O(1) 反查 {@code KillReward} 并执行击杀效果与掉落。</p>
	 */
	public static final DataComponentType<ResourceLocation> KILL_REWARD_ID =
		register("chasm", "kill_reward_id", ResourceLocation.CODEC);

	/**
	 * 内部组件：物品栈上存储「护甲套装 id」（第十四步模板层，namespace 为 chasm，不与用户组件冲突）。
	 *
	 * <p>由 {@code ItemBuilder.armor(...)} 写入；{@code ChasmArmorItem} 在物品栏 tick 时
	 * 读取该 id 反查 {@code ArmorSetSpec}，统计已穿戴件数并触发半套/全套效果。</p>
	 */
	public static final DataComponentType<ResourceLocation> ARMOR_SET_ID =
		register("chasm", "armor_set_id", ResourceLocation.CODEC);

	/**
	 * 内部组件：物品栈上的「事件处理器索引」—— {@code 事件种类 id → 处理器 id 列表}（按事件分桶）。
	 *
	 * <p>这是 {@link api.chasm.item.event.ChasmItemEvents} 的存储层：事件源（受击/挖方块/useOn/射箭/
	 * 盾挡/自定义）分发时只遍历本事件的桶，不扫描无关处理器；桶内顺序稳定 = 注册挂载顺序（可预期）。</p>
	 */
	public static final DataComponentType<Map<ResourceLocation, List<ResourceLocation>>> EVENT_HANDLERS =
		register("chasm", "event_handlers",
			Codec.unboundedMap(ResourceLocation.CODEC, ResourceLocation.CODEC.listOf()));

	/**
	 * 内部组件：物品栈的「贡献来源列表」（唯一真源，见 {@link api.chasm.contrib.ItemContributions}）。
	 *
	 * <p>词缀 / 宝石 / 附魔 / 套装 / 静态默认 都只往这里**登记来源 id**；真正的行为（事件处理器桶）
	 * 与属性（ATTRIBUTE_MODIFIERS）由 {@code ItemContributions.rebuild} 从来源**编译快照**出来。
	 * 组件可序列化 → 存读档/网络/复制物品自动保持一致，无需各系统各自偷存状态。</p>
	 */
	public static final DataComponentType<List<ResourceLocation>> CONTRIBUTORS =
		register("chasm", "contributors", ResourceLocation.CODEC.listOf());

	/** 内部组件：贡献是否为脏（变更即置位，安全点批量重编译）。 */
	public static final DataComponentType<Boolean> CONTRIB_DIRTY =
		register("chasm", "contrib_dirty", Codec.BOOL);

	/**
	 * 内部组件（**兼容保留，已不再写入**）：历史上记录过"物化过的属性命名空间"。
	 *
	 * <p>保留注册是为了让旧存档里已经写进去的该组件能被正常解析而不报错；新逻辑改用
	 * {@link #CONTRIB_MODIFIER_IDS} 精确记账。</p>
	 */
	public static final DataComponentType<List<String>> CONTRIB_NAMESPACES =
		register("chasm", "contrib_namespaces", Codec.STRING.listOf());

	/**
	 * 内部组件：上一次贡献编译**写过**的事件处理器 id（精确所有权标记）。
	 *
	 * <p>重编译时先按它摘除旧记录，再写入新的，于是：来源移除 → 其处理器消失；
	 * 而玩家/模组用 {@code ChasmItemEvents.attach} 手工挂的处理器（不在此表里）会被完整保留。
	 * 没有它会二选一：要么手工挂载被冲掉，要么被移除来源的处理器永久残留。</p>
	 */
	public static final DataComponentType<List<ResourceLocation>> CONTRIB_HANDLER_IDS =
		register("chasm", "contrib_handler_ids", ResourceLocation.CODEC.listOf());

	/**
	 * 内部组件：镶嵌插槽中的宝石栈（见 {@link api.chasm.contrib.socket.ChasmSockets}）。
	 *
	 * <p><b>为什么用 {@link ItemContainerContents} 而不是 {@code List<ItemStack>}</b>：
	 * {@code ItemStack} 没有覆写 {@code equals/hashCode}（javap 核实），装进组件后该组件的
	 * 相等性退化为**身份比较**——NBT 往返/网络同步后 {@code ItemStack.matches} 会把同一把武器
	 * 判定成"变了"（实测 old 写法 {@code socket_gems nbtRoundTrip matches=false}）。
	 * {@code ItemContainerContents} 是原版专为"组件内装物品"设计的类型，带正确的
	 * {@code equals/hashCode} 与官方序列化。</p>
	 */
	public static final DataComponentType<ItemContainerContents> SOCKET_GEMS =
		register("chasm", "socket_gems", ItemContainerContents.CODEC);

	/** 内部组件：宝石栈自带的贡献者 id（优先于物品登记表，便于数据包/掉落物携带）。 */
	public static final DataComponentType<ResourceLocation> GEM_CONTRIBUTOR =
		register("chasm", "gem_contributor", ResourceLocation.CODEC);

	/** 内部组件：插槽数量（0/缺席 = 不可镶嵌）。 */
	public static final DataComponentType<Integer> SOCKET_CAPACITY =
		register("chasm", "socket_capacity", Codec.INT);

	/**
	 * 内部组件：上一次贡献编译**写过**的属性修饰符 id（精确所有权标记）。
	 *
	 * <p>取代早先"按命名空间清空"的粗粒度做法：宝石贡献者 id 常与本模组物品同命名空间
	 * （如 mymod:gem/ruby 与 mymod:greed_sword），按命名空间清空会连物品**自己的**攻击力/攻速
	 * 修饰符一起删掉。按 id 记账后，只摘除管线自己写过的那些，零误伤。</p>
	 */
	public static final DataComponentType<List<ResourceLocation>> CONTRIB_MODIFIER_IDS =
		register("chasm", "contrib_modifier_ids", ResourceLocation.CODEC.listOf());

	/** 内部组件：贡献快照版本号（每次重编译 +1，供缓存/调试/联机校验）。 */
	public static final DataComponentType<Integer> CONTRIB_REV =
		register("chasm", "contrib_rev", Codec.INT);

	private ChasmData() {
	}

	/** 读取某事件桶上的处理器 id（无则空列表，只读语义）。 */
	public static List<ResourceLocation> eventHandlerIds(ItemStack stack, ResourceLocation eventId) {
		Map<ResourceLocation, List<ResourceLocation>> map = stack.get(EVENT_HANDLERS);
		if (map == null) {
			return List.of();
		}
		List<ResourceLocation> bucket = map.get(eventId);
		return bucket == null || bucket.isEmpty() ? List.of() : bucket;
	}

	/** 向某事件桶挂载一个处理器 id（幂等；保持顺序，不改动其他事件桶）。 */
	public static void addEventHandlerId(ItemStack stack, ResourceLocation eventId, ResourceLocation handlerId) {
		Map<ResourceLocation, List<ResourceLocation>> old = stack.get(EVENT_HANDLERS);
		Map<ResourceLocation, List<ResourceLocation>> copy = old == null
			? new LinkedHashMap<>() : new LinkedHashMap<>(old);
		List<ResourceLocation> bucket = new ArrayList<>(copy.getOrDefault(eventId, List.of()));
		if (bucket.contains(handlerId)) {
			return;
		}
		bucket.add(handlerId);
		copy.put(eventId, Collections.unmodifiableList(bucket));
		stack.set(EVENT_HANDLERS, Collections.unmodifiableMap(copy));
	}

	/** 从某事件桶移除一个处理器 id；空桶会一并删除。 */
	public static void removeEventHandlerId(ItemStack stack, ResourceLocation eventId, ResourceLocation handlerId) {
		Map<ResourceLocation, List<ResourceLocation>> old = stack.get(EVENT_HANDLERS);
		if (old == null) {
			return;
		}
		Map<ResourceLocation, List<ResourceLocation>> copy = new LinkedHashMap<>(old);
		List<ResourceLocation> bucket = new ArrayList<>(copy.getOrDefault(eventId, List.of()));
		if (!bucket.remove(handlerId)) {
			return;
		}
		if (bucket.isEmpty()) {
			copy.remove(eventId);
		} else {
			copy.put(eventId, Collections.unmodifiableList(bucket));
		}
		if (copy.isEmpty()) {
			stack.remove(EVENT_HANDLERS);
		} else {
			stack.set(EVENT_HANDLERS, Collections.unmodifiableMap(copy));
		}
	}

	/**
	 * 按 (modId, recordClass) 精确查询已注册的 DataComponentType（跨模组同名同类安全）。
	 *
	 * @param modId 所属模组命名空间
	 * @param type  record 类（此前通过 {@link #register(String, String, Class, Codec)} 注册过）
	 * @throws IllegalArgumentException 未注册或该模组未注册
	 */
	@SuppressWarnings("unchecked")
	public <T> DataComponentType<T> componentType(String modId, Class<T> type) {
		Map<String, DataComponentType<?>> byMod = BY_MOD_CLASS.get(type);
		if (byMod == null) {
			throw new IllegalArgumentException("未注册的数据组件类型: " + type.getName());
		}
		DataComponentType<?> ct = byMod.get(modId);
		if (ct == null) {
			throw new IllegalArgumentException(
				"数据组件类型 " + type.getName() + " 未在模组 " + modId + " 注册（已注册模组: " + byMod.keySet() + "）");
		}
		return (DataComponentType<T>) ct;
	}

	/**
	 * 按 record 类查询已注册的 DataComponentType。
	 *
	 * <p>仅当该类被<b>唯一</b>模组注册时可用；跨模组同名同类（同一 Class 被多模组注册）时抛出
	 * 歧义引导错误，请改用 {@link #componentType(String, Class)} 或
	 * {@code Chasm.mod(modId).data()}。</p>
	 *
	 * @param type record 类（此前通过 {@link #register(String, String, Class, Codec)} 注册过）
	 * @throws IllegalArgumentException 未注册或跨模组歧义
	 */
	@SuppressWarnings("unchecked")
	public <T> DataComponentType<T> componentType(Class<T> type) {
		Map<String, DataComponentType<?>> byMod = BY_MOD_CLASS.get(type);
		if (byMod == null) {
			throw new IllegalArgumentException("未注册的数据组件类型: " + type.getName());
		}
		if (byMod.size() > 1) {
			throw new IllegalArgumentException("数据组件类型 " + type.getName()
				+ " 被多个模组注册（" + byMod.keySet()
				+ "），需用 componentType(modId, class) 或 Chasm.mod(modId).data() 消歧");
		}
		return (DataComponentType<T>) byMod.values().iterator().next();
	}

	/**
	 * 注册一个数据组件（按 Codec，不依赖 record 类索引）。
	 *
	 * <p>适用于纯数据组件（如 int/String 等标量），无需声明 record 类型。
	 * 与 {@link #register(String, String, Class, Codec)} 的区别：本方法不建立
	 * 「类 → 组件类型」索引，因此不能通过 {@link ChasmData#get(ItemStack, Class)} 等
	 * 类安全访问器读取（如需类安全访问，请用那个重载）。</p>
	 *
	 * @param modId 所属模组命名空间
	 * @param name  数据组件注册名
	 * @param codec 编解码器
	 * @param <T>   数据值类型
	 * @return 注册好的 DataComponentType
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static <T> DataComponentType<T> register(String modId, String name, Codec<T> codec) {
		ResourceLocation registryId = ResourceLocation.fromNamespaceAndPath(modId, name);
		DataComponentType<T> existing = (DataComponentType<T>) BuiltInRegistries.DATA_COMPONENT_TYPE.get(registryId);
		if (existing != null) {
			// 幂等：同 id 已注册（DataGen/重复扫描）直接复用，避免重复注册覆盖
			return existing;
		}
		DataComponentType<T> componentType = DataComponentType.<T>builder()
			.persistent(codec)
			.networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.fromCodec(codec))
			.build();
		registerInto(registryId, componentType);
		ChasmLogger.call(modId, "ChasmData", "register", "注册数据组件 {}", registryId);
		return componentType;
	}

	/**
	 * 注册一个数据组件并按 record 类建立索引（由 {@code @DataComponent} 扫描器调用）。
	 *
	 * @param modId  所属模组命名空间
	 * @param id     数据组件注册名
	 * @param type   record 类型
	 * @param codec  编解码器
	 * @param <T>    record 类型
	 * @return 注册好的 DataComponentType
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static <T> DataComponentType<T> register(String modId, String id, Class<T> type, Codec<T> codec) {
		ResourceLocation registryId = ResourceLocation.fromNamespaceAndPath(modId, id);
		DataComponentType<T> componentType = (DataComponentType<T>) BuiltInRegistries.DATA_COMPONENT_TYPE.get(registryId);
		if (componentType == null) {
			componentType = DataComponentType.<T>builder()
				.persistent(codec)
				.networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.fromCodec(codec))
				.build();
			registerInto(registryId, componentType);
		}
		// 按 (modId, recordClass) 隔离索引：跨模组同名同类互不覆盖
		BY_MOD_CLASS.computeIfAbsent(type, k -> new ConcurrentHashMap<>()).put(modId, componentType);
		ChasmLogger.call(modId, "ChasmData", "register", "注册数据组件 {}", registryId);
		return componentType;
	}

	/**
	 * 读取物品上的数据组件（无则返回 null）。
	 *
	 * @param stack 物品栈
	 * @param modId record 类型所属模组（跨模组同名同类消歧）
	 * @param type  record 类型（@DataComponent 声明的类型）
	 */
	@SuppressWarnings("unchecked")
	public <T> T get(ItemStack stack, String modId, Class<T> type) {
		DataComponentType<T> ct = (DataComponentType<T>) componentType(modId, type);
		return stack.get(ct);
	}

	/**
	 * 读取物品上的数据组件（无则返回 null）。
	 *
	 * @param stack 物品栈
	 * @param type  record 类型（@DataComponent 声明的类型）
	 */
	@SuppressWarnings("unchecked")
	public <T> T get(ItemStack stack, Class<T> type) {
		DataComponentType<T> ct = (DataComponentType<T>) componentType(type);
		return stack.get(ct);
	}

	/**
	 * 读取物品上的数据组件（无则返回默认值）。
	 *
	 * @param stack 物品栈
	 * @param modId record 类型所属模组（跨模组同名同类消歧）
	 * @param type  record 类型
	 * @param fallback 默认值
	 */
	@SuppressWarnings("unchecked")
	public <T> T getOrDefault(ItemStack stack, String modId, Class<T> type, T fallback) {
		DataComponentType<T> ct = (DataComponentType<T>) componentType(modId, type);
		return stack.getOrDefault(ct, fallback);
	}

	/**
	 * 读取物品上的数据组件（无则返回默认值）。
	 *
	 * @param stack 物品栈
	 * @param type  record 类型
	 * @param fallback 默认值
	 */
	@SuppressWarnings("unchecked")
	public <T> T getOrDefault(ItemStack stack, Class<T> type, T fallback) {
		DataComponentType<T> ct = (DataComponentType<T>) componentType(type);
		return stack.getOrDefault(ct, fallback);
	}

	/**
	 * 写入物品上的数据组件。
	 *
	 * @param stack 物品栈
	 * @param modId record 类型所属模组（跨模组同名同类消歧）
	 * @param type  record 类型
	 * @param value 数据值
	 */
	@SuppressWarnings("unchecked")
	public <T> void set(ItemStack stack, String modId, Class<T> type, T value) {
		DataComponentType<T> ct = (DataComponentType<T>) componentType(modId, type);
		stack.set(ct, value);
	}

	/**
	 * 写入物品上的数据组件。
	 *
	 * @param stack 物品栈
	 * @param type  record 类型
	 * @param value 数据值
	 */
	@SuppressWarnings("unchecked")
	public <T> void set(ItemStack stack, Class<T> type, T value) {
		DataComponentType<T> ct = (DataComponentType<T>) componentType(type);
		stack.set(ct, value);
	}

	/**
	 * 数据空间暴露（供 ModContext 查询用）。
	 *
	 * @param modId 模组命名空间
	 */
	public static ModDataSpace space(String modId) {
		return new ModDataSpace(modId);
	}

	/** 按 modId 隔离的数据空间视图：跨模组同名同类 record 的类型安全访问入口。 */
	public static final class ModDataSpace {
		private final String modId;

		ModDataSpace(String modId) {
			this.modId = modId;
		}

		/** 所属模组。 */
		public String modId() {
			return modId;
		}

		/** 本模组注册的某 record 类的 DataComponentType。 */
		public <T> DataComponentType<T> componentType(Class<T> type) {
			return INSTANCE.componentType(modId, type);
		}

		/** 读取物品上本模组的 record 组件（无则返回 null）。 */
		public <T> T get(ItemStack stack, Class<T> type) {
			return INSTANCE.get(stack, modId, type);
		}

		/** 读取物品上本模组的 record 组件（无则返回默认值）。 */
		public <T> T getOrDefault(ItemStack stack, Class<T> type, T fallback) {
			return INSTANCE.getOrDefault(stack, modId, type, fallback);
		}

		/** 写入物品上本模组的 record 组件。 */
		public <T> void set(ItemStack stack, Class<T> type, T value) {
			INSTANCE.set(stack, modId, type, value);
		}
	}
}
