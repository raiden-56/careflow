'use client';

import Link from 'next/link';
import { Box, Button, Chip, Stack, Typography } from '@mui/material';
import { Clock, UserRound } from 'lucide-react';
import type { TaskView } from '@/lib/api/clinical';
import { formatDateTime, fromNow, humanize } from '@/lib/format';
import { StatusChip } from '@/components/ui/StatusChip';
import { colors } from '@/theme/tokens';
import type { TaskActions } from './TaskDialogs';

export interface TaskCardProps {
  task: TaskView;
  actions?: TaskActions;
  /** Show the patient with a link to their record. */
  patientHref?: string;
  /** Hide the status chip (e.g. when the column already shows it). */
  hideStatus?: boolean;
}

/** Care task with priority, due time, assignee and the next sensible actions. */
export function TaskCard({ task, actions, patientHref, hideStatus }: TaskCardProps) {
  const active = task.status === 'OPEN' || task.status === 'IN_PROGRESS';
  const overdue = active && task.overdue;
  const accent = !active ? colors.border : overdue ? colors.danger : task.priority === 'URGENT' ? colors.danger : task.priority === 'HIGH' ? colors.warning : colors.primary;
  const busy = actions?.busyId === task.id;
  return (
    <Box
      sx={{
        border: 1,
        borderColor: 'divider',
        borderLeft: `4px solid ${accent}`,
        borderRadius: 2,
        p: 3,
        bgcolor: overdue ? colors.dangerSoft : 'background.paper',
        opacity: active ? 1 : 0.85,
      }}
    >
      <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
        {!hideStatus && <StatusChip value={task.status} />}
        {task.priority !== 'NORMAL' && <StatusChip value={task.priority} />}
        <Chip size="small" variant="outlined" label={humanize(task.taskType)} />
        {overdue && <Chip size="small" color="error" label="Overdue" />}
      </Stack>
      <Typography variant="body2" fontWeight={600} sx={{ mt: 1.5, wordBreak: 'break-word', textDecoration: task.status === 'CANCELLED' ? 'line-through' : undefined }}>
        {task.title}
      </Typography>
      {patientHref && task.patient && (
        <Typography variant="body2" sx={{ mt: 0.5 }}>
          <Box component={Link} href={patientHref} sx={{ color: 'primary.main', fontWeight: 600, textDecoration: 'none' }}>
            {task.patient.patientName}
          </Box>{' '}
          <Typography component="span" variant="caption" color="text.secondary">
            {task.patient.location ?? 'Awaiting bed'}
          </Typography>
        </Typography>
      )}
      {task.description && (
        <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 0.5, wordBreak: 'break-word' }}>
          {task.description}
        </Typography>
      )}
      <Stack direction="row" spacing={2} sx={{ mt: 1 }} flexWrap="wrap" useFlexGap>
        {task.dueAt && active && (
          <Typography variant="caption" color={overdue ? 'error.main' : 'text.secondary'} sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5 }}>
            <Clock size={12} aria-hidden /> Due {formatDateTime(task.dueAt)} ({fromNow(task.dueAt)})
          </Typography>
        )}
        <Typography variant="caption" color="text.secondary" sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5 }}>
          <UserRound size={12} aria-hidden /> {task.assignedToName ?? (task.assignedRole ? `Any ${humanize(task.assignedRole).toLowerCase()}` : 'Unassigned')}
        </Typography>
      </Stack>
      {!active && (
        <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 0.5, wordBreak: 'break-word' }}>
          {task.status === 'DONE' ? 'Completed' : 'Cancelled'} {formatDateTime(task.completedAt)}
          {task.completedBy ? ` by ${task.completedBy}` : ''}
          {task.completionNotes ? `: ${task.completionNotes}` : ''}
        </Typography>
      )}
      {active && actions && (
        <Stack direction="row" spacing={1} sx={{ mt: 2 }} flexWrap="wrap" useFlexGap>
          {task.status === 'OPEN' && (
            <Button size="small" variant="outlined" disabled={busy} onClick={() => actions.start(task)}>
              Start
            </Button>
          )}
          {task.status === 'IN_PROGRESS' && (
            <Button size="small" color="inherit" disabled={busy} onClick={() => actions.reopen(task)}>
              Back to open
            </Button>
          )}
          <Button size="small" variant="contained" color="success" disabled={busy} onClick={() => actions.complete(task)}>
            Complete
          </Button>
          <Button size="small" color="inherit" disabled={busy} onClick={() => actions.assign(task)}>
            Assign
          </Button>
          <Button size="small" color="error" disabled={busy} onClick={() => actions.cancel(task)}>
            Cancel
          </Button>
        </Stack>
      )}
    </Box>
  );
}
