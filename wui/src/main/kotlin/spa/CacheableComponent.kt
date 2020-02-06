package spa

import kotlin.js.Promise

abstract class CacheableComponent<StateType>(dstId: String) : Component(dstId) {

    abstract fun getCacheableState(): StateType

    abstract fun createFromState(state: StateType): Promise<*>
}
