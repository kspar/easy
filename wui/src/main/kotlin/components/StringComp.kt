package components

import rip.kspar.ezspa.Component

class StringComp(
    var text: String,
    parent: Component
) : Component(parent) {

    override fun render() = text
}