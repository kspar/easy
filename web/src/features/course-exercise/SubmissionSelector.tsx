import { Box, Chip, Menu, MenuItem, Typography } from '@mui/material'
import { ArrowDropDownOutlined } from '@mui/icons-material'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import RelativeTime from '../../components/RelativeTime.tsx'
import type { TeacherSubmissionSummaryResp } from '../../api/types.ts'

export default function SubmissionSelector({
  submissions,
  selectedId,
  onSelect,
}: {
  submissions: TeacherSubmissionSummaryResp[]
  selectedId: string
  onSelect: (id: string) => void
}) {
  const { t } = useTranslation()
  const [anchor, setAnchor] = useState<Element | null>(null)

  const current = submissions.find((s) => s.id === selectedId)
  const latest = submissions[0]
  const isLatest = current?.id === latest?.id

  if (!current) return null

  return (
    <>
      <Chip
        label={
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
            <span>{t('submission.viewingSubmission', { nr: current.submission_number })}</span>
            {isLatest && (
              <Typography component="span" variant="caption" sx={{ opacity: 0.7 }}>
                ({t('submission.latestSubmission')})
              </Typography>
            )}
          </Box>
        }
        deleteIcon={<ArrowDropDownOutlined />}
        onDelete={(e: React.MouseEvent<HTMLElement>) => setAnchor(e.currentTarget.closest('div'))}
        onClick={(e) => setAnchor(e.currentTarget)}
        size="small"
        variant="outlined"
      />
      <Menu
        anchorEl={anchor}
        open={!!anchor}
        onClose={() => setAnchor(null)}
      >
        {submissions.map((sub, i) => (
          <MenuItem
            key={sub.id}
            selected={sub.id === selectedId}
            onClick={() => { onSelect(sub.id); setAnchor(null) }}
          >
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, width: '100%' }}>
              <Typography variant="body2">
                #{sub.submission_number}
              </Typography>
              {i === 0 && (
                <Chip label={t('submission.latestSubmission')} size="small" color="primary" variant="outlined" sx={{ fontSize: '0.7rem', height: 20 }} />
              )}
              <Typography variant="caption" color="text.secondary" sx={{ ml: 'auto' }}>
                <RelativeTime date={sub.created_at} />
              </Typography>
              {sub.grade && (
                <Chip label={sub.grade.grade} size="small" variant="outlined" sx={{ fontSize: '0.7rem', height: 20, minWidth: 32 }} />
              )}
            </Box>
          </MenuItem>
        ))}
      </Menu>
    </>
  )
}
