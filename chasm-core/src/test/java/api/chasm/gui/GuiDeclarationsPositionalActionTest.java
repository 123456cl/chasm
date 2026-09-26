package api.chasm.gui;

import api.chasm.gui.decl.GuiDeclarations;
import api.chasm.gui.decl.GuiNode;
import api.chasm.gui.decl.GuiState;

import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **带坐标的动作通道**（core 新能力）：动作处理器除了 {@code value} 还能拿到点击点的
 * **面板像素** x/y。
 *
 * <p>真机出处：滑块的段位就是按鼠标 x 算的 ——
 * {@code GuiSliderSegmented.calculateSegment(refX, mouseX):81-83} 的
 * {@code Math.round((valueSteps - 1) * clamp((mouseX - refX - x) / width, 0, 1))}。
 * 坐标由客户端命中时记录（{@code DeclClient.hitTest}），派发时交给处理器。</p>
 *
 * <p>本测试同时钉住"向后兼容"：没有注册带坐标版本的动作，走的还是原来那一句
 * {@code handler.handle(player, value, state)}，多出来的坐标参数不会影响它。</p>
 */
class GuiDeclarationsPositionalActionTest {

	private static final ResourceLocation ID =
		ResourceLocation.fromNamespaceAndPath("chasmtest", "positional_actions");
	private static final ResourceLocation NEVER_REGISTERED =
		ResourceLocation.fromNamespaceAndPath("chasmtest", "positional_never_registered");

	/** 动作的 value（节点自带的那个；服务端派发用的就是它，不是客户端发的序号）。 */
	private static final int SLIDE_VALUE = 1000 + 3;

	@BeforeEach
	void resetRegistry() {
		GuiDeclarations.register(ID, GuiDeclarationsPositionalActionTest::build,
			Map.of("plain", (player, value, state) -> {
			}));
		GuiDeclarations.clearClick(ID);
	}

	/** 三个节点：0 = 普通动作，1 = 滑条（带坐标），2 = 没有动作。 */
	private static List<GuiNode> build(GuiState state) {
		return List.of(
			GuiNode.of("plain", 0, 0, 10, 10).action("plain", 7),
			GuiNode.of("slide", 10, 10, 200, 12).action("slide", SLIDE_VALUE),
			GuiNode.of("none", 0, 0, 5, 5));
	}

	/** 挂一个会把收到的参数原样记下来的带坐标处理器。 */
	private static final class Probe implements GuiDeclarations.PositionalHandler {
		final double[] xy = { Double.NaN, Double.NaN };
		int value = Integer.MIN_VALUE;
		boolean called;

		@Override
		public void handleAt(Object player, double x, double y, int value, GuiState state) {
			this.xy[0] = x;
			this.xy[1] = y;
			this.value = value;
			this.called = true;
		}
	}

	/** 显式坐标派发：处理器拿到的就是面板像素 x/y（真机 calculateSegment 的输入口径）。 */
	@Test
	void positionalActionReceivesPanelCoordinates() {
		Probe probe = new Probe();
		assertTrue(GuiDeclarations.attachPositionalAction(ID, "slide", probe));

		GuiState state = new GuiState(null);
		assertTrue(GuiDeclarations.dispatch(ID, null, 1, state, 58.5, 123.0, true));

		assertTrue(probe.called);
		assertEquals(58.5, probe.xy[0], 1e-9);
		assertEquals(123.0, probe.xy[1], 1e-9);
		assertEquals(SLIDE_VALUE, probe.value, "value 仍取自节点（与旧通道同一个值）");
	}

	/** 旧签名（客户端只发序号）：坐标取自"客户端最近一次命中记录"。 */
	@Test
	void recordedClientClickFeedsTheOriginalDispatchSignature() {
		Probe probe = new Probe();
		GuiDeclarations.attachPositionalAction(ID, "slide", probe);

		// 客户端命中：DeclClient.hitTest 会记下"鼠标 - 面板原点"
		GuiDeclarations.recordClick(ID, 13.25, 14.75);

		GuiDeclarations.dispatch(ID, null, 1, new GuiState(null));

		assertTrue(probe.called);
		assertEquals(13.25, probe.xy[0], 1e-9);
		assertEquals(14.75, probe.xy[1], 1e-9);
	}

