package api.chasm.particle.client;

import java.util.LinkedHashMap;
import java.util.Map;

import api.chasm.log.ChasmLogger;
import api.chasm.particle.ChasmParticleType;
import api.chasm.particle.ChasmParticles;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry;

import net.minecraft.resources.ResourceLocation;

/**
 * **粒子外观注册的客户端入口点**（见 {@code chasm-core/src/main/resources/fabric.mod.json} 的
 * {@code client} 列表）。
 *
 * <h2>为什么框架要自带一个 client 入口点</h2>
 * <p>原版只提供"按 {@code ParticleType} 查 provider"的表，<b>没有公开的注册 API</b>
 * （1.21.1 的 provider 表在 {@code ParticleEngine} 里，只由原版自己的 reload 填充）。
 * Fabric 给的等价入口是 {@code ParticleFactoryRegistry}（实测
 * {@code javap net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry}：
 * {@code register(ParticleType<T>, ParticleProvider<T>)} 与
 * {@code register(ParticleType<T>, PendingParticleFactory<T>)}），
 * 它必须在客户端初始化期调用。所以这一层由框架统一持有：模组只管
 * {@link #registerFactory(ChasmParticleType, ChasmParticleFactory)} 声明外观，安装时机由框架负责。</p>
 *
 * <h2>时序（两种调用顺序都安全）</h2>
 * <ol>
 *   <li><b>模组 main 入口点</b>（在 client 入口点之前）登记 → 先进 {@link #PENDING}，
 *       等本类的 {@link #onInitializeClient()} 统一安装；</li>
 *   <li><b>安装之后</b>再登记 → 直接进 {@code ParticleFactoryRegistry}。
 *       Fabric 的实现自带"引擎未就绪先暂存"的延迟表（实测
 *       {@code ParticleFactoryRegistryImpl$DeferredParticleFactoryRegistry} 与
 *       {@code $DirectParticleFactoryRegistry}），两条路都不会抛。</li>
 * </ol>
 *
 * <p>未注册的粒子类型（拼错 id）不会 NPE 也不会崩客户端：打一条 WARN 后跳过。</p>
 */
@Environment(EnvType.CLIENT)
public final class ChasmParticleClient implements ClientModInitializer {

	/** 入口点跑完之前登记的工厂（id → 工厂），安装后清空。 */
	private static final Map<ResourceLocation, ChasmParticleFactory> PENDING = new LinkedHashMap<>();

	/** 入口点是否已跑过（跑过之后登记即装即用）。 */
	private static boolean installed;

	/**
	 * 登记一个粒子类型的外观工厂。
	 *
	 * <p>可在模组 main 入口点（客户端一侧）或 client 入口点调用；见类注释的时序说明。</p>
	 *
	 * @throws IllegalArgumentException 参数为 null 时（坏参数当场炸，不留 NPE）
	 */
	public static void registerFactory(ChasmParticleType type, ChasmParticleFactory factory) {
		if (type == null) {
			throw new IllegalArgumentException("粒子类型不可为 null（先调 ChasmParticles.register）");
		}
		if (factory == null) {
			throw new IllegalArgumentException("粒子外观工厂不可为 null：" + type.id());
		}
		if (installed) {
			install(type, factory);
			return;
		}
		PENDING.put(type.id(), factory);
	}

	@Override
	public void onInitializeClient() {
		installed = true;
		int installedCount = 0;
		for (Map.Entry<ResourceLocation, ChasmParticleFactory> entry : PENDING.entrySet()) {
			ChasmParticleType type = ChasmParticles.byId(entry.getKey());
			if (type == null) {
				ChasmLogger.warn("chasm", "粒子外观工厂 {} 找不到已注册的粒子类型，已跳过（不崩客户端）", entry.getKey());
				continue;
			}
			install(type, entry.getValue());
			installedCount++;
		}
		PENDING.clear();
		if (installedCount > 0) {
			ChasmLogger.info("chasm", "CALL ChasmParticleClient: 安装粒子外观工厂 {} 个", installedCount);
		}
	}

	/** 真正落到 Fabric 的注册表上：id → 贴图集 → 粒子。 */
	private static void install(ChasmParticleType type, ChasmParticleFactory factory) {
		ParticleFactoryRegistry registry = ParticleFactoryRegistry.getInstance();
		registry.register(type.options(), sprites -> (particleType, level, x, y, z, dx, dy, dz) ->
			factory.create(level, x, y, z, dx, dy, dz, sprites));
	}

	// 入口点由 Fabric 反射实例化，保留隐式 public 无参构造（不写 private 构造，避免反射被拒）。
}
