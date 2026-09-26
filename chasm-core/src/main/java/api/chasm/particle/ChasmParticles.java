package api.chasm.particle;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import api.chasm.log.ChasmLogger;

import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;

/**
 * **粒子注册入口（chasm-core 新增，零 mixin）**。
 *
 * <h2>它补的是哪一个缺口</h2>
 * <p>{@code docs/扩展点.md:141}：「音效/粒子/结构生成还没有统一声明入口」。
 * 真实 Tetra 的粒子是 {@code DeferredRegister<ParticleType<?>>}
 * （{@code _ref/.../TetraRegistries.java:114}）+ 客户端外观注册
 * （{@code ClientSetup.java:93-94} {@code registerSpriteSet(...)}）；1.21.1 Fabric 下等价物是
 * <b>静态注册表直注册</b>（{@code BuiltInRegistries.PARTICLE_TYPE}）+ Fabric 的
 * {@code ParticleFactoryRegistry}（客户端）。本类负责前半段，后者见
 * {@code api.chasm.particle.client.ChasmParticleClient}。</p>
 *
 * <h2>用法（三步，全部零 mixin）</h2>
 * <pre>{@code
 * // 1) mod 初始化（注册表冻结前）：注册粒子类型
 * ChasmParticleType sparkle = ChasmParticles.register("tetra", "sparkle");
 * // 2) 客户端初始化（client 入口点）：登记外观（SpriteSet -> 粒子）
 * ChasmParticleClient.registerFactory(sparkle, (level, x, y, z, dx, dy, dz, sprites) -> new MyParticle(...));
 * // 3) 任意一端冒粒子（服务端是空操作，只有客户端真的生成）
 * sparkle.spawn(level, x, y, z, 0, 0, 0);
 * }</pre>
 * <p>资源侧只需 {@code assets/<namespace>/particles/<id>.json}（贴图列表），
 * 与真实数据包同构（如 {@code tetra-port/src/main/resources/assets/tetra/particles/sparkle.json}）。</p>
 *
 * <h2>真实语义细节（照抄，不自由发挥）</h2>
 * <ul>
 *   <li>真实 {@code TetraRegistries.java:354}
 *       {@code particles.register(SparkleParticleType.identifier, () -> new SimpleParticleType(false));}
 *       —— 即 {@code overrideLimiter = false}，本类沿用同一取值。</li>
 *   <li>1.21.1 的 {@code SimpleParticleType(boolean)} 是 <b>protected</b>
 *       （实测 {@code javap net.minecraft.core.particles.SimpleParticleType}：
 *       {@code protected net.minecraft.core.particles.SimpleParticleType(boolean);}），
 *       原版 {@code ParticleTypes} 靠"同包"访问；框架无 mixin/AT，所以用同包私有子类
 *       {@link SimpleImpl} 构造 —— 语义与真实完全相同，只是换了个构造入口。</li>
 * </ul>
 *
 * <h2>复杂度与线程安全</h2>
 * <p>{@link #register} 是 O(1)（{@link LinkedHashMap} 查表 + 一次注册表写入）；
 * {@link #byId} 是 O(1) 且对 {@code null} 安全。索引表只在注册期写入（模组初始化线程），
 * 启动完成后只读。</p>
 */
public final class ChasmParticles {

	/** 注册名 → 句柄（O(1) 反查；同 id 幂等）。 */
	private static final Map<ResourceLocation, ChasmParticleType> BY_ID = new LinkedHashMap<>();

	private ChasmParticles() {
	}

	// ------------------------------------------------------------------ 注册

