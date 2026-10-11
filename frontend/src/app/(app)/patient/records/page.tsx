'use client';

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Card,
  Chip,
  Stack,
  Tab,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Tabs,
  Typography,
} from '@mui/material';
import { ClipboardList, FileHeart, FlaskConical, ShieldCheck } from 'lucide-react';
import { ck, clinicalApi, noteTypeLabel, PortalResult } from '@/lib/api/clinical';
import { PortalPatientSwitcher, usePortal } from '@/lib/portal';
import { formatDate, formatDateTime } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { SectionCard } from '@/components/ui/SectionCard';
import { StatusChip } from '@/components/ui/StatusChip';
import { EmptyState, ErrorState, PageSkeleton } from '@/components/ui/States';

const FLAG_LABEL: Record<string, string> = {
  NORMAL: 'Within range',
  LOW: 'Below range',
  HIGH: 'Above range',
  CRITICAL_LOW: 'Well below range',
  CRITICAL_HIGH: 'Well above range',
  ABNORMAL: 'Outside usual findings',
};

function ResultCard({ r }: { r: PortalResult }) {
  return (
    <Card variant="outlined" sx={{ p: { xs: 3, sm: 4 } }}>
      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={1} sx={{ mb: 2 }}>
        <Box>
          <Typography variant="h5" component="h3">
            {r.testName}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            Reported {formatDateTime(r.resultedAt)}
            {r.admissionNumber ? ` · Stay ${r.admissionNumber}` : ''}
          </Typography>
        </Box>
        <Chip size="small" variant="outlined" label={r.category === 'IMAGING' ? 'Imaging' : 'Laboratory'} sx={{ alignSelf: { xs: 'flex-start', sm: 'center' } }} />
      </Stack>
      <TableContainer>
        <Table size="small" aria-label={`${r.testName} results`}>
          <TableHead>
            <TableRow>
              <TableCell>Test</TableCell>
              <TableCell>Result</TableCell>
              <TableCell sx={{ display: { xs: 'none', sm: 'table-cell' } }}>Usual range</TableCell>
              <TableCell align="right">Note</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {r.components.map((c) => (
              <TableRow key={c.id}>
                <TableCell sx={{ wordBreak: 'break-word' }}>{c.component}</TableCell>
                <TableCell sx={{ wordBreak: 'break-word' }}>
                  <strong>{c.valueText}</strong> {c.unit}
                  <Typography variant="caption" color="text.secondary" component="div" sx={{ display: { sm: 'none' } }}>
                    {c.referenceRange ? `Usual: ${c.referenceRange}` : ''}
                  </Typography>
                </TableCell>
                <TableCell sx={{ display: { xs: 'none', sm: 'table-cell' } }}>{c.referenceRange ?? '—'}</TableCell>
                <TableCell align="right">
                  <StatusChip
                    value={c.flag}
                    label={FLAG_LABEL[c.flag]}
                    tone={c.flag === 'NORMAL' ? 'success' : c.flag.startsWith('CRITICAL') ? 'error' : 'warning'}
                  />
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
      {r.reviewComment && (
        <Alert severity="info" variant="outlined" sx={{ mt: 3 }}>
          <Typography variant="caption" fontWeight={600} component="div">
            Your doctor&apos;s comment
          </Typography>
          {r.reviewComment}
        </Alert>
      )}
    </Card>
  );
}

export default function PatientRecordsPage() {
  const { patientId, patient, canSeeClinical } = usePortal();
  const [tab, setTab] = useState<'updates' | 'results' | 'plans'>('updates');
  const q = useQuery({
    queryKey: ck.portalRecords(patientId ?? ''),
    queryFn: () => clinicalApi.portalRecords(patientId!),
    enabled: !!patientId && canSeeClinical,
  });

  const header = (
    <PageHeader
      title="Medical records"
      subtitle={patient && patient.relationship !== 'SELF' ? `Records shared for ${patient.name}` : 'Updates, test results and care plans your care team has shared with you'}
      breadcrumbs={[{ label: 'Home', href: '/patient' }, { label: 'Medical records' }]}
      actions={<PortalPatientSwitcher />}
    />
  );
  if (!patientId) return <PageSkeleton />;
  if (!canSeeClinical) {
    return (
      <>
        {header}
        <Card>
          <EmptyState
            icon={ShieldCheck}
            title="Clinical records are not shared with you"
            description="Your access to this patient covers billing information only. The patient (or the hospital) can change this."
          />
        </Card>
      </>
    );
  }
  if (q.isLoading) return <PageSkeleton />;
  if (q.error || !q.data) {
    return (
      <>
        {header}
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      </>
    );
  }
  const d = q.data;
  return (
    <>
      {header}
      <Alert severity="info" icon={<FileHeart size={18} />} sx={{ mb: 5 }}>
        You see information after your doctor has reviewed it and chosen to share it. If something is unclear, please ask your care team.
      </Alert>
      <Card sx={{ mb: 5 }}>
        <Tabs value={tab} onChange={(_, v) => setTab(v)} variant="scrollable" allowScrollButtonsMobile sx={{ px: 2 }}>
          <Tab value="updates" label={`Care updates (${d.notes.length})`} />
          <Tab value="results" label={`Test results (${d.results.length})`} />
          <Tab value="plans" label={`Care plans (${d.plans.length})`} />
        </Tabs>
      </Card>
      {tab === 'updates' && (
        <SectionCard title="Updates from your care team">
          {d.notes.length === 0 ? (
            <EmptyState icon={FileHeart} title="No updates shared yet" description="Summaries your doctors share will appear here." compact />
          ) : (
            <Stack spacing={4}>
              {d.notes.map((n) => (
                <Box key={n.id} sx={{ borderLeft: 3, borderColor: 'primary.main', pl: 3 }}>
                  <Typography variant="body1" fontWeight={600}>
                    {n.title}
                  </Typography>
                  <Typography variant="caption" color="text.secondary" component="div" sx={{ mb: 1 }}>
                    {noteTypeLabel(n.noteType)} · {n.authorName} · {formatDate(n.authoredAt)}
                  </Typography>
                  <Typography variant="body2" sx={{ whiteSpace: 'pre-line' }}>
                    {n.patientSummary}
                  </Typography>
                </Box>
              ))}
            </Stack>
          )}
        </SectionCard>
      )}
      {tab === 'results' &&
        (d.results.length === 0 ? (
          <Card>
            <EmptyState icon={FlaskConical} title="No results shared yet" description="Test results appear after your doctor has reviewed them." />
          </Card>
        ) : (
          <Stack spacing={4}>
            {d.results.map((r) => (
              <ResultCard key={r.orderId} r={r} />
            ))}
          </Stack>
        ))}
      {tab === 'plans' && (
        <SectionCard title="Your care plan">
          {d.plans.length === 0 ? (
            <EmptyState icon={ClipboardList} title="No care plan shared yet" compact />
          ) : (
            <Stack spacing={4}>
              {d.plans.map((p) => (
                <Box key={p.id} sx={{ opacity: p.status === 'ACTIVE' ? 1 : 0.75 }}>
                  <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }} flexWrap="wrap" useFlexGap>
                    <Typography variant="body1" fontWeight={600}>
                      {p.status === 'ACTIVE' ? 'Current plan' : 'Earlier plan'}
                    </Typography>
                    <StatusChip value={p.status} label={p.status === 'ACTIVE' ? 'Current' : 'Updated since'} tone={p.status === 'ACTIVE' ? 'success' : 'neutral'} />
                  </Stack>
                  <Typography variant="body2" sx={{ whiteSpace: 'pre-line' }}>
                    {p.patientSummary}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {p.createdBy} · {formatDate(p.createdAt)}
                    {p.admissionNumber ? ` · Stay ${p.admissionNumber}` : ''}
                  </Typography>
                </Box>
              ))}
            </Stack>
          )}
        </SectionCard>
      )}
    </>
  );
}
