package spa

import doInPromise
import kotlinx.coroutines.await
import unionPromise
import kotlin.js.Promise

/**
 * A component that has a state object that can be cached.
 * The parent component should call [getCacheableState] and cache the resulting state object.
 * To create this component from a cached state object, the parent component should call [createAndBuildFromState].
 * If this component has cacheable children then it should also implement [createAndBuildChildrenFromState], or else
 * the children won't be created from state.
 * @param StateType type of the cacheable state object, must be @Serializable
 */
abstract class CacheableComponent<StateType>(dstId: String, parent: Component?) : Component(dstId, parent) {

    /**
     * Return a cacheable state object representing this component's current state.
     */
    abstract fun getCacheableState(): StateType

    /**
     * Create this component from a cached state object.
     * This method's functionality should be the same as [create], except it uses a cached state object
     * to (partly) avoid loading or calculating state.
     */
    protected abstract fun createFromState(state: StateType): Promise<*>

    /**
     * Create and build children (by calling non-cacheable children's [createAndBuild]
     * or cacheable children's [createAndBuildFromState]).
     *
     * The default implementation calls all children's [createAndBuild] i.e. does not use a cache even if
     * children are cacheable.
     */
    protected open fun createAndBuildChildrenFromState(state: StateType): Promise<*> = doInPromise {
        children.map { it.createAndBuild() }.unionPromise().await()
    }

    /**
     * This should be called in place of [createAndBuild] with a state object.
     */
    fun createAndBuildFromState(state: StateType): Promise<*> = doInPromise {
        paintLoading()
        createFromState(state).await()
        buildThis()
        createAndBuildChildrenFromState(state).await()
    }
}
