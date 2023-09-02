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
    // TODO: non-null
    protected open fun create(): Promise<*>? = null

    /**
     * Produce HTML that represents this component's current state. This HTML is inserted into the destination element
     * when building the component.
     */
    protected open fun render(): String = plainDstStr(children.map { it.dstId })

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
    protected open fun renderLoading(): String? = null

    /**
     * Clear everything rendered by this component, destroy global effects etc.
     * This is (should be - see [destroy]) guaranteed to be called before this component is cleared out of the DOM.
     * Default implementation clears the component's destination.
     * To destroy a component, call [destroy].
     */
    protected open fun destroyThis() = getElemByIdOrNull(dstId)?.clear()

    /**
     * Clear everything rendered by this component and its children, destroy global effects etc.
     * Make sure to always call this for each component that was created but is now discarded.
     * [Page]s should ensure that this function is called on [Page.destruct] for all root components
     * (from where it propagates down to all current children).
     * However, if a component's children change, then this function should be manually called for all removed children
     * by the parent component since [Page] root [destroy]s will only propagate to current (not previous) children.
     */
    fun destroy() {
        // If is created, then assume all lateinit children are initialized
        if (isCreated) {
            children.forEach { it.destroy() }
            destroyThis()
        }
    }

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
    open fun createAndBuild(): Promise<*> = doInPromise {
        destroy()
        paintLoading()
        create()?.await()
        isCreated = true
        buildThis()
        children.map { it.createAndBuild() }.unionPromise().await()
        postChildrenBuilt()
    }

    open fun hasUnsavedChanges(): Boolean = if (isCreated) children.any { it.hasUnsavedChanges() } else false

    open fun createAndBuild3(): Promise<*>? {
        paintLoading()
        val p = create()
        if (p != null) {
            return p.then {
                buildThis()
                b()
            }.then {
                postChildrenBuilt()
            }
        }

        buildThis()
        val c = b()
        if (c != null) {
            return c.then {
                postChildrenBuilt()
            }
        }

        postChildrenBuilt()
        return null
    }

    fun b(): Promise<*>? {
        val cp = children.mapNotNull { it.createAndBuild3() }
        if (cp.isNotEmpty())
            return cp.unionPromise()
        return null
    }

    /**
     * Rebuild this component and recreate its children.
     */
    fun rebuildAndRecreateChildren(): Promise<*> = doInPromise {
        buildThis()
        children.map { it.createAndBuild() }.unionPromise().await()
        postChildrenBuilt()
    }

    /**
     * Append the child's destination HTML element inside this component's destination,
     * and then [createAndBuild] the child.
     */
    fun appendChild(child: Component): Promise<*> = doInPromise {
        getElemById(dstId).appendHTML(plainDstStr(child.dstId))
        child.createAndBuild().await()
        postChildrenBuilt()
    }

    /**
     * Rebuild this component and its children.
     */
    fun rebuild() {
        destroy()
        buildThis()
        children.forEach { it.rebuild() }
        postChildrenBuilt()
    }

    override fun toString() = plainDstStr(dstId)

    // FIXME: temporarily public to optimise ezcoll
    fun buildThis() {
        paint()
        postRender()
    }

    protected fun paintLoading() {
        renderLoading()?.let {
            getElemById(dstId).innerHTML = it
        }
    }

    private var isCreated: Boolean = false

    private fun paint() {
        try {
            getElemById(dstId).innerHTML = render()
        } catch (e: ElementNotFoundException) {
            val ancestorDstStr = getAncestorsRec().joinToString(" > ") { it.dstId }
            EzSpa.Logger.warn { "Couldn't find destination ID $dstId when painting component \n  Trace: $ancestorDstStr" }
            throw e
        }
    }

    private fun getAncestorsRec(): List<Component> =
        (parent?.getAncestorsRec() ?: emptyList()) + listOf(this)
}
