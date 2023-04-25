package components.text

import rip.kspar.ezspa.Component
import tmRender

class AttrsComp(
    val attrs: Map<String, String>,
    parent: Component,
) : Component(parent) {

    override fun render() = tmRender(
        "t-c-attrs",
        "attrs" to attrs.map {
            mapOf(
                "label" to it.key,
                "value" to it.value,
            )
        }
    )
}