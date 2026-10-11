'use client';

import { useState } from 'react';
import Link from 'next/link';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Box, Card, Stack, Tab, Tabs, Typography } from '@mui/material';
import { OctagonAlert } from 'lucide-react';
import { ck, clinicalApi, InvestigationWorklist, LabOrderView } from '@/lib/api/clinical';
import { useTableState } from '@/lib/hooks';
import { formatDateTime, fromNow, humanize } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { DataTable } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';
import {
  abnormalSummary,
  InvestigationActionButtons,
  ResultEntryDialog,
  ReviewResultsDialog,
  useInvestigationActions,
  useInvestigationPermissions,
} from '@/components/clinical/investigations/InvestigationsPanel';
import { FilterChips, FlagChip } from '@/components/clinical/shared';
import { colors } from '@/theme/tokens';

type TabKey = 'review' | 'pending' | 'all';
type PendingView = 'AWAITING_RESULT' | 'PENDING_COLLECTION';

export default function DoctorInvestigationsPage() {
  const can = useInvestigationPermissions();
  const t = useTableState({ size: 20 });
  const [tab, setTab] = useState<TabKey>('review');
  const [pending, setPending] = useState<PendingView>('AWAITING_RESULT');
  const [reviewing, setReviewing] = useState<LabOrderView | null>(null);
  const [entering, setEntering] = useState<LabOrderView | null>(null);
  const actions = useInvestigationActions();

  const view: InvestigationWorklist = tab === 'review' ? 'AWAITING_REVIEW' : tab === 'pending' ? pending : 'ALL';
  const params = { view, page: t.page, size: t.size };
  const q = useQuery({ queryKey: ck.worklist(params), queryFn: () => clinicalApi.worklist(params), placeholderData: keepPreviousData });
  const patientHref = (o: LabOrderView) => `/doctor/patients/${o.admissionId}?tab=investigations`;

  return (
    <>
      <PageHeader
        title="Investigations"
        subtitle="Results awaiting your review and investigations still in progress for your patients."
        breadcrumbs={[{ label: 'Clinician', href: '/doctor' }, { label: 'Investigations' }]}
      />
      <Card>
        <Tabs
          value={tab}
          onChange={(_, v) => {
            setTab(v);
            t.setPage(0);
          }}
          variant="scrollable"
          allowScrollButtonsMobile
          sx={{ px: { xs: 1, sm: 4 }, borderBottom: 1, borderColor: 'divider' }}
          aria-label="Worklist"
        >
          <Tab value="review" label="Awaiting review" />
          <Tab value="pending" label="Pending" />
          <Tab value="all" label="All" />
        </Tabs>
        {tab === 'pending' && (
          <Box sx={{ px: 4, pt: 3 }}>
            <FilterChips<PendingView>
              ariaLabel="Pending stage"
              value={pending}
              onChange={(v) => {
                setPending(v);
                t.setPage(0);
              }}
              options={[
                { value: 'AWAITING_RESULT', label: 'Awaiting result' },
                { value: 'PENDING_COLLECTION', label: 'Awaiting collection' },
              ]}
            />
          </Box>
        )}
        <Box sx={{ pt: tab === 'pending' ? 1 : 0 }}>
          <DataTable<LabOrderView>
            ariaLabel="Investigation worklist"
            rows={q.data?.content}
            loading={q.isFetching}
            error={q.error}
            onRetry={() => q.refetch()}
            rowKey={(o) => o.id}
            page={t.page}
            pageSize={t.size}
            total={q.data?.totalElements}
            onPageChange={t.setPage}
            onPageSizeChange={t.setSize}
            emptyTitle={tab === 'review' ? 'No results awaiting review' : tab === 'pending' ? 'Nothing pending' : 'No investigations'}
            emptyDescription={tab === 'review' ? 'New results for your patients appear here for sign-off.' : undefined}
            rowSx={(o) => (o.hasCritical && o.status === 'RESULTED' ? { bgcolor: colors.dangerSoft, boxShadow: `inset 4px 0 0 ${colors.danger}` } : undefined)}
            columns={[
              {
                key: 'test',
                header: 'Investigation',
                primary: true,
                render: (o) => {
                  const summary = abnormalSummary(o);
                  return (
                    <Box sx={{ minWidth: 0, maxWidth: 380 }}>
                      <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                        <Typography variant="body2" fontWeight={600}>
                          {o.testName}
                        </Typography>
                        {o.priority !== 'ROUTINE' && <StatusChip value={o.priority} />}
                        {o.hasCritical && <FlagChip tone="danger" icon={<OctagonAlert size={12} />} label="Critical" />}
                        {!o.hasCritical && o.hasAbnormal && <FlagChip tone="warning" label="Abnormal" />}
                      </Stack>
                      <Typography variant="caption" color="text.secondary" component="div">
                        {o.orderNumber} · {humanize(o.category)}
                        {o.results.some((r) => r.source === 'LIS_ADAPTER') ? ' · LIS (dev adapter)' : ''}
                      </Typography>
                      {summary && (
                        <Typography variant="caption" component="div" sx={{ color: o.hasCritical ? colors.danger : colors.warning, fontWeight: 600, wordBreak: 'break-word' }}>
                          {summary}
                        </Typography>
                      )}
                    </Box>
                  );
                },
              },
              {
                key: 'patient',
                header: 'Patient',
                render: (o) =>
                  o.patient ? (
                    <Box>
                      <Typography
                        component={Link}
                        href={patientHref(o)}
                        variant="body2"
                        fontWeight={600}
                        sx={{ color: 'primary.main', textDecoration: 'none', '&:hover': { textDecoration: 'underline' } }}
                      >
                        {o.patient.patientName}
                      </Typography>
                      <Typography variant="caption" color="text.secondary" component="div">
                        {o.patient.mrn} · {o.patient.location ?? 'Awaiting bed'}
                      </Typography>
                    </Box>
                  ) : (
                    <Typography component={Link} href={patientHref(o)} variant="body2">
                      Open record
                    </Typography>
                  ),
              },
              {
                key: 'status',
                header: 'Status',
                render: (o) => (
                  <Stack spacing={0.5} alignItems={{ xs: 'flex-end', md: 'flex-start' }}>
                    <StatusChip value={o.status} />
                    {o.released && <FlagChip tone="success" label="Released" />}
                  </Stack>
                ),
              },
              {
                key: 'ordered',
                header: 'Ordered',
                hideOnMobile: true,
                render: (o) => (
                  <Box>
                    <Typography variant="body2" sx={{ whiteSpace: 'nowrap' }}>
                      {formatDateTime(o.orderedAt)}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      {o.orderedBy ?? '—'}
                    </Typography>
                  </Box>
                ),
              },
              {
                key: 'progress',
                header: 'Progress',
                render: (o) => {
                  const resulted = o.results[0]?.resultedAt;
                  const text = resulted
                    ? `Resulted ${fromNow(resulted)}`
                    : o.collectedAt
                      ? `Collected ${fromNow(o.collectedAt)}`
                      : o.dueAt
                        ? `Due ${formatDateTime(o.dueAt, 'DD MMM, HH:mm')}`
                        : `Ordered ${fromNow(o.orderedAt)}`;
                  const overdue = !resulted && o.dueAt && Date.parse(o.dueAt) < Date.now() && o.status !== 'CANCELLED';
                  return (
                    <Typography variant="body2" sx={{ color: overdue ? colors.danger : undefined, fontWeight: overdue ? 600 : 400 }}>
                      {text}
                      {overdue ? ' · overdue' : ''}
                    </Typography>
                  );
                },
              },
              {
                key: 'actions',
                header: '',
                align: 'right',
                render: (o) => (
                  <Box sx={{ display: 'flex', justifyContent: 'flex-end' }}>
                    <InvestigationActionButtons order={o} onAction={actions.request} onReview={setReviewing} onEnterResults={setEntering} />
                  </Box>
                ),
              },
            ]}
          />
        </Box>
      </Card>
      {actions.dialogs}
      <ReviewResultsDialog order={reviewing} onClose={() => setReviewing(null)} canRelease={can.release} />
      <ResultEntryDialog order={entering} onClose={() => setEntering(null)} />
    </>
  );
}
