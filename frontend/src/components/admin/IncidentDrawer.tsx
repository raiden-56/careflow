'use client';

import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Button,
  Divider,
  Drawer,
  IconButton,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { X } from 'lucide-react';
import { incidentApi, IncidentStatus } from '@/lib/api/admin';
import { errorMessage } from '@/lib/api/client';
import { useToast } from '@/lib/hooks';
import { formatDateTime, humanize } from '@/lib/format';
import { statusMeta } from '@/lib/status';
import { StatusChip } from '@/components/ui/StatusChip';
import { DefinitionList } from '@/components/ui/Display';
import { ErrorState, TableSkeleton } from '@/components/ui/States';

/** Incident detail with audit history; reviewers also get assignment and review-workflow controls. */
export function IncidentDrawer({ id, onClose }: { id: string | null; onClose: () => void }) {
  const toast = useToast();
  const qc = useQueryClient();
  const detail = useQuery({ queryKey: ['incidents', 'detail', id], queryFn: () => incidentApi.get(id!), enabled: !!id });
  const history = useQuery({ queryKey: ['incidents', 'history', id], queryFn: () => incidentApi.history(id!), enabled: !!id });
  const canReview = detail.data?.canReview ?? false;
  const reviewers = useQuery({ queryKey: ['incidents', 'reviewers'], queryFn: incidentApi.reviewers, enabled: !!id && canReview });
  const [target, setTarget] = useState<IncidentStatus | ''>('');
  const [notes, setNotes] = useState('');
  const [actions, setActions] = useState('');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setTarget('');
    setNotes('');
    setActions('');
    setError(null);
  }, [id, detail.data?.status]);

  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['incidents'] });
  };
  const assign = useMutation({
    mutationFn: (reviewerId: string) => incidentApi.assign(id!, reviewerId),
    onSuccess: (d) => {
      toast.success(`Assigned to ${d.reviewerName}`);
      refresh();
    },
    onError: toast.error,
  });
  const transition = useMutation({
    mutationFn: () =>
      incidentApi.transition(id!, {
        status: target as IncidentStatus,
        reviewNotes: notes.trim() || undefined,
        correctiveActions: actions.trim() || undefined,
        version: detail.data!.version,
      }),
    onSuccess: (d) => {
      toast.success(`${d.incidentNumber} is now ${statusMeta(d.status).label.toLowerCase()}`);
      refresh();
    },
    onError: (e) => setError(errorMessage(e)),
  });

  const d = detail.data;
  return (
    <Drawer anchor="right" open={!!id} onClose={onClose} slotProps={{ paper: { sx: { width: { xs: '100%', sm: 560 } } } }}>
      <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ px: 5, py: 4 }}>
        <Box>
          <Typography variant="h4" component="h2">
            {d ? d.incidentNumber : 'Incident'}
          </Typography>
          {d && (
            <Typography variant="caption" color="text.secondary">
              {humanize(d.incidentType)} · reported {formatDateTime(d.reportedAt)}
            </Typography>
          )}
        </Box>
        <IconButton aria-label="Close" onClick={onClose}>
          <X size={18} />
        </IconButton>
      </Stack>
      <Divider />
      <Box sx={{ p: 5, overflowY: 'auto' }}>
        {detail.isLoading ? (
          <TableSkeleton rows={8} columns={2} />
        ) : detail.error ? (
          <ErrorState error={detail.error} />
        ) : d ? (
          <Stack spacing={5}>
            <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
              <StatusChip value={d.status} size="medium" />
              <StatusChip value={d.severity} size="medium" />
            </Stack>
            <DefinitionList
              items={[
                { label: 'Occurred', value: formatDateTime(d.occurredAt) },
                { label: 'Location', value: [d.wardName, d.location].filter(Boolean).join(' · ') || '—' },
                { label: 'Patient', value: d.patientName ? `${d.patientName}${d.patientMrn ? ' (' + d.patientMrn + ')' : ''}` : 'Not linked' },
                { label: 'Admission', value: d.admissionNumber ?? '—' },
                { label: 'Medication event', value: d.medicationEvent ?? '—', span: 2 },
                { label: 'Reported by', value: `${d.reportedByName ?? '—'}${d.reportedByRole ? ' (' + humanize(d.reportedByRole) + ')' : ''}` },
                { label: 'Reviewer', value: d.reviewerName ?? 'Not assigned' },
                { label: 'What happened', value: <Box component="span" sx={{ whiteSpace: 'pre-wrap' }}>{d.description}</Box>, span: 2 },
                { label: 'Immediate actions', value: d.immediateActions ?? '—', span: 2 },
                { label: 'Review findings', value: d.reviewNotes ?? '—', span: 2 },
                { label: 'Corrective actions', value: d.correctiveActions ?? '—', span: 2 },
              ]}
            />

            {canReview && (
              <Box>
                <Typography variant="subtitle2" sx={{ mb: 2 }}>
                  Review
                </Typography>
                <Stack spacing={3}>
                  {d.status !== 'CLOSED' && (
                    <TextField
                      select
                      label="Reviewer"
                      value={d.reviewerId ?? ''}
                      onChange={(e) => e.target.value && assign.mutate(e.target.value)}
                      disabled={assign.isPending}
                      helperText="Only staff whose role can review incidents are listed."
                    >
                      <MenuItem value="" disabled>
                        Select a reviewer
                      </MenuItem>
                      {(reviewers.data ?? []).map((r) => (
                        <MenuItem key={r.id} value={r.id}>
                          {r.fullName} · {humanize(r.role)}
                        </MenuItem>
                      ))}
                    </TextField>
                  )}
                  {d.allowedTransitions.length > 0 && (
                    <>
                      <TextField select label="Move to" value={target} onChange={(e) => setTarget(e.target.value as IncidentStatus)}>
                        {d.allowedTransitions.map((s) => (
                          <MenuItem key={s} value={s}>
                            {statusMeta(s).label}
                            {d.status === 'CLOSED' ? ' (reopen)' : ''}
                          </MenuItem>
                        ))}
                      </TextField>
                      {target && (
                        <>
                          <TextField
                            label={d.status === 'CLOSED' ? 'Reason for reopening' : 'Review findings'}
                            value={notes}
                            onChange={(e) => setNotes(e.target.value)}
                            multiline
                            minRows={3}
                            required={target === 'CLOSED' && !d.reviewNotes}
                            helperText={d.reviewNotes ? 'Leave blank to keep the current findings.' : undefined}
                          />
                          {(target === 'ACTION_REQUIRED' || target === 'CLOSED') && (
                            <TextField
                              label="Corrective actions"
                              value={actions}
                              onChange={(e) => setActions(e.target.value)}
                              multiline
                              minRows={3}
                              required={!d.correctiveActions}
                              helperText={d.correctiveActions ? 'Leave blank to keep the current actions.' : 'Required for this step.'}
                            />
                          )}
                          {error && <Alert severity="error">{error}</Alert>}
                          <Stack direction="row" justifyContent="flex-end">
                            <Button variant="contained" disabled={transition.isPending} onClick={() => { setError(null); transition.mutate(); }}>
                              {target === 'CLOSED' ? 'Close incident' : `Move to ${statusMeta(target).label.toLowerCase()}`}
                            </Button>
                          </Stack>
                        </>
                      )}
                    </>
                  )}
                </Stack>
              </Box>
            )}

            <Box>
              <Typography variant="subtitle2" sx={{ mb: 2 }}>
                Audit trail
              </Typography>
              {history.data?.length ? (
                <Stack spacing={2.5} component="ol" sx={{ m: 0, pl: 0, listStyle: 'none' }}>
                  {history.data.map((h, i) => (
                    <Box component="li" key={i} sx={{ borderLeft: 2, borderColor: 'divider', pl: 3 }}>
                      <Typography variant="body2" fontWeight={500}>
                        {humanize(h.action.replace('INCIDENT_', ''))}
                      </Typography>
                      <Typography variant="caption" color="text.secondary" component="div">
                        {formatDateTime(h.at)} · {h.actorName ?? 'System'}
                      </Typography>
                      {h.summary && (
                        <Typography variant="caption" component="div" sx={{ wordBreak: 'break-word' }}>
                          {h.summary}
                        </Typography>
                      )}
                    </Box>
                  ))}
                </Stack>
              ) : (
                <Typography variant="body2" color="text.secondary">
                  No audited changes yet.
                </Typography>
              )}
            </Box>
          </Stack>
        ) : null}
      </Box>
    </Drawer>
  );
}
