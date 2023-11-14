package components.text

import rip.kspar.ezspa.Component
import template

class AttrsComp(
    attrs: Map<String, String>,
    parent: Component,
) : Component(parent) {

    var attrs = attrs
        set(value) {
            field = value
            rebuild()
        }

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