package spa

import kotlin.js.Promise

/**
 * A component that has a state object that can be cached.
 * The parent component should call [getCacheableState] and cache the resulting state object.
 * To create this component from a cached state object, the parent component should call [createFromState].
 * @param StateType type of the cacheable state object, must be @Serializable
 */
abstract class CacheableComponent<StateType>(dstId: String) : Component(dstId) {

    /**
     * Return a cacheable state object representing this component's current state.
     */
    abstract fun getCacheableState(): StateType

    /**
     * Create this component from a cached state object. This can be called in place of [create].
     * This method's functionality should be the same as [create], except it uses a cached state object
     * to (partly) avoid loading or calculating state.
     */
    abstract fun createFromState(state: StateType): Promise<*>
}
