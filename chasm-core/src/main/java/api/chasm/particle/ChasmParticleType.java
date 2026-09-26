package api.chasm.particle;

import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * **粒子类型句柄**（{@link ChasmParticles} 注册后的返回值）。
 *
 * <h2>为什么需要它</h2>
 * <p>框架此前没有粒子注册入口，"自定义粒子"只能靠 mixin 或直接写原版注册表 ——
 * 见 {@code docs/扩展点.md:141}「音效/粒子/结构生成还没有统一声明入口」。
 * 本类是那条缺口的<b>最小可用</b>闭环：注册（{@link ChasmParticles}）→ 服务端/客户端冒粒子
 * （{@link #spawn}）→ 客户端外观（{@code api.chasm.particle.client.ChasmParticleClient}）。</p>
 *
 * <h2>语义与真实模组对齐</h2>
 * <p>真实 Tetra 的晶洞粒子 {@code SparkleParticleType}（{@code _ref/Tetra-1.20/tetra-1.20/src/main/java/}
 * {@code se/mickelus/tetra/blocks/geode/particle/SparkleParticleType.java:7-11}）就是一个
 * {@code SimpleParticleType}（{@code identifier = "sparkle"}），方块在
 * {@code GeodeBlock.animateTick}（{@code GeodeBlock.java:44-60}）里
 * <b>只在客户端</b>、<b>偶发</b>地 {@code level.addParticle(SparkleParticleType.instance, x, y, z, 0, 0, 0)}
 * —— 本句柄的 {@link #options()} 就是喂给 {@code Level#addParticle} 的那个参数。</p>
 *
 * <p>不可变、线程安全（字段全 final）；{@link #spawn} 不持有状态，复杂度 O(1)。</p>
 */
public final class ChasmParticleType {

	private final ResourceLocation id;
	private final SimpleParticleType options;

	ChasmParticleType(ResourceLocation id, SimpleParticleType options) {
		this.id = id;
		this.options = options;
	}

	/** 注册名（{@code tetra:sparkle} 这种完整形态）。 */
	public ResourceLocation id() {
		return id;
	}

	/**
	 * 原版粒子选项：直接喂 {@code Level#addParticle(ParticleOptions, ...)} /
	 * {@code ServerLevel#sendParticles(...)}。
	 *
	 * <p>{@code SimpleParticleType extends ParticleType<SimpleParticleType>}（实测
	 * {@code javap net.minecraft.core.particles.SimpleParticleType}），所以它是自洽的
	 * {@code ParticleOptions}，网络同步也不需要额外 {@code StreamCodec}。</p>
	 */
	public SimpleParticleType options() {
		return options;
	}

	/**
	 * 在指定坐标冒一个粒子（速度给 0 即静止粒子，同真实 {@code GeodeBlock.java:57}）。
	 *
	 * <p><b>两端都可安全调用</b>：{@code Level#addParticle} 在服务端实现是空操作，
	 * 只有 {@code ClientLevel} 会真的生成粒子 —— 与真实模组在 {@code animateTick} 里的用法一致。</p>
	 *
	 * <p>{@code level == null} 直接返回（禁 NPE），不抛异常：粒子的唯一职责是"好看"，
	 * 不该因为它把主逻辑打断。</p>
	 */
	public void spawn(Level level, double x, double y, double z, double dx, double dy, double dz) {
		if (level == null) {
			return;
		}
		level.addParticle(options, x, y, z, dx, dy, dz);
	}

	/** {@link #spawn(Level, double, double, double, double, double, double)} 的坐标版（速度 0）。 */
	public void spawn(Level level, Vec3 pos, Vec3 velocity) {
		if (pos == null || velocity == null) {
			return;
		}
		spawn(level, pos.x, pos.y, pos.z, velocity.x, velocity.y, velocity.z);
	}

	@Override
	public String toString() {
		return "ChasmParticleType[" + id + "]";
	}
}
