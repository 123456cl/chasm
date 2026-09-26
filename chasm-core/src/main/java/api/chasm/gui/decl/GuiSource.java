package api.chasm.gui.decl;

import java.util.List;

/**
 * **界面的唯一真相**：{@code 状态 → 节点列表}。
 *
 * <p>对照 Tetra 的 {@code GuiModuleList.update()}：那边是"清空子元素 → 按当前物品重建"，
 * 这边就是同一个思路的框架化 —— 你只写这一个函数，绘制、命中、显隐全部由它派生。</p>
 *
 * <p>硬性约定：**列表顺序 = 绘制顺序 = 命中优先级**（后面的画在上面，命中从后往前找）。
 * 因此不需要重叠校验：想压住谁就放在后面。</p>
 */
@FunctionalInterface
public interface GuiSource {

	List<GuiNode> build(GuiState state);
}
