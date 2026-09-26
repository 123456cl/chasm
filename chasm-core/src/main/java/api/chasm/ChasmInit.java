package api.chasm;

import api.chasm.gui.ChasmGuiChannel;
import api.chasm.gui.ChasmGuiStateChannel;
import api.chasm.item.ChasmItemNotesTooltip;
import api.chasm.item.event.ChasmItemHookAdapters;
import api.chasm.key.ChasmKeyChannel;
import api.chasm.player.PlayerVarChannel;
import api.chasm.template.ChasmKillEvents;

import net.fabricmc.api.ModInitializer;

/**
 * Chasm Core 的主入口点：内部基础设施的初始化。
 *
 * <p>当前职责：注册声明式界面的按钮点击 C2S 网络通道、自定义按键的 C2S 网络通道、
 * PlayerVar 的 S2C 同步通道（{@link PlayerVarChannel}），以及模板层的击杀监听
 * （{@link ChasmKillEvents}，复用 Fabric 的 {@code ServerLivingEntityEvents.AFTER_DEATH}，
 * 不自制总线）。Core 作为玩法 API 库，运行在服务端与客户端（两侧都会执行 {@code main}
 * 入口点），因此网络编解码在两侧都登记，客户端仅声明即用，无需额外配置。</p>
 */
public final class ChasmInit implements ModInitializer {

	@Override
	public void onInitialize() {
		ChasmGuiChannel.init();
		ChasmGuiStateChannel.init();
		ChasmKeyChannel.init();
		PlayerVarChannel.init();
		ChasmKillEvents.init();
		ChasmItemHookAdapters.init();
		ChasmItemNotesTooltip.init();
	}
}