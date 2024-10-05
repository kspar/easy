package pages.participants

import AppProperties
import EzDate
import Icons
import components.ToastThing
import components.form.*
import components.text.WarningComp
import copyToClipboard
import dao.CoursesTeacherDAO
import debug
import kotlinx.coroutines.await
import pages.links.CourseJoinByLinkPage
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import show
import template
import translation.Str

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
    private var copyBtn: IconButtonComp? = null

    private var currentLink: CoursesTeacherDAO.ExistingLink? = null

    override val children: List<Component>
        get() = listOfNotNull(switch, validity, usedCount, maxUses, warning, save, copyBtn)

    override fun create() = doInPromise {
        val link = CoursesTeacherDAO.getJoinLink(courseId).await()
        currentLink = link

        switch = ToggleComp(
            Str.disabled, Str.enabled,
            initialValue = (currentLink != null),
            onValueChange = { updateJoinLink(true) },
            parent = this,
        )

        if (link != null) {
            usedCount = AddStudentsByLinkUsedCountComp(courseId, link.used_count, this)

            validity = DateTimeFieldComp(
                Str.validUntil, true, showRequiredMsg = false,
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
                ButtonComp.Type.FILLED, Str.doSave,
                onClick = { updateJoinLink() },
                parent = this
            )

            copyBtn = IconButtonComp(
                Icons.copy, Str.doCopy,
                onClick = {
                    copyToClipboard(
                        AppProperties.WUI_ROOT + CourseJoinByLinkPage.link(link.invite_id)
                    ).await()
                    ToastThing(Str.copied)
                },
                parent = this
            )
        } else {
            usedCount?.destroy()
            usedCount = null
            validity = null
            maxUses = null
            warning = null
            save = null
            copyBtn = null
        }
    }

    override fun render() = template(
        """
            <ez-course-join-link>
                $switch
                {{#isEnabled}}
                    <ez-block-container style='margin-top: 3rem; gap: 0 4rem;'>
                        <ez-block style='flex-grow: 0;'>$validity</ez-block>
                        <ez-block>{{usedLabel}} $usedCount / 
                            <ez-inline-flex style='margin-left: .2rem;'>$maxUses</ez-inline-flex>    
                        </ez-block>
                    </ez-block-container>
                    $warning
                    <ez-flex style='margin-bottom: 1rem'>$save</ez-flex>
                    <ez-link-wrap>
                        <ez-link-icon>${Icons.lahendus}</ez-link-icon>
                        <ez-link>{{link}}</ez-link>
                        <ez-link-copy>$copyBtn</ez-link-copy>
                    </ez-link-wrap>
                {{/isEnabled}}
            </ez-course-join-link>
        """.trimIndent(),
        "isEnabled" to (currentLink != null),
        "used" to currentLink?.used_count,
        "link" to currentLink?.invite_id?.let { AppProperties.WUI_ROOT_PRETTY + CourseJoinByLinkPage.link(it) },
        "usedLabel" to Str.used,
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
                        warningMsg.setMsg(Str.linkExpired)
                    }

                    maxUsesValue != null && link.used_count >= maxUsesValue -> {
                        warningMsg.setMsg(Str.linkAllowedUsesFull)
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