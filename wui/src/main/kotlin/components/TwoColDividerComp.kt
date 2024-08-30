package components

import Icons
import storage.Key
import storage.LocalStore
import components.form.IconButtonComp
import hide
import kotlinx.dom.addClass
import kotlinx.dom.removeClass
import kotlinx.serialization.Serializable
import parseToOrCatch
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.getElemBySelector
import show
import stringify
import template
import warn


class TwoColDividerComp(
    private val localStorePersistKey: Key,
    parent: Component
) : Component(parent) {

    @Serializable
    enum class ExpandState { SPLIT, LEFT_EXPANDED, RIGHT_EXPANDED }

    private lateinit var expandLeftBtn: IconButtonComp
    private lateinit var expandRightBtn: IconButtonComp

    private var expandState = retrieveExpandState() ?: ExpandState.SPLIT

    override val children: List<Component>
        get() = listOf(expandLeftBtn, expandRightBtn)

    override fun create() = doInPromise {

        expandLeftBtn = IconButtonComp(
            Icons.next, null,
            onClick = {
                when (expandState) {
                    ExpandState.SPLIT -> {
                        expandState = ExpandState.LEFT_EXPANDED
                        expandLeftBtn.hide()
                        rootElement.getElemBySelector("ez-expand-divider").addClass("expanded-left")
                    }

                    ExpandState.RIGHT_EXPANDED -> {
                        expandState = ExpandState.SPLIT
                        expandRightBtn.show()
                        rootElement.getElemBySelector("ez-expand-divider").removeClass("expanded-right")
                    }

                    ExpandState.LEFT_EXPANDED -> warn { "Expand left, invalid state $expandState" }
                }
                saveExpandState(expandState)
            },
            parent = this
        )

        expandRightBtn = IconButtonComp(
            Icons.previous, null,

            onClick = {
                when (expandState) {
                    ExpandState.SPLIT -> {
                        expandState = ExpandState.RIGHT_EXPANDED
                        expandRightBtn.hide()
                        rootElement.getElemBySelector("ez-expand-divider").addClass("expanded-right")
                    }

                    ExpandState.LEFT_EXPANDED -> {
                        expandState = ExpandState.SPLIT
                        expandLeftBtn.show()
                        rootElement.getElemBySelector("ez-expand-divider").removeClass("expanded-left")
                    }

                    ExpandState.RIGHT_EXPANDED -> warn { "Expand right, invalid state $expandState" }
                }
                saveExpandState(expandState)
            },
            parent = this
        )
    }

    override fun render() = template(
        """
            <ez-expand-divider class='{{class}}'>
                <ez-expand-divider-line>
                    <ez-expand-buttons>
                        $expandLeftBtn
                        $expandRightBtn
                    </ez-expand-buttons>
                </ez-expand-divider-line>
            </ez-expand-divider>
        """.trimIndent(),
        "class" to when (expandState) {
            ExpandState.LEFT_EXPANDED -> "expanded-left"
            ExpandState.RIGHT_EXPANDED -> "expanded-right"
            ExpandState.SPLIT -> null
        }
    )

    override fun postChildrenBuilt() {
        when (expandState) {
            ExpandState.LEFT_EXPANDED -> expandLeftBtn.hide()
            ExpandState.RIGHT_EXPANDED -> expandRightBtn.hide()
            ExpandState.SPLIT -> {}
        }
    }

    private fun retrieveExpandState() =
        LocalStore.get(localStorePersistKey)?.parseToOrCatch(ExpandState.serializer())


    private fun saveExpandState(newState: ExpandState) {
        LocalStore.set(
            localStorePersistKey,
            ExpandState.serializer().stringify(newState)
        )
    }
}
