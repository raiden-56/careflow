'use client';

import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Autocomplete,
  Box,
  Button,
  Card,
  Chip,
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
import { FileSignature, Inbox, MessageSquareReply, Plus, ShieldCheck, ShieldOff, Timer } from 'lucide-react';
import {
  CaptureMethod,
  ConsentStatus,
  ConsentType,
  ConsentView,
  PatientRequestStatus,
  PatientRequestType,
  PatientRequestView,
  privacyApi,
} from '@/lib/api/admin';
import { patientApi } from '@/lib/api/core';
import type { PatientSummary } from '@/lib/api/types';
import { useAuth } from '@/lib/auth/AuthProvider';
import { applyServerErrors, useDebouncedValue, useToast } from '@/lib/hooks';
import { errorMessage } from '@/lib/api/client';
import { formatDate, formatDateTime, humanize, isoToLocalInput, localInputToIso } from '@/lib/format';
import { optionalText } from '@/lib/validation';
import { PageHeader } from '@/components/ui/PageHeader';
import { KpiCard } from '@/components/ui/KpiCard';
import { DataTable } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { DefinitionList } from '@/components/ui/Display';
import { RHFSelect, RHFTextField } from '@/components/forms/Fields';

const CONSENT_TYPES: ConsentType[] = ['TREATMENT', 'PROCEDURE', 'DATA_SHARING', 'CAREGIVER_ACCESS', 'COMMUNICATION', 'RESEARCH', 'INSURANCE_SHARING'];
const REQUEST_TYPES: PatientRequestType[] = ['RECORD_COPY', 'CORRECTION', 'RESTRICT_SHARING', 'WITHDRAW_CONSENT', 'ACCESS_REVIEW', 'OTHER'];

export default function PrivacyPage() {
  const { hasPermission } = useAuth();
  const canRequests = hasPermission('PATIENT_REQUEST_MANAGE');
  const canConsents = hasPermission('CONSENT_MANAGE');
  const [tab, setTab] = useState<'requests' | 'consents'>(canRequests ? 'requests' : 'consents');
  const stats = useQuery({ queryKey: ['privacy', 'stats'], queryFn: privacyApi.stats });
  return (
    <>
      <PageHeader
        title="Consent & patient requests"
        subtitle="Record and withdraw consent, and respond to patients' requests about their records. Patients follow progress in their portal."
        breadcrumbs={[{ label: 'Administration', href: '/admin' }, { label: 'Consent & requests' }]}
      />
      <Grid container spacing={4} sx={{ mb: 5 }}>
        <Grid size={{ xs: 6, lg: 3 }}>
          <KpiCard label="Open requests" value={stats.data?.requestsOpen ?? 0} icon={Inbox} tone="warning" loading={stats.isLoading} />
        </Grid>
        <Grid size={{ xs: 6, lg: 3 }}>
          <KpiCard label="In review" value={stats.data?.requestsInReview ?? 0} icon={Timer} tone="info" loading={stats.isLoading} hint={stats.data ? `${stats.data.requestsClosedLast30Days} closed in 30 days` : undefined} />
        </Grid>
        <Grid size={{ xs: 6, lg: 3 }}>
          <KpiCard label="Active consents" value={stats.data?.consentsGranted ?? 0} icon={ShieldCheck} tone="success" loading={stats.isLoading} />
        </Grid>
        <Grid size={{ xs: 6, lg: 3 }}>
          <KpiCard label="Withdrawn consents" value={stats.data?.consentsWithdrawn ?? 0} icon={ShieldOff} tone="neutral" loading={stats.isLoading} />
        </Grid>
      </Grid>
      <Card>
        <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ px: 4, borderBottom: 1, borderColor: 'divider' }}>
          {canRequests && <Tab value="requests" label="Patient requests" />}
          {canConsents && <Tab value="consents" label="Consent records" />}
        </Tabs>
        {tab === 'requests' && canRequests && <RequestsTab />}
        {tab === 'consents' && canConsents && <ConsentsTab />}
      </Card>
    </>
  );
}

