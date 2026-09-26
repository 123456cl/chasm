package api.chasm.blockentity.client;

import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * **客户端方块实体渲染器注册入口**（框架此前只有服务端 BE/ticker，没有渲染这一层）。
 *
 * <p>用途：需要"动态方块"的场景——重铸台上浮起来敲击的锤子、熔炉里翻滚的矿石、
 * 祭坛上悬浮的物品。注册必须在**客户端初始化期**调用（{@code client} 入口点里）。</p>
 *
 * <pre>{@code
 * ChasmBlockEntityRenderers.register(REFORGING_TABLE_TYPE, ctx -> new ReforgingTableRenderer());
 * }</pre>
 */
public final class ChasmBlockEntityRenderers {

	private ChasmBlockEntityRenderers() {
	}

	/** 注册某个 BE 类型的渲染器。 */
	public static <T extends BlockEntity> void register(BlockEntityType<T> type,
		BlockEntityRendererProvider<T> provider) {
		if (type == null || provider == null) {
			throw new IllegalArgumentException("BE 类型与渲染器都不可为 null");
		}
		BlockEntityRenderers.register(type, provider);
	}
}
