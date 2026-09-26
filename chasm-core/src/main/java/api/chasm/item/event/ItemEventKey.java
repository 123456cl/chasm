package api.chasm.item.event;

import net.minecraft.resources.ResourceLocation;

/**
 * 事件种类键（**开放注册**）：一个 id + 一个结果协议。
 *
 * @param id       事件种类 id（如 mymod:on_hit）
 * @param protocol 该事件采用的结果协议（可自定义）
 * @param <R>      结果类型
 */
public record ItemEventKey<R>(ResourceLocation id, ResultProtocol<R> protocol) {
}
