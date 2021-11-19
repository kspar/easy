package rip.kspar.ezspa

import kotlinx.coroutines.await
import kotlinx.dom.clear
import kotlin.js.Promise

/**
 * An independent part of the app and/or UI that is capable of rendering itself to HTML by implementing the [render] method.
 * A component can also load data, perform calculations etc in [create] and perform various tasks after rendering via [postRender].
 * Each component must also be given a destination Element ID [dstId] as a constructor parameter - the component
 * is painted inside the Element with the given ID, overwriting previous contents.
 * The destination element must exist in the DOM before this component is painted.
 * A component can have children components that are automatically created and built when [createAndBuild]ing this component
 * and rebuilt when [rebuild]ing this component.
 * [parent] is this component's parent component in the component tree, should be null if this is the root component.
 *
 * Call [createAndBuild] to create and build this component and its children.
 * Call [rebuild] to rebuild this component and its children (if its state has changed).
 *
 * Components should not directly call any methods other than [createAndBuild] and [rebuild] on themselves.
 */
abstract class Component(
    private val parent: Component?,
    val dstId: String = IdGenerator.nextId()
) {

    /**
     * This component's dependants.
     */
    protected open val children: List<Component> = emptyList()

    /**
     * Load data, modify state, create children etc. Return a promise that resolves when everything is complete and
     * the component is ready to be rendered.
     * NB! Avoid performing synchronous tasks.
     */
    protected open fun create(): Promise<*> = Promise.Companion.resolve(Unit)

    /**
     * Produce HTML that represents this component's current state. This HTML is inserted into the destination element
     * when building the component.
     */
    protected abstract fun render(): String

    /**
     * Perform UI initialisation, caching and other tasks after the component has been painted.
     */
    protected open fun postRender() {}

    /**
     * Perform UI initialisation or other tasks after the component's children have been painted and postRendered.
     */
    protected open fun postChildrenBuilt() {}

    /**
     * Produce HTML to be inserted into the destination element before [create]ing this component,
     * typically indicates loading.
     */
    protected open fun renderLoading(): String = ""


    /**
     * Callback function invoked when this component's state has changed.
     * The component should call this function whenever changing its state outside of [create].
     *
     * The default implementation calls this component's parent's [onStateChanged] i.e. bubbles the change up.
     */
    var onStateChanged: () -> Unit = { parent?.onStateChanged?.invoke() }

    /**
     * Create and build this component, and then recursively create and build its children in parallel.
     * Returns a promise that resolves when everything is complete.
     */
    fun createAndBuild(): Promise<*> = doInPromise {
        paintLoading()
        create().await()
        buildThis()
        children.map { it.createAndBuild() }.unionPromise().await()
        postChildrenBuilt()
    }

    /**
     * Rebuild this component and its children.
     */
    fun rebuild(recreateChildren: Boolean = true): Promise<*> = doInPromise {
        buildThis()

        if (recreateChildren)
            recreateChildren().await()
        else
            rebuildChildren().await()

        postChildrenBuilt()
    }

    /**
     * Recreate and rebuild this component's children.
     */
    fun recreateChildren(): Promise<*> = children.map { it.createAndBuild() }.unionPromise()

    /**
     * Rebuild this component's children.
     */
    fun rebuildChildren(): Promise<*> = children.map { it.rebuild(false) }.unionPromise()

    /**
     * Clear this component's destination i.e. delete everything rendered by the component.
     * Can be reversed by [createAndBuild] or [rebuild].
     */
    fun clear() {
        getElemById(dstId).clear()
    }

    protected fun buildThis() {
        paint()
        postRender()
    }

    protected fun paintLoading() {
        renderLoading().let {
            if (it.isNotEmpty()) {
                getElemById(dstId).innerHTML = it
            }
        }
    }

    private fun paint() {
        getElemById(dstId).innerHTML = render()
    }
}
