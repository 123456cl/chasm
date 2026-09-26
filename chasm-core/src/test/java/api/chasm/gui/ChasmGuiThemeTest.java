package api.chasm.gui;

import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 主题：注册表回落 + 资源包换肤契约（约定贴图名）+ 按钮状态选择。 */
class ChasmGuiThemeTest {

	private static ResourceLocation chasm(String path) {
		return ResourceLocation.fromNamespaceAndPath("chasm", path);
	}

	@Test
	void defaultThemePinsTheResourcePackContract() {
		ChasmGuiTheme t = ChasmGuiThemes.resolve(null);
		assertSame(ChasmGuiTheme.DEFAULT, t);
		// 这些路径就是资源包要放的贴图（assets/<ns>/textures/gui/sprites/<path>.png）
		assertEquals(chasm("panel"), t.panel().sprite(), "面板贴图名是资源包换肤契约，不可随意改");
		assertEquals(chasm("slot"), t.slot().sprite());
		assertEquals(chasm("slot_frame"), t.slotFrame().sprite());
		assertEquals(chasm("button"), t.button().sprite());
		assertEquals(chasm("button_hover"), t.buttonHover().sprite());
		assertEquals(chasm("button_disabled"), t.buttonDisabled().sprite());
		assertEquals(chasm("slider_track"), t.sliderTrack().sprite());
		assertEquals(chasm("bar_fill"), t.barFill().sprite());
		assertEquals(chasm("slider_track"), t.sliderTrack().sprite());
		assertEquals(chasm("slider_handle"), t.sliderHandle().sprite());
		assertEquals(chasm("focus_ring"), t.focusRing().sprite());
	}

	@Test
	void defaultThemeKeepsFallbackColors() {
		ChasmGuiTheme t = ChasmGuiTheme.DEFAULT;
		// 每个元素都必须有兜底颜色：资源包没装 / 贴图缺失时也不能画成空白或紫黑
		for (ChasmGuiTheme.Element e : new ChasmGuiTheme.Element[] { t.panel(), t.slot(), t.slotFrame(),
			t.button(), t.buttonHover(), t.buttonDisabled(), t.buttonInner(), t.buttonHoverInner(),
			t.barTrack(), t.barFill(), t.sliderTrack(), t.sliderHandle(), t.focusRing() }) {
			assertTrue((e.color() >>> 24) == 0xFF, "兜底颜色必须是不透明 ARGB: " + e);
		}
		assertEquals(0xFF8B8B8B, t.panel().color(), "未装资源包时观感与旧版一致");
	}

	@Test
	void unknownThemeFallsBackToDefault() {
		assertSame(ChasmGuiTheme.DEFAULT, ChasmGuiThemes.resolve(chasm("does_not_exist")));
		assertEquals(null, ChasmGuiThemes.get(chasm("does_not_exist")));
	}

	@Test
	void customThemeCanBeRegisteredAndResolved() {
		ResourceLocation id = ResourceLocation.fromNamespaceAndPath("chasmtest", "dark");
		ChasmGuiTheme dark = ChasmGuiTheme.builder(id)
			.panel(ChasmGuiTheme.Element.of(0xFF101010))
			.button(ChasmGuiTheme.Element.of(0xFF303030), ChasmGuiTheme.Element.of(0xFF404040))
			.textColor(0xFFE0E0E0)
			.build();
		ChasmGuiThemes.register(dark);
		assertSame(dark, ChasmGuiThemes.resolve(id));
		assertEquals(0xFF101010, dark.panel().color());
		assertFalse(dark.panel().hasSprite(), "纯颜色主题也应可用（不强制贴图）");
		assertEquals(0xFFE0E0E0, dark.textColor());
		// 未覆盖的项继承默认主题
		assertEquals(ChasmGuiTheme.DEFAULT.slot().sprite(), dark.slot().sprite());
	}

	@Test
	void buttonElementFollowsState() {
		ChasmGuiTheme t = ChasmGuiTheme.DEFAULT;
		assertSame(t.button(), t.buttonFor(false, false));
		assertSame(t.buttonHover(), t.buttonFor(true, false));
		assertSame(t.buttonDisabled(), t.buttonFor(true, true), "禁用优先于悬停");
	}

	@Test
	void elementHelpersBehave() {
		ChasmGuiTheme.Element color = ChasmGuiTheme.Element.of(0xFF123456);
		assertFalse(color.hasSprite());
		assertEquals(0xFF123456, color.color());
		ChasmGuiTheme.Element sprite = ChasmGuiTheme.Element.of(chasm("panel"), 0xFF000000);
		assertTrue(sprite.hasSprite());
		assertNotNull(sprite.sprite());
		assertThrows(IllegalArgumentException.class, () -> ChasmGuiThemes.register(null));
	}
}
