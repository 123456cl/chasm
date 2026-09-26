package api.chasm.item.event;

import api.chasm.data.ChasmData;
import api.chasm.log.ChasmLogger;
import api.chasm.safety.ChasmSafety;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物品事件总线（**开放**：事件种类与结果协议都可注册；处理器按 id 挂在栈上、按事件分桶）。
 *
 * <p>用法（第三方可完全自定义协议与事件）：</p>
 * <pre>
 * // 1) 注册一个新的事件种类，用你自己的协议
 * ItemEventKey&lt;MyResult&gt; ON_HIT = ChasmItemEvents.key(id("mymod","on_hit"), MY_PROTOCOL);
 *
 * // 2) 注册处理器（可复用内置，也可自定义）
 * ChasmItemEvents.handler(id("mymod","lifesteal"), ON_HIT, (ctx, stack, cur) -&gt; cur.plus(...));
 *
 * // 3) 把处理器 id 挂到某个物品栈（词缀/宝石系统在应用时调用）
 * ChasmItemEvents.attach(stack, ON_HIT, id("mymod","lifesteal"));
 *
 * // 4) 事件源（受击/挖方块/useOn/射箭/盾挡…）只需调用 dispatch
 * MyResult r = ChasmItemEvents.dispatch(stack, ON_HIT, payload);
 * </pre>
 *
 * <p>性能：处理器按事件分桶存在栈上（{@link ChasmData#EVENT_HANDLERS}），事件只遍历本桶；
 * 处理器注册表为 ConcurrentHashMap O(1)；异常逐处理器隔离。</p>
 */
public final class ChasmItemEvents {

	private static final Map<ResourceLocation, ItemEventKey<?>> KEYS = new ConcurrentHashMap<>();
	private static final Map<ResourceLocation, RegisteredHandler<?, ?>> HANDLERS = new ConcurrentHashMap<>();

	/**
	 * 兴趣计数（兴趣掩码）：事件种类 id → 已注册处理器数。
	 *
	 * <p>事件源在进入分发前先用 {@link #isInteresting} 早退——没有人监听的事件**零成本**
	 * （不收集物品栈、不构造负载、不做任何分配），这是"几百个模组同场"的关键。</p>
	 */
	private static final Map<ResourceLocation, Integer> INTEREST = new ConcurrentHashMap<>();

	private record RegisteredHandler<P, R>(ResourceLocation eventId, ItemEventHandler<P, R> impl) {
	}

	private ChasmItemEvents() {
	}

	/** 注册（幂等）一个事件种类。 */
	public static <R> ItemEventKey<R> key(ResourceLocation id, ResultProtocol<R> protocol) {
		Object existing = KEYS.get(id);
		if (existing != null) {
			return (ItemEventKey<R>) existing;
		}
		ItemEventKey<R> k = new ItemEventKey<>(id, protocol);
		KEYS.put(id, k);
		return k;
	}

	/** 注册一个处理器实现（按处理器 id 唯一）。 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static <P, R> void handler(ResourceLocation handlerId, ItemEventKey<R> event, ItemEventHandler<P, R> impl) {
		RegisteredHandler<?, ?> old = HANDLERS.put(handlerId, new RegisteredHandler(event.id(), impl));
		if (old == null) {
			INTEREST.merge(event.id(), 1, Integer::sum);
		} else if (!old.eventId().equals(event.id())) {
			INTEREST.merge(old.eventId(), -1, Integer::sum);
			INTEREST.merge(event.id(), 1, Integer::sum);
		}
	}

	/** 兴趣掩码查询：该事件是否有任何已注册处理器（无 → 事件源可直接早退）。 */
	public static boolean isInteresting(ItemEventKey<?> event) {
		return INTEREST.getOrDefault(event.id(), 0) > 0;
	}

	/** 全部事件种类的兴趣位图快照（调试/监控：谁在被监听）。 */
	public static Map<ResourceLocation, Integer> interestSnapshot() {
		return Map.copyOf(INTEREST);
	}

	/** 把一个处理器 id 挂到物品栈的某事件桶。 */
	public static void attach(ItemStack stack, ItemEventKey<?> event, ResourceLocation handlerId) {
		ChasmData.addEventHandlerId(stack, event.id(), handlerId);
	}

	/** 从物品栈移除某处理器（某事件桶）。 */
	public static void detach(ItemStack stack, ItemEventKey<?> event, ResourceLocation handlerId) {
		ChasmData.removeEventHandlerId(stack, event.id(), handlerId);
	}

	/** 该栈在某事件上挂着哪些处理器。 */
	public static List<ResourceLocation> attached(ItemStack stack, ItemEventKey<?> event) {
		return ChasmData.eventHandlerIds(stack, event.id());
	}

	/**
	 * 分发一次事件：按协议 initial → 逐处理器 merge；协议判定终止则提前结束。
	 * 单个处理器异常被隔离（记日志），不影响其余与游戏主流程。
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static <P, R> R dispatch(ItemStack stack, ItemEventKey<R> event, P payload) {
		ResultProtocol<R> protocol = event.protocol();
		R result = protocol.initial();
		if (!isInteresting(event)) {
			return result;
		}
		List<ResourceLocation> bucket = ChasmData.eventHandlerIds(stack, event.id());
		if (bucket.isEmpty()) {
			return result;
		}
		for (ResourceLocation handlerId : bucket) {
			RegisteredHandler<?, ?> rh = HANDLERS.get(handlerId);
			if (rh == null || !rh.eventId().equals(event.id())) {
				continue;
			}
			try {
				R incoming = ((ItemEventHandler<P, R>) rh.impl()).handle(payload, stack, result);
				result = protocol.merge(result, incoming);
			} catch (Throwable t) {
				ChasmLogger.error("chasm", "物品事件处理器 {} 异常（已隔离）: {}", handlerId, String.valueOf(t));
			}
			if (protocol.terminates(result)) {
				break;
			}
		}
		return result;
	}

	/**
	 * 按实体分发：从{@link ItemStackSources}为该事件注册的来源收集物品栈，逐栈分发并按协议合并
	 * （协议判定终止则提前结束）。
	 */
	public static <P, R> R dispatchEntity(net.minecraft.world.entity.LivingEntity entity,
										  ItemEventKey<R> event, P payload) {
		if (!isInteresting(event)) {
			return event.protocol().initial();
		}
		return dispatchAll(ItemStackSources.collect(event, entity), event, payload);
	}

	/**
	 * 分发到一组**已收集**的物品栈（复用上下文）：事件源可一次收集、多处分发，
	 * 避免同一帧内重复枚举装备/背包。
	 */
	public static <P, R> R dispatchAll(List<ItemStack> stacks, ItemEventKey<R> event, P payload) {
		ResultProtocol<R> protocol = event.protocol();
		R result = protocol.initial();
		if (stacks == null || stacks.isEmpty() || !isInteresting(event)) {
			return result;
		}
		for (ItemStack stack : stacks) {
			result = protocol.merge(result, dispatch(stack, event, payload));
			if (protocol.terminates(result)) {
				break;
			}
		}
		return result;
	}


	/** 已注册事件种类数（调试）。 */
	public static int keyCount() {
		return KEYS.size();
	}

	/** 已注册处理器数（调试）。 */
	public static int handlerCount() {
		return HANDLERS.size();
	}
}
