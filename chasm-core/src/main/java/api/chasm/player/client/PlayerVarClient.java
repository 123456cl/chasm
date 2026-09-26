package api.chasm.player.client;

import api.chasm.player.PlayerVar;
import api.chasm.player.PlayerVarSyncS2CPayload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * PlayerVar 的客户端初始化：注册 S2C 同步包的接收器。
 *
 * <p>收到服务端的 {@link PlayerVarSyncS2CPayload} 后，按变量 id 反查 {@link PlayerVar}，
 * 解码最新值写入其客户端只读缓存（{@link PlayerVar#receiveSync}）。回退到客户端主线程
 * 执行，保证渲染线程读到的缓存值一致。</p>
 *
 * <p>本类是 client entrypoint（见 fabric.mod.json），仅在客户端执行，可直接引用客户端类。</p>
 */
@Environment(EnvType.CLIENT)
public final class PlayerVarClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(PlayerVarSyncS2CPayload.TYPE,
			(payload, context) -> context.client().execute(() ->
				PlayerVar.byId(payload.varId()).ifPresent(var -> var.receiveSync(payload.value()))));
	}
}
