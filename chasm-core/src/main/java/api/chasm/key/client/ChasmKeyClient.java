package api.chasm.key.client;

import api.chasm.item.ChasmKeyBinding;
import api.chasm.key.ChasmKeys;
import api.chasm.key.KeyPressC2SPayload;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.KeyMapping;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * Chasm 自定义按键的客户端初始化（第十二步）。
 *
 * <p>职责：</p>
 * <ol>
 *   <li>为 {@link ChasmKeys} 中每个声明的按键创建并注册原版 {@link KeyMapping}
 *       （经 {@link KeyBindingHelper}），自动出现在「选项 -&gt; 按键控制」列表，
 *       玩家可随意改键，冲突检测由原版处理。</li>
 *   <li>注册客户端 tick 监听，用 {@code KeyMapping.consumeClick()} 做<b>上升沿检测</b>
 *       （每帧只在下按瞬间返回 true），按下时校验玩家主手持有物品并发送
 *       {@link KeyPressC2SPayload}；服务端再兜底校验，杜绝高频发包。</li>
 * </ol>
 *
 * <p>本类是 client entrypoint（见 fabric.mod.json），仅在客户端执行，可直接引用客户端类。</p>
 */
@Environment(EnvType.CLIENT)
public final class ChasmKeyClient implements ClientModInitializer {

	/** 原版按键控制列表中的分组名（翻译键，见 chasm-core lang 文件）。 */
	public static final String CATEGORY_KEY = "key.category.chasm";

	/** 声明的逻辑按键 id → 客户端原版键位映射（仅客户端持有，避免 ChasmKeyBinding 依赖客户端类）。 */
	private final Map<ResourceLocation, KeyMapping> keyMappings = new HashMap<>();

	@Override
	public void onInitializeClient() {
		// 1) 为每个声明的按键创建原版 KeyMapping 并注册（自动进按键控制列表）
		for (ChasmKeyBinding key : ChasmKeys.INSTANCE.all()) {
			KeyMapping mapping = new KeyMapping(
				key.translationKey(), InputConstants.Type.KEYSYM, key.defaultKeyCode(), CATEGORY_KEY);
			KeyBindingHelper.registerKeyBinding(mapping);
			keyMappings.put(key.id(), mapping);
		}
		// 2) 上升沿检测 + 发送按键包（主手物品）
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player == null) {
				return;
			}
			for (ChasmKeyBinding key : ChasmKeys.INSTANCE.all()) {
				// consumeClick()：每帧只在下按瞬间返回 true，天然防每帧狂发包
				KeyMapping mapping = keyMappings.get(key.id());
				if (mapping == null || !mapping.consumeClick()) {
					continue;
				}
				ItemStack held = client.player.getMainHandItem();
				if (held.isEmpty()) {
					continue;
				}
				ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(held.getItem());
				ClientPlayNetworking.send(new KeyPressC2SPayload(
					itemId, key.id(), InteractionHand.MAIN_HAND));
			}
		});
	}
}