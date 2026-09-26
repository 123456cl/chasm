package api.chasm.item.client;

import api.chasm.item.ChasmItemTooltips;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;

/** 把 {@link ChasmItemTooltips} 接到原版提示渲染（零 mixin）。 */
@Environment(EnvType.CLIENT)
public final class ChasmTooltipClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		ItemTooltipCallback.EVENT.register((stack, context, flag, lines) ->
			ChasmItemTooltips.appendAll(stack, flag, lines));
	}
}
