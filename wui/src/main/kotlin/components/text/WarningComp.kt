package components.text

import Icons
import rip.kspar.ezspa.Component
import template

class WarningComp(
    private var msg: String? = null,
    private val monospaceMsg: Boolean = false,
    parent: Component
) : Component(parent) {

    override fun render() = template(
        """
            {{#msg}}
                <ez-warning class='icon-med'>
                    {{{icon}}}
                    {{#pre}}
                        <pre>{{msg}}</pre>
                    {{/pre}}
                    {{^pre}}
                        <span>{{msg}}</span>
                    {{/pre}}
                </ez-warning>
            {{/msg}}
        """.trimIndent(),
        "icon" to Icons.errorInfoUnf,
        "msg" to msg,
        "pre" to monospaceMsg,
    )

    fun setMsg(feedback: String?) {
        this.msg = feedback
        rebuild()
    }
}