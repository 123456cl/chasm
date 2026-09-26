package api.chasm.player;

import api.chasm.log.ChasmLogger;

import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * 玩家持久化变量（Chasm 通用「每玩家变量」API）。
 *
 * <p>基于 Fabric {@code DataAttachment}（fabric-data-attachment-api）实现：
 * 类型安全、随世界存档持久化、玩家死亡重生后保留。支持任意类型（int/double/string/record），
 * 并可选声明 {@code min}/{@code max} 自动钳制。</p>
 *
 * <p>关键约定：所有读写必须是 {@link ServerPlayer}（服务端）。当传入的是客户端
 * {@code Player}（{@code LocalPlayer}）时，读返回默认值、写被忽略并打 WARN 日志，
 * 避免客户端误操作与数据不同步。</p>
 *
 * <p><b>多人同步</b>：服务端每次 {@code set/modify} 值发生变化后，会把最新值经 S2C 数据包
 * 推送给该玩家；玩家加入时全量同步所有已注册变量。客户端经
 * {@link #clientSafeGet(Player)} 读取只读缓存，HUD/UI 即可展示实时值。</p>
 *
 * <p>声明（模组静态字段，onInitialize 前后均可）：</p>
 * <pre>{@code
 * public static final PlayerVar<Integer> MANA =
 *     Chasm.playerVar("mymod", "mana", ChasmCodecs.INT)
 *         .defaultValue(50).min(0).max(100).build();
 * }</pre>
 *
 * <p>运行（服务端）：</p>
 * <pre>{@code
 * int mana = MANA.get(player);
 * MANA.set(player, mana - 5);
 * MANA.modify(player, v -> v + 10);
 * }</pre>
 *
 * @param <T> 变量类型（建议不可变类型，如数值或 record）
 */
public final class PlayerVar<T extends Comparable<T>> {

	/**
	 * 全局注册表：变量 id（modId:name）→ PlayerVar。
	 *
	 * <p><b>修复 PlayerVar 多人同步缺失</b>：服务端变更只同步"该变量"给"该玩家"；
	 * 玩家加入时经 {@link PlayerVarChannel#syncAll} 全量推送所有已注册变量，客户端经
	 * {@link #receiveSync(CompoundTag)} 解码写入只读缓存，HUD/UI 即可展示实时值。</p>
	 */
	private static final Map<ResourceLocation, PlayerVar<?>> REGISTRY = new ConcurrentHashMap<>();

	/** 按 id 查询全局已注册的玩家变量（S2C 数据包反查、跨侧访问用）。 */
	public static Optional<PlayerVar<?>> byId(ResourceLocation id) {
		return Optional.ofNullable(REGISTRY.get(id));
	}

	/** 全部已注册的玩家变量（只读视图，供玩家加入时全量同步）。 */
	public static Collection<PlayerVar<?>> all() {
		return Collections.unmodifiableCollection(REGISTRY.values());
	}

	private final AttachmentType<T> attachment;
	private final T defaultValue;
	private final T min;
	private final T max;
	private final String id;
	/** 变量唯一 id 的 ResourceLocation 形式（modId:name，兼作 S2C 同步标识与全局注册表键）。 */
	private final ResourceLocation location;
	/** 编解码器（持久化 + S2C 网络同步共用）。 */
	private final Codec<T> codec;

	/**
	 * 客户端只读缓存。服务端每次 get/set/modify 后同步刷新；
	 * 客户端（LocalPlayer）经 {@link #clientSafeGet(Player)} 读取时直接命中本缓存，
	 * 避免反复打服务端数据附件或产生 WARN 日志。
	 * <p>{@code transient} 防序列化、{@code volatile} 保证跨线程（渲染线程/网络线程）可见。
	 */
	private transient volatile T clientCache;

	private PlayerVar(String modId, String name, Codec<T> codec, T defaultValue, T min, T max) {
		this.defaultValue = defaultValue;
		this.min = min;
		this.max = max;
		this.id = modId + ":" + name;
		this.location = ResourceLocation.fromNamespaceAndPath(modId, name);
		this.codec = codec;
		// create(...) 注册一个可持久化、死亡保留、自动初始化的附件类型。
		this.attachment = AttachmentRegistry.create(location, b -> {
			b.persistent(codec);
			b.copyOnDeath();
			b.initializer(() -> defaultValue);
		});
		REGISTRY.put(location, this);
	}

	/** 创建 Builder。 */
	public static <T extends Comparable<T>> Builder<T> builder(String modId, String name, Codec<T> codec) {
		return new Builder<>(modId, name, codec);
	}

	/** 服务端玩家；客户端/非玩家返回 null。 */
	private ServerPlayer server(Player player) {
		return player instanceof ServerPlayer sp ? sp : null;
	}

	/** 把值钳制到 [min, max]（未声明 min/max 时原样返回）。 */
	private T clamp(T value) {
		if (value == null) {
			return defaultValue;
		}
		if (min != null && value.compareTo(min) < 0) {
			return min;
		}
		if (max != null && value.compareTo(max) > 0) {
			return max;
		}
		return value;
	}

	private void warnClientOnly(String op) {
		ChasmLogger.warn("chasm", "PlayerVar {} 在客户端{}（仅服务端生效）", id, op);
	}

	/** 读取当前值（服务端；客户端返回默认值并告警）。 */
	public T get(Player player) {
		ServerPlayer sp = server(player);
		if (sp == null) {
			warnClientOnly("读取");
			return defaultValue;
		}
		T value = clamp(attachedOf(sp).getAttachedOrCreate(attachment, () -> defaultValue));
		clientCache = value; // 服务端读取后同步刷新客户端只读缓存
		return value;
	}

	/** 读取当前值，若尚未初始化则写入默认值并返回（不告警，仅服务端）。 */
	public T getOrSet(Player player) {
		ServerPlayer sp = server(player);
		if (sp == null) {
			warnClientOnly("读取");
			return defaultValue;
		}
		return clamp(attachedOf(sp).getAttachedOrCreate(attachment, () -> defaultValue));
	}

	/** 写入新值（自动钳制到 [min,max]；客户端调用被忽略并告警）。 */
	public void set(Player player, T value) {
		ServerPlayer sp = server(player);
		if (sp == null) {
			warnClientOnly("写入");
			return;
		}
		T clamped = clamp(value);
		T current = attachedOf(sp).getAttachedOrCreate(attachment, () -> defaultValue);
		clientCache = clamped; // 服务端写入后同步刷新客户端只读缓存
		if (Objects.equals(current, clamped)) {
			return; // 值未变化：不落附件、不发同步包，避免无谓的 NBT 写与网络包
		}
		attachedOf(sp).setAttached(attachment, clamped);
		syncTo(sp);
	}

	/** 以函数修改当前值（读→应用→写，自动钳制）。 */
	public void modify(Player player, UnaryOperator<T> modifier) {
		ServerPlayer sp = server(player);
		if (sp == null) {
			warnClientOnly("修改");
			return;
		}
		T current = attachedOf(sp).getAttachedOrCreate(attachment, () -> defaultValue);
		T clamped = clamp(modifier.apply(current));
		attachedOf(sp).setAttached(attachment, clamped);
		clientCache = clamped; // 服务端修改后同步刷新客户端只读缓存
		if (!Objects.equals(current, clamped)) {
			syncTo(sp); // 值变化才推送 S2C，避免无谓的网络包
		}
	}

	/** 是否已初始化（不创建默认值）。 */
	public boolean has(Player player) {
		ServerPlayer sp = server(player);
		return sp != null && attachedOf(sp).hasAttached(attachment);
	}

	/**
	 * 客户端安全读取。
	 *
	 * <p>服务端（{@code ServerPlayer}）：等价 {@link #get(Player)}，读服务端数据附件并把最新值
	 * 同步进 {@link #clientCache}（服务端侧缓存供本进程内只读场景使用；同步给客户端由
	 * {@code set/modify} 与玩家加入全量同步链路承担）。客户端（{@code LocalPlayer}）：直接读本地
	 * 只读缓存 {@link #clientCache}（无缓存时回退 {@link #defaultValue}），既不打 WARN 也不触发
	 * 数据附件访问。UI 展示、HUD 渲染建议走本方法，避免每次渲染都读服务端。</p>
	 *
	 * @param player 玩家
	 * @return 值（客户端为只读缓存命中的值）
	 */
	public T clientSafeGet(Player player) {
		if (isServerOnly(player)) {
			return get(player);
		}
		// 客户端：直接命中只读缓存，不访问服务端数据附件、不打 WARN
		T cached = clientCache;
		return Objects.isNull(cached) ? defaultValue : cached;
	}

	/**
	 * 手动把值写入客户端只读缓存（只从数据包同步链路调用）。
	 *
	 * <p>仅当明确已通过数据包把本变量的最新值同步到客户端时才调用，用于客户端侧的只读缓存预置
	 * 与刷新（例如承载 UDP 压缩通信流、每秒低频同步的场景）。本方法允许在客户端线程执行，只更新
	 * {@link #clientCache}，不触碰服务端数据附件。</p>
	 *
	 * @param value 从服务端同步得到的值
	 */
	public void cacheReadOnly(T value) {
		this.clientCache = value;
	}

	/** 玩家是否为服务端玩家（{@code ServerPlayer}）。客户端 {@code LocalPlayer} 返回 false。 */
	public boolean isServerOnly(Player player) {
		return player instanceof ServerPlayer;
	}

	/**
	 * 客户端侧是否已有只读缓存副本。
	 *
	 * <p>即 {@link #clientCache} 是否已被设置（经服务端 get/set/modify 刷新，或
	 * {@link #cacheReadOnly(Object)} 预置）。客户端 UI 可用它判断能否安全地只读展示，而无需
	 * 回退默认值。</p>
	 *
	 * @return 已有缓存副本则 true
	 */
	public boolean isClientSafe() {
		return !Objects.isNull(clientCache);
	}

	/** 变量 id（modId:name，日志用）。 */
	public String id() {
		return id;
	}

	/** 变量 id 的 ResourceLocation 形式（modId:name，S2C 同步标识）。 */
	public ResourceLocation location() {
		return location;
	}

	/** 编解码器（持久化 + 网络同步共用）。 */
	public Codec<T> codec() {
		return codec;
	}

	/**
	 * **附件接口的显式视图**（{@code Entity} 由 Fabric API 的接口注入 mixin 实现
	 * {@link AttachmentTarget}，注入不保证出现在编译期类路径上）。
	 *
	 * <p>2026-09-22 修：loom 的项目级派生 minecraft jar 在一次中断的重建里丢掉了 Fabric API 的接口注入，
	 * 于是直接写 {@code attachedOf(sp).getAttachedOrCreate(...)} 会"找不到符号"（源码自 09-05 未变、09-19 编译过）。
	 * 按 Fabric 官方推荐改成显式转型：编译期只依赖 {@link AttachmentTarget} 这个真实存在的接口，
	 * 运行期对象确实实现了它（mixin），转型是零成本的 —— 从此不再依赖注入是否进了编译类路径。</p>
	 */
	private static AttachmentTarget attachedOf(ServerPlayer sp) {
		return (AttachmentTarget) sp;
	}

	/** 服务端读取该玩家的当前值（自动初始化默认值；供全量同步/跨侧读取，不触发客户端告警）。 */
	T serverValue(ServerPlayer sp) {
		return clamp(attachedOf(sp).getAttachedOrCreate(attachment, () -> defaultValue));
	}

	/**
	 * 服务端变更后把最新值编码为 S2C 数据包发给该玩家（仅服务端线程调用）。
	 *
	 * <p>值从附件重读（刚刚 {@code setAttached} 写入的最新值），避免调用方与发送逻辑之间
	 * 的钳制不一致。</p>
	 *
	 * @param sp 服务端玩家
	 */
	void syncTo(ServerPlayer sp) {
		PlayerVarChannel.send(sp, location, codec, serverValue(sp));
	}

	/**
	 * 客户端接收 S2C 同步包：解码最新值并写入只读缓存（仅数据包接收器调用）。
	 *
	 * <p>从 NBT 包装（{@code {"v": <编码值>}}）中解码，失败时回退默认值，保证渲染线程
	 * 读取的 {@link #clientCache} 始终有效。本方法可安全地在网络/客户端线程执行。</p>
	 *
	 * @param payload 同步包携带的 NBT 值（{@code {"v": ...}} 包装）
	 */
	public void receiveSync(CompoundTag payload) {
		if (payload == null) {
			return;
		}
		Tag raw = payload.get("v");
		if (raw == null) {
			return;
		}
		T value = codec.parse(NbtOps.INSTANCE, raw).result().orElse(defaultValue);
		cacheReadOnly(value);
	}

	/** 默认值（Builder 声明）。 */
	public T defaultValue() {
		return defaultValue;
	}

	/** 下限（可能为 null）。 */
	public T min() {
		return min;
	}

	/** 上限（可能为 null）。 */
	public T max() {
		return max;
	}

	/** {@link #builder(String, String, Codec)} 的链式构建器。 */
	public static final class Builder<T extends Comparable<T>> {

		private final String modId;
		private final String name;
		private final Codec<T> codec;
		private T defaultValue;
		private T min;
		private T max;

		private Builder(String modId, String name, Codec<T> codec) {
			this.modId = modId;
			this.name = name;
			this.codec = codec;
		}

		/** 开局/默认值（未初始化时的初始值）。 */
		public Builder<T> defaultValue(T value) {
			this.defaultValue = value;
			return this;
		}

		/** 下限（写入时自动钳制）。 */
		public Builder<T> min(T value) {
			this.min = value;
			return this;
		}

		/** 上限（写入时自动钳制）。 */
		public Builder<T> max(T value) {
			this.max = value;
			return this;
		}

		/** 构建并注册 PlayerVar。 */
		public PlayerVar<T> build() {
			ChasmLogger.info(modId, "注册玩家持久化变量 {}", modId + ":" + name);
			return new PlayerVar<>(modId, name, codec, defaultValue, min, max);
		}
	}
}