	/** 没有坐标（专用服务器 / 没点过）：处理器收到 NaN，而不是被静默跳过。 */
	@Test
	void missingCoordinatesAreReportedAsNaN() {
		Probe probe = new Probe();
		GuiDeclarations.attachPositionalAction(ID, "slide", probe);

		GuiDeclarations.dispatch(ID, null, 1, new GuiState(null));   // 旧签名（void）

		assertTrue(probe.called, "处理器仍被调用一次（由它决定坐标缺失时怎么办）");
		assertTrue(Double.isNaN(probe.xy[0]));
		assertTrue(Double.isNaN(probe.xy[1]));
	}

	/** 现有通道零改动：普通动作多出来的坐标不影响它，且不会被带坐标版本截胡。 */
	@Test
	void plainActionChannelIsUnchanged() {
		AtomicReference<int[]> seen = new AtomicReference<>();
		GuiDeclarations.attachAction(ID, "plain", (player, value, state) -> seen.set(new int[] { value }));

		assertNull(GuiDeclarations.positionalActionOf(ID, "plain"), "普通动作没有带坐标版本");
		assertNotNull(GuiDeclarations.actionOf(ID, "plain"));

		assertTrue(GuiDeclarations.dispatch(ID, null, 0, new GuiState(null), 999.0, 999.0, true));
		assertEquals(7, seen.get()[0], "普通动作只吃 value，坐标不参与也不报错");
	}

	/** 带坐标处理器同时就是一个 {@link GuiDeclarations.ActionHandler}（探针 / 回退都靠它）。 */
	@Test
	void positionalHandlerIsAlsoAPlainActionHandler() {
		Probe probe = new Probe();
		GuiDeclarations.attachPositionalAction(ID, "slide", probe);

		assertSame(probe, GuiDeclarations.actionOf(ID, "slide"),
			"同一个对象同时进两张表 —— 探针的 handlerRegistered 仍为真");
		assertSame(probe, GuiDeclarations.positionalActionOf(ID, "slide"));

		// 直接走普通入口：默认把坐标当缺失
		GuiDeclarations.actionOf(ID, "slide").handle(null, 42, new GuiState(null));
		assertEquals(42, probe.value);
		assertTrue(Double.isNaN(probe.xy[0]));
	}

	/** 注册边界：界面没注册 / 任一参数为 null → false（什么都没做）。 */
	@Test
	void attachPositionalActionRejectsUnregisteredGuiAndNulls() {
		assertFalse(GuiDeclarations.attachPositionalAction(NEVER_REGISTERED, "slide", new Probe()));
		assertFalse(GuiDeclarations.attachPositionalAction(ID, null, new Probe()));
		assertFalse(GuiDeclarations.attachPositionalAction(ID, "slide", null));
		assertFalse(GuiDeclarations.attachPositionalAction(null, "slide", new Probe()));
		assertNull(GuiDeclarations.positionalActionOf(NEVER_REGISTERED, "slide"));
	}

	/** 越界 / 无动作 / 未知界面：返回 false，不崩、不误派发。 */
	@Test
	void dispatchGuardsStayInPlace() {
		GuiState state = new GuiState(null);
		assertFalse(GuiDeclarations.dispatch(ID, null, -1, state, 0, 0, true));
		assertFalse(GuiDeclarations.dispatch(ID, null, 99, state, 0, 0, true));
		assertFalse(GuiDeclarations.dispatch(ID, null, 2, state, 0, 0, true), "节点没有动作");
		assertFalse(GuiDeclarations.dispatch(NEVER_REGISTERED, null, 0, state, 0, 0, true));
	}

	/** 重新注册 = 重新声明：上一轮旁挂的带坐标动作与坐标必须被清掉。 */
	@Test
	void reRegisterClearsAttachedPositionalActions() {
		Probe probe = new Probe();
		GuiDeclarations.attachPositionalAction(ID, "slide", probe);
		GuiDeclarations.recordClick(ID, 1, 2);

		GuiDeclarations.register(ID, GuiDeclarationsPositionalActionTest::build,
			Map.of("plain", (player, value, state) -> {
			}));

		assertNull(GuiDeclarations.positionalActionOf(ID, "slide"), "旧 handler 不许劫持新界面");
		assertFalse(GuiDeclarations.lastClick(ID).present(), "旧坐标不许残留");
	}
}
