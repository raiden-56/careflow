'use client';

import { ReactNode, useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Grid,
  MenuItem,
  Stack,
  TextField,
  Typography,
  useMediaQuery,
  useTheme,
} from '@mui/material';
import { clinicalApi, TaskPriority, TaskStatus, TaskType, TaskView } from '@/lib/api/clinical';
import { admissionApi, qk, staffApi } from '@/lib/api/core';
import { errorMessage } from '@/lib/api/client';
import { useAuth } from '@/lib/auth/AuthProvider';
import { applyServerErrors, useToast } from '@/lib/hooks';
import { humanize, localInputToIso } from '@/lib/format';
import { RHFSelect, RHFTextField } from '@/components/forms/Fields';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';

export const TASK_TYPES: TaskType[] = ['OBSERVATION', 'WOUND_CARE', 'MOBILISATION', 'HYGIENE', 'EDUCATION', 'LAB_COLLECTION', 'DISCHARGE_PREP', 'TRANSFER', 'OTHER'];
export const TASK_PRIORITIES: TaskPriority[] = ['LOW', 'NORMAL', 'HIGH', 'URGENT'];

const OPEN_ADMISSION = new Set(['PENDING_BED', 'ADMITTED', 'DISCHARGE_IN_PROGRESS']);

/** Nurses that tasks can be assigned to. */
export function useNurseOptions(enabled = true) {
  const staff = useQuery({ queryKey: qk.directory({ role: 'NURSE' }), queryFn: () => staffApi.directory({ role: 'NURSE' }), enabled, staleTime: 5 * 60_000 });
  return useMemo(() => (staff.data ?? []).map((s) => ({ value: s.id, label: s.fullName })), [staff.data]);
}

// ------------------------------------------------------------------ create

const createSchema = z.object({
  admissionId: z.string().min(1, 'Choose a patient'),
  taskType: z.string().min(1, 'Choose a task type'),
  title: z.string().trim().min(3, 'Give the task a short title').max(300, 'At most 300 characters'),
  description: z.string().trim().max(2000, 'At most 2000 characters').optional(),
  priority: z.string().min(1),
  dueAt: z
    .string()
    .optional()
    .refine((v) => !v || new Date(localInputToIso(v)).getTime() >= Date.now() - 60 * 60_000, 'The due time cannot be in the past'),
  assignedTo: z.string().optional(),
});
type CreateValues = z.infer<typeof createSchema>;

export interface CreateTaskDialogProps {
  open: boolean;
  onClose: () => void;
  /** Fixed admission (patient tab). When omitted the nurse chooses one of their patients. */
  admissionId?: string;
  onCreated?: (t: TaskView) => void;
}

