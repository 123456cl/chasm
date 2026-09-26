package api.chasm.model;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.minecraft.world.inventory.InventoryMenu;

/**
 * 单测用的**最小贴图替身**：直接用像素造一张 16×16 的 {@code SpriteContents}，
 * 不读任何 png、不碰资源管理器。
 *
 * <p>为什么要靠像素造而不是 mock：几何生成走的是**原版** {@code ItemModelGenerator}，它按
 * {@code SpriteContents.isTransparent(frame, x, y)} 扫描像素来决定侧壁（见
 * {@link ChasmItemGeometry} 的类注释）。mock 掉这一层就等于没测到几何。</p>
 *
 * <p>{@code TextureAtlasSprite} 的构造器是 {@code protected}，所以这里派生子类；
 * 图集尺寸给 1024，于是每格 1/64 —— 足以验证 UV 落在该 sprite 自己的图集区域里。</p>
 */
final class TestSprites {

	/** 贴图集尺寸（原版 blocks 图集远大于此；这里只要 UV 四舍五入不退化）。 */
	static final int ATLAS = 1024;

	private TestSprites() {
	}

	/** 一张 16×16 的贴图；{@code opaque} 返回 true 的像素写白色不透明，其余保持全 0（透明）。 */
	static TextureAtlasSprite of(ResourceLocation id, OpaqueMask opaque) {
		NativeImage image = new NativeImage(NativeImage.Format.RGBA, 16, 16, true);
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				if (opaque.isOpaque(x, y)) {
					image.setPixelRGBA(x, y, 0xFFFFFFFF);
				}
			}
		}
		return of(id, image, 0, 0);
	}

	/** 用一张已经填好的图造 sprite（tetra-port 那边的真实 png 走这条）。 */
	static TextureAtlasSprite of(ResourceLocation id, NativeImage image, int x, int y) {
		SpriteContents contents = new SpriteContents(id, new FrameSize(image.getWidth(), image.getHeight()),
			image, ResourceMetadata.EMPTY);
		return new TestSprite(InventoryMenu.BLOCK_ATLAS, contents, ATLAS, ATLAS, x, y);
	}

	/** 不透明像素的判据。 */
	@FunctionalInterface
	interface OpaqueMask {
		boolean isOpaque(int x, int y);
	}

	/** {@code TextureAtlasSprite} 的构造器是 protected，只能这样拿到实例。 */
	static final class TestSprite extends TextureAtlasSprite {

		TestSprite(ResourceLocation atlasLocation, SpriteContents contents,
				int atlasWidth, int atlasHeight, int x, int y) {
			super(atlasLocation, contents, atlasWidth, atlasHeight, x, y);
		}
	}
}
