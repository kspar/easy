package pages.participants

import AppProperties
import EzDate
import Icons
import components.form.ButtonComp
import components.form.DateTimeFieldComp
import components.form.IntFieldComp
import components.form.ToggleComp
import components.text.WarningComp
import dao.CoursesTeacherDAO
import debug
import kotlinx.coroutines.await
import pages.links.CourseJoinByLinkPage
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import show
import template

class AddStudentsByLinkTabComp(
    private val courseId: String,
    parent: Component,
) : Component(parent) {

    private val defaultExpiryDays = 365
    private val defaultAllowedUses = 50

    private lateinit var switch: ToggleComp
    private var validity: DateTimeFieldComp? = null
    private var maxUses: IntFieldComp? = null
    private var warning: WarningComp? = null
    private var save: ButtonComp? = null

    private var currentLink: CoursesTeacherDAO.ExistingLink? = null

    override val children: List<Component>
        get() = listOfNotNull(switch, validity, maxUses, warning, save)

    override fun create() = doInPromise {
        val link = CoursesTeacherDAO.getJoinLink(courseId).await()
        currentLink = link

        switch = ToggleComp(
            "Keelatud", "Lubatud",
            initialValue = (currentLink != null),
            onValueChange = { updateJoinLink(true) },
            parent = this,
        )

        if (link != null) {
            validity = DateTimeFieldComp(
                "Aktiivne kuni", true,
                initialValue = link.expires_at,
                onValueChange = { updateSaveBtn() },
                parent = this
            )

            maxUses = IntFieldComp(
                "", true, minValue = 0, maxValue = 10000, fieldNameForMessage = "Kasutuskordade arv",
                initialValue = link.allowed_uses,
                onValueChange = { updateSaveBtn() },
                parent = this
            )

            warning = WarningComp(parent = this)

            save = ButtonComp(
                ButtonComp.Type.PRIMARY, "Salvesta",
                onClick = { updateJoinLink() }, parent = this
            )
        } else {
            validity = null
            maxUses = null
            warning = null
            save = null
        }
    }

    override fun render() = template(
        """
            <ez-course-join-link>
                $switch
                {{#isEnabled}}
                    <ez-block-container style='margin-top: 3rem; gap: 0 4rem;'>
                        <ez-block style='flex-grow: 0;'>$validity</ez-block>
                        <ez-block>
                            Kasutatud {{used}} / 
                            <ez-inline-flex style='margin-left: .2rem;'>$maxUses</ez-inline-flex>    
                        </ez-block>
                    </ez-block-container>
                    $warning
                    <ez-flex>$save</ez-flex>
                    <ez-link-wrap>
                        ${Icons.lahendus}<ez-link>{{link}}</ez-link>                    
                    </ez-link-wrap>
                {{/isEnabled}}
            </ez-course-join-link>
        """.trimIndent(),
        "isEnabled" to (currentLink != null),
        "used" to currentLink?.used_count,
        "link" to currentLink?.invite_id?.let { AppProperties.WUI_ROOT_PRETTY + CourseJoinByLinkPage.link(it) },
    )

    override fun postChildrenBuilt() {
        updateSaveBtn()
    }

    private fun updateSaveBtn() {
        if (switch.isToggled) {

            // Button is enabled when fields are valid
            val isValid = validity!!.isValid && maxUses!!.isValid
            save?.setEnabled(isValid)

            // Show save btn only when there are unsaved changes
            val isSomethingToSave = when {
                !currentLink!!.expires_at.isOnSameMinute(validity?.getValue()!!)
                        || currentLink!!.allowed_uses != maxUses!!.getIntValue() -> true

                else -> false
            }
            save?.show(isSomethingToSave)

            // Show warning only when saved
            if (!isSomethingToSave) {
                when {
                    validity?.getValue()!! < EzDate.now() -> {
                        warning!!.setMsg("Link on aegunud")
                    }

                    currentLink!!.used_count >= maxUses?.getIntValue()!! -> {
                        warning!!.setMsg("Lingi kasutuskordade arv on täis")
                    }

                    else -> {
                        warning!!.setMsg(null)
                    }
                }
            } else {
                warning!!.setMsg(null)
            }
        }
    }

    private suspend fun updateJoinLink(defaultValues: Boolean = false) {
        val enabled = switch.isToggled

        when {
            !enabled -> CoursesTeacherDAO.deleteJoinLink(courseId).await()

            enabled && defaultValues -> {
                val inviteId = CoursesTeacherDAO.createJoinLink(
                    courseId, EzDate.nowDeltaDays(defaultExpiryDays), defaultAllowedUses
                ).await().invite_id
                debug { "Invite id: $inviteId" }
            }

            else -> {
                val expiresAt = validity?.getValue()!!
                val allowedUses = maxUses?.getIntValue()!!
                CoursesTeacherDAO.createJoinLink(courseId, expiresAt, allowedUses).await().invite_id
            }
        }

        createAndBuild().await()
    }
}