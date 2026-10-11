'use client';

import { useState } from 'react';
import Link from 'next/link';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  Grid,
  IconButton,
  Pagination,
  Stack,
  Tooltip,
  Typography,
} from '@mui/material';
import { Download, Eye, FileText, Plus, ShieldCheck, ShieldOff, Users } from 'lucide-react';
import { ConsentView, documentApi, PatientRequestType, privacyApi } from '@/lib/api/admin';
import { patientApi, qk } from '@/lib/api/core';
import { applyServerErrors, useToast } from '@/lib/hooks';
import { errorMessage } from '@/lib/api/client';
import { formatDate, formatDateTime, humanize } from '@/lib/format';
import { PortalPatientSwitcher, usePortal } from '@/lib/portal';
import { PageHeader } from '@/components/ui/PageHeader';
import { SectionCard } from '@/components/ui/SectionCard';
import { StatusChip } from '@/components/ui/StatusChip';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { EmptyState, ErrorState, PageSkeleton, TableSkeleton } from '@/components/ui/States';
import { RHFSelect, RHFTextField } from '@/components/forms/Fields';
import { formatBytes, useDocumentDownload } from '@/components/admin/DocumentsPanel';

const REQUEST_TYPES: { value: PatientRequestType; label: string; hint: string }[] = [
  { value: 'RECORD_COPY', label: 'Copy of my records', hint: 'Receive a copy of your health records.' },
  { value: 'CORRECTION', label: 'Correct my information', hint: 'Tell us about something in your record that is wrong.' },
  { value: 'RESTRICT_SHARING', label: 'Restrict sharing', hint: 'Limit who your information is shared with.' },
  { value: 'WITHDRAW_CONSENT', label: 'Withdraw a consent', hint: 'For consents you cannot withdraw directly here, such as treatment consent.' },
  { value: 'ACCESS_REVIEW', label: 'Review who accessed my record', hint: 'Ask the hospital to investigate an access you do not recognise.' },
  { value: 'OTHER', label: 'Something else', hint: 'Any other privacy question.' },
];

export default function PatientPrivacyPage() {
  const { patientId, patient, canSeeClinical } = usePortal();
  if (!patientId) return <PageSkeleton />;
  if (!patient) return <EmptyState title="No patient record linked" description="Ask the hospital registration desk to link your portal account to your patient record." />;
  const self = patient.relationship === 'SELF';
  const canAct = self || patient.scope === 'FULL';
  return (
    <>
      <PageHeader
        title="Privacy & consent"
        subtitle={self ? 'Your consents, requests, released documents and who has accessed your record.' : `Privacy information for ${patient.name}.`}
        breadcrumbs={[{ label: 'My health', href: '/patient' }, { label: 'Privacy & consent' }]}
        actions={<PortalPatientSwitcher />}
      />
      <Grid container spacing={5}>
        <Grid size={{ xs: 12, lg: 7 }}>
          <Stack spacing={5}>
            <ConsentsSection patientId={patientId} canAct={canAct} />
            <RequestsSection patientId={patientId} canAct={canAct} />
          </Stack>
        </Grid>
        <Grid size={{ xs: 12, lg: 5 }}>
          <Stack spacing={5}>
            {canSeeClinical ? <DocumentsSection patientId={patientId} /> : null}
            {canSeeClinical ? (
              <AccessLogSection patientId={patientId} />
            ) : (
              <SectionCard title="Who accessed this record">
                <Alert severity="info">Your access covers billing only, so the access history is not shown.</Alert>
              </SectionCard>
            )}
            <CaregiverSection patientId={patientId} self={self} relationship={patient.relationship} scope={patient.scope} />
          </Stack>
        </Grid>
      </Grid>
    </>
  );
}

// ------------------------------------------------------------------ consents

