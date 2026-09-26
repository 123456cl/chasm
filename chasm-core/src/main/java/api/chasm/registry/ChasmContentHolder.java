package api.chasm.registry;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * **内容容器类**：把 {@link Register @Register} 声明从 mod 入口类里拆出去。
 *
 * <h2>为什么需要（实践中长出来的）</h2>
 * <p>框架原先只扫 {@code @ChasmMod} 入口类的静态字段，于是"一个模组的全部内容"被迫挤在同一个类里。
 * 真实端口（tetra-port）的入口类因此长到 1300+ 行、四十多个静态声明：每加一件内容都要回到那个文件，
 * 多个功能并行开发时必然互相踩（同一个文件只能有一个人安全地改）。</p>
 *
 * <p>给内容类打上本注解、再在入口类里扫一次，声明就注册到同一个 mod 下 ——
 * id 前缀、{@link api.chasm.context.ModContext} 暴露、DataGen 收集路径与入口类**完全一致**：</p>
 *
 * <pre>{@code
 * @ChasmContentHolder("mymod")
 * public final class MyModItems {
 *     @Register("mana_sword")
 *     public static final Item MANA_SWORD = Chasm.item().durability(1200).register();
 *
 *     /** 非声明式接线（监听器 / 事件 / 配置）放这里，入口类只调一次。 *&#47;
 *     public static void registerAll() { ... }
 * }
 *
 * @ChasmMod(id = "mymod")
 * public class MyMod implements ModInitializer {
 *     @Override
 *     public void onInitialize() {
 *         ChasmRegistrar.scan(this.getClass());
 *         ChasmRegistrar.scanContent(MyModItems.class);   // 内容容器
 *         MyModItems.registerAll();
 *     }
 * }
 * }</pre>
 *
 * <p><b>约束</b>（与入口类逐字相同）：容器里的 {@code @Register} 字段必须是 {@code static}，
 * 且必须在注册表冻结前完成扫描。容器类本身**不需要** {@code @ChasmMod} —— mod 归属由 {@link #value()} 给出。</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ChasmContentHolder {

	/** 内容归属的 mod 命名空间（即对应入口类 {@code @ChasmMod(id = ...)} 的那个值）。 */
	String value();
}
