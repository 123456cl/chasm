package api.chasm.trait;

/**
 * 行为特质（Trait）统一标记接口。
 *
 * <p>所有具体事件的特质接口（如 {@link UseTrait}、{@link AttackTrait}）都继承本标记，
 * 使 {@link ChasmTraits} 注册表能以统一的 {@link ItemTrait} 类型存储不同事件的特质，
 * 同时保持每个事件注册与查询的类型安全。</p>
 *
 * <p>特质本身不携带参数；运行时的参数通过上下文（如 {@code UseContext}）传递，
 * 需要持久化的数据存放在物品栈的数据组件中，随物品栈序列化。</p>
 */
public interface ItemTrait {
}