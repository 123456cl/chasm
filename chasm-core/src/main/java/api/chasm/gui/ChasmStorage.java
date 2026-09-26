package api.chasm.gui;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 声明式界面的**存储来源**（本轮补上的结构性缺口）。
 *
 * <p>旧行为：界面里的"存储槽"永远是一个临时 {@link net.minecraft.world.SimpleContainer}，
 * 关窗时把物品**全部塞回玩家背包** —— 于是"打开填充物品、一关全跑背包里"，根本无法当仓库用。</p>
 *
 * <p>现在由本接口决定"物品放哪、关窗怎么办"，内置三种：</p>
 * <table border="1">
 *   <tr><th>工厂</th><th>物品存在哪</th><th>关窗行为</th><th>典型用途</th></tr>
 *   <tr><td>{@link #transientStorage(int)}</td><td>临时容器</td><td>归还玩家背包（旧行为）</td>
 *       <td>一次性玩法界面</td></tr>
 *   <tr><td>{@link #of(Container)}</td><td>你自己给的容器（方块实体/共享库存/合成缓存）</td>
 *       <td>什么都不做（内容归容器管）</td><td>机器、箱子、方块实体面板</td></tr>
 *   <tr><td>{@link #ofItem(ItemStack, int)}</td><td><b>物品自带的容器组件</b>（{@code minecraft:container}）</td>
 *       <td>写回该物品；物品已不在身上则把内容掉落到地面</td><td>便携盒/背包物品</td></tr>
 * </table>
 *
 * <p>用法（服务端）：</p>
 * <pre>{@code
 * // 便携盒：内容存在"魔法盒"这个物品里，关窗后依然在
 * MAGIC_BOX.openItem(serverPlayer, serverPlayer.getMainHandItem());
 *
 * // 机器：内容存在方块实体里（BE 实现 Container，或自己包一层适配器）
 * MAGIC_BOX.openWith(serverPlayer, ChasmStorage.of(myBlockEntityContainer));
 * }</pre>
 */
public interface ChasmStorage {

	/** 供菜单建槽位与读写内容的容器（服务端权威）。 */
	Container container();

	/** 关窗时是否把内容归还玩家背包（只有"临时"存储为 true）。 */
	boolean returnsItemsOnClose();

	/**
	 * 关窗收尾。
	 *
	 * @param player 关窗的玩家；**允许为 null**（测试/离线收尾场景，写入仍会执行，只是跳过"物品是否还在身上"的判断）
	 */
	void onClosed(Player player);

	/** 周期性落盘（可选；物品自带容器用它降低崩溃丢失风险）。默认无操作。 */
	default void tick(Player player) {
	}

	/** 临时存储：关窗归还玩家（旧行为，保持向后兼容）。 */
	static ChasmStorage transientStorage(int size) {
		return new TransientStorage(size);
	}

	/** 外部容器（方块实体、共享库存…）：界面直接读写它，关窗不做任何搬运。 */
	static ChasmStorage of(Container container) {
		return new ContainerStorage(container);
	}

	/**
	 * 物品自带容器（便携盒/背包）：内容存进该物品的 {@code minecraft:container} 组件。
	 *
	 * @param stack 承载容器的物品栈（服务端持有的那一份引用）
	 * @param size  槽位数
	 */
	static ChasmStorage ofItem(ItemStack stack, int size) {
		return new ItemStorage(stack, size);
	}
}
