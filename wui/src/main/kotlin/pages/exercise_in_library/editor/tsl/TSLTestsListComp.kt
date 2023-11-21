package pages.exercise_in_library.editor.tsl

import debug
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.doInPromise
import translation.Str
import tsl.common.model.PlaceholderTest
import tsl.common.model.Test

class TSLTestsListComp(
    private var tests: List<Test>,
    private val onUpdate: () -> Unit,
    private val onValidChanged: () -> Unit,
    parent: Component
) : Component(parent) {

    private lateinit var testComps: MutableList<TSLTestComp>

    private lateinit var reorderModal: TSLReorderTestModalComp

    override val children: List<Component>
        get() = testComps + reorderModal


    override fun create() = doInPromise {
        testComps = tests.mapIndexed { i, test -> createTestComp(i, test) }.toMutableList()
        reorderModal = TSLReorderTestModalComp(this)
    }

    fun updateAndGetTests(): List<Test> {
        updateAndGetComps()
        return tests
    }

    suspend fun addTest() {
        val newTestComp = createTestComp(testComps.size, PlaceholderTest(IdGenerator.nextLongId()))
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


    private suspend fun copyTest(test: Test, title: String) = changeTests {
        val testCopy = test.copyTest(IdGenerator.nextLongId())
        testCopy.name = title + " " + Str.tslCopySuffix
        val newIndex = it.indexOfFirst { it.id == test.id } + 1
        it.add(newIndex, testCopy)
    }

    private suspend fun removeTest(deletedTest: Test) = changeTests {
        it.remove(deletedTest)
    }

    private suspend fun restoreTest(restoredTest: Test, idx: Int, isOpen: Boolean) {
        changeTests {
            it.add(idx, restoredTest)
        }
        if (isOpen) openTests(listOf(restoredTest.id))
    }

    private suspend fun moveTest(test: Test) {
        val testToComp = updateAndGetComps()

        val allTests = testToComp.map { (test, comp) ->
            TSLReorderTestModalComp.MovableItem(test.id.toString(), test.name.orEmpty(), comp.idx)
        }
        reorderModal.allItems = allTests
        reorderModal.movingItem = allTests.first { it.id == test.id.toString() }

        reorderModal.createAndBuild().await()
        val newIdx = reorderModal.openWithClosePromise().await()
        if (newIdx != null) {
            changeTests {
                it.removeAll { it.id == test.id }
                it.add(newIdx, test)
            }
        }
    }

    private fun updateAndGetComps(): Map<Test, TSLTestComp> {
        val map = testComps.associateBy { it.getTestModel() }
        tests = map.keys.toList()
        return map
    }

    private fun createTestComp(idx: Int, testModel: Test) =
        TSLTestComp(
            idx,
            testModel,
            onUpdate,
            onValidChanged,
            ::copyTest,
            ::removeTest,
            ::restoreTest,
            ::moveTest,
            parent = this
        )

    private suspend fun changeTests(change: (tests: MutableList<Test>) -> Unit) {
        updateAndGetTests()
        val currentTests = tests.toMutableList()
        val open = getOpenTests()

        change(currentTests)

        tests = currentTests
        createAndBuild().await()
        openTests(open)
        onUpdate()
    }
}