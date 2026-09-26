package api.chasm.item;

import api.chasm.item.context.UseContext;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.function.Consumer;

/**
 * 物品控制台：暴露一个已注册物品的底层操作能力。
 *
 * <p>任何模组（包括未使用 Chasm 声明的模组）都可以通过
 * {@code Chasm.mod("目标modId").item("物品id")} 拿到控制台，对目标物品做：</p>
 * <ul>
 *   <li>{@link #appendRightClick(Consumer)} 追加右键行为（不覆盖原有）</li>
 *   <li>{@link #replaceRightClick(Consumer)} 覆写右键行为（清空原有）</li>
 *   <li>{@link #item()} 直接拿到物品实例做底层操作</li>
 * </ul>
 *
 * <p>基于 {@link ChasmItemSupport}（而非具体 {@code ChasmItem}），因此对任意 Chasm 物品
 * 类型（普通物品 / 剑 / 后续的斧、镐等）都能做统一扩展。</p>
 */
public final class ChasmItemController {

	private final ResourceLocation registryId;
	private final Item item;
	private final ChasmItemSupport support;

	public ChasmItemController(ResourceLocation registryId, ChasmItemSupport support) {
		this.registryId = registryId;
		this.item = (Item) support;
		this.support = support;
	}

	/** 完整注册 id（modId:name）。 */
	public ResourceLocation registryId() {
		return registryId;
	}

	/** 底层物品实例（可做任意原版级操作）。 */
	public Item item() {
		return item;
	}

	/** 该物品绑定的类型（未绑定返回 null）。 */
	public ItemType chasmType() {
		return support.chasmType();
	}

	/** 追加一个右键行为（保留原有行为，追加执行）。 */
	public ChasmItemController appendRightClick(Consumer<UseContext> handler) {
		support.chasmBehavior().addRightClickHandler(handler);
		return this;
	}

	/** 覆写全部右键行为（清空原有，替换为给定行为）。 */
	public ChasmItemController replaceRightClick(Consumer<UseContext> handler) {
		ChasmItemBehavior behavior = support.chasmBehavior();
		behavior.clearRightClickHandlers();
		behavior.addRightClickHandler(handler);
		return this;
	}

	/** 声明式显示名（DataGen 生成语言文件用），可为 null。 */
	public String chasmDisplayName() {
		return support.chasmBehavior().chasmDisplayName();
	}

	/** 声明式贴图路径（DataGen 生成模型用），可为 null。 */
	public String chasmTexture() {
		return support.chasmBehavior().chasmTexture();
	}

	/** 是否允许 DataGen 自动生成模型（{@code false} = 模型由模组自带，见 {@code ItemBuilder.ownModel()}）。 */
	public boolean chasmAutoModel() {
		return support.chasmBehavior().chasmAutoModel();
	}
}