function ConsentsSection({ patientId, canAct }: { patientId: string; canAct: boolean }) {
  const toast = useToast();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['portal', 'consents', patientId], queryFn: () => privacyApi.portalConsents(patientId) });
  const [withdraw, setWithdraw] = useState<ConsentView | null>(null);
  const m = useMutation({
    mutationFn: ({ c, reason }: { c: ConsentView; reason: string }) => privacyApi.portalWithdraw(patientId, c.id, reason),
    onSuccess: () => {
      toast.success('Consent withdrawn. The hospital has been informed.');
      setWithdraw(null);
      qc.invalidateQueries({ queryKey: ['portal', 'consents', patientId] });
    },
    onError: toast.error,
  });
  return (
    <SectionCard title="Consents" subtitle="What you have agreed to. Some consents can be withdrawn here; others need your care team.">
      {q.isLoading ? (
        <TableSkeleton rows={3} columns={2} />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      ) : !q.data?.length ? (
        <EmptyState icon={ShieldCheck} title="No consents recorded" compact />
      ) : (
        <Stack divider={<Divider flexItem />}>
          {q.data.map((c) => (
            <Stack key={c.id} direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ py: 3 }} alignItems={{ sm: 'flex-start' }}>
              <Box sx={{ flex: 1, minWidth: 0 }}>
                <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                  <Typography variant="body2" fontWeight={600}>
                    {humanize(c.consentType)}
                  </Typography>
                  <StatusChip value={c.effectiveStatus} />
                </Stack>
                <Typography variant="body2" sx={{ mt: 0.5 }}>
                  {c.purpose}
                </Typography>
                <Typography variant="caption" color="text.secondary" component="div">
                  {c.sharedWith ? `Shared with ${c.sharedWith} · ` : ''}
                  {c.effectiveStatus === 'WITHDRAWN' && c.withdrawnAt ? `Withdrawn ${formatDate(c.withdrawnAt)}` : `Given ${formatDate(c.grantedAt)} (${humanize(c.captureMethod).toLowerCase()})`}
                  {c.expiresAt ? ` · expires ${formatDate(c.expiresAt)}` : ''}
                </Typography>
              </Box>
              {canAct && c.portalWithdrawable && (
                <Button size="small" color="error" variant="outlined" startIcon={<ShieldOff size={14} />} onClick={() => setWithdraw(c)} sx={{ flexShrink: 0 }}>
                  Withdraw
                </Button>
              )}
            </Stack>
          ))}
        </Stack>
      )}
      <ConfirmDialog
        open={!!withdraw}
        title={`Withdraw ${withdraw ? humanize(withdraw.consentType).toLowerCase() : ''} consent?`}
        description="From now on the hospital will stop using your information for this purpose. Information already shared cannot be recalled. You can give consent again later through the hospital."
        tone="error"
        requireReason
        reasonLabel="Reason (shared with the hospital)"
        confirmLabel="Withdraw consent"
        loading={m.isPending}
        onConfirm={(reason) => withdraw && m.mutate({ c: withdraw, reason: reason! })}
        onClose={() => setWithdraw(null)}
      />
    </SectionCard>
  );
}

// ------------------------------------------------------------------ requests

const requestSchema = z.object({
  requestType: z.enum(['RECORD_COPY', 'CORRECTION', 'RESTRICT_SHARING', 'WITHDRAW_CONSENT', 'ACCESS_REVIEW', 'OTHER'], { message: 'Choose what you need' }),
  subject: z.string().trim().min(3, 'Add a short subject').max(300),
  description: z.string().trim().min(10, 'Please describe your request (at least 10 characters)').max(2000),
});
type RequestValues = z.infer<typeof requestSchema>;

