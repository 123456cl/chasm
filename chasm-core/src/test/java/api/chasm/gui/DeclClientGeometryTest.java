package api.chasm.gui;

import api.chasm.gui.decl.client.DeclClient;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 声明式节点的几何约定：**屏幕矩形 = 面板原点 + 面板内位置**（外加位置过渡的平滑值）。
 *
 * <p>这条不变量被破坏过一次：加位置动画时漏掉了面板原点，于是整个界面朝左上偏了正好
 * (leftPos, topPos)（实测用户那里是 1000+ / 300+ 像素），并且所有节点挤在屏幕左上角互相压字。
 * 这个测试就是防止它再发生。</p>
 */
class DeclClientGeometryTest {

	@Test
	void screenRectIncludesPanelOrigin() {
		GuiRect panel = new GuiRect(136, 42, 48, 48);
		// 唯一 key：smooth 首次调用直接取目标值，因此结果是确定的
		GuiRect screen = DeclClient.animatedScreenRect(1000, 300,
			"chasmtest:geometry:" + System.nanoTime(), panel);
		assertEquals(1136, screen.x(), "漏加面板原点 = 整个界面朝左上偏 (leftPos, topPos)");
		assertEquals(342, screen.y());
		assertEquals(48, screen.width());
		assertEquals(48, screen.height());
	}

	@Test
	void screenRectTracksWhateverOriginItIsGiven() {
		GuiRect panel = new GuiRect(0, 0, 10, 10);
		GuiRect a = DeclClient.animatedScreenRect(0, 0, "chasmtest:geometry:a:" + System.nanoTime(), panel);
		GuiRect b = DeclClient.animatedScreenRect(320, 240, "chasmtest:geometry:b:" + System.nanoTime(), panel);
		assertEquals(320, b.x() - a.x(), "面板原点变多少，屏幕位置就该跟着变多少");
		assertEquals(240, b.y() - a.y());
	}
}