// ================================================================== requests

function RequestsTab() {
  const toast = useToast();
  const qc = useQueryClient();
  const [status, setStatus] = useState<PatientRequestStatus | ''>('OPEN');
  const [type, setType] = useState('');
  const [mrn, setMrn] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [selected, setSelected] = useState<PatientRequestView | null>(null);
  const dMrn = useDebouncedValue(mrn, 350);
  const params = { status: status || undefined, type: type || undefined, patientMrn: dMrn || undefined, page, size };
  const q = useQuery({ queryKey: ['privacy', 'requests', params], queryFn: () => privacyApi.requests(params), placeholderData: keepPreviousData });
  return (
    <>
      <Stack direction={{ xs: 'column', md: 'row' }} spacing={3} sx={{ p: 4 }} alignItems={{ md: 'center' }}>
        <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
          {(['OPEN', 'IN_REVIEW', 'COMPLETED', 'REJECTED', ''] as const).map((s) => (
            <Chip key={s || 'all'} label={s ? humanize(s) : 'All'} clickable color={status === s ? 'primary' : 'default'} variant={status === s ? 'filled' : 'outlined'} onClick={() => { setStatus(s); setPage(0); }} />
          ))}
        </Stack>
        <TextField select label="Type" value={type} onChange={(e) => { setType(e.target.value); setPage(0); }} sx={{ minWidth: 200 }}>
          <MenuItem value="">Any type</MenuItem>
          {REQUEST_TYPES.map((t) => (
            <MenuItem key={t} value={t}>
              {humanize(t)}
            </MenuItem>
          ))}
        </TextField>
        <TextField label="Patient MRN" value={mrn} onChange={(e) => { setMrn(e.target.value); setPage(0); }} sx={{ minWidth: 180 }} />
      </Stack>
      <DataTable<PatientRequestView>
        ariaLabel="Patient requests"
        rows={q.data?.content}
        loading={q.isFetching}
        error={q.error}
        onRetry={() => q.refetch()}
        rowKey={(r) => r.id}
        onRowClick={setSelected}
        page={page}
        pageSize={size}
        total={q.data?.totalElements}
        onPageChange={setPage}
        onPageSizeChange={setSize}
        emptyTitle="No requests here"
        columns={[
          {
            key: 'subject',
            header: 'Request',
            primary: true,
            render: (r) => (
              <Box>
                <Typography variant="body2" fontWeight={600}>
                  {r.subject}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {humanize(r.requestType)}
                </Typography>
              </Box>
            ),
          },
          { key: 'patient', header: 'Patient', render: (r) => `${r.patientName ?? '—'} · ${r.patientMrn ?? ''}` },
          { key: 'raised', header: 'Raised', render: (r) => `${formatDateTime(r.raisedAt)} · ${r.raisedByName ?? ''}` },
          { key: 'status', header: 'Status', render: (r) => <StatusChip value={r.status} /> },
          {
            key: 'act',
            header: '',
            align: 'right',
            hideOnMobile: true, // the whole card opens the response dialog on mobile
            render: (r) =>
              r.status === 'OPEN' || r.status === 'IN_REVIEW' ? (
                <Button size="small" startIcon={<MessageSquareReply size={14} />} onClick={(e) => { e.stopPropagation(); setSelected(r); }}>
                  Respond
                </Button>
              ) : null,
          },
        ]}
      />
      <RespondDialog
        request={selected}
        onClose={() => setSelected(null)}
        onDone={(r) => {
          toast.success(`Request marked ${humanize(r.status).toLowerCase()} — the patient has been notified`);
          setSelected(null);
          qc.invalidateQueries({ queryKey: ['privacy'] });
        }}
      />
    </>
  );
}