function RequestsSection({ patientId, canAct }: { patientId: string; canAct: boolean }) {
  const toast = useToast();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['portal', 'requests', patientId], queryFn: () => privacyApi.portalRequests(patientId) });
  const [open, setOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const { control, handleSubmit, reset, watch, setError: setFieldError } = useForm<RequestValues>({
    resolver: zodResolver(requestSchema),
    defaultValues: { requestType: 'RECORD_COPY', subject: '', description: '' },
  });
  const type = watch('requestType');
  const m = useMutation({
    mutationFn: (v: RequestValues) => privacyApi.portalSubmit(patientId, v),
    onSuccess: () => {
      toast.success('Request sent to the hospital privacy team');
      setOpen(false);
      reset();
      qc.invalidateQueries({ queryKey: ['portal', 'requests', patientId] });
    },
    onError: (e) => {
      if (!applyServerErrors(e, setFieldError)) setError(errorMessage(e));
    },
  });
  return (
    <SectionCard
      title="My requests"
      subtitle="Ask for a copy of your records, a correction or a change to how your information is shared."
      action={
        canAct && (
          <Button size="small" variant="contained" startIcon={<Plus size={14} />} onClick={() => { setError(null); setOpen(true); }}>
            New request
          </Button>
        )
      }
    >
      {q.isLoading ? (
        <TableSkeleton rows={2} columns={2} />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      ) : !q.data?.length ? (
        <EmptyState title="No requests yet" compact />
      ) : (
        <Stack divider={<Divider flexItem />}>
          {q.data.map((r) => (
            <Box key={r.id} sx={{ py: 3 }}>
              <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                <Typography variant="body2" fontWeight={600}>
                  {r.subject}
                </Typography>
                <StatusChip value={r.status} />
              </Stack>
              <Typography variant="caption" color="text.secondary" component="div">
                {humanize(r.requestType)} · sent {formatDateTime(r.raisedAt)}
              </Typography>
              {r.response && (
                <Alert severity={r.status === 'REJECTED' ? 'warning' : 'info'} sx={{ mt: 2 }} icon={false}>
                  <Typography variant="caption" fontWeight={600} component="div">
                    Response from the hospital{r.respondedAt ? ` · ${formatDateTime(r.respondedAt)}` : ''}
                  </Typography>
                  {r.response}
                </Alert>
              )}
            </Box>
          ))}
        </Stack>
      )}
      <Dialog open={open} onClose={m.isPending ? undefined : () => setOpen(false)} maxWidth="sm" fullWidth aria-labelledby="req-title">
        <DialogTitle id="req-title">New privacy request</DialogTitle>
        <DialogContent>
          <Stack component="form" id="req-form" spacing={3} sx={{ pt: 1 }} onSubmit={handleSubmit((v) => m.mutate(v))} noValidate>
            {error && <Alert severity="error">{error}</Alert>}
            <RHFSelect
              control={control}
              name="requestType"
              label="What do you need?"
              fullWidth
              options={REQUEST_TYPES.map((t) => ({ value: t.value, label: t.label }))}
              helperText={REQUEST_TYPES.find((t) => t.value === type)?.hint}
            />
            <RHFTextField control={control} name="subject" label="Subject" required fullWidth />
            <RHFTextField control={control} name="description" label="Details" required fullWidth multiline minRows={4} />
            <Typography variant="caption" color="text.secondary">
              The hospital privacy team will respond in this portal. Do not include passwords or bank details.
            </Typography>
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 6, pb: 4 }}>
          <Button color="inherit" onClick={() => setOpen(false)} disabled={m.isPending}>
            Cancel
          </Button>
          <Button type="submit" form="req-form" variant="contained" disabled={m.isPending}>
            Send request
          </Button>
        </DialogActions>
      </Dialog>
    </SectionCard>
  );
}

// ------------------------------------------------------------------ documents, access log, caregivers

function DocumentsSection({ patientId }: { patientId: string }) {
  const q = useQuery({ queryKey: ['portal', 'documents', patientId], queryFn: () => documentApi.portalList(patientId) });
  const { download, busy } = useDocumentDownload();
  return (
    <SectionCard title="My documents" subtitle="Reports and documents your care team has shared with you.">
      {q.isLoading ? (
        <TableSkeleton rows={2} columns={2} />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      ) : !q.data?.length ? (
        <EmptyState icon={FileText} title="No documents shared yet" description="Documents appear here once your doctor releases them." compact />
      ) : (
        <Stack divider={<Divider flexItem />}>
          {q.data.map((d) => (
            <Stack key={d.id} direction="row" spacing={2} alignItems="center" sx={{ py: 2 }}>
              <FileText size={18} aria-hidden />
              <Box sx={{ flex: 1, minWidth: 0 }}>
                <Typography variant="body2" fontWeight={500} sx={{ wordBreak: 'break-word' }}>
                  {d.title}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {humanize(d.documentType)} · {formatDate(d.uploadedAt)} · {formatBytes(d.sizeBytes)}
                </Typography>
              </Box>
              <Tooltip title="Download">
                <IconButton aria-label={`Download ${d.title}`} disabled={busy === d.id} onClick={() => download(d)}>
                  <Download size={18} />
                </IconButton>
              </Tooltip>
            </Stack>
          ))}
        </Stack>
      )}
    </SectionCard>
  );
}

