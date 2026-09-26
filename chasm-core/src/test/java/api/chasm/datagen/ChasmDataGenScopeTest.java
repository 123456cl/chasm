package api.chasm.datagen;

import api.chasm.context.ChasmContextRegistry;
import api.chasm.context.ModContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DataGen 生成作用域：只为本模组（含框架命名空间）生成产物。
 *
 * <p>背景（2026-09 实测的框架 bug）：同一个开发环境里加载多个使用 Chasm 的模组时，
 * 不限定作用域就会把别人的物品模型/配方/语言条目生成到自己的产物目录里——
 * 产物混入第三方命名内容，且互相覆盖。</p>
 */
class ChasmDataGenScopeTest {

	@AfterEach
	void resetScope() {
		// 空 = 不过滤（默认行为），避免影响其它测试
		ChasmDataGen.scopeTo();
	}

	@Test
	void scopedContextsOnlyContainScopedMods() {
		ChasmContextRegistry.get("scope_mod_a");
		ChasmContextRegistry.get("scope_mod_b");
		ChasmContextRegistry.get("chasm");
		ChasmDataGen.scopeTo("scope_mod_a", "chasm");

		Set<String> ids = ids(ChasmDataGen.scopedContexts());
		assertTrue(ids.contains("scope_mod_a"), "本模组必须在作用域内");
		assertTrue(ids.contains("chasm"), "框架命名空间必须在作用域内");
		assertFalse(ids.contains("scope_mod_b"), "别的模组的内容不得混进本模组的产物");
	}

	@Test
	void emptyScopeFallsBackToGlobalView() {
		ChasmContextRegistry.get("scope_mod_a");
		ChasmDataGen.scopeTo();

		assertTrue(ids(ChasmDataGen.scopedContexts()).contains("scope_mod_a"),
			"未限定作用域时保持旧的全局行为");
	}

	@Test
	void blankIdsAreTreatedAsUnscoped() {
		ChasmContextRegistry.get("scope_mod_a");
		ChasmDataGen.scopeTo(null, "  ");

		assertTrue(ids(ChasmDataGen.scopedContexts()).contains("scope_mod_a"),
			"全是空 id 时按不过滤处理，不能变成'什么都不生成'");
	}

	private static Set<String> ids(Iterable<ModContext> contexts) {
		Set<String> ids = new HashSet<>();
		for (ModContext ctx : contexts) {
			ids.add(ctx.id());
		}
		return ids;
	}
}
