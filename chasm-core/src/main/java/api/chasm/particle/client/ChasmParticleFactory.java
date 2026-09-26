package api.chasm.particle.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SpriteSet;

/**
 * **粒子外观工厂（客户端专用）**：把"注册过的粒子类型"变成看得见的粒子。
 *
 * <p>与真实 Tetra 的 {@code SparkleParticle.Provider}
 * （{@code _ref/.../blocks/geode/particle/SparkleParticle.java:29-40}
 * {@code implements ParticleProvider<SimpleParticleType>}，构造收一个 {@link SpriteSet}）
 * 是同一件事，只是抽成"收 SpriteSet + 坐标 + 初速度"的单一函数，
 * 免得每个模组都重复写一遍 {@code ParticleProvider} 的样板。</p>
 *
 * <p>返回值即原版 {@link Particle}：颜色/寿命/动画由实现方用原版粒子基类
 * （如 {@code SimpleAnimatedParticle}）自行决定，框架不插手。</p>
 *
 * <p>本接口引用客户端类，<b>只能在客户端加载</b>（实现类请打 {@link Environment}{@code (EnvType.CLIENT)}
 * 标记，并只在客户端分支里引用）。</p>
 */
@FunctionalInterface
@Environment(EnvType.CLIENT)
public interface ChasmParticleFactory {

	/**
	 * 造一个粒子实例。
	 *
	 * @param level  客户端世界（非 null）
	 * @param x      粒子 x（方块中心 + 面偏移，调用方算好）
	 * @param y      粒子 y
	 * @param z      粒子 z
	 * @param dx     初速度 x（静止粒子传 0）
	 * @param dy     初速度 y
	 * @param dz     初速度 z
	 * @param sprites 该粒子 id 对应的贴图集（{@code assets/<ns>/particles/<id>.json} 里的贴图列表）
	 * @return 粒子实例；实现方不应返回 null
	 */
	Particle create(ClientLevel level, double x, double y, double z,
		double dx, double dy, double dz, SpriteSet sprites);
}
