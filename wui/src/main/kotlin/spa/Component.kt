package spa

import getElemById
import kotlin.js.Promise

/**
 * An independent part of the app and/or UI that is capable of rendering itself to HTML by implementing the [render] method.
 * A component can also load data via [create] and perform initialisation after rendering via [init].
 * Each component must also be given a destination Element ID [dstId] as a constructor parameter - the component
 * is painted inside the Element with the given ID, overwriting its previous contents.
 * The destination element must exist in the DOM before this component is painted.
 * A component can have children components that are automatically built when building this component.
 *
 * Call [build] to (re-)build this component: render it and its children and then initialise them.
 */
abstract class Component(val dstId: String) {

    /**
     * Load data, modify state, create children etc. Return a promise that resolves when everything is complete and
     * the component is ready to be rendered.
     * Avoid performing synchronous tasks.
     * Should typically be called by the parent component before building.
     */
    open fun create(): Promise<*> = Promise.Companion.resolve(Unit)

    /**
     * Paint this component and all of its children components into the DOM.
     * Initialise this component and all of its children.
     * Should be typically called after the component is created.
     */
    fun build() {
        paint()
        children.forEach(Component::paint)
        init()
        children.forEach(Component::init)
    }


    /**
     * This component's dependants. When this component is (re-)built, its children are also (re-)built.
     */
    protected open val children: List<Component> = emptyList()

    /**
     * Produce HTML that represents this component's current state. This HTML is inserted into the destination element
     * when building the component.
     */
    protected abstract fun render(): String

    /**
     * Perform UI or other initialisation tasks after the component and all of its children have been painted.
     */
    protected open fun init() {}

    private fun paint() {
        getElemById(dstId).innerHTML = render()
    }
}
