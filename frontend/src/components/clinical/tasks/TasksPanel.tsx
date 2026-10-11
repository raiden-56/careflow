'use client';

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Box, Button, Chip, Stack, Typography } from '@mui/material';
import { ListChecks, Plus } from 'lucide-react';
import { ck, clinicalApi } from '@/lib/api/clinical';
import { useAuth } from '@/lib/auth/AuthProvider';
import { EmptyState, ErrorState, TableSkeleton } from '@/components/ui/States';
import { CreateTaskDialog, useTaskActions } from './TaskDialogs';
import { TaskCard } from './TaskCard';

/** Care tasks for one admission: active first, closed history on demand. */
export function TasksPanel({ admissionId }: { admissionId: string }) {
  const { hasPermission } = useAuth();
  const canManage = hasPermission('CARE_TASK_MANAGE');
  const [showClosed, setShowClosed] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const q = useQuery({ queryKey: ck.tasks(admissionId), queryFn: () => clinicalApi.tasks(admissionId) });
  const actions = useTaskActions();

  if (q.isLoading) return <TableSkeleton rows={3} columns={2} />;
  if (q.error) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const all = q.data ?? [];
  const active = all
    .filter((t) => t.status === 'OPEN' || t.status === 'IN_PROGRESS')
    .sort((a, b) => Number(b.overdue) - Number(a.overdue) || (a.dueAt ?? '9').localeCompare(b.dueAt ?? '9'));
  const closed = all
    .filter((t) => t.status === 'DONE' || t.status === 'CANCELLED')
    .sort((a, b) => (b.completedAt ?? '').localeCompare(a.completedAt ?? ''));
  const overdue = active.filter((t) => t.overdue).length;

  return (
    <Box>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} justifyContent="space-between" alignItems={{ sm: 'center' }} sx={{ mb: 3 }}>
        <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
          <Chip label={`Active (${active.length})`} clickable color={!showClosed ? 'primary' : 'default'} variant={!showClosed ? 'filled' : 'outlined'} onClick={() => setShowClosed(false)} />
          <Chip label={`Completed / cancelled (${closed.length})`} clickable color={showClosed ? 'primary' : 'default'} variant={showClosed ? 'filled' : 'outlined'} onClick={() => setShowClosed(true)} />
          {overdue > 0 && <Chip label={`${overdue} overdue`} color="error" variant="outlined" />}
        </Stack>
        {canManage && (
          <Button variant="contained" size="small" startIcon={<Plus size={16} />} onClick={() => setCreateOpen(true)} sx={{ alignSelf: { xs: 'flex-start', sm: 'auto' } }}>
            New task
          </Button>
        )}
      </Stack>
      {(showClosed ? closed : active).length === 0 ? (
        <EmptyState
          icon={ListChecks}
          title={showClosed ? 'No completed tasks yet' : 'No open care tasks'}
          description={!showClosed && canManage ? 'Create a task for observations, wound care, mobilisation, education and more.' : undefined}
          compact
        />
      ) : (
        <Stack spacing={2}>
          {(showClosed ? closed : active).map((t) => (
            <TaskCard key={t.id} task={t} actions={canManage ? actions : undefined} />
          ))}
        </Stack>
      )}
      {!showClosed && closed.length > 0 && (
        <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 3 }}>
          {closed.length} completed or cancelled task(s) in history.
        </Typography>
      )}
      <CreateTaskDialog open={createOpen} onClose={() => setCreateOpen(false)} admissionId={admissionId} />
      {actions.dialogs}
    </Box>
  );
}