	/**
	 * 把一个 {@code <modId>:<id>} 粒子类型注册进 {@code BuiltInRegistries.PARTICLE_TYPE}。
	 *
	 * <p><b>必须在注册表冻结前调用</b>（模组的 {@code onInitialize()} 里）：1.21.1 的
	 * {@code BuiltInRegistries.bootStrap()} 会 {@code createContents()} + {@code freeze()}，
	 * 冻结后再注册抛 {@code IllegalStateException}。</p>
	 *
	 * <p>幂等：同一个 id 重复调用返回<b>同一个</b>句柄，不会二次注册（注册表写入不可重复）。</p>
	 *
	 * @param modId 命名空间（非空、非空白）
	 * @param id    路径（非空、非空白）
	 * @return 粒子句柄（永不为 null）
	 * @throws IllegalArgumentException 参数为空/空白时（不把坏参数漏成 NPE）
	 */
	public static ChasmParticleType register(String modId, String id) {
		ResourceLocation key = key(modId, id);
		ChasmParticleType existing = BY_ID.get(key);
		if (existing != null) {
			return existing;
		}
		ChasmParticleType handle = registerInto(BuiltInRegistries.PARTICLE_TYPE, key);
		BY_ID.put(key, handle);
		ChasmLogger.info("chasm", "CALL ChasmParticles.register: 注册粒子类型 {}", key);
		return handle;
	}

	/**
	 * 把粒子类型注册进<b>给定</b>注册表并返回句柄，<b>不碰</b> {@link #BY_ID}。
	 *
	 * <p>抽出来的唯一原因是**可验证**：单测拿自建 {@code MappedRegistry} 跑同一段注册代码，
	 * 就能断言 id/类型被 1.21.1 的 {@code Registry.register} 接受，而不受"测试 JVM 里注册表是否已冻结"影响。
	 * 索引表只在正式路径（{@link #register}）里填充 —— 否则单测会把反查表指向别的注册表实例
	 * （同一个坑见 {@code com.example.chasm.tetra.TetraStructureProcessors#registerInto} 的注释）。</p>
	 */
	public static ChasmParticleType registerInto(Registry<ParticleType<?>> registry, String modId, String id) {
		if (registry == null) {
			throw new IllegalArgumentException("粒子注册表不可为 null");
		}
		return registerInto(registry, key(modId, id));
	}

	private static ChasmParticleType registerInto(Registry<ParticleType<?>> registry, ResourceLocation key) {
		// 真实取值：overrideLimiter = false（TetraRegistries.java:354）
		SimpleParticleType options = Registry.register(registry, key, new SimpleImpl(false));
		return new ChasmParticleType(key, options);
	}

	// ------------------------------------------------------------------ 反查

	/** 按注册名取句柄；未知（含 null）→ null，不抛异常。 */
	@Nullable
	public static ChasmParticleType byId(@Nullable ResourceLocation id) {
		return id == null ? null : BY_ID.get(id);
	}

	/** 按 {@code modId:id} 取句柄；参数为空或未注册 → null。 */
	@Nullable
	public static ChasmParticleType byId(@Nullable String modId, @Nullable String id) {
		if (modId == null || id == null || modId.isBlank() || id.isBlank()) {
			return null;
		}
		return BY_ID.get(ResourceLocation.fromNamespaceAndPath(modId, id));
	}

	/** 已注册的粒子 id（只读快照，注册顺序）。 */
	public static List<ResourceLocation> registeredIds() {
		return List.copyOf(BY_ID.keySet());
	}

	/** 已注册数量（O(1)）。 */
	public static int count() {
		return BY_ID.size();
	}

	/** 已注册句柄（只读快照，注册顺序）。 */
	public static List<ChasmParticleType> registered() {
		return List.copyOf(BY_ID.values());
	}

	// ------------------------------------------------------------------ 工具

	/** 校验并拼注册名；坏参数在这里就炸，不留给下游 NPE。 */
	private static ResourceLocation key(String modId, String id) {
		if (modId == null || modId.isBlank()) {
			throw new IllegalArgumentException("粒子命名空间（modId）不可为空");
		}
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("粒子 id 不可为空");
		}
		return ResourceLocation.fromNamespaceAndPath(modId, id);
	}

	/**
	 * {@code SimpleParticleType} 的唯一可用构造入口（父类构造器是 protected，见类注释）。
	 *
	 * <p>私有静态子类，不对外暴露类型：调用方拿到的静态类型永远是原版 {@link SimpleParticleType}，
	 * 所以不存在"框架私有类型泄进公开 API"的问题。</p>
	 */
	private static final class SimpleImpl extends SimpleParticleType {

		SimpleImpl(boolean overrideLimiter) {
			super(overrideLimiter);
		}
	}
}
