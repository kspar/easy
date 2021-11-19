package components

import rip.kspar.ezspa.Component

class TextComp(
    var text: String,
    parent: Component
) : Component(parent) {

    override fun render() = text
}