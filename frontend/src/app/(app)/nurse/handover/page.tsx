'use client';

import { Suspense, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import dayjs from 'dayjs';
import { useRouter, useSearchParams } from 'next/navigation';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  AlertTitle,
  Box,
  Button,
  Card,
  CardActionArea,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  Grid,
  MenuItem,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';
import { ArrowLeft, ArrowRight, BellRing, Plus, RefreshCw, Repeat2, Save, Send } from 'lucide-react';
import { ck, clinicalApi, HandoverItemUpdate, HandoverItemView, HandoverPriority, HandoverView, Shift } from '@/lib/api/clinical';
import { ApiError, errorMessage } from '@/lib/api/client';
import { useMe } from '@/lib/auth/AuthProvider';
import { useToast } from '@/lib/hooks';
import { formatDate, formatDateTime, nowInHospitalTz, todayLocalDate } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { SectionCard } from '@/components/ui/SectionCard';
import { StatusChip } from '@/components/ui/StatusChip';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { EmptyState, ErrorState, PageSkeleton } from '@/components/ui/States';
import { colors } from '@/theme/tokens';

const SHIFTS: { value: Shift; label: string }[] = [
  { value: 'MORNING', label: 'Morning' },
  { value: 'EVENING', label: 'Evening' },
  { value: 'NIGHT', label: 'Night' },
];
const shiftLabel = (s: Shift) => SHIFTS.find((x) => x.value === s)?.label ?? s;
const PRIORITIES: HandoverPriority[] = ['NORMAL', 'WATCH', 'CRITICAL'];

type ItemField = 'conditionSummary' | 'importantObservations' | 'pendingMedications' | 'pendingTasks' | 'outstandingItems' | 'escalations';
const ITEM_FIELDS: { key: ItemField; label: string }[] = [
  { key: 'conditionSummary', label: 'Condition summary' },
  { key: 'importantObservations', label: 'Important observations' },
  { key: 'pendingMedications', label: 'Pending medications' },
  { key: 'pendingTasks', label: 'Pending tasks' },
  { key: 'outstandingItems', label: 'Outstanding items' },
  { key: 'escalations', label: 'Escalations / alerts' },
];

const priorityAccent = (p: HandoverPriority) => (p === 'CRITICAL' ? colors.danger : p === 'WATCH' ? colors.warning : colors.border);

function currentShift(): Shift {
  const h = nowInHospitalTz().hour();
  return h >= 7 && h < 14 ? 'MORNING' : h >= 14 && h < 21 ? 'EVENING' : 'NIGHT';
}

// ------------------------------------------------------------------ list

function HandoverCard({ h, meId, onOpen }: { h: HandoverView; meId: string; onOpen: () => void }) {
  const awaitingMe = h.status === 'SUBMITTED' && h.toUserId === meId;
  return (
    <Card sx={{ borderColor: awaitingMe ? colors.warning : undefined, borderWidth: awaitingMe ? 2 : 1, bgcolor: awaitingMe ? colors.warningSoft : undefined }}>
      <CardActionArea onClick={onOpen} sx={{ p: 4 }}>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} justifyContent="space-between" alignItems={{ sm: 'center' }}>
          <Box sx={{ minWidth: 0 }}>
            <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
              <Typography variant="body2" fontWeight={700}>
                {h.wardName} · {shiftLabel(h.shift)} shift, {formatDate(h.shiftDate)}
              </Typography>
              <StatusChip value={h.status} />
              {awaitingMe && <Chip size="small" color="warning" icon={<BellRing size={13} />} label="Awaiting your acknowledgement" />}
            </Stack>
            <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
              {h.fromUserName ?? '—'} <ArrowRight size={12} style={{ verticalAlign: 'middle' }} aria-label="to" /> {h.toUserName ?? 'receiver not chosen'}
            </Typography>
            <Typography variant="caption" color="text.secondary" component="div">
              {h.itemCount} patient{h.itemCount === 1 ? '' : 's'}
              {h.criticalCount > 0 ? ` · ${h.criticalCount} critical` : ''}
              {h.submittedAt ? ` · submitted ${formatDateTime(h.submittedAt)}` : ` · started ${formatDateTime(h.createdAt)}`}
              {h.acknowledgedAt ? ` · acknowledged ${formatDateTime(h.acknowledgedAt)}` : ''}
            </Typography>
          </Box>
          {h.criticalCount > 0 && <StatusChip value="CRITICAL" label={`${h.criticalCount} critical`} />}
        </Stack>
      </CardActionArea>
    </Card>
  );
}

