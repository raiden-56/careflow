'use client';

import { use, useState } from 'react';
import Link from 'next/link';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Card,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Grid,
  IconButton,
  MenuItem,
  Stack,
  Tab,
  Tabs,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import { ArrowLeftRight, BedDouble, FileCheck2, Trash2, UserPlus, XCircle } from 'lucide-react';
import { admissionApi, qk, staffApi } from '@/lib/api/core';
import type { BedStay, BedView, CareTeamMember, TransferView } from '@/lib/api/types';
import { useAuth } from '@/lib/auth/AuthProvider';
import { useToast } from '@/lib/hooks';
import { formatDate, formatDateTime, humanize } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { SectionCard } from '@/components/ui/SectionCard';
import { DefinitionList } from '@/components/ui/Display';
import { DataTable } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { ErrorState, PageSkeleton } from '@/components/ui/States';
import { PatientBanner } from '@/components/patient/PatientBanner';
import { BedPicker } from '@/components/beds/BedPicker';
import { AdmissionTimeline } from '@/components/timeline/AdmissionTimeline';

export default function AdmissionDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const toast = useToast();
  const queryClient = useQueryClient();
  const { hasPermission } = useAuth();
  const [tab, setTab] = useState<'overview' | 'timeline'>('overview');
  const [assignOpen, setAssignOpen] = useState(false);
  const [transferOpen, setTransferOpen] = useState(false);
  const [cancelOpen, setCancelOpen] = useState(false);
  const [teamOpen, setTeamOpen] = useState(false);
  const [bed, setBed] = useState<BedView | null>(null);
  const [transferReason, setTransferReason] = useState('');
  const [transferPriority, setTransferPriority] = useState<'ROUTINE' | 'URGENT'>('ROUTINE');
  const [member, setMember] = useState({ userId: '', careRole: 'CONSULTANT' as CareTeamMember['careRole'] });
  const [actionError, setActionError] = useState<string | null>(null);

  const q = useQuery({ queryKey: qk.admission(id), queryFn: () => admissionApi.get(id) });
  const staff = useQuery({ queryKey: qk.directory({}), queryFn: () => staffApi.directory({}), enabled: teamOpen });

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: qk.admission(id) });
    queryClient.invalidateQueries({ queryKey: ['admissions'] });
    queryClient.invalidateQueries({ queryKey: ['beds'] });
    queryClient.invalidateQueries({ queryKey: ['admission', id, 'timeline'] });
  };

  const assign = useMutation({
    mutationFn: () => admissionApi.assignBed(id, bed!.id),
    onSuccess: () => {
      toast.success('Bed assigned');
      setAssignOpen(false);
      setBed(null);
      refresh();
    },
    onError: (e) => {
      setActionError((e as Error).message);
      queryClient.invalidateQueries({ queryKey: ['beds'] });
    },
  });
  const transfer = useMutation({
    mutationFn: () => admissionApi.requestTransfer(id, { toBedId: bed?.id, toWardId: bed?.wardId, reason: transferReason, priority: transferPriority }),
    onSuccess: () => {
      toast.success('Transfer requested');
      setTransferOpen(false);
      setBed(null);
      setTransferReason('');
      refresh();
    },
    onError: (e) => setActionError((e as Error).message),
  });
  const cancel = useMutation({
    mutationFn: (reason: string) => admissionApi.cancel(id, reason),
    onSuccess: () => {
      toast.success('Admission cancelled');
      setCancelOpen(false);
      refresh();
    },
    onError: toast.error,
  });
  const addMember = useMutation({
    mutationFn: () => admissionApi.addCareTeam(id, member.userId, member.careRole),
    onSuccess: () => {
      toast.success('Care team updated');
      setTeamOpen(false);
      refresh();
    },
    onError: (e) => setActionError((e as Error).message),
  });
  const removeMember = useMutation({
    mutationFn: (memberId: string) => admissionApi.removeCareTeam(id, memberId),
    onSuccess: () => {
      toast.success('Removed from care team');
      refresh();
    },
    onError: toast.error,
  });

  if (q.isLoading) return <PageSkeleton />;
  if (q.error || !q.data) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const d = q.data;
  const a = d.summary;
  const open = ['PENDING_BED', 'ADMITTED', 'DISCHARGE_IN_PROGRESS'].includes(a.status);
  const pendingTransfer = d.transfers.find((t) => t.status === 'REQUESTED' || t.status === 'APPROVED');

  return (
    <>
      <PageHeader
        title={`Admission ${a.admissionNumber}`}
        breadcrumbs={[{ label: 'Administration', href: '/admin' }, { label: 'Admissions', href: '/admin/admissions' }, { label: a.admissionNumber }]}
        actions={
          <>
            {open && !a.location && hasPermission('BED_ASSIGN') && (
              <Button variant="contained" startIcon={<BedDouble size={16} />} onClick={() => { setActionError(null); setAssignOpen(true); }}>
                Assign bed
              </Button>
            )}
            {open && a.location && hasPermission('TRANSFER_REQUEST') && !pendingTransfer && (
              <Button variant="outlined" startIcon={<ArrowLeftRight size={16} />} onClick={() => { setActionError(null); setTransferOpen(true); }}>
                Request transfer
              </Button>
            )}
            {hasPermission('DISCHARGE_VIEW') && a.status !== 'PENDING_BED' && a.status !== 'CANCELLED' && (
              <Button component={Link} href={`/admin/discharges/${a.id}`} variant="outlined" startIcon={<FileCheck2 size={16} />}>
                Discharge workflow
              </Button>
            )}
            {(a.status === 'PENDING_BED' || a.status === 'ADMITTED') && hasPermission('ADMISSION_MANAGE') && (
              <Button color="error" startIcon={<XCircle size={16} />} onClick={() => setCancelOpen(true)}>
                Cancel
              </Button>
            )}
          </>
        }
      />
      <PatientBanner admission={a} />
      {a.status === 'PENDING_BED' && (
        <Alert severity="warning" sx={{ mb: 5 }}>
          This patient is waiting for a bed. Assign an available bed to complete the admission.
        </Alert>
      )}
      {pendingTransfer && (
        <Alert severity="info" sx={{ mb: 5 }} action={<Button component={Link} href="/admin/transfers" color="inherit" size="small">Manage</Button>}>
          Transfer {humanize(pendingTransfer.status)}: {pendingTransfer.fromLocation} → {pendingTransfer.toLocation ?? pendingTransfer.toWardName} ({pendingTransfer.reason})
        </Alert>
      )}
      <Card sx={{ mb: 5 }}>
        <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ px: 4 }}>
          <Tab value="overview" label="Overview" />
          <Tab value="timeline" label="Timeline" />
        </Tabs>
      </Card>
      {tab === 'overview' ? (
        <Grid container spacing={5}>
          <Grid size={{ xs: 12, lg: 7 }}>
            <SectionCard title="Admission">
              <DefinitionList
                items={[
                  { label: 'Type', value: humanize(a.admissionType) },
                  { label: 'Admitted', value: formatDateTime(a.admittedAt) },
                  { label: 'Department', value: a.departmentName },
                  { label: 'Treating doctor', value: a.attendingDoctorName },
                  { label: 'Expected discharge', value: formatDate(a.expectedDischargeDate) },
                  { label: 'Length of stay', value: `${a.lengthOfStayDays} day(s)` },
                  { label: 'Reason for admission', value: d.admissionReason, span: 2 },
                  { label: 'Referring doctor', value: d.referringDoctor },
                  { label: 'Bed requirements', value: d.bedRequirements ?? (a.requiresIsolation ? 'Isolation' : null) },
                  { label: 'Created by', value: `${d.createdByName ?? '—'} · ${formatDateTime(d.createdAt)}` },
                  ...(a.dischargedAt ? [{ label: 'Discharged', value: `${formatDateTime(a.dischargedAt)} (${humanize(d.dischargeDisposition)})` }] : []),
                  ...(d.cancellationReason ? [{ label: 'Cancellation reason', value: d.cancellationReason, span: 2 as const }] : []),
                ]}
              />
            </SectionCard>
          </Grid>
          <Grid size={{ xs: 12, lg: 5 }}>
            <SectionCard
              title="Care team"
              action={
                open && hasPermission('ADMISSION_MANAGE') && (
                  <Button size="small" startIcon={<UserPlus size={14} />} onClick={() => { setActionError(null); setTeamOpen(true); }}>
                    Add
                  </Button>
                )
              }
            >
              <Stack spacing={2}>
                {d.careTeam.map((m) => (
                  <Stack key={m.id} direction="row" justifyContent="space-between" alignItems="center">
                    <div>
                      <Typography variant="body2" fontWeight={600}>
                        {m.fullName}
                      </Typography>
                      <Typography variant="caption" color="text.secondary">
                        {humanize(m.careRole)} · since {formatDate(m.addedAt)}
                      </Typography>
                    </div>
                    {open && hasPermission('ADMISSION_MANAGE') && m.careRole !== 'ATTENDING' && (
                      <Tooltip title="Remove from care team">
                        <IconButton size="small" onClick={() => removeMember.mutate(m.id)} aria-label={`Remove ${m.fullName}`}>
                          <Trash2 size={16} />
                        </IconButton>
                      </Tooltip>
                    )}
                  </Stack>
                ))}
              </Stack>
            </SectionCard>
          </Grid>
          <Grid size={12}>
            <SectionCard title="Bed history" flush>
              <DataTable<BedStay>
                rows={d.bedHistory}
                rowKey={(s) => s.bedId + s.assignedAt}
                emptyTitle="No bed assigned yet"
                columns={[
                  { key: 'bed', header: 'Bed', primary: true, render: (s) => `${s.wardName} · ${s.roomNumber}-${s.bedNumber}` },
                  { key: 'from', header: 'From', render: (s) => formatDateTime(s.assignedAt) },
                  { key: 'to', header: 'To', render: (s) => (s.releasedAt ? formatDateTime(s.releasedAt) : <StatusChip value="OCCUPIED" label="Current" />) },
                  { key: 'reason', header: 'Released for', render: (s) => humanize(s.releaseReason) },
                ]}
              />
            </SectionCard>
          </Grid>
          {d.transfers.length > 0 && (
            <Grid size={12}>
              <SectionCard title="Transfers" flush>
                <DataTable<TransferView>
                  rows={d.transfers}
                  rowKey={(t) => t.id}
                  columns={[
                    { key: 'when', header: 'Requested', primary: true, render: (t) => formatDateTime(t.requestedAt) },
                    { key: 'route', header: 'Route', render: (t) => `${t.fromLocation ?? '—'} → ${t.toLocation ?? t.toWardName ?? '—'}` },
                    { key: 'reason', header: 'Reason', render: (t) => t.reason },
                    { key: 'status', header: 'Status', render: (t) => <StatusChip value={t.status} /> },
                    { key: 'by', header: 'Requested by', render: (t) => t.requestedBy },
                  ]}
                />
              </SectionCard>
            </Grid>
          )}
        </Grid>
      ) : (
        <SectionCard title="Admission timeline" subtitle="Events visible to your role. Clinical detail is restricted to the care team.">
          <AdmissionTimeline admissionId={id} />
        </SectionCard>
      )}

      <Dialog open={assignOpen} onClose={() => setAssignOpen(false)} maxWidth="md" fullWidth>
        <DialogTitle>Assign bed</DialogTitle>
        <DialogContent>
          {actionError && <Alert severity="error" sx={{ mb: 3 }}>{actionError}</Alert>}
          <BedPicker value={bed?.id} onChange={setBed} requireIsolation={a.requiresIsolation} />
        </DialogContent>
        <DialogActions sx={{ px: 6, pb: 4 }}>
          <Button color="inherit" onClick={() => setAssignOpen(false)}>
            Cancel
          </Button>
          <Button variant="contained" disabled={!bed || assign.isPending} onClick={() => assign.mutate()}>
            {assign.isPending ? 'Assigning…' : 'Assign bed'}
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={transferOpen} onClose={() => setTransferOpen(false)} maxWidth="md" fullWidth>
        <DialogTitle>Request transfer</DialogTitle>
        <DialogContent>
          <Stack spacing={4} sx={{ pt: 1 }}>
            {actionError && <Alert severity="error">{actionError}</Alert>}
            <Typography variant="body2" color="text.secondary">
              Current bed: {a.location ? `${a.location.wardName} · ${a.location.roomNumber}-${a.location.bedNumber}` : '—'}. Select a destination
              bed (bed management approves and completes the move).
            </Typography>
            <BedPicker value={bed?.id} onChange={setBed} requireIsolation={a.requiresIsolation} />
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3}>
              <TextField label="Reason" required value={transferReason} onChange={(e) => setTransferReason(e.target.value)} multiline minRows={2} />
              <TextField select label="Priority" value={transferPriority} onChange={(e) => setTransferPriority(e.target.value as 'ROUTINE' | 'URGENT')} sx={{ maxWidth: { sm: 180 } }}>
                <MenuItem value="ROUTINE">Routine</MenuItem>
                <MenuItem value="URGENT">Urgent</MenuItem>
              </TextField>
            </Stack>
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 6, pb: 4 }}>
          <Button color="inherit" onClick={() => setTransferOpen(false)}>
            Cancel
          </Button>
          <Button variant="contained" disabled={!bed || transferReason.trim().length < 3 || transfer.isPending} onClick={() => transfer.mutate()}>
            Request transfer
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={teamOpen} onClose={() => setTeamOpen(false)} maxWidth="xs" fullWidth>
        <DialogTitle>Add care team member</DialogTitle>
        <DialogContent>
          <Stack spacing={4} sx={{ pt: 1 }}>
            {actionError && <Alert severity="error">{actionError}</Alert>}
            <TextField select label="Care role" value={member.careRole} onChange={(e) => setMember({ userId: '', careRole: e.target.value as CareTeamMember['careRole'] })}>
              <MenuItem value="CONSULTANT">Consultant</MenuItem>
              <MenuItem value="RESIDENT">Resident</MenuItem>
              <MenuItem value="PRIMARY_NURSE">Primary nurse</MenuItem>
            </TextField>
            <TextField select label="Staff member" value={member.userId} onChange={(e) => setMember((m) => ({ ...m, userId: e.target.value }))}>
              {(staff.data ?? [])
                .filter((s) => (member.careRole === 'PRIMARY_NURSE' ? s.role === 'NURSE' : s.role === 'DOCTOR'))
                .filter((s) => !d.careTeam.some((m) => m.userId === s.id))
                .map((s) => (
                  <MenuItem key={s.id} value={s.id}>
                    {s.fullName} {s.departmentName ? `· ${s.departmentName}` : ''}
                  </MenuItem>
                ))}
            </TextField>
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 6, pb: 4 }}>
          <Button color="inherit" onClick={() => setTeamOpen(false)}>
            Cancel
          </Button>
          <Button variant="contained" disabled={!member.userId || addMember.isPending} onClick={() => addMember.mutate()}>
            Add
          </Button>
        </DialogActions>
      </Dialog>

      <ConfirmDialog
        open={cancelOpen}
        title="Cancel this admission?"
        description="Use only for admissions created in error. Any assigned bed is released for cleaning. This is recorded in the audit log."
        tone="error"
        confirmLabel="Cancel admission"
        requireReason
        loading={cancel.isPending}
        onConfirm={(reason) => cancel.mutate(reason!)}
        onClose={() => setCancelOpen(false)}
      />
    </>
  );
}
