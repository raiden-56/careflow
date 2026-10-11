'use client';

import { useMemo, useState } from 'react';
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
  Grid,
  MenuItem,
  Stack,
  TextField,
  Typography,
  useMediaQuery,
  useTheme,
} from '@mui/material';
import { ArrowLeftRight } from 'lucide-react';
import { admissionApi, facilityApi, qk, transferApi } from '@/lib/api/core';
import type { BedView, TransferStatus, TransferView } from '@/lib/api/types';
import { errorMessage } from '@/lib/api/client';
import { useAuth, useMe } from '@/lib/auth/AuthProvider';
import { useToast } from '@/lib/hooks';
import { formatDateTime } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { DataTable } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { BedPicker } from '@/components/beds/BedPicker';

const FILTERS: { label: string; value: TransferStatus[] }[] = [
  { label: 'Open', value: ['REQUESTED', 'APPROVED'] },
  { label: 'Completed', value: ['COMPLETED'] },
  { label: 'Rejected / cancelled', value: ['REJECTED', 'CANCELLED'] },
  { label: 'All', value: [] },
];

function RequestTransferDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down('sm'));
  const [admissionId, setAdmissionId] = useState('');
  const [wardId, setWardId] = useState('');
  const [bed, setBed] = useState<BedView | null>(null);
  const [reason, setReason] = useState('');
  const [priority, setPriority] = useState<'ROUTINE' | 'URGENT'>('ROUTINE');
  const [touched, setTouched] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const patients = useQuery({ queryKey: qk.myAdmissions, queryFn: admissionApi.my, enabled: open });
  const wards = useQuery({ queryKey: qk.wards, queryFn: facilityApi.wards, enabled: open });
  const eligible = useMemo(() => (patients.data ?? []).filter((a) => a.status === 'ADMITTED' && a.location), [patients.data]);
  const selected = eligible.find((a) => a.id === admissionId);

  const reset = () => {
    setAdmissionId('');
    setWardId('');
    setBed(null);
    setReason('');
    setPriority('ROUTINE');
    setTouched(false);
    setError(null);
  };
  const close = () => {
    reset();
    onClose();
  };

  const reasonInvalid = reason.trim().length < 3;
  const destinationMissing = !bed && !wardId;
  const sameBed = !!bed && bed.id === selected?.location?.bedId;
  const invalid = !admissionId || reasonInvalid || destinationMissing || sameBed;

  const request = useMutation({
    mutationFn: () =>
      admissionApi.requestTransfer(admissionId, {
        toBedId: bed?.id,
        toWardId: bed?.wardId ?? (wardId || undefined),
        reason: reason.trim(),
        priority,
      }),
    onSuccess: () => {
      toast.success('Transfer requested. Bed management will review it.');
      queryClient.invalidateQueries({ queryKey: ['transfers'] });
      queryClient.invalidateQueries({ queryKey: ['admissions'] });
      queryClient.invalidateQueries({ queryKey: ['admission', admissionId] });
      close();
    },
    onError: (e) => setError(errorMessage(e)),
  });

  return (
    <Dialog open={open} onClose={request.isPending ? undefined : close} maxWidth="md" fullWidth fullScreen={fullScreen} aria-labelledby="request-transfer-title">
      <DialogTitle id="request-transfer-title">Request a transfer</DialogTitle>
      <DialogContent>
        <Stack spacing={4} sx={{ pt: 2 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <Grid container spacing={3}>
            <Grid size={{ xs: 12, md: 6 }}>
              <TextField
                select
                fullWidth
                required
                label="Patient"
                value={admissionId}
                onChange={(e) => setAdmissionId(e.target.value)}
                error={touched && !admissionId}
                helperText={
                  patients.isLoading
                    ? 'Loading your patients…'
                    : eligible.length === 0
                      ? 'None of your patients currently occupies a bed.'
                      : touched && !admissionId
                        ? 'Choose a patient'
                        : 'Admitted patients with a bed'
                }
              >
                {eligible.map((a) => (
                  <MenuItem key={a.id} value={a.id}>
                    {a.patient.fullName} · {a.location?.wardName} {a.location?.roomNumber}-{a.location?.bedNumber}
                  </MenuItem>
                ))}
              </TextField>
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <TextField select fullWidth label="Priority" value={priority} onChange={(e) => setPriority(e.target.value as 'ROUTINE' | 'URGENT')}>
                <MenuItem value="ROUTINE">Routine</MenuItem>
                <MenuItem value="URGENT">Urgent</MenuItem>
              </TextField>
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <TextField
                select
                fullWidth
                label="Destination ward"
                value={bed ? bed.wardId : wardId}
                disabled={!!bed}
                onChange={(e) => setWardId(e.target.value)}
                error={touched && destinationMissing}
                helperText={bed ? 'From the chosen bed' : touched && destinationMissing ? 'Choose a ward or a bed' : 'Or pick a specific bed below'}
              >
                <MenuItem value="">
                  <em>Not specified</em>
                </MenuItem>
                {(wards.data ?? [])
                  .filter((w) => w.active)
                  .map((w) => (
                    <MenuItem key={w.id} value={w.id}>
                      {w.name}
                    </MenuItem>
                  ))}
              </TextField>
            </Grid>
          </Grid>
          {selected?.location && (
            <Typography variant="body2" color="text.secondary">
              Current location: {selected.location.wardName} · {selected.location.roomNumber}-{selected.location.bedNumber}
            </Typography>
          )}
          <Box>
            <Typography variant="body2" fontWeight={600} sx={{ mb: 2 }}>
              Destination bed (optional)
            </Typography>
            <BedPicker value={bed?.id ?? null} onChange={setBed} defaultWardId={wardId || undefined} />
            {sameBed && (
              <Typography variant="caption" color="error.main">
                The patient is already in this bed.
              </Typography>
            )}
          </Box>
          <TextField
            label="Clinical reason"
            required
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            multiline
            minRows={2}
            error={touched && reasonInvalid}
            helperText={touched && reasonInvalid ? 'Document the reason (at least 3 characters).' : 'e.g. step-down from ICU, requires isolation, closer monitoring'}
            slotProps={{ htmlInput: { maxLength: 1000 } }}
          />
          <Typography variant="caption" color="text.secondary">
            The request is reviewed by bed management. The patient stays in the current bed until the move is completed.
          </Typography>
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={close} disabled={request.isPending}>
          Cancel
        </Button>
        <Button
          variant="contained"
          disabled={request.isPending}
          onClick={() => {
            setTouched(true);
            if (!invalid) request.mutate();
          }}
        >
          {request.isPending ? 'Sending…' : 'Request transfer'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

export default function NurseTransfersPage() {
  const me = useMe();
  const { hasPermission } = useAuth();
  const toast = useToast();
  const queryClient = useQueryClient();
  const canRequest = hasPermission('TRANSFER_REQUEST');
  const [filter, setFilter] = useState(0);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [requestOpen, setRequestOpen] = useState(false);
  const [cancelling, setCancelling] = useState<TransferView | null>(null);
  const params = { status: FILTERS[filter].value, page, size };
  const q = useQuery({ queryKey: qk.transfers(params), queryFn: () => transferApi.list(params), placeholderData: keepPreviousData });

  const cancel = useMutation({
    mutationFn: ({ t, notes }: { t: TransferView; notes: string }) => transferApi.cancel(t.id, notes),
    onSuccess: () => {
      toast.success('Transfer request cancelled');
      setCancelling(null);
      queryClient.invalidateQueries({ queryKey: ['transfers'] });
      queryClient.invalidateQueries({ queryKey: ['admissions'] });
    },
    onError: (e) => {
      toast.error(e);
      queryClient.invalidateQueries({ queryKey: ['transfers'] });
    },
  });

  return (
    <>
      <PageHeader
        title="Transfers"
        subtitle="Request ward or bed transfers for your patients and follow their progress."
        breadcrumbs={[{ label: 'Nursing', href: '/nurse' }, { label: 'Transfers' }]}
        actions={
          canRequest && (
            <Button variant="contained" startIcon={<ArrowLeftRight size={16} />} onClick={() => setRequestOpen(true)}>
              Request transfer
            </Button>
          )
        }
      />
      <Card>
        <Stack direction="row" spacing={1} sx={{ p: 4 }} flexWrap="wrap" useFlexGap role="group" aria-label="Status filter">
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
        <DataTable<TransferView>
          rows={q.data?.content}
          loading={q.isFetching}
          error={q.error}
          onRetry={() => q.refetch()}
          rowKey={(t) => t.id}
          page={page}
          pageSize={size}
          total={q.data?.totalElements}
          onPageChange={setPage}
          onPageSizeChange={(s) => {
            setSize(s);
            setPage(0);
          }}
          ariaLabel="Transfer requests"
          emptyTitle={filter === 0 ? 'No open transfer requests' : 'No transfers here'}
          emptyDescription={filter === 0 && canRequest ? 'Requests you or colleagues make for your patients appear here.' : undefined}
          columns={[
            {
              key: 'patient',
              header: 'Patient',
              primary: true,
              render: (t) => (
                <Box>
                  <Typography variant="body2" fontWeight={600} component={Link} href={`/nurse/patients/${t.admissionId}`} sx={{ color: 'primary.main', textDecoration: 'none' }}>
                    {t.patientName}
                  </Typography>
                  <Typography variant="caption" color="text.secondary" component="div">
                    {t.patientMrn} · {t.admissionNumber}
                  </Typography>
                </Box>
              ),
            },
            {
              key: 'route',
              header: 'From → to',
              render: (t) => (
                <Box>
                  <Typography variant="body2">{t.fromLocation ?? '—'}</Typography>
                  <Typography variant="caption" color="text.secondary">
                    → {t.toLocation ?? t.toWardName ?? 'bed to be chosen'}
                  </Typography>
                </Box>
              ),
            },
            {
              key: 'reason',
              header: 'Reason',
              render: (t) => (
                <Typography variant="body2" sx={{ maxWidth: 280, wordBreak: 'break-word' }}>
                  {t.reason}
                </Typography>
              ),
            },
            { key: 'priority', header: 'Priority', render: (t) => <StatusChip value={t.priority} /> },
            {
              key: 'status',
              header: 'Status',
              render: (t) => (
                <Box>
                  <StatusChip value={t.status} />
                  {t.decisionNotes && (
                    <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 0.5, maxWidth: 220, wordBreak: 'break-word' }}>
                      {t.decidedBy ? `${t.decidedBy}: ` : ''}
                      {t.decisionNotes}
                    </Typography>
                  )}
                </Box>
              ),
            },
            {
              key: 'requested',
              header: 'Requested',
              render: (t) => (
                <Box>
                  <Typography variant="body2" sx={{ whiteSpace: 'nowrap' }}>
                    {formatDateTime(t.requestedAt)}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {t.requestedBy}
                  </Typography>
                </Box>
              ),
            },
            {
              key: 'actions',
              header: '',
              align: 'right',
              render: (t) =>
                t.status === 'REQUESTED' && t.requestedBy === me.fullName ? (
                  <Button size="small" color="error" onClick={() => setCancelling(t)}>
                    Cancel request
                  </Button>
                ) : null,
            },
          ]}
        />
      </Card>

      <RequestTransferDialog open={requestOpen} onClose={() => setRequestOpen(false)} />
      <ConfirmDialog
        open={!!cancelling}
        title="Cancel transfer request?"
        description={cancelling ? `${cancelling.patientName}: ${cancelling.fromLocation ?? ''} → ${cancelling.toLocation ?? cancelling.toWardName ?? 'unspecified'}` : undefined}
        requireReason
        reasonLabel="Reason for cancelling"
        tone="error"
        confirmLabel="Cancel request"
        cancelLabel="Keep request"
        loading={cancel.isPending}
        onConfirm={(notes) => cancelling && cancel.mutate({ t: cancelling, notes: notes! })}
        onClose={() => setCancelling(null)}
      />
    </>
  );
}
