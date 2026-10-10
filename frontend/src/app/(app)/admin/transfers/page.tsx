'use client';

import { useState } from 'react';
import Link from 'next/link';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Box, Button, Card, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Stack, TextField, Typography } from '@mui/material';
import { transferApi, qk } from '@/lib/api/core';
import type { BedView, TransferStatus, TransferView } from '@/lib/api/types';
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

export default function TransfersPage() {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [filter, setFilter] = useState(0);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [approve, setApprove] = useState<TransferView | null>(null);
  const [reject, setReject] = useState<TransferView | null>(null);
  const [complete, setComplete] = useState<TransferView | null>(null);
  const [bed, setBed] = useState<BedView | null>(null);
  const [notes, setNotes] = useState('');
  const [error, setError] = useState<string | null>(null);
  const params = { status: FILTERS[filter].value, page, size };
  const q = useQuery({ queryKey: qk.transfers(params), queryFn: () => transferApi.list(params), placeholderData: keepPreviousData });
  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['transfers'] });
    queryClient.invalidateQueries({ queryKey: ['beds'] });
    queryClient.invalidateQueries({ queryKey: ['admissions'] });
  };

  const approveM = useMutation({
    mutationFn: () => transferApi.approve(approve!.id, bed?.id ?? approve!.toBedId, notes || undefined),
    onSuccess: () => {
      toast.success('Transfer approved');
      setApprove(null);
      refresh();
    },
    onError: (e) => setError((e as Error).message),
  });
  const completeM = useMutation({
    mutationFn: (t: TransferView) => transferApi.complete(t.id),
    onSuccess: (t) => {
      toast.success(`${t.patientName} moved to ${t.toLocation}`);
      setComplete(null);
      refresh();
    },
    onError: (e) => {
      toast.error(e);
      refresh();
    },
  });
  const rejectM = useMutation({
    mutationFn: (reason: string) => transferApi.reject(reject!.id, reason),
    onSuccess: () => {
      toast.success('Transfer rejected');
      setReject(null);
      refresh();
    },
    onError: toast.error,
  });

  return (
    <>
      <PageHeader
        title="Transfers"
        subtitle="Approve destination beds and complete moves. Completion releases the old bed for cleaning in the same transaction."
        breadcrumbs={[{ label: 'Administration', href: '/admin' }, { label: 'Transfers' }]}
      />
      <Card>
        <Stack direction="row" spacing={1} sx={{ p: 4 }} flexWrap="wrap" useFlexGap>
          {FILTERS.map((f, i) => (
            <Chip key={f.label} label={f.label} clickable color={filter === i ? 'primary' : 'default'} variant={filter === i ? 'filled' : 'outlined'} onClick={() => { setFilter(i); setPage(0); }} />
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
          onPageSizeChange={setSize}
          emptyTitle="No transfers here"
          columns={[
            {
              key: 'patient',
              header: 'Patient',
              primary: true,
              render: (t) => (
                <Box>
                  <Typography variant="body2" fontWeight={600} component={Link} href={`/admin/admissions/${t.admissionId}`} sx={{ color: 'inherit' }}>
                    {t.patientName}
                  </Typography>
                  <Typography variant="caption" color="text.secondary" component="div">
                    {t.patientMrn} · {t.admissionNumber}
                  </Typography>
                </Box>
              ),
            },
            { key: 'route', header: 'From → to', render: (t) => <Box><Typography variant="body2">{t.fromLocation}</Typography><Typography variant="caption" color="text.secondary">→ {t.toLocation ?? t.toWardName ?? 'bed to be chosen'}</Typography></Box> },
            { key: 'reason', header: 'Reason', hideOnMobile: true, render: (t) => t.reason },
            { key: 'priority', header: 'Priority', render: (t) => <StatusChip value={t.priority} /> },
            { key: 'status', header: 'Status', render: (t) => <StatusChip value={t.status} /> },
            { key: 'req', header: 'Requested', render: (t) => <Box><Typography variant="body2">{formatDateTime(t.requestedAt)}</Typography><Typography variant="caption" color="text.secondary">{t.requestedBy}</Typography></Box> },
            {
              key: 'actions',
              header: '',
              align: 'right',
              render: (t) => (
                <Stack direction="row" spacing={1} justifyContent="flex-end">
                  {t.status === 'REQUESTED' && (
                    <Button size="small" variant="outlined" onClick={() => { setError(null); setBed(null); setNotes(''); setApprove(t); }}>
                      Approve
                    </Button>
                  )}
                  {t.status === 'APPROVED' && (
                    <Button size="small" variant="contained" onClick={() => setComplete(t)}>
                      Complete move
                    </Button>
                  )}
                  {(t.status === 'REQUESTED' || t.status === 'APPROVED') && (
                    <Button size="small" color="error" onClick={() => setReject(t)}>
                      Reject
                    </Button>
                  )}
                </Stack>
              ),
            },
          ]}
        />
      </Card>

      <Dialog open={!!approve} onClose={() => setApprove(null)} maxWidth="md" fullWidth>
        <DialogTitle>Approve transfer — {approve?.patientName}</DialogTitle>
        <DialogContent>
          <Stack spacing={4} sx={{ pt: 1 }}>
            {error && <Alert severity="error">{error}</Alert>}
            <Typography variant="body2" color="text.secondary">
              Requested destination: {approve?.toLocation ?? approve?.toWardName ?? 'not specified'}. Confirm it or choose another available bed.
            </Typography>
            <BedPicker value={bed?.id ?? approve?.toBedId} onChange={setBed} defaultWardId={approve?.toWardId} />
            <TextField label="Notes (optional)" value={notes} onChange={(e) => setNotes(e.target.value)} />
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 6, pb: 4 }}>
          <Button color="inherit" onClick={() => setApprove(null)}>
            Cancel
          </Button>
          <Button variant="contained" disabled={(!bed && !approve?.toBedId) || approveM.isPending} onClick={() => approveM.mutate()}>
            Approve
          </Button>
        </DialogActions>
      </Dialog>
      <ConfirmDialog
        open={!!complete}
        title="Complete transfer?"
        description={`${complete?.patientName} moves from ${complete?.fromLocation} to ${complete?.toLocation}. The previous bed is set to cleaning.`}
        confirmLabel="Complete move"
        loading={completeM.isPending}
        onConfirm={() => complete && completeM.mutate(complete)}
        onClose={() => setComplete(null)}
      />
      <ConfirmDialog
        open={!!reject}
        title="Reject transfer?"
        tone="error"
        requireReason
        confirmLabel="Reject"
        loading={rejectM.isPending}
        onConfirm={(r) => rejectM.mutate(r!)}
        onClose={() => setReject(null)}
      />
    </>
  );
}
