package api.chasm.gui;

import net.minecraft.server.level.ServerPlayer;

/**
 * 控件动作处理器（服务端主线程执行）。
 *
 * <p>值已由框架**夹取到控件声明的值域**后才交给处理器；处理器只关心"值变了要做什么"。</p>
 */
@FunctionalInterface
public interface ChasmWidgetHandler {

	/**
	 * @param player 触发的玩家
	 * @param value  夹取后的新值
	 * @param menu   当前界面菜单（可写数据键，客户端会自动收到同步）
	 */
	void onAction(ServerPlayer player, int value, ChasmMenu menu);
}
