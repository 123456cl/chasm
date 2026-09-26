package api.chasm.data;

import com.mojang.serialization.Codec;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据组件注册审计 API（09-11 事故后的护栏）：
 * 正常运行不能有"晚注册"；且模组能查询当前是否处于 DataGen 环境。
 */
class ChasmDataLateRegistrationTest {

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void successfulRegistrationIsNotFlaggedAsLate() {
		ResourceLocation id = ResourceLocation.fromNamespaceAndPath("chasmtest", "audited_component");
		ChasmData.register("chasmtest", "audited_component", Codec.STRING);
		assertTrue(BuiltInRegistries.DATA_COMPONENT_TYPE.containsKey(id), "正常登记应成功");
		assertFalse(ChasmData.lateRegistrations().contains(id), "成功登记不应被标记为晚注册");
	}

	@Test
	void datagenFlagIsVisibleToMods() {
		assertFalse(ChasmData.isDatagenEnvironment(), "单测环境不是 DataGen");
	}
}
