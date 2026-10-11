'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Button,
  Card,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { ck, DoseView, FollowUpStatus, medicationApi } from '@/lib/api/clinical';
import { errorMessage } from '@/lib/api/client';
import { useAuth } from '@/lib/auth/AuthProvider';
import { useToast } from '@/lib/hooks';
import { formatDateTime } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { DataTable } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';
import { AdministerDialog, doseSig, ExceptionDialog, invalidateMedication, isPendingDose } from '@/components/clinical/mar/DoseDialogs';
import { colors } from '@/theme/tokens';

type FollowUpTarget = Exclude<FollowUpStatus, 'NONE'>;

const FILTERS: { label: string; value: FollowUpTarget[] }[] = [
  { label: 'Needs follow-up', value: ['OPEN', 'IN_PROGRESS'] },
  { label: 'Open', value: ['OPEN'] },
  { label: 'In progress', value: ['IN_PROGRESS'] },
  { label: 'Resolved', value: ['RESOLVED'] },
  { label: 'All', value: ['OPEN', 'IN_PROGRESS', 'RESOLVED'] },
];

function FollowUpDialog({ dose, onClose }: { dose: DoseView | null; onClose: () => void }) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<FollowUpTarget>('IN_PROGRESS');
  const [notes, setNotes] = useState('');
  const [touched, setTouched] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (dose) {
      setStatus(dose.followUpStatus === 'IN_PROGRESS' ? 'RESOLVED' : 'IN_PROGRESS');
      setNotes('');
      setTouched(false);
      setError(null);
    }
  }, [dose]);
  const notesRequired = status === 'RESOLVED';
  const invalid = notesRequired && notes.trim().length < 3;
  const save = useMutation({
    mutationFn: () => medicationApi.followUp(dose!.id, status, notes.trim() || undefined),
    onSuccess: (d) => {
      toast.success(`Follow-up ${status === 'RESOLVED' ? 'resolved' : 'updated'}`);
      invalidateMedication(queryClient, d.admissionId);
      onClose();
    },
    onError: (e) => setError(errorMessage(e)),
  });
  return (
    <Dialog open={!!dose} onClose={save.isPending ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="followup-title">
      <DialogTitle id="followup-title">Update follow-up</DialogTitle>
      <DialogContent>
        {dose && (
          <Stack spacing={4} sx={{ pt: 1 }}>
            <Box sx={{ border: 1, borderColor: 'divider', borderRadius: 2, p: 3, bgcolor: colors.surfaceAlt }}>
              <Typography variant="body2" fontWeight={700}>
                {dose.patient?.patientName}{' '}
                <Typography component="span" variant="caption" color="text.secondary">
                  {dose.patient?.mrn} · {dose.patient?.location ?? 'Awaiting bed'}
                </Typography>
              </Typography>
              <Typography variant="body2" sx={{ mt: 1 }}>
                {dose.medicationName} · {doseSig(dose)}
              </Typography>
              <Stack direction="row" spacing={1} alignItems="center" sx={{ mt: 1 }} flexWrap="wrap" useFlexGap>
                <StatusChip value={dose.displayStatus} />
                <Typography variant="caption">Scheduled {formatDateTime(dose.scheduledAt)}</Typography>
              </Stack>
              {dose.reason && (
                <Typography variant="caption" component="div" sx={{ mt: 1 }}>
                  Reason: {dose.reason}
                </Typography>
              )}
              {dose.followUpNotes && (
                <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 0.5 }}>
                  Previous follow-up: {dose.followUpNotes}
                  {dose.followUpBy ? ` (${dose.followUpBy}, ${formatDateTime(dose.followUpAt)})` : ''}
                </Typography>
              )}
            </Box>
            {error && <Alert severity="error">{error}</Alert>}
            <TextField select label="Follow-up status" value={status} onChange={(e) => setStatus(e.target.value as FollowUpTarget)}>
              <MenuItem value="OPEN">Open</MenuItem>
              <MenuItem value="IN_PROGRESS">In progress</MenuItem>
              <MenuItem value="RESOLVED">Resolved</MenuItem>
            </TextField>
            <TextField
              label={notesRequired ? 'How was it resolved?' : 'Notes (optional)'}
              required={notesRequired}
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              onBlur={() => setTouched(true)}
              multiline
              minRows={3}
              error={touched && invalid}
              helperText={touched && invalid ? 'Document how the exception was resolved (at least 3 characters).' : 'e.g. prescriber informed, dose rescheduled, patient counselled'}
              slotProps={{ htmlInput: { maxLength: 2000 } }}
            />
            <Typography variant="caption" color="text.secondary">
              Follow-up updates do not change the prescription.
            </Typography>
          </Stack>
        )}
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={save.isPending}>
          Cancel
        </Button>
        <Button
          variant="contained"
          disabled={save.isPending}
          onClick={() => {
            setTouched(true);
            if (!invalid) save.mutate();
          }}
        >
          {save.isPending ? 'Saving…' : 'Save follow-up'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

export default function ExceptionQueuePage() {
  const { hasPermission } = useAuth();
  const canAdminister = hasPermission('MED_ADMINISTER');
  const [filter, setFilter] = useState(0);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [followUp, setFollowUp] = useState<DoseView | null>(null);
  const [give, setGive] = useState<DoseView | null>(null);
  const [exception, setException] = useState<DoseView | null>(null);
  const params = { followUp: FILTERS[filter].value, page, size };
  const q = useQuery({ queryKey: ck.exceptions(params), queryFn: () => medicationApi.exceptions(params), placeholderData: keepPreviousData, refetchInterval: 60_000 });
  const policy = useQuery({ queryKey: ck.rounds({ hours: 4 }), queryFn: () => medicationApi.rounds({ hours: 4 }), enabled: canAdminister, staleTime: 60_000 });

  return (
    <>
      <PageHeader
        title="Medication exceptions"
        subtitle="Delayed, refused, missed, held and overdue doses that need follow-up. Recording or resolving an exception never changes the prescription."
        breadcrumbs={[{ label: 'Nursing', href: '/nurse' }, { label: 'Medication exceptions' }]}
        actions={
          <Button component={Link} href="/nurse/medications" variant="outlined">
            Medication rounds
          </Button>
        }
      />
      <Card>
        <Stack direction="row" spacing={1} sx={{ p: 4 }} flexWrap="wrap" useFlexGap role="group" aria-label="Follow-up filter">
          {FILTERS.map((f, i) => (
            <Chip
              key={f.label}
              label={f.label}
              clickable
              color={filter === i ? 'primary' : 'default'}
              variant={filter === i ? 'filled' : 'outlined'}
              onClick={() => {
                setFilter(i);
                setPage(0);
              }}
            />
          ))}
        </Stack>
        <DataTable<DoseView>
          rows={q.data?.content}
          loading={q.isFetching}
          error={q.error}
          onRetry={() => q.refetch()}
          rowKey={(d) => d.id}
          page={page}
          pageSize={size}
          total={q.data?.totalElements}
          onPageChange={setPage}
          onPageSizeChange={(s) => {
            setSize(s);
            setPage(0);
          }}
          ariaLabel="Medication exception queue"
          emptyTitle={filter === 0 ? 'No exceptions need follow-up' : 'No exceptions here'}
          emptyDescription={filter === 0 ? 'Delayed, refused, missed, held and overdue doses for your patients appear here.' : undefined}
          rowSx={(d) => (d.followUpStatus === 'OPEN' && d.displayStatus === 'OVERDUE' ? { bgcolor: colors.dangerSoft } : undefined)}
          columns={[
            {
              key: 'scheduled',
              header: 'Scheduled',
              render: (d) => (
                <Box>
                  <Typography variant="body2" sx={{ whiteSpace: 'nowrap' }}>
                    {formatDateTime(d.scheduledAt)}
                  </Typography>
                  {d.displayStatus === 'OVERDUE' && d.minutesLate > 0 && (
                    <Typography variant="caption" color="error.main">
                      {d.minutesLate} min late
                    </Typography>
                  )}
                </Box>
              ),
            },
            {
              key: 'patient',
              header: 'Patient',
              render: (d) =>
                d.patient ? (
                  <Box>
                    <Typography
                      variant="body2"
                      fontWeight={600}
                      component={Link}
                      href={`/nurse/patients/${d.admissionId}?tab=medications`}
                      sx={{ color: 'primary.main', textDecoration: 'none' }}
                    >
                      {d.patient.patientName}
                    </Typography>
                    <Typography variant="caption" color="text.secondary" component="div">
                      {d.patient.location ?? 'Awaiting bed'}
                    </Typography>
                  </Box>
                ) : (
                  '—'
                ),
            },
            {
              key: 'med',
              header: 'Medication',
              primary: true,
              render: (d) => (
                <Box sx={{ minWidth: 0 }}>
                  <Typography variant="body2" fontWeight={600}>
                    {d.medicationName}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {doseSig(d)}
                  </Typography>
                </Box>
              ),
            },
            { key: 'exception', header: 'Exception', render: (d) => <StatusChip value={d.displayStatus} /> },
            {
              key: 'reason',
              header: 'Reason',
              render: (d) => (
                <Typography variant="caption" component="div" sx={{ maxWidth: 240, wordBreak: 'break-word' }}>
                  {d.reason ?? (d.displayStatus === 'OVERDUE' ? 'Not yet recorded' : '—')}
                  {d.recordedBy ? ` · ${d.recordedBy}` : ''}
                </Typography>
              ),
            },
            { key: 'team', header: 'Responsible team', render: (d) => <Typography variant="caption">{d.responsibleTeam ?? '—'}</Typography> },
            {
              key: 'followup',
              header: 'Follow-up',
              render: (d) => (
                <Box>
                  <StatusChip value={d.followUpStatus} />
                  {d.followUpNotes && (
                    <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 0.5, maxWidth: 220, wordBreak: 'break-word' }}>
                      {d.followUpNotes}
                    </Typography>
                  )}
                </Box>
              ),
            },
            {
              key: 'actions',
              header: '',
              align: 'right',
              render: (d) => (
                <Stack direction="row" spacing={1} justifyContent="flex-end" flexWrap="wrap" useFlexGap>
                  {canAdminister && isPendingDose(d) && (!d.orderStatus || d.orderStatus === 'ACTIVE') && (
                    <Button size="small" variant="contained" onClick={() => setGive(d)}>
                      Give
                    </Button>
                  )}
                  {canAdminister && isPendingDose(d) && (
                    <Button size="small" variant="outlined" color="warning" onClick={() => setException(d)}>
                      Exception
                    </Button>
                  )}
                  {d.followUpStatus !== 'RESOLVED' && (
                    <Button size="small" variant="outlined" onClick={() => setFollowUp(d)} sx={{ whiteSpace: 'nowrap' }}>
                      Follow up
                    </Button>
                  )}
                </Stack>
              ),
            },
          ]}
        />
      </Card>
      <FollowUpDialog dose={followUp} onClose={() => setFollowUp(null)} />
      <AdministerDialog dose={give} onClose={() => setGive(null)} />
      <ExceptionDialog dose={exception} allowHold={!!policy.data?.nurseMayHoldDose} onClose={() => setException(null)} />
    </>
  );
}