function RespondDialog({ request, onClose, onDone }: { request: PatientRequestView | null; onClose: () => void; onDone: (r: PatientRequestView) => void }) {
  const [status, setStatus] = useState<PatientRequestStatus>('IN_REVIEW');
  const [response, setResponse] = useState('');
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    setStatus(request?.status === 'IN_REVIEW' ? 'COMPLETED' : 'IN_REVIEW');
    setResponse(request?.response ?? '');
    setError(null);
  }, [request]);
  const closed = request && (request.status === 'COMPLETED' || request.status === 'REJECTED');
  const m = useMutation({
    mutationFn: () => privacyApi.respond(request!.id, { status, response: response.trim(), version: request!.version }),
    onSuccess: onDone,
    onError: (e) => setError(errorMessage(e)),
  });
  return (
    <Dialog open={!!request} onClose={m.isPending ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="respond-title">
      <DialogTitle id="respond-title">{request?.subject}</DialogTitle>
      <DialogContent>
        {request && (
          <Stack spacing={4} sx={{ pt: 1 }}>
            <DefinitionList
              items={[
                { label: 'Patient', value: `${request.patientName} (${request.patientMrn})` },
                { label: 'Type', value: humanize(request.requestType) },
                { label: 'Raised', value: `${formatDateTime(request.raisedAt)} by ${request.raisedByName}` },
                { label: 'Status', value: <StatusChip value={request.status} /> },
                { label: 'Request', value: <Box component="span" sx={{ whiteSpace: 'pre-wrap' }}>{request.description}</Box>, span: 2 },
                ...(request.response ? [{ label: `Last response${request.respondedByName ? ' · ' + request.respondedByName : ''}`, value: request.response, span: 2 as const }] : []),
              ]}
            />
            {!closed && (
              <>
                <TextField select label="Outcome" value={status} onChange={(e) => setStatus(e.target.value as PatientRequestStatus)}>
                  <MenuItem value="IN_REVIEW">In review (acknowledge)</MenuItem>
                  <MenuItem value="COMPLETED">Completed</MenuItem>
                  <MenuItem value="REJECTED">Rejected</MenuItem>
                </TextField>
                <TextField
                  label="Response to the patient"
                  value={response}
                  onChange={(e) => setResponse(e.target.value)}
                  multiline
                  minRows={3}
                  required
                  error={!!response && response.trim().length < 3}
                  helperText="Shown to the patient in the portal. Explain the outcome or next step in plain language."
                />
                {error && <Alert severity="error">{error}</Alert>}
              </>
            )}
          </Stack>
        )}
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={m.isPending}>
          {closed ? 'Close' : 'Cancel'}
        </Button>
        {!closed && (
          <Button variant="contained" disabled={m.isPending || response.trim().length < 3} onClick={() => m.mutate()}>
            Send response
          </Button>
        )}
      </DialogActions>
    </Dialog>
  );
}

// ================================================================== consents

