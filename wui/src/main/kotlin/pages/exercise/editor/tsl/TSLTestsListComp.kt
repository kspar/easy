package pages.exercise.editor.tsl

import debug
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import tsl.common.model.PlaceholderTest
import tsl.common.model.Test

class TSLTestsListComp(
    tests: List<Test>,
    private val onUpdate: () -> Unit,
    private val onValidChanged: () -> Unit,
    parent: Component
) : Component(parent) {

    private val testComps = tests.map { createTestComp(it) }.toMutableList()

    override val children: List<Component>
        get() = testComps

    fun getTests() = testComps.map { it.getTestModel() }

    suspend fun addTest() {
        val newTestComp = createTestComp(PlaceholderTest(IdGenerator.nextLongId()))
        testComps.add(newTestComp)
        appendChild(newTestComp).await()
    }

    fun getOpenTests() = testComps.filter { it.isOpen }.map { it.getTestModel().id }

    fun openTests(testIds: List<Long>) {
        debug { "Opening TSL tests $testIds" }
        testComps.filter { testIds.contains(it.getTestModel().id) }.forEach { it.open() }
    }

    fun setEditable(nowEditable: Boolean) {
        testComps.forEach { it.setEditable(nowEditable) }
    }

    fun isValid() = testComps.none { !it.isValid() }

    private fun createTestComp(testModel: Test) = TSLTestComp(testModel, onUpdate, onValidChanged, parent = this)
}