package pages.exercise

import spa.Component


class TestingTabComp(
        parent: Component?
) : Component(parent) {
    override fun render(): String = "testing tab"

}