function AccessLogSection({ patientId }: { patientId: string }) {
  const [page, setPage] = useState(0);
  const q = useQuery({ queryKey: ['portal', 'access-log', patientId, page], queryFn: () => privacyApi.accessLog(patientId, page, 8) });
  return (
    <SectionCard title="Who accessed my record" subtitle="Hospital staff who viewed or updated your record, most recent first.">
      {q.isLoading ? (
        <TableSkeleton rows={4} columns={2} />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      ) : !q.data?.content.length ? (
        <EmptyState icon={Eye} title="No staff access recorded yet" compact />
      ) : (
        <>
          <Stack divider={<Divider flexItem />}>
            {q.data.content.map((e, i) => (
              <Box key={i} sx={{ py: 2 }}>
                <Typography variant="body2" component="div">
                  <strong>{e.staffName}</strong> <Chip size="small" label={e.role} variant="outlined" sx={{ ml: 1 }} />
                </Typography>
                <Typography variant="caption" color="text.secondary" component="div">
                  {e.activity} · {formatDateTime(e.at)}
                </Typography>
              </Box>
            ))}
          </Stack>
          {q.data.totalPages > 1 && (
            <Stack alignItems="center" sx={{ mt: 2 }}>
              <Pagination count={q.data.totalPages} page={page + 1} onChange={(_, p) => setPage(p - 1)} size="small" />
            </Stack>
          )}
          <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 2 }}>
            Don&apos;t recognise an access? Send a &quot;Review who accessed my record&quot; request.
          </Typography>
        </>
      )}
    </SectionCard>
  );
}

function CaregiverSection({ patientId, self, relationship, scope }: { patientId: string; self: boolean; relationship: string; scope: string }) {
  const q = useQuery({ queryKey: qk.caregivers(patientId), queryFn: () => patientApi.caregivers(patientId), enabled: self });
  return (
    <SectionCard
      title="Caregiver access"
      subtitle={self ? 'People you have allowed to view your records.' : 'Your access to this record.'}
      action={
        self && (
          <Button component={Link} href="/profile" size="small" startIcon={<Users size={14} />}>
            Manage
          </Button>
        )
      }
    >
      {!self ? (
        <Typography variant="body2">
          You view this record as <strong>{relationship}</strong> with {humanize(scope).toLowerCase()} access. Only the patient can change caregiver access.
        </Typography>
      ) : q.isLoading ? (
        <TableSkeleton rows={2} columns={2} />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      ) : !q.data?.filter((g) => g.active).length ? (
        <EmptyState icon={Users} title="No caregivers have access" description="You can add a family member in your profile." compact />
      ) : (
        <Stack spacing={2}>
          {q.data
            .filter((g) => g.active)
            .map((g) => (
              <Stack key={g.id} direction="row" justifyContent="space-between" spacing={2}>
                <Box>
                  <Typography variant="body2" fontWeight={500}>
                    {g.granteeName ?? g.granteeEmail}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {g.relationship} · since {formatDate(g.grantedAt)}
                    {g.expiresAt ? ` · until ${formatDate(g.expiresAt)}` : ''}
                  </Typography>
                </Box>
                <Chip size="small" label={humanize(g.scope)} />
              </Stack>
            ))}
        </Stack>
      )}
    </SectionCard>
  );
}
