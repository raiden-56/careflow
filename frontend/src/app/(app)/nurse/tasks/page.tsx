'use client';

import { ReactNode, useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Box, Button, Card, Chip, FormControlLabel, MenuItem, Stack, Switch, TextField, Typography } from '@mui/material';
import { CheckCircle2, CircleDot, ListChecks, Loader, Plus } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { ck, clinicalApi, TaskView } from '@/lib/api/clinical';
import { useAuth, useMe } from '@/lib/auth/AuthProvider';
import { PageHeader } from '@/components/ui/PageHeader';
import { EmptyState, ErrorState, PageSkeleton } from '@/components/ui/States';
import { CreateTaskDialog, useTaskActions } from '@/components/clinical/tasks/TaskDialogs';
import { TaskCard } from '@/components/clinical/tasks/TaskCard';
import { colors } from '@/theme/tokens';

const patientHref = (t: TaskView) => `/nurse/patients/${t.admissionId}?tab=tasks`;

function Column({
  title,
  icon: Icon,
  tasks,
  empty,
  accent,
  children,
}: {
  title: string;
  icon: LucideIcon;
  tasks: TaskView[];
  empty: string;
  accent: string;
  children: (t: TaskView) => ReactNode;
}) {
  return (
    <Card sx={{ display: 'flex', flexDirection: 'column', minWidth: 0, bgcolor: colors.surfaceAlt }} component="section" aria-label={title}>
      <Stack direction="row" spacing={1.5} alignItems="center" sx={{ px: 4, py: 3, borderTop: `3px solid ${accent}` }}>
        <Icon size={16} color={accent} aria-hidden />
        <Typography variant="h4" component="h2" sx={{ flex: 1 }}>
          {title}
        </Typography>
        <Chip size="small" label={tasks.length} />
      </Stack>
      <Stack spacing={2} sx={{ px: 3, pb: 3 }}>
        {tasks.length === 0 ? (
          <Typography variant="body2" color="text.secondary" sx={{ px: 1, py: 4, textAlign: 'center' }}>
            {empty}
          </Typography>
        ) : (
          tasks.map((t) => <Box key={t.id}>{children(t)}</Box>)
        )}
      </Stack>
    </Card>
  );
}

export default function TaskBoardPage() {
  const me = useMe();
  const { hasPermission } = useAuth();
  const canManage = hasPermission('CARE_TASK_MANAGE');
  const wards = me.staff?.wards ?? [];
  const [wardId, setWardId] = useState('');
  const [mine, setMine] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const params = { wardId: wardId || undefined, mine: mine || undefined };
  const q = useQuery({ queryKey: ck.taskBoard(params), queryFn: () => clinicalApi.taskBoard(params), placeholderData: keepPreviousData, refetchInterval: 60_000 });
  const actions = useTaskActions();

  if (q.isLoading) return <PageSkeleton />;
  const all = q.data ?? [];
  const byDue = (a: TaskView, b: TaskView) => Number(b.overdue) - Number(a.overdue) || (a.dueAt ?? '9').localeCompare(b.dueAt ?? '9');
  const open = all.filter((t) => t.status === 'OPEN').sort(byDue);
  const inProgress = all.filter((t) => t.status === 'IN_PROGRESS').sort(byDue);
  const done = all.filter((t) => t.status === 'DONE' || t.status === 'CANCELLED').sort((a, b) => (b.completedAt ?? '').localeCompare(a.completedAt ?? ''));
  const overdue = [...open, ...inProgress].filter((t) => t.overdue).length;
  const card = (t: TaskView) => <TaskCard task={t} actions={canManage ? actions : undefined} patientHref={patientHref(t)} hideStatus />;

  return (
    <>
      <PageHeader
        title="Care task board"
        subtitle="Nursing tasks for your patients. Completed and cancelled tasks stay visible for 24 hours."
        breadcrumbs={[{ label: 'Nursing', href: '/nurse' }, { label: 'Care task board' }]}
        actions={
          canManage && (
            <Button variant="contained" startIcon={<Plus size={16} />} onClick={() => setCreateOpen(true)}>
              New task
            </Button>
          )
        }
      />
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3} sx={{ mb: 5 }} alignItems={{ sm: 'center' }}>
        <TextField select size="small" label="Ward" value={wardId} onChange={(e) => setWardId(e.target.value)} sx={{ maxWidth: { sm: 260 } }} slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }}>
          <MenuItem value="">All my wards</MenuItem>
          {wards.map((w) => (
            <MenuItem key={w.id} value={w.id}>
              {w.name}
            </MenuItem>
          ))}
        </TextField>
        <FormControlLabel control={<Switch checked={mine} onChange={(e) => setMine(e.target.checked)} />} label="Assigned to me" sx={{ whiteSpace: 'nowrap' }} />
        {overdue > 0 && <Chip color="error" variant="outlined" label={`${overdue} overdue`} sx={{ alignSelf: { xs: 'flex-start', sm: 'center' } }} />}
      </Stack>

      {q.error && !q.data ? (
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      ) : all.length === 0 ? (
        <Card>
          <EmptyState
            icon={ListChecks}
            title={mine ? 'No tasks assigned to you' : 'No care tasks'}
            description={mine ? 'Turn off "Assigned to me" to see all tasks for your wards.' : 'Tasks created for your patients appear here.'}
            action={
              canManage && (
                <Button variant="outlined" startIcon={<Plus size={16} />} onClick={() => setCreateOpen(true)}>
                  New task
                </Button>
              )
            }
          />
        </Card>
      ) : (
        <Box
          sx={{
            display: 'grid',
            gap: 4,
            gridTemplateColumns: { xs: 'minmax(0, 1fr)', md: 'repeat(3, minmax(0, 1fr))' },
            alignItems: 'start',
            opacity: q.isFetching ? 0.75 : 1,
            transition: 'opacity 120ms',
          }}
        >
          <Column title="Open" icon={CircleDot} tasks={open} empty="No open tasks" accent={colors.info}>
            {card}
          </Column>
          <Column title="In progress" icon={Loader} tasks={inProgress} empty="Nothing in progress" accent={colors.warning}>
            {card}
          </Column>
          <Column title="Done (24 h)" icon={CheckCircle2} tasks={done} empty="Nothing completed in the last 24 hours" accent={colors.success}>
            {(t) => <TaskCard task={t} patientHref={patientHref(t)} />}
          </Column>
        </Box>
      )}

      <CreateTaskDialog open={createOpen} onClose={() => setCreateOpen(false)} />
      {actions.dialogs}
    </>
  );
}