function CreateHandoverDialog({ open, onClose, onCreated }: { open: boolean; onClose: () => void; onCreated: (h: HandoverView) => void }) {
  const me = useMe();
  const toast = useToast();
  const queryClient = useQueryClient();
  const wards = me.staff?.wards ?? [];
  const today = todayLocalDate();
  const dates = [
    { value: dayjs(today).subtract(1, 'day').format('YYYY-MM-DD'), label: 'Yesterday' },
    { value: today, label: 'Today' },
    { value: dayjs(today).add(1, 'day').format('YYYY-MM-DD'), label: 'Tomorrow' },
  ];
  const [wardId, setWardId] = useState('');
  const [shiftDate, setShiftDate] = useState(today);
  const [shift, setShift] = useState<Shift>('MORNING');
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (open) {
      setWardId(wards[0]?.id ?? '');
      setShiftDate(todayLocalDate());
      setShift(currentShift());
      setError(null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);
  const create = useMutation({
    mutationFn: () => clinicalApi.createHandover({ wardId, shiftDate, shift }),
    onSuccess: (h) => {
      toast.success('Handover drafted from current records');
      queryClient.invalidateQueries({ queryKey: ['clinical-lists', 'handovers'] });
      onCreated(h);
    },
    onError: (e) => setError(errorMessage(e)),
  });
  return (
    <Dialog open={open} onClose={create.isPending ? undefined : onClose} maxWidth="xs" fullWidth aria-labelledby="create-handover-title">
      <DialogTitle id="create-handover-title">Start a shift handover</DialogTitle>
      <DialogContent>
        <Stack spacing={3} sx={{ pt: 1 }}>
          <Typography variant="body2" color="text.secondary">
            Each patient on the ward is pre-filled from live records (observations, pending doses, tasks, alerts). Review and edit before
            submitting.
          </Typography>
          {error && <Alert severity="error">{error}</Alert>}
          <TextField select label="Ward" value={wardId} onChange={(e) => setWardId(e.target.value)} required>
            {wards.map((w) => (
              <MenuItem key={w.id} value={w.id}>
                {w.name}
              </MenuItem>
            ))}
          </TextField>
          <TextField select label="Shift date" value={shiftDate} onChange={(e) => setShiftDate(e.target.value)}>
            {dates.map((d) => (
              <MenuItem key={d.value} value={d.value}>
                {d.label} · {formatDate(d.value)}
              </MenuItem>
            ))}
          </TextField>
          <TextField select label="Shift being handed over" value={shift} onChange={(e) => setShift(e.target.value as Shift)}>
            {SHIFTS.map((s) => (
              <MenuItem key={s.value} value={s.value}>
                {s.label}
              </MenuItem>
            ))}
          </TextField>
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={create.isPending}>
          Cancel
        </Button>
        <Button variant="contained" onClick={() => create.mutate()} disabled={!wardId || create.isPending}>
          {create.isPending ? 'Preparing…' : 'Create draft'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function HandoverList({ onOpen }: { onOpen: (id: string) => void }) {
  const me = useMe();
  const wards = me.staff?.wards ?? [];
  const [wardId, setWardId] = useState('');
  const [toMe, setToMe] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const params = { wardId: wardId || undefined, toMe: toMe || undefined, size: 50 };
  const q = useQuery({ queryKey: ck.handovers(params), queryFn: () => clinicalApi.handovers(params), placeholderData: keepPreviousData });
  const list = q.data?.content ?? [];
  const awaiting = list.filter((h) => h.status === 'SUBMITTED' && h.toUserId === me.id);
  const drafts = list.filter((h) => h.status === 'DRAFT');
  const others = list.filter((h) => !awaiting.includes(h) && !drafts.includes(h));

  return (
    <>
      <PageHeader
        title="Shift handover"
        subtitle="Structured nurse-to-nurse handover for your wards, pre-filled from live patient records."
        breadcrumbs={[{ label: 'Nursing', href: '/nurse' }, { label: 'Shift handover' }]}
        actions={
          wards.length > 0 && (
            <Button variant="contained" startIcon={<Plus size={16} />} onClick={() => setCreateOpen(true)}>
              New handover
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
        <FormControlLabel control={<Switch checked={toMe} onChange={(e) => setToMe(e.target.checked)} />} label="Addressed to me" sx={{ whiteSpace: 'nowrap' }} />
      </Stack>

      {q.isLoading ? (
        <PageSkeleton />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      ) : list.length === 0 ? (
        <Card>
          <EmptyState
            icon={Repeat2}
            title={wards.length === 0 ? 'No ward assignment' : 'No handovers yet'}
            description={wards.length === 0 ? 'You are not assigned to a ward. Contact the nursing office.' : 'Start a handover at the end of your shift.'}
          />
        </Card>
      ) : (
        <Stack spacing={5}>
          {awaiting.length > 0 && (
            <Box component="section" aria-label="Awaiting your acknowledgement">
              <Alert severity="warning" icon={<BellRing size={18} />} sx={{ mb: 3 }}>
                <AlertTitle>
                  {awaiting.length} handover{awaiting.length === 1 ? '' : 's'} awaiting your acknowledgement
                </AlertTitle>
                Read each patient summary, then acknowledge to confirm you have taken over care.
              </Alert>
              <Stack spacing={2}>
                {awaiting.map((h) => (
                  <HandoverCard key={h.id} h={h} meId={me.id} onOpen={() => onOpen(h.id)} />
                ))}
              </Stack>
            </Box>
          )}
          {drafts.length > 0 && (
            <Box component="section">
              <Typography variant="overline" color="text.secondary" component="h2" sx={{ display: 'block', mb: 1.5 }}>
                Your drafts
              </Typography>
              <Stack spacing={2}>
                {drafts.map((h) => (
                  <HandoverCard key={h.id} h={h} meId={me.id} onOpen={() => onOpen(h.id)} />
                ))}
              </Stack>
            </Box>
          )}
          {others.length > 0 && (
            <Box component="section">
              <Typography variant="overline" color="text.secondary" component="h2" sx={{ display: 'block', mb: 1.5 }}>
                Recent handovers
              </Typography>
              <Stack spacing={2}>
                {others.map((h) => (
                  <HandoverCard key={h.id} h={h} meId={me.id} onOpen={() => onOpen(h.id)} />
                ))}
              </Stack>
            </Box>
          )}
        </Stack>
      )}
      <CreateHandoverDialog open={createOpen} onClose={() => setCreateOpen(false)} onCreated={(h) => { setCreateOpen(false); onOpen(h.id); }} />
    </>
  );
}

// ------------------------------------------------------------------ detail

type ItemDraft = Pick<HandoverItemUpdate, 'id'> & Record<ItemField, string> & { priority: HandoverPriority };

const toDraft = (it: HandoverItemView): ItemDraft => ({
  id: it.id,
  conditionSummary: it.conditionSummary ?? '',
  importantObservations: it.importantObservations ?? '',
  pendingMedications: it.pendingMedications ?? '',
  pendingTasks: it.pendingTasks ?? '',
  outstandingItems: it.outstandingItems ?? '',
  escalations: it.escalations ?? '',
  priority: it.priority,
});

function PatientHeading({ item }: { item: HandoverItemView }) {
  return (
    <Box sx={{ minWidth: 0 }}>
      {item.patient ? (
        <Typography variant="body1" fontWeight={700}>
          <Box component={Link} href={`/nurse/patients/${item.admissionId}`} sx={{ color: 'inherit', textDecoration: 'none', '&:hover': { color: 'primary.main' } }}>
            {item.patient.patientName}
          </Box>
        </Typography>
      ) : (
        <Typography variant="body1" fontWeight={700}>
          Patient
        </Typography>
      )}
      <Typography variant="caption" color="text.secondary" component="div">
        {[item.patient?.location ?? 'Awaiting bed', item.patient?.mrn, item.patient?.attendingDoctorName].filter(Boolean).join(' · ')}
      </Typography>
      {item.patient?.allergies && item.patient.allergies.length > 0 && (
        <Typography variant="caption" color="warning.main" component="div" sx={{ fontWeight: 600 }}>
          Allergies: {item.patient.allergies.join(', ')}
        </Typography>
      )}
    </Box>
  );
}

function ReadOnlyItem({ item }: { item: HandoverItemView }) {
  return (
    <Card sx={{ borderLeft: `4px solid ${priorityAccent(item.priority)}` }}>
      <Box sx={{ p: 4 }}>
        <Stack direction="row" spacing={2} justifyContent="space-between" alignItems="flex-start">
          <PatientHeading item={item} />
          <StatusChip value={item.priority} />
        </Stack>
        <Box sx={{ mt: 3, display: 'grid', gap: 3, gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' } }}>
          {ITEM_FIELDS.map((f) => (
            <Box key={f.key} sx={{ minWidth: 0 }}>
              <Typography variant="caption" color="text.secondary" component="div">
                {f.label}
              </Typography>
              <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                {item[f.key] || '—'}
              </Typography>
            </Box>
          ))}
        </Box>
      </Box>
    </Card>
  );
}

function HandoverDetail({ id, onBack }: { id: string; onBack: () => void }) {
  const me = useMe();
  const toast = useToast();
  const queryClient = useQueryClient();
  const q = useQuery({ queryKey: ck.handover(id), queryFn: () => clinicalApi.handover(id) });
  const h = q.data;
  const editable = !!h && h.status === 'DRAFT' && h.fromUserId === me.id;
  const canAck = !!h && h.status === 'SUBMITTED' && h.toUserId === me.id;
  const receivers = useQuery({ queryKey: ck.receivingNurses(h?.wardId ?? ''), queryFn: () => clinicalApi.receivingNurses(h!.wardId), enabled: editable });

  const [items, setItems] = useState<ItemDraft[]>([]);
  const [generalNotes, setGeneralNotes] = useState('');
  const [toUserId, setToUserId] = useState('');
  const [version, setVersion] = useState(0);
  const [dirty, setDirty] = useState(false);
  const [submitOpen, setSubmitOpen] = useState(false);
  const [ackOpen, setAckOpen] = useState(false);
  const [ackComment, setAckComment] = useState('');
  const [refreshing, setRefreshing] = useState<HandoverItemView | null>(null);

  const load = (v: HandoverView) => {
    setItems(v.items.map(toDraft));
    setGeneralNotes(v.generalNotes ?? '');
    setToUserId(v.toUserId ?? '');
    setVersion(v.version);
    setDirty(false);
  };
  useEffect(() => {
    if (h) load(h);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [h?.id, h?.version, h?.status]);

  const after = (v: HandoverView) => {
    queryClient.setQueryData(ck.handover(id), v);
    queryClient.invalidateQueries({ queryKey: ['clinical-lists', 'handovers'] });
    load(v);
  };
  const onConflict = (e: unknown) => {
    toast.error(e);
    if (e instanceof ApiError && e.status === 409) q.refetch();
  };

  const saveDraft = () =>
    clinicalApi.updateHandover(id, {
      generalNotes: generalNotes.trim() || null,
      toUserId: toUserId || null,
      items: items.map((i) => ({ ...i })),
      version,
    });

  const save = useMutation({
    mutationFn: saveDraft,
    onSuccess: (v) => {
      toast.success('Draft saved');
      after(v);
    },
    onError: onConflict,
  });
  const refresh = useMutation({
    mutationFn: async (item: HandoverItemView) => {
      if (dirty) await saveDraft();
      return clinicalApi.refreshHandoverItem(id, item.id);
    },
    onSuccess: (v) => {
      toast.success('Patient summary refreshed from current records');
      setRefreshing(null);
      after(v);
    },
    onError: (e) => {
      setRefreshing(null);
      onConflict(e);
    },
  });
  const submit = useMutation({
    mutationFn: async () => {
      if (dirty) await saveDraft();
      return clinicalApi.submitHandover(id, toUserId);
    },
    onSuccess: (v) => {
      toast.success(`Handover submitted to ${v.toUserName ?? 'the receiving nurse'}`);
      setSubmitOpen(false);
      after(v);
      queryClient.invalidateQueries({ queryKey: ['notifications'] });
    },
    onError: (e) => {
      setSubmitOpen(false);
      onConflict(e);
    },
  });
  const acknowledge = useMutation({
    mutationFn: () => clinicalApi.acknowledgeHandover(id, ackComment.trim() || undefined),
    onSuccess: (v) => {
      toast.success('Handover acknowledged');
      setAckOpen(false);
      after(v);
      queryClient.invalidateQueries({ queryKey: ['notifications'] });
      queryClient.invalidateQueries({ queryKey: ck.nurseDashboard });
    },
    onError: (e) => {
      setAckOpen(false);
      onConflict(e);
    },
  });

  const update = (itemId: string, patch: Partial<ItemDraft>) => {
    setItems((cur) => cur.map((i) => (i.id === itemId ? { ...i, ...patch } : i)));
    setDirty(true);
  };
  const receiverName = useMemo(() => receivers.data?.find((r) => r.id === toUserId)?.fullName ?? h?.toUserName, [receivers.data, toUserId, h?.toUserName]);
  const sortedItems = useMemo(() => {
    const order = { CRITICAL: 0, WATCH: 1, NORMAL: 2 } as const;
    return (h?.items ?? []).slice().sort((a, b) => order[a.priority] - order[b.priority] || (a.patient?.location ?? '').localeCompare(b.patient?.location ?? ''));
  }, [h?.items]);

  const crumbs = [{ label: 'Nursing', href: '/nurse' }, { label: 'Shift handover', href: '/nurse/handover' }, { label: h ? `${h.wardName} · ${shiftLabel(h.shift)}` : 'Handover' }];
  const back = (
    <Button color="inherit" startIcon={<ArrowLeft size={16} />} onClick={onBack}>
      All handovers
    </Button>
  );

  if (q.isLoading) return <PageSkeleton />;
  if (q.error || !h) {
    return (
      <>
        <PageHeader title="Shift handover" breadcrumbs={crumbs} actions={back} />
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      </>
    );
  }

  const busy = save.isPending || submit.isPending || refresh.isPending;

  return (
    <>
      <PageHeader
        title={`${h.wardName} · ${shiftLabel(h.shift)} shift`}
        subtitle={
          <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
            <StatusChip value={h.status} />
            <span>
              {formatDate(h.shiftDate)} · from {h.fromUserName ?? '—'}
              {h.toUserName ? ` to ${h.toUserName}` : ''}
              {h.submittedAt ? ` · submitted ${formatDateTime(h.submittedAt)}` : ''}
            </span>
          </Stack>
        }
        breadcrumbs={crumbs}
        actions={
          <>
            {back}
            {editable && (
              <Button variant="outlined" startIcon={<Save size={16} />} disabled={busy || !dirty} onClick={() => save.mutate()}>
                {save.isPending ? 'Saving…' : 'Save draft'}
              </Button>
            )}
            {editable && (
              <Button variant="contained" startIcon={<Send size={16} />} disabled={busy || !toUserId} onClick={() => setSubmitOpen(true)}>
                Submit
              </Button>
            )}
            {canAck && (
              <Button variant="contained" color="warning" onClick={() => { setAckComment(''); setAckOpen(true); }}>
                Acknowledge
              </Button>
            )}
          </>
        }
      />

      <Stack spacing={5}>
        {canAck && (
          <Alert severity="warning" icon={<BellRing size={18} />}>
            <AlertTitle>Handover addressed to you</AlertTitle>
            Review every patient below, then acknowledge to confirm you have taken over their care.
          </Alert>
        )}
        {h.status === 'ACKNOWLEDGED' && (
          <Alert severity="success">
            Acknowledged by {h.acknowledgedBy ?? h.toUserName} on {formatDateTime(h.acknowledgedAt)}
            {h.ackComment ? `: "${h.ackComment}"` : '.'}
          </Alert>
        )}

        {editable ? (
          <SectionCard title="Handover details">
            <Grid container spacing={3}>
              <Grid size={{ xs: 12, md: 4 }}>
                <TextField
                  select
                  fullWidth
                  label="Receiving nurse"
                  required
                  value={toUserId}
                  onChange={(e) => {
                    setToUserId(e.target.value);
                    setDirty(true);
                  }}
                  helperText={receivers.data && receivers.data.length === 0 ? 'No other nurse is assigned to this ward.' : 'Nurses assigned to this ward'}
                >
                  {(receivers.data ?? []).map((r) => (
                    <MenuItem key={r.id} value={r.id}>
                      {r.fullName}
                    </MenuItem>
                  ))}
                </TextField>
              </Grid>
              <Grid size={{ xs: 12, md: 8 }}>
                <TextField
                  fullWidth
                  label="General notes for the ward"
                  value={generalNotes}
                  onChange={(e) => {
                    setGeneralNotes(e.target.value);
                    setDirty(true);
                  }}
                  multiline
                  minRows={2}
                  slotProps={{ htmlInput: { maxLength: 4000 } }}
                />
              </Grid>
            </Grid>
            {dirty && (
              <Typography variant="caption" color="warning.main" component="div" sx={{ mt: 2 }}>
                Unsaved changes. They are saved automatically when you submit.
              </Typography>
            )}
          </SectionCard>
        ) : (
          h.generalNotes && (
            <SectionCard title="General notes">
              <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                {h.generalNotes}
              </Typography>
            </SectionCard>
          )
        )}

        <Box component="section" aria-label="Patients">
          <Typography variant="overline" color="text.secondary" component="h2" sx={{ display: 'block', mb: 2 }}>
            Patients ({h.items.length}){h.criticalCount > 0 ? ` · ${h.criticalCount} critical` : ''}
          </Typography>
          {h.items.length === 0 ? (
            <Card>
              <EmptyState title="No patients on this ward" compact />
            </Card>
          ) : (
            <Stack spacing={3}>
              {sortedItems.map((item) => {
                if (!editable) return <ReadOnlyItem key={item.id} item={item} />;
                const d = items.find((x) => x.id === item.id);
                if (!d) return null;
                return (
                  <Card key={item.id} sx={{ borderLeft: `4px solid ${priorityAccent(d.priority)}` }}>
                    <Box sx={{ p: 4 }}>
                      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} justifyContent="space-between" alignItems={{ sm: 'flex-start' }}>
                        <PatientHeading item={item} />
                        <Stack direction="row" spacing={2} alignItems="center" sx={{ flexShrink: 0 }}>
                          <TextField
                            select
                            size="small"
                            label="Priority"
                            value={d.priority}
                            onChange={(e) => update(item.id, { priority: e.target.value as HandoverPriority })}
                            sx={{ minWidth: 130 }}
                          >
                            {PRIORITIES.map((p) => (
                              <MenuItem key={p} value={p}>
                                {p === 'NORMAL' ? 'Normal' : p === 'WATCH' ? 'Watch' : 'Critical'}
                              </MenuItem>
                            ))}
                          </TextField>
                          <Button size="small" color="inherit" startIcon={<RefreshCw size={14} />} disabled={busy} onClick={() => setRefreshing(item)}>
                            Refresh
                          </Button>
                        </Stack>
                      </Stack>
                      <Grid container spacing={3} sx={{ mt: 3 }}>
                        {ITEM_FIELDS.map((f) => (
                          <Grid key={f.key} size={{ xs: 12, md: 6 }}>
                            <TextField
                              fullWidth
                              label={f.label}
                              value={d[f.key]}
                              onChange={(e) => update(item.id, { [f.key]: e.target.value } as Partial<ItemDraft>)}
                              multiline
                              minRows={2}
                              slotProps={{ htmlInput: { maxLength: 4000 } }}
                            />
                          </Grid>
                        ))}
                      </Grid>
                    </Box>
                  </Card>
                );
              })}
            </Stack>
          )}
        </Box>
      </Stack>

      <ConfirmDialog
        open={submitOpen}
        title="Submit handover?"
        description={`The handover for ${h.wardName} (${shiftLabel(h.shift)}, ${formatDate(h.shiftDate)}) is sent to ${receiverName ?? 'the receiving nurse'}, who must acknowledge it. It cannot be edited after submission.`}
        confirmLabel="Submit handover"
        loading={submit.isPending}
        onConfirm={() => submit.mutate()}
        onClose={() => setSubmitOpen(false)}
      />
      <ConfirmDialog
        open={ackOpen}
        title="Acknowledge handover"
        description={`Confirm you have received the ${shiftLabel(h.shift).toLowerCase()} shift handover for ${h.wardName} from ${h.fromUserName ?? 'the handing-over nurse'}.`}
        confirmLabel="Acknowledge"
        tone="warning"
        loading={acknowledge.isPending}
        onConfirm={() => acknowledge.mutate()}
        onClose={() => setAckOpen(false)}
      >
        <TextField
          fullWidth
          label="Comment (optional)"
          placeholder="e.g. Received at bedside; all patients reviewed"
          value={ackComment}
          onChange={(e) => setAckComment(e.target.value)}
          multiline
          minRows={2}
          slotProps={{ htmlInput: { maxLength: 1000 } }}
          sx={{ mt: 1 }}
        />
      </ConfirmDialog>
      <ConfirmDialog
        open={!!refreshing}
        title="Refresh from current records?"
        description={`The summary for ${refreshing?.patient?.patientName ?? 'this patient'} is rebuilt from live observations, doses, tasks and alerts. Edits to this patient's entry are replaced.`}
        confirmLabel="Refresh"
        loading={refresh.isPending}
        onConfirm={() => refreshing && refresh.mutate(refreshing)}
        onClose={() => setRefreshing(null)}
      />
    </>
  );
}

// ------------------------------------------------------------------ page

function HandoverPageInner() {
  const router = useRouter();
  const params = useSearchParams();
  const id = params.get('id');
  if (id) return <HandoverDetail key={id} id={id} onBack={() => router.push('/nurse/handover')} />;
  return <HandoverList onOpen={(hid) => router.push(`/nurse/handover?id=${hid}`)} />;
}

export default function HandoverPage() {
  return (
    <Suspense fallback={<PageSkeleton />}>
      <HandoverPageInner />
    </Suspense>
  );
}
