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
    private var usedCount: AddStudentsByLinkUsedCountComp? = null
    private var maxUses: IntFieldComp? = null
    private var warning: WarningComp? = null
    private var save: ButtonComp? = null

    private var currentLink: CoursesTeacherDAO.ExistingLink? = null

    override val children: List<Component>
        get() = listOfNotNull(switch, validity, usedCount, maxUses, warning, save)

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
            usedCount = AddStudentsByLinkUsedCountComp(courseId, link.used_count, this)

            validity = DateTimeFieldComp(
                "Aktiivne kuni", true, showRequiredMsg = false,
                initialValue = link.expires_at,
                onValueChange = { updateSaveBtn() },
                parent = this
            )

            maxUses = IntFieldComp(
                "", true, minValue = 0, maxValue = 10000,
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
            usedCount?.destroy()
            usedCount = null
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
                        <ez-block>Kasutatud $usedCount / 
                            <ez-inline-flex style='margin-left: .2rem;'>$maxUses</ez-inline-flex>    
                        </ez-block>
                    </ez-block-container>
                    $warning
                    <ez-flex style='margin-bottom: 1rem'>$save</ez-flex>
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

            val link = currentLink!!
            val validityField = validity!!
            val validityFieldValue = validityField.getValue()
            val maxUsesField = maxUses!!
            val maxUsesValue = maxUsesField.getIntValue()
            val saveBtn = save!!
            val warningMsg = warning!!

            // Button is enabled when fields are valid
            val isValid = validityField.isValid && maxUsesField.isValid
            saveBtn.setEnabled(isValid)

            // Show save btn only when there are unsaved changes
            val isSomethingToSave = when {
                validityFieldValue == null -> true
                !link.expires_at.isOnSameMinute(validityFieldValue) -> true
                link.allowed_uses != maxUsesValue -> true
                else -> false
            }
            saveBtn.show(isSomethingToSave)

            // Show warning only when saved
            if (!isSomethingToSave) {
                when {
                    validityFieldValue != null && validityFieldValue < EzDate.now() -> {
                        warningMsg.setMsg("Link on aegunud")
                    }

                    maxUsesValue != null && link.used_count >= maxUsesValue -> {
                        warningMsg.setMsg("Lingi kasutuskordade arv on täis")
                    }

                    else -> {
                        warningMsg.setMsg(null)
                    }
                }
            } else {
                warningMsg.setMsg(null)
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