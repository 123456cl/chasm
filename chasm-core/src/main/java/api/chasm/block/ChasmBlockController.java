package api.chasm.block;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.function.Consumer;

/**
 * 方块控制台：暴露一个已注册方块的底层操作能力（SPI 扩展点）。
 *
 * <p>任何模组可通过 {@code Chasm.mod("目标modId").block("方块id")} 拿到控制台，
 * 对方块做追加/覆写右键行为等操作。</p>
 */
public final class ChasmBlockController {

	private final ResourceLocation registryId;
	private final ChasmBlock block;
	private final Item item;

	public ChasmBlockController(ResourceLocation registryId, ChasmBlock block, Item item) {
		this.registryId = registryId;
		this.block = block;
		this.item = item;
	}

	/** 完整注册 id（modId:name）。 */
	public ResourceLocation registryId() {
		return registryId;
	}

	/** 底层方块实例。 */
	public Block block() {
		return block;
	}

	/** 自动生成的方块物品（BlockItem）；DataGen 收集模式下可能为 null。 */
	public Item item() {
		return item;
	}

	/** 追加右键行为（保留原有）。 */
	public ChasmBlockController appendUse(Consumer<BlockUseContext> handler) {
		block.addUseHandler(handler);
		return this;
	}

	/** 覆写全部右键行为。 */
	public ChasmBlockController replaceUse(Consumer<BlockUseContext> handler) {
		block.clearUseHandlers();
		block.addUseHandler(handler);
		return this;
	}

	/** 声明式显示名（DataGen 用）。 */
	public String chasmDisplayName() {
		return block.chasmDisplayName();
	}

	/** 声明式贴图路径（DataGen 用）。 */
	public String chasmTexture() {
		return block.chasmTexture();
	}

	/** 需要的挖掘工具标签（DataGen 生成 mineable 标签用），可为 null。 */
	public String chasmRequiredTool() {
		return block.chasmRequiredTool();
	}

	/** 掉落声明（DataGen 生成 loot table 用）。 */
	public LootDeclaration chasmLoot() {
		return block.chasmLoot();
	}
}
