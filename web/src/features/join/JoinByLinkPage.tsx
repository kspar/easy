import { useEffect, useRef, useState } from 'react'
import { Navigate, useNavigate, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../../auth/useAuth.ts'
import {
  useCourseByInvite,
  useJoinByInvite,
  useStudentCourses,
} from '../../api/courses.ts'
import JoinCard from './JoinCard.tsx'
import usePageTitle from '../../hooks/usePageTitle.ts'

/**
 * Antenna flare and the smile land by ~700ms, the card then holds before lifting away at
 * 1.15s — long enough to register as a moment, short enough not to feel held up.
 */
const CELEBRATION_MS = 1700

export default function JoinByLinkPage({ isMoodle = false }: { isMoodle?: boolean }) {
  const { inviteId: inviteIdParam } = useParams<{ inviteId: string }>()
  // Invite ids are generated as uppercase, links are matched case-insensitively
  const inviteId = (inviteIdParam ?? '').toUpperCase()

  const { t } = useTranslation()
  const navigate = useNavigate()
  const { availableRoles } = useAuth()
  const isStudent = availableRoles.includes('student')

  const { data: invited, isLoading, error } = useCourseByInvite(inviteId, isMoodle, isStudent)
  const { data: myCourses, isLoading: coursesLoading } = useStudentCourses(isStudent)
  const join = useJoinByInvite(inviteId, isMoodle)

  const [joined, setJoined] = useState(false)
  const handoverRef = useRef<ReturnType<typeof setTimeout>>(undefined)
  useEffect(() => () => clearTimeout(handoverRef.current), [])

  usePageTitle(invited?.course_title)

  if (!isStudent) {
    return (
      <JoinCard title={t('join.studentsOnly')} body={t('join.studentsOnlyMsg')} />
    )
  }

  // Same robot as below, antenna still dark — it lights up in place once the course resolves
  if (isLoading || coursesLoading) {
    return <JoinCard loading />
  }

  if (error || !invited) {
    return <JoinCard title={t('join.invalidLink')} body={t('join.invalidLinkMsg')} />
  }

  // Already on the course, no need to ask anything. Skipped once joining has started:
  // that refreshes the course list, which would otherwise land here and redirect
  // straight past the confirmation, dropping the welcome message with it.
  const joiningStarted = join.isPending || join.isSuccess || joined
  if (!joiningStarted && myCourses?.some((c) => c.id === invited.course_id)) {
    return <Navigate to={`/courses/${invited.course_id}/exercises`} replace />
  }

  function handleJoin() {
    join.mutate(undefined, {
      onSuccess: (resp) => {
        const to = `/courses/${resp.course_id}/exercises`
        const state = { joinedCourse: true }
        if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
          navigate(to, { state })
          return
        }
        setJoined(true)
        handoverRef.current = setTimeout(() => navigate(to, { state }), CELEBRATION_MS)
      },
    })
  }

  return (
    <JoinCard
      eyebrow={t('join.eyebrow')}
      title={invited.course_title}
      inviteId={inviteId}
      inviteLabel={t('join.inviteCode')}
      joinLabel={t('join.join')}
      joiningLabel={t('join.joining')}
      joinedLabel={t('join.joined')}
      joining={join.isPending}
      joined={joined}
      onJoin={handleJoin}
      body={join.error ? t('join.invalidLinkMsg') : undefined}
    />
  )
}
