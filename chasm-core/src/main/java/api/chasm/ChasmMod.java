package api.chasm;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个类为 Chasm 模组入口。
 *
 * <p>声明式注册的核心：{@code @ChasmMod} 提供模组命名空间（modId），
 * 类中带 {@link api.chasm.registry.Register @Register} 注解的静态字段会在
 * {@link api.chasm.registry.ChasmRegistrar#scan(Class)} 时被自动扫描注册。</p>
 *
 * <pre>{@code
 * @ChasmMod(id = "mymod")
 * public class MyMod implements ModInitializer {
 *     @Register("mana_sword")
 *     public static final Item MANA_SWORD = Chasm.item()
 *         .durability(1200)
 *         .onRightClick(ctx -> { ... })
 *         .register();
 *
 *     @Override
 *     public void onInitialize() {
 *         ChasmRegistrar.scan(this.getClass());
 *     }
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ChasmMod {

	/** 模组命名空间（对应 fabric.mod.json 的 id） */
	String id();
}
