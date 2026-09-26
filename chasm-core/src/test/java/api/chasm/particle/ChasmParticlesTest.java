package api.chasm.particle;

import com.mojang.serialization.Lifecycle;

import net.minecraft.SharedConstants;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **粒子注册 API 的回归测试**（框架新增能力）。
 *
 * <p>守四件事：</p>
 * <ol>
 *   <li>注册真的把类型写进注册表，且注册名就是传入的 {@code <modId>:<id>}
 *       —— 自建注册表跑同一段注册代码，不受测试 JVM 是否冻结影响；</li>
 *   <li>反查表 {@code BY_ID} <b>不被自建注册表污染</b>
 *       （{@code TetraStructureProcessors.registerInto} 当年就踩过这个坑）；</li>
 *   <li>{@link ChasmParticles#byId} 与 {@link ChasmParticleType#spawn} 对 null
 *       <b>不抛 NPE</b>；</li>
 *   <li>正式路径（{@code BuiltInRegistries}）同 id 幂等，返回同一句柄。</li>
 * </ol>
 */
class ChasmParticlesTest {

	/** 正式注册路径在本 JVM 是否可用（注册表可能已被别的测试冻结）。 */
	private static boolean liveReady;
	private static String liveNote = "(未尝试)";
	private static ChasmParticleType liveHandle;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		try {
			liveHandle = ChasmParticles.register("chasm_test", "particle_probe");
			liveReady = true;
		} catch (RuntimeException e) {
			liveReady = false;
			liveNote = e.getClass().getSimpleName() + ": " + e.getMessage();
			System.out.println("[ChasmParticlesTest] 活注册表不可用，跳过正式路径断言：" + liveNote);
		}
	}

	// ---------------------------------------------------------------- 1. 注册语义

	@Test
	void registerIntoWritesTheRequestedId() {
		MappedRegistry<ParticleType<?>> registry = new MappedRegistry<>(Registries.PARTICLE_TYPE, Lifecycle.stable());
		ChasmParticleType handle = ChasmParticles.registerInto(registry, "chasm_test", "probe_particle");

		assertEquals(1, registry.size(), "只写一个粒子类型");
		ResourceLocation expected = ResourceLocation.fromNamespaceAndPath("chasm_test", "probe_particle");
		assertEquals(expected, handle.id(), "句柄 id");
		assertEquals(expected, registry.getKey(handle.options()), "注册表里的键就是传入的 id");
		// 1.21.1 的 Registry 用 get(ResourceLocation)（实测 javap net.minecraft.core.Registry：没有 getValue）
		assertSame(handle.options(), registry.get(expected), "句柄 options 就是注册进表的那个实例");
		assertNotNull(handle.options(), "options 不为 null（Level#addParticle 的入参）");
		assertTrue(handle.options() instanceof SimpleParticleType, "真实 Tetra 用的是 SimpleParticleType");
	}

	@Test
	void selfBuiltRegistryDoesNotPolluteTheLookupTable() {
		MappedRegistry<ParticleType<?>> registry = new MappedRegistry<>(Registries.PARTICLE_TYPE, Lifecycle.stable());
		ChasmParticles.registerInto(registry, "chasm_test", "isolated_probe");
		assertNull(ChasmParticles.byId("chasm_test", "isolated_probe"),
			"自建注册表路径不得写反查表（否则活注册表的反查会指向别的注册表实例）");
	}

	/** 正式路径：注册名、幂等、反查三件事。 */
	@Test
	void liveRegistrationIsIdempotentAndLookupable() {
		Assumptions.assumeTrue(liveReady, () -> "BuiltInRegistries 已冻结：" + liveNote);
		ResourceLocation expected = ResourceLocation.fromNamespaceAndPath("chasm_test", "particle_probe");
		assertEquals(expected, BuiltInRegistries.PARTICLE_TYPE.getKey(liveHandle.options()), "活注册表里的键");
		assertSame(liveHandle, ChasmParticles.register("chasm_test", "particle_probe"), "同 id 幂等：返回同一句柄");
		assertSame(liveHandle, ChasmParticles.byId(expected), "按 id 反查");
		assertSame(liveHandle, ChasmParticles.byId("chasm_test", "particle_probe"), "按 modId/id 反查");
		assertTrue(ChasmParticles.registeredIds().contains(expected), "已注册清单包含它");
		assertEquals(ChasmParticles.registeredIds().size(), ChasmParticles.count(), "count() 与清单一致");
	}

	// ---------------------------------------------------------------- 2. 禁 NPE

	@Test
	void lookupIsNullSafe() {
		assertNull(ChasmParticles.byId((ResourceLocation) null), "byId(null) 必须返回 null 而不是抛 NPE");
		assertNull(ChasmParticles.byId(null, null), "byId(null, null) 必须返回 null");
		assertNull(ChasmParticles.byId("chasm_test", "no_such_particle"), "未注册 → null");
		assertNull(ChasmParticles.byId("chasm_test", "  "), "空白 id → null");
	}

	@Test
	void badIdsFailFastWithIllegalArgument() {
		assertThrows(IllegalArgumentException.class, () -> ChasmParticles.register(null, "x"));
		assertThrows(IllegalArgumentException.class, () -> ChasmParticles.register("  ", "x"));
		assertThrows(IllegalArgumentException.class, () -> ChasmParticles.register("chasm_test", null));
		assertThrows(IllegalArgumentException.class, () -> ChasmParticles.registerInto(null, "chasm_test", "x"));
	}

	/** 未注册/空世界时冒粒子必须是"安静地什么都不做"，不能崩主逻辑。 */
	@Test
	void spawnIsNullSafe() {
		ChasmParticleType handle = liveReady ? liveHandle
			: ChasmParticles.registerInto(new MappedRegistry<>(Registries.PARTICLE_TYPE, Lifecycle.stable()),
				"chasm_test", "spawn_probe");
		handle.spawn(null, 0, 0, 0, 0, 0, 0);
		handle.spawn(null, null, null);
		assertNotNull(handle.toString(), "toString 不抛");
	}
}
