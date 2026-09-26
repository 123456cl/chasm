package api.chasm.gui;

import net.minecraft.server.level.ServerPlayer;

/**
 * 按钮点击回调（服务端主线程安全执行）。
 *
 * <p>在游戏内点击按钮后，客户端发送 C2S 按钮包，服务端经
 * {@link ChasmGuiChannel} 收到后在服务端主线程回调本接口。</p>
 */
@FunctionalInterface
public interface ChasmButtonHandler {

	/**
	 * 服务端按钮点击逻辑。
	 *
	 * @param player 触发点击的玩家（服务端实体）
	 * @param click  本次点击信息（gui id、按钮名、按钮索引）
	 */
	void onClick(ServerPlayer player, ChasmGuiClick click);
}