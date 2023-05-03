package components.text

import rip.kspar.ezspa.Component
import template

class AttrsComp(
    val attrs: Map<String, String>,
    parent: Component,
) : Component(parent) {

    override fun render() = template(
        """
            {{#attrs}}
                <ez-attr><ez-attr-label>{{label}}:</ez-attr-label>{{value}}</ez-attr>
            {{/attrs}}
        """.trimIndent(),
        "attrs" to attrs.map {
            mapOf(
                "label" to it.key,
                "value" to it.value,
            )
        }
    )
}