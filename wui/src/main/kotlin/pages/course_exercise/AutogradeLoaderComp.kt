package pages.course_exercise

import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.sleep
import template

class AutogradeLoaderComp(
    var isActive: Boolean,
    parent: Component?, // temp null for non-wui3
    dstId: String = IdGenerator.nextId(), // temp
) : Component(parent, dstId) {
    override fun render() = if (isActive) template(
        """
            <div class="autograde-animation">
                <div class="wrap">
                    <div class="prog">
                        <pre>def fibo(n):</pre>
                        <pre>    if n == 0 or n == 1:</pre>
                        <pre>        return n</pre>
                        <pre>    return fibo(n-1) + fibo(n-2)</pre>
                    </div>
                    <div class="robots">
                        <div class="robot-row">
                            <div class="robot-wrap">
                                <div class="antennaball"></div>
                                <div class="antenna"></div>
                                <div class="robot">
                                    <div class="eye"></div>
                                    <div class="eye"></div>
                                </div>
                                <div class="robot-jaw"></div>
                            </div>
                            <div class="shade"></div>
                        </div>
                        <div class="robot-row">
                            <div class="shade"></div>
                            <div class="robot-wrap">
                                <div class="antennaball"></div>
                                <div class="antenna"></div>
                                <div class="robot">
                                    <div class="eye"></div>
                                    <div class="eye"></div>
                                </div>
                                <div class="robot-jaw"></div>
                            </div>
                        </div>
                        <div class="robot-row">
                            <div class="robot-wrap">
                                <div class="antennaball"></div>
                                <div class="antenna"></div>
                                <div class="robot">
                                    <div class="eye"></div>
                                    <div class="eye"></div>
                                </div>
                                <div class="robot-jaw"></div>
                            </div>
                            <div class="shade"></div>
                        </div>
                        <div class="robot-row">
                            <div class="shade"></div>
                            <div class="robot-wrap">
                                <div class="antennaball"></div>
                                <div class="antenna"></div>
                                <div class="robot">
                                    <div class="eye"></div>
                                    <div class="eye"></div>
                                </div>
                                <div class="robot-jaw"></div>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        """.trimIndent()
    ) else ""

    suspend fun runUntil(isQuick: Boolean = false, predicate: () -> Boolean) {
        val animationTimeMs = 5000

        isActive = true
        var elapsedAnimation = 0
        while (isActive) {
            rebuild()

            if (isQuick) {
                elapsedAnimation = 0
                while (elapsedAnimation < animationTimeMs) {
                    if (!predicate())
                        break
                    sleep(animationTimeMs / 10).await()
                    elapsedAnimation += animationTimeMs / 10
                }

            } else {
                sleep(animationTimeMs).await()
            }

            isActive = predicate()
        }
        rebuild()
    }
}