function ConsentsTab() {
  const toast = useToast();
  const qc = useQueryClient();
  const [status, setStatus] = useState<ConsentStatus | ''>('');
  const [type, setType] = useState('');
  const [mrn, setMrn] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [recordOpen, setRecordOpen] = useState(false);
  const [withdraw, setWithdraw] = useState<ConsentView | null>(null);
  const [detail, setDetail] = useState<ConsentView | null>(null);
  const dMrn = useDebouncedValue(mrn, 350);
  const params = { status: status || undefined, type: type || undefined, patientMrn: dMrn || undefined, page, size };
  const q = useQuery({ queryKey: ['privacy', 'consents', params], queryFn: () => privacyApi.consents(params), placeholderData: keepPreviousData });
  const withdrawM = useMutation({
    mutationFn: ({ c, reason }: { c: ConsentView; reason: string }) => privacyApi.withdrawConsent(c.id, reason),
    onSuccess: () => {
      toast.success('Consent withdrawn');
      setWithdraw(null);
      qc.invalidateQueries({ queryKey: ['privacy'] });
    },
    onError: toast.error,
  });
  return (
    <>
      <Stack direction={{ xs: 'column', md: 'row' }} spacing={3} sx={{ p: 4 }} alignItems={{ md: 'center' }}>
        <TextField select label="Status" value={status} onChange={(e) => { setStatus(e.target.value as ConsentStatus); setPage(0); }} sx={{ minWidth: 160 }}>
          <MenuItem value="">Any status</MenuItem>
          {(['GRANTED', 'WITHDRAWN', 'EXPIRED', 'DECLINED'] as const).map((s) => (
            <MenuItem key={s} value={s}>
              {humanize(s)}
            </MenuItem>
          ))}
        </TextField>
        <TextField select label="Type" value={type} onChange={(e) => { setType(e.target.value); setPage(0); }} sx={{ minWidth: 200 }}>
          <MenuItem value="">Any type</MenuItem>
          {CONSENT_TYPES.map((t) => (
            <MenuItem key={t} value={t}>
              {humanize(t)}
            </MenuItem>
          ))}
        </TextField>
        <TextField label="Patient MRN" value={mrn} onChange={(e) => { setMrn(e.target.value); setPage(0); }} sx={{ minWidth: 180 }} />
        <Box sx={{ flex: 1 }} />
        <Button variant="contained" startIcon={<Plus size={16} />} onClick={() => setRecordOpen(true)}>
          Record consent
        </Button>
      </Stack>
      <DataTable<ConsentView>
        ariaLabel="Consent records"
        rows={q.data?.content}
        loading={q.isFetching}
        error={q.error}
        onRetry={() => q.refetch()}
        rowKey={(c) => c.id}
        onRowClick={setDetail}
        page={page}
        pageSize={size}
        total={q.data?.totalElements}
        onPageChange={setPage}
        onPageSizeChange={setSize}
        emptyTitle="No consent records match"
        columns={[
          {
            key: 'type',
            header: 'Consent',
            primary: true,
            render: (c) => (
              <Box>
                <Typography variant="body2" fontWeight={600}>
                  {humanize(c.consentType)}
                </Typography>
                <Typography variant="caption" color="text.secondary" sx={{ display: 'block', maxWidth: 360 }}>
                  {c.purpose}
                </Typography>
              </Box>
            ),
          },
          { key: 'patient', header: 'Patient', render: (c) => `${c.patientName ?? '—'} · ${c.patientMrn ?? ''}` },
          { key: 'with', header: 'Shared with', hideOnMobile: true, render: (c) => c.sharedWith ?? '—' },
          { key: 'date', header: 'Recorded', render: (c) => `${formatDate(c.grantedAt)} · ${humanize(c.captureMethod)}` },
          { key: 'status', header: 'Status', render: (c) => <StatusChip value={c.effectiveStatus} /> },
          {
            key: 'act',
            header: '',
            align: 'right',
            hideOnMobile: true, // on mobile, withdrawal is offered in the detail dialog opened by the card
            render: (c) =>
              c.effectiveStatus === 'GRANTED' ? (
                <Tooltip title="Withdraw consent">
                  <IconButton size="small" aria-label={`Withdraw ${c.consentType} consent for ${c.patientName}`} onClick={(e) => { e.stopPropagation(); setWithdraw(c); }}>
                    <ShieldOff size={16} />
                  </IconButton>
                </Tooltip>
              ) : null,
          },
        ]}
      />
      <RecordConsentDialog
        open={recordOpen}
        onClose={() => setRecordOpen(false)}
        onSaved={() => {
          toast.success('Consent recorded');
          setRecordOpen(false);
          qc.invalidateQueries({ queryKey: ['privacy'] });
        }}
      />
      <ConfirmDialog
        open={!!withdraw}
        title="Withdraw this consent?"
        description={withdraw ? `${humanize(withdraw.consentType)} consent for ${withdraw.patientName}. Withdrawal is recorded with the reason and cannot be undone — a new consent must be recorded if the patient agrees again.` : ''}
        tone="error"
        requireReason
        reasonLabel="Reason for withdrawal"
        confirmLabel="Withdraw"
        loading={withdrawM.isPending}
        onConfirm={(reason) => withdraw && withdrawM.mutate({ c: withdraw, reason: reason! })}
        onClose={() => setWithdraw(null)}
      />
      <Dialog open={!!detail} onClose={() => setDetail(null)} maxWidth="sm" fullWidth aria-labelledby="consent-title">
        <DialogTitle id="consent-title">{detail ? `${humanize(detail.consentType)} consent` : ''}</DialogTitle>
        <DialogContent>
          {detail && (
            <DefinitionList
              items={[
                { label: 'Patient', value: `${detail.patientName} (${detail.patientMrn})` },
                { label: 'Status', value: <StatusChip value={detail.effectiveStatus} /> },
                { label: 'Purpose', value: detail.purpose, span: 2 },
                { label: 'Shared with', value: detail.sharedWith },
                { label: 'Admission', value: detail.admissionNumber },
                { label: 'Recorded', value: formatDateTime(detail.grantedAt) },
                { label: 'Method', value: humanize(detail.captureMethod) },
                { label: 'Captured by', value: detail.capturedByName ?? 'Patient (portal)' },
                { label: 'Expires', value: detail.expiresAt ? formatDate(detail.expiresAt) : 'No expiry' },
                ...(detail.withdrawnAt ? [{ label: 'Withdrawn', value: `${formatDateTime(detail.withdrawnAt)} — ${detail.withdrawalReason ?? ''}`, span: 2 as const }] : []),
                { label: 'Notes', value: detail.notes, span: 2 },
              ]}
            />
          )}
        </DialogContent>
        <DialogActions sx={{ px: 6, pb: 4 }}>
          {detail?.effectiveStatus === 'GRANTED' && (
            <Button
              color="error"
              startIcon={<ShieldOff size={16} />}
              onClick={() => {
                setWithdraw(detail);
                setDetail(null);
              }}
            >
              Withdraw
            </Button>
          )}
          <Button onClick={() => setDetail(null)}>Close</Button>
        </DialogActions>
      </Dialog>
    </>
  );
}

const consentSchema = z
  .object({
    consentType: z.enum(CONSENT_TYPES as [ConsentType, ...ConsentType[]], { message: 'Select the consent type' }),
    purpose: z.string().trim().min(3, 'Describe what the patient agreed to').max(500),
    sharedWith: optionalText(200, 'Shared with'),
    captureMethod: z.enum(['WRITTEN', 'VERBAL', 'ELECTRONIC']),
    decision: z.enum(['GRANTED', 'DECLINED']),
    admissionId: z.string().optional(),
    grantedAt: z.string().min(1, 'When was consent given?'),
    expiresAt: z.string().optional(),
    notes: optionalText(1000, 'Notes'),
  })
  .superRefine((v, ctx) => {
    if (['DATA_SHARING', 'INSURANCE_SHARING', 'CAREGIVER_ACCESS'].includes(v.consentType) && !v.sharedWith)
      ctx.addIssue({ code: 'custom', path: ['sharedWith'], message: 'State who the information is shared with' });
    if (v.expiresAt && v.expiresAt <= v.grantedAt) ctx.addIssue({ code: 'custom', path: ['expiresAt'], message: 'Expiry must be after the consent date' });
  });
type ConsentValues = z.infer<typeof consentSchema>;

function RecordConsentDialog({ open, onClose, onSaved }: { open: boolean; onClose: () => void; onSaved: () => void }) {
  const [patient, setPatient] = useState<PatientSummary | null>(null);
  const [pq, setPq] = useState('');
  const [error, setError] = useState<string | null>(null);
  const dpq = useDebouncedValue(pq, 300);
  const patients = useQuery({ queryKey: ['patients', { q: dpq, size: 10 }], queryFn: () => patientApi.search({ q: dpq, size: 10 }), enabled: open && dpq.trim().length >= 2 });
  const admissions = useQuery({ queryKey: ['patient', patient?.id, 'admissions'], queryFn: () => patientApi.admissions(patient!.id), enabled: !!patient });
  const { control, handleSubmit, reset, setError: setFieldError } = useForm<ConsentValues>({ resolver: zodResolver(consentSchema) });
  useEffect(() => {
    if (open) {
      setPatient(null);
      setPq('');
      setError(null);
      reset({ consentType: '' as ConsentType, purpose: '', sharedWith: '', captureMethod: 'WRITTEN', decision: 'GRANTED', admissionId: '', grantedAt: isoToLocalInput(), expiresAt: '', notes: '' });
    }
  }, [open, reset]);
  const m = useMutation({
    mutationFn: (v: ConsentValues) =>
      privacyApi.recordConsent({
        patientId: patient!.id,
        admissionId: v.admissionId || null,
        consentType: v.consentType,
        purpose: v.purpose,
        sharedWith: v.sharedWith || null,
        captureMethod: v.captureMethod as CaptureMethod,
        decision: v.decision,
        grantedAt: localInputToIso(v.grantedAt),
        expiresAt: v.expiresAt ? localInputToIso(v.expiresAt) : null,
        notes: v.notes || null,
      }),
    onSuccess: onSaved,
    onError: (e) => {
      if (!applyServerErrors(e, setFieldError)) setError(errorMessage(e));
    },
  });
  return (
    <Dialog open={open} onClose={m.isPending ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="record-consent-title">
      <DialogTitle id="record-consent-title">Record consent</DialogTitle>
      <DialogContent>
        <Stack component="form" id="consent-form" spacing={3} sx={{ pt: 1 }} onSubmit={handleSubmit((v) => (patient ? m.mutate(v) : setError('Select the patient first.')))} noValidate>
          {error && <Alert severity="error">{error}</Alert>}
          <Autocomplete
            options={patients.data?.content ?? []}
            value={patient}
            onChange={(_, v) => setPatient(v)}
            inputValue={pq}
            onInputChange={(_, v) => setPq(v)}
            filterOptions={(x) => x}
            loading={patients.isFetching}
            getOptionLabel={(p) => `${p.fullName} · ${p.mrn}`}
            isOptionEqualToValue={(a, b) => a.id === b.id}
            noOptionsText={pq.length < 2 ? 'Type at least 2 characters' : 'No patients match'}
            renderInput={(p) => <TextField {...p} label="Patient" required />}
          />
          <RHFSelect control={control} name="consentType" label="Consent type" required fullWidth options={CONSENT_TYPES.map((t) => ({ value: t, label: humanize(t) }))} />
          <RHFTextField control={control} name="purpose" label="Purpose / scope" required fullWidth multiline minRows={2} />
          <RHFTextField control={control} name="sharedWith" label="Shared with" fullWidth helperText="Required for data sharing, insurance and caregiver consents." />
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3}>
            <RHFSelect control={control} name="decision" label="Decision" options={[{ value: 'GRANTED', label: 'Granted' }, { value: 'DECLINED', label: 'Declined' }]} sx={{ flex: 1 }} />
            <RHFSelect control={control} name="captureMethod" label="Captured" options={['WRITTEN', 'VERBAL', 'ELECTRONIC'].map((x) => ({ value: x, label: humanize(x) }))} sx={{ flex: 1 }} />
          </Stack>
          {patient && (
            <RHFSelect
              control={control}
              name="admissionId"
              label="Related admission"
              fullWidth
              emptyLabel="Not admission-specific"
              options={(admissions.data ?? []).map((a) => ({ value: a.id, label: `${a.admissionNumber} · ${formatDate(a.admittedAt)} · ${humanize(a.status)}` }))}
            />
          )}
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3}>
            <RHFTextField control={control} name="grantedAt" label="Consent date" type="datetime-local" required sx={{ flex: 1 }} />
            <RHFTextField control={control} name="expiresAt" label="Expires (optional)" type="datetime-local" sx={{ flex: 1 }} />
          </Stack>
          <RHFTextField control={control} name="notes" label="Notes" fullWidth multiline minRows={2} helperText="e.g. interpreter used, witness, form reference." />
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={m.isPending}>
          Cancel
        </Button>
        <Button type="submit" form="consent-form" variant="contained" startIcon={<FileSignature size={16} />} disabled={m.isPending}>
          Record
        </Button>
      </DialogActions>
    </Dialog>
  );
}