/** Creates a care task for one admission, optionally assigned to a nurse. */
export function CreateTaskDialog({ open, onClose, admissionId, onCreated }: CreateTaskDialogProps) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down('sm'));
  const [error, setError] = useState<string | null>(null);
  const nurses = useNurseOptions(open);
  const patients = useQuery({ queryKey: qk.myAdmissions, queryFn: admissionApi.my, enabled: open && !admissionId });
  const patientOptions = useMemo(
    () =>
      (patients.data ?? [])
        .filter((a) => OPEN_ADMISSION.has(a.status))
        .map((a) => ({
          value: a.id,
          label: `${a.patient.fullName} · ${a.location ? `${a.location.wardName ?? ''} ${a.location.roomNumber ?? ''}-${a.location.bedNumber}`.trim() : 'awaiting bed'}`,
        })),
    [patients.data],
  );
  const defaults: CreateValues = { admissionId: admissionId ?? '', taskType: 'OBSERVATION', title: '', description: '', priority: 'NORMAL', dueAt: '', assignedTo: '' };
  const { control, handleSubmit, reset, setError: setFieldError } = useForm<CreateValues>({ resolver: zodResolver(createSchema), defaultValues: defaults });

  useEffect(() => {
    if (open) {
      reset({ ...defaults, admissionId: admissionId ?? '' });
      setError(null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, admissionId, reset]);

  const save = useMutation({
    mutationFn: (v: CreateValues) =>
      clinicalApi.createTask(v.admissionId, {
        taskType: v.taskType as TaskType,
        title: v.title.trim(),
        description: v.description?.trim() || null,
        priority: v.priority as TaskPriority,
        dueAt: v.dueAt ? localInputToIso(v.dueAt) : null,
        assignedTo: v.assignedTo || null,
      }),
    onSuccess: (t) => {
      toast.success('Task created');
      queryClient.invalidateQueries({ queryKey: ['clinical', t.admissionId] });
      queryClient.invalidateQueries({ queryKey: ['clinical-lists'] });
      onCreated?.(t);
      onClose();
    },
    onError: (e) => {
      if (!applyServerErrors(e, setFieldError)) setError(errorMessage(e));
    },
  });

  return (
    <Dialog open={open} onClose={save.isPending ? undefined : onClose} maxWidth="sm" fullWidth fullScreen={fullScreen} aria-labelledby="create-task-title">
      <DialogTitle id="create-task-title">New care task</DialogTitle>
      <DialogContent>
        <Stack spacing={4} component="form" id="create-task-form" noValidate onSubmit={handleSubmit((v) => save.mutate(v))} sx={{ pt: 2 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <Grid container spacing={3}>
            {!admissionId && (
              <Grid size={{ xs: 12 }}>
                <RHFSelect
                  control={control}
                  name="admissionId"
                  label="Patient"
                  required
                  options={patientOptions}
                  disabled={patients.isLoading}
                  helperText={patients.isLoading ? 'Loading your patients…' : patientOptions.length === 0 ? 'You have no assigned inpatients.' : undefined}
                  fullWidth
                />
              </Grid>
            )}
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFSelect control={control} name="taskType" label="Type" options={TASK_TYPES.map((t) => ({ value: t, label: humanize(t) }))} fullWidth />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFSelect control={control} name="priority" label="Priority" options={TASK_PRIORITIES.map((t) => ({ value: t, label: humanize(t) }))} fullWidth />
            </Grid>
            <Grid size={{ xs: 12 }}>
              <RHFTextField control={control} name="title" label="Task" required placeholder="e.g. Dressing change, left leg wound" fullWidth />
            </Grid>
            <Grid size={{ xs: 12 }}>
              <RHFTextField control={control} name="description" label="Details (optional)" multiline minRows={2} fullWidth />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFTextField control={control} name="dueAt" label="Due (optional)" type="datetime-local" fullWidth />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFSelect control={control} name="assignedTo" label="Assign to" emptyLabel="Unassigned (ward nurses)" options={nurses} fullWidth />
            </Grid>
          </Grid>
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={save.isPending}>
          Cancel
        </Button>
        <Button type="submit" form="create-task-form" variant="contained" disabled={save.isPending}>
          {save.isPending ? 'Saving…' : 'Create task'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

// ------------------------------------------------------------------ complete

export function CompleteTaskDialog({ task, onClose, onConfirm, loading, error }: { task: TaskView | null; onClose: () => void; onConfirm: (notes: string) => void; loading?: boolean; error?: string | null }) {
  const [notes, setNotes] = useState('');
  useEffect(() => {
    if (task) setNotes('');
  }, [task]);
  return (
    <Dialog open={!!task} onClose={loading ? undefined : onClose} maxWidth="xs" fullWidth aria-labelledby="complete-task-title">
      <DialogTitle id="complete-task-title">Complete task</DialogTitle>
      <DialogContent>
        <Stack spacing={3} sx={{ pt: 1 }}>
          <Typography variant="body2">
            <strong>{task?.title}</strong>
            {task?.patient ? ` · ${task.patient.patientName}` : ''}
          </Typography>
          <TextField
            autoFocus
            label="Completion notes (optional)"
            placeholder="What was done, findings, follow-up needed"
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            multiline
            minRows={3}
            slotProps={{ htmlInput: { maxLength: 2000 } }}
          />
          {error && <Alert severity="error">{error}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={loading}>
          Cancel
        </Button>
        <Button variant="contained" color="success" onClick={() => onConfirm(notes.trim())} disabled={loading}>
          {loading ? 'Saving…' : 'Mark done'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

// ------------------------------------------------------------------ assign

export function AssignTaskDialog({ task, onClose, onConfirm, loading }: { task: TaskView | null; onClose: () => void; onConfirm: (userId: string | null) => void; loading?: boolean }) {
  const { me } = useAuth();
  const nurses = useNurseOptions(!!task);
  const [userId, setUserId] = useState('');
  useEffect(() => {
    if (task) setUserId(task.assignedTo ?? '');
  }, [task]);
  return (
    <Dialog open={!!task} onClose={loading ? undefined : onClose} maxWidth="xs" fullWidth aria-labelledby="assign-task-title">
      <DialogTitle id="assign-task-title">Assign task</DialogTitle>
      <DialogContent>
        <Stack spacing={3} sx={{ pt: 1 }}>
          <Typography variant="body2">
            <strong>{task?.title}</strong>
            {task?.patient ? ` · ${task.patient.patientName}` : ''}
          </Typography>
          <TextField select label="Assign to" value={userId} onChange={(e) => setUserId(e.target.value)}>
            <MenuItem value="">
              <em>Unassigned (ward nurses)</em>
            </MenuItem>
            {nurses.map((n) => (
              <MenuItem key={n.value} value={n.value}>
                {n.label}
                {n.value === me?.id ? ' (me)' : ''}
              </MenuItem>
            ))}
          </TextField>
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={loading}>
          Cancel
        </Button>
        <Button variant="contained" onClick={() => onConfirm(userId || null)} disabled={loading}>
          {loading ? 'Saving…' : 'Save'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

// ------------------------------------------------------------------ shared actions

export interface TaskActions {
  start: (t: TaskView) => void;
  reopen: (t: TaskView) => void;
  complete: (t: TaskView) => void;
  cancel: (t: TaskView) => void;
  assign: (t: TaskView) => void;
  busyId: string | null;
  dialogs: ReactNode;
}

/** Start / complete / cancel / assign with their dialogs; render `dialogs` once in the page. */
export function useTaskActions(): TaskActions {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [completing, setCompleting] = useState<TaskView | null>(null);
  const [cancelling, setCancelling] = useState<TaskView | null>(null);
  const [assigning, setAssigning] = useState<TaskView | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refresh = (t: TaskView) => {
    queryClient.invalidateQueries({ queryKey: ['clinical', t.admissionId] });
    queryClient.invalidateQueries({ queryKey: ['clinical-lists'] });
  };

  const status = useMutation({
    mutationFn: ({ task, status, notes }: { task: TaskView; status: TaskStatus; notes?: string }) => clinicalApi.taskStatus(task.id, status, notes),
    onSuccess: (t) => {
      toast.success(t.status === 'IN_PROGRESS' ? 'Task started' : t.status === 'CANCELLED' ? 'Task cancelled' : 'Task returned to open');
      setCancelling(null);
      refresh(t);
    },
    onError: (e, v) => {
      toast.error(e);
      refresh(v.task);
    },
  });
  const complete = useMutation({
    mutationFn: ({ task, notes }: { task: TaskView; notes: string }) => clinicalApi.completeTask(task.id, notes || undefined),
    onSuccess: (t) => {
      toast.success('Task completed');
      setCompleting(null);
      refresh(t);
    },
    onError: (e, v) => {
      setError(errorMessage(e));
      refresh(v.task);
    },
  });
  const assign = useMutation({
    mutationFn: ({ task, userId }: { task: TaskView; userId: string | null }) => clinicalApi.assignTask(task.id, userId),
    onSuccess: (t) => {
      toast.success(t.assignedToName ? `Assigned to ${t.assignedToName}` : 'Task unassigned');
      setAssigning(null);
      refresh(t);
    },
    onError: (e, v) => {
      toast.error(e);
      refresh(v.task);
    },
  });

  const busyId = status.isPending ? status.variables?.task.id ?? null : null;

  return {
    start: (t) => status.mutate({ task: t, status: 'IN_PROGRESS' }),
    reopen: (t) => status.mutate({ task: t, status: 'OPEN' }),
    complete: (t) => {
      setError(null);
      setCompleting(t);
    },
    cancel: setCancelling,
    assign: setAssigning,
    busyId,
    dialogs: (
      <>
        <CompleteTaskDialog
          task={completing}
          onClose={() => setCompleting(null)}
          loading={complete.isPending}
          error={error}
          onConfirm={(notes) => completing && complete.mutate({ task: completing, notes })}
        />
        <AssignTaskDialog
          task={assigning}
          onClose={() => setAssigning(null)}
          loading={assign.isPending}
          onConfirm={(userId) => assigning && assign.mutate({ task: assigning, userId })}
        />
        <ConfirmDialog
          open={!!cancelling}
          title="Cancel task?"
          description={cancelling ? `${cancelling.title}${cancelling.patient ? ` · ${cancelling.patient.patientName}` : ''}` : undefined}
          requireReason
          reasonLabel="Reason for cancelling"
          tone="error"
          confirmLabel="Cancel task"
          cancelLabel="Keep task"
          loading={status.isPending}
          onConfirm={(reason) => cancelling && status.mutate({ task: cancelling, status: 'CANCELLED', notes: reason })}
          onClose={() => setCancelling(null)}
        />
      </>
    ),
  };
}
