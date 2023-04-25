package pages.exercise.editor.tsl

import debug
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import tsl.common.model.Test

class TSLTestsListComp(
    tests: List<Test>,
    private val onUpdate: () -> Unit,
    parent: Component
) : Component(parent) {

    private val testComps = tests.map { createTestComp(it) }.toMutableList()

    override val children: List<Component>
        get() = testComps

    fun getTests() = testComps.mapNotNull { it.getTestModel() }

    suspend fun addTest() {
        val newTestComp = createTestComp(null)
        testComps.add(newTestComp)
        appendChild(newTestComp).await()
    }

    fun getOpenTests() = testComps.filter { it.isOpen }.mapNotNull { it.getTestModel()?.id }

    fun openTests(testIds: List<Long>) {
        debug { "Opening $testIds" }
        testComps.filter { testIds.contains(it.getTestModel()?.id) }.forEach { it.open() }
    }

    private fun createTestComp(testModel: Test?) = TSLTestComp(testModel, onUpdate, parent = this)
}