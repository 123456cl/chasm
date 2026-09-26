package api.chasm.data;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个静态字段为待注册的数据组件（Data Component）。
 *
 * <p>字段类型必须是 record，且字段值为该类型的实例（通常为默认值）。</p>
 *
 * <p>注册时自动完成：Codec 自动生成（{@link ChasmCodec}）、{@code DataComponentType}
 * 注册到 {@code modId} 命名空间、暴露到 {@code Chasm.mod(modId).data(id)} 数据空间
 * （按 modId 隔离）。</p>
 *
 * <pre>{@code
 * @ChasmMod(id = "mymod")
 * public class MyMod implements ModInitializer {
 *     @DataComponent("mana")
 *     public static final ManaData MANA = ManaData.EMPTY;
 *
 *     @Override
 *     public void onInitialize() {
 *         ChasmRegistrar.scan(this.getClass());
 *     }
 * }
 *
 * public record ManaData(int current, int max) {
 *     public static final ManaData EMPTY = new ManaData(0, 0);
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface DataComponent {

	/** 数据组件注册名（不含命名空间，例如 "mana"） */
	String value();
}
