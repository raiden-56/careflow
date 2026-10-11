'use client';

import { ReactNode, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Box, Button, Collapse, Stack, Typography } from '@mui/material';
import { ChevronDown, ChevronUp, ClipboardCheck, FlaskConical, OctagonAlert, Plus, Send, TestTube, XCircle, FilePen } from 'lucide-react';
import { ck, clinicalApi, LabOrderView } from '@/lib/api/clinical';
import { useAuth } from '@/lib/auth/AuthProvider';
import { formatDateTime, humanize } from '@/lib/format';
import { StatusChip } from '@/components/ui/StatusChip';
import { EmptyState, ErrorState, TableSkeleton } from '@/components/ui/States';
import { colors } from '@/theme/tokens';
import { FilterChips, FlagChip, MetaLine, RecordCard } from '../shared';
import {
  abnormalSummary,
  InvestigationActionKind,
  OrderInvestigationDialog,
  ResultEntryDialog,
  ResultsTable,
  ReviewResultsDialog,
  useInvestigationActions,
} from './InvestigationDialogs';

export {
  OrderInvestigationDialog,
  ResultEntryDialog,
  ResultsTable,
  ReviewResultsDialog,
  useInvestigationActions,
  abnormalSummary,
} from './InvestigationDialogs';

const OUTSTANDING = ['ORDERED', 'COLLECTED', 'IN_PROGRESS', 'RESULTED'];

/** Which workflow actions apply to an investigation for the signed-in user. */
export function useInvestigationPermissions() {
  const { hasPermission } = useAuth();
  return {
    order: hasPermission('LAB_ORDER'),
    collect: hasPermission('LAB_COLLECT'),
    enter: hasPermission('LAB_RESULT_ENTER'),
    review: hasPermission('LAB_RESULT_REVIEW'),
    release: hasPermission('RELEASE_TO_PATIENT'),
  };
}

/** Action buttons for one investigation. */
export function InvestigationActionButtons({
  order: o,
  onAction,
  onEnterResults,
  onReview,
}: {
  order: LabOrderView;
  onAction: (kind: InvestigationActionKind, o: LabOrderView) => void;
  onEnterResults?: (o: LabOrderView) => void;
  onReview?: (o: LabOrderView) => void;
}) {
  const can = useInvestigationPermissions();
  const b: ReactNode[] = [];
  if (can.collect && o.status === 'ORDERED' && o.category === 'LAB')
    b.push(
      <Button key="collect" size="small" variant="outlined" startIcon={<TestTube size={14} />} onClick={() => onAction('collect', o)}>
        Collect
      </Button>,
    );
  const resultable = o.status === 'COLLECTED' || o.status === 'IN_PROGRESS' || (o.status === 'ORDERED' && o.category !== 'LAB');
  if (can.enter && resultable && onEnterResults)
    b.push(
      <Button key="enter" size="small" variant="outlined" startIcon={<FilePen size={14} />} onClick={() => onEnterResults(o)}>
        Enter results
      </Button>,
    );
  if (can.review && o.status === 'RESULTED' && onReview)
    b.push(
      <Button key="review" size="small" variant="contained" color={o.hasCritical ? 'error' : 'primary'} startIcon={<ClipboardCheck size={14} />} onClick={() => onReview(o)}>
        Review
      </Button>,
    );
  if (can.release && o.status === 'REVIEWED' && !o.released)
    b.push(
      <Button key="release" size="small" startIcon={<Send size={14} />} onClick={() => onAction('release', o)}>
        Release to patient
      </Button>,
    );
  if (can.order && (o.status === 'ORDERED' || o.status === 'COLLECTED' || o.status === 'IN_PROGRESS'))
    b.push(
      <Button key="cancel" size="small" color="error" startIcon={<XCircle size={14} />} onClick={() => onAction('cancel', o)}>
        Cancel
      </Button>,
    );
  if (!b.length) return null;
  return (
    <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
      {b}
    </Stack>
  );
}

/** Investigation orders for one admission, with expandable results and workflow actions. */
export function InvestigationsPanel({ admissionId }: { admissionId: string }) {
  const can = useInvestigationPermissions();
  const [filter, setFilter] = useState<'all' | 'outstanding'>('all');
  const [orderOpen, setOrderOpen] = useState(false);
  const [entering, setEntering] = useState<LabOrderView | null>(null);
  const [reviewing, setReviewing] = useState<LabOrderView | null>(null);
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const q = useQuery({ queryKey: ck.investigations(admissionId), queryFn: () => clinicalApi.investigations(admissionId) });
  const actions = useInvestigationActions();

  const all = q.data ?? [];
  const list = filter === 'all' ? all : all.filter((o) => OUTSTANDING.includes(o.status));
  const outstandingCount = all.filter((o) => OUTSTANDING.includes(o.status)).length;

  return (
    <Box>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} justifyContent="space-between" alignItems={{ sm: 'center' }} sx={{ mb: 3 }}>
        <FilterChips
          ariaLabel="Investigation filter"
          value={filter}
          onChange={setFilter}
          options={[
            { value: 'all', label: `All (${all.length})` },
            { value: 'outstanding', label: `Outstanding (${outstandingCount})` },
          ]}
        />
        {can.order && (
          <Button variant="contained" startIcon={<Plus size={16} />} onClick={() => setOrderOpen(true)} sx={{ alignSelf: { xs: 'flex-start', sm: 'auto' } }}>
            Order investigation
          </Button>
        )}
      </Stack>
      {q.isLoading ? (
        <TableSkeleton rows={4} columns={3} />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      ) : list.length === 0 ? (
        <EmptyState
          icon={FlaskConical}
          title={filter === 'all' ? 'No investigations ordered' : 'Nothing outstanding'}
          description={filter === 'outstanding' && all.length ? 'All investigations are reviewed or cancelled.' : undefined}
          compact
        />
      ) : (
        <Stack spacing={2}>
          {list.map((o) => (
            <InvestigationCard
              key={o.id}
              order={o}
              expanded={expanded[o.id] ?? (o.status === 'RESULTED' && o.results.length > 0)}
              onToggle={() => setExpanded((e) => ({ ...e, [o.id]: !(e[o.id] ?? (o.status === 'RESULTED' && o.results.length > 0)) }))}
              actions={<InvestigationActionButtons order={o} onAction={actions.request} onEnterResults={setEntering} onReview={setReviewing} />}
            />
          ))}
        </Stack>
      )}
      {actions.dialogs}
      <OrderInvestigationDialog open={orderOpen} onClose={() => setOrderOpen(false)} admissionId={admissionId} />
      <ResultEntryDialog order={entering} onClose={() => setEntering(null)} />
      <ReviewResultsDialog order={reviewing} onClose={() => setReviewing(null)} canRelease={can.release} />
    </Box>
  );
}

/** One investigation with status/priority/flags and expandable results. */
export function InvestigationCard({
  order: o,
  expanded,
  onToggle,
  actions,
  header,
}: {
  order: LabOrderView;
  expanded: boolean;
  onToggle: () => void;
  actions?: ReactNode;
  header?: ReactNode;
}) {
  const cancelled = o.status === 'CANCELLED';
  const accent = o.status === 'RESULTED' ? (o.hasCritical ? colors.danger : colors.warning) : undefined;
  const summary = abnormalSummary(o);
  return (
    <RecordCard accent={accent} muted={cancelled}>
      {header}
      <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} justifyContent="space-between" alignItems={{ md: 'flex-start' }}>
        <Box sx={{ minWidth: 0 }}>
          <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
            <Typography variant="body1" fontWeight={600}>
              {o.testName}
            </Typography>
            <StatusChip value={o.status} />
            {o.priority !== 'ROUTINE' && <StatusChip value={o.priority} />}
          </Stack>
          <Typography variant="caption" color="text.secondary">
            {o.orderNumber} · {humanize(o.category)}
            {o.specimen ? ` · ${o.specimen}` : ''}
          </Typography>
          {o.clinicalInfo && (
            <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
              {o.clinicalInfo}
            </Typography>
          )}
        </Box>
        <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ flexShrink: 0 }}>
          {o.hasCritical && <FlagChip tone="danger" icon={<OctagonAlert size={12} />} label="Critical result" />}
          {!o.hasCritical && o.hasAbnormal && <FlagChip tone="warning" label="Abnormal" />}
          {o.released && <FlagChip tone="success" icon={<Send size={12} />} label="Released to patient" />}
          {o.results.some((r) => r.source === 'LIS_ADAPTER') && <FlagChip tone="info" label="LIS (dev adapter)" />}
        </Stack>
      </Stack>
      {summary && !expanded && (
        <Typography variant="body2" sx={{ mt: 1, color: o.hasCritical ? colors.danger : colors.warning, fontWeight: 500 }}>
          {summary}
        </Typography>
      )}
      {cancelled && (
        <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 1 }}>
          Cancelled {formatDateTime(o.cancelledAt)} by {o.cancelledBy ?? '—'}
          {o.cancelReason ? `: ${o.cancelReason}` : ''}
        </Typography>
      )}
      <MetaLine
        items={[
          ['Ordered', `${formatDateTime(o.orderedAt)} · ${o.orderedBy ?? '—'}`],
          o.dueAt && !cancelled && o.status !== 'REVIEWED' && o.status !== 'RESULTED' ? ['Due', formatDateTime(o.dueAt)] : null,
          o.collectedAt ? ['Collected', `${formatDateTime(o.collectedAt)} · ${o.collectedBy ?? '—'}`] : null,
          o.chargePosted ? ['Billing', cancelled && !o.collectedAt ? 'Charge reversed' : 'Charge posted'] : null,
        ]}
      />
      {o.results.length > 0 && (
        <>
          <Button
            size="small"
            onClick={onToggle}
            endIcon={expanded ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
            aria-expanded={expanded}
            sx={{ mt: 1.5, ml: -1 }}
          >
            {expanded ? 'Hide results' : `Show results (${o.results.length})`}
          </Button>
          <Collapse in={expanded} unmountOnExit>
            <Box sx={{ mt: 1, mx: { xs: -3, sm: -4 }, borderTop: 1, borderBottom: 1, borderColor: 'divider', pb: 2 }}>
              <ResultsTable order={o} />
            </Box>
          </Collapse>
        </>
      )}
      {actions && <Box sx={{ mt: 2 }}>{actions}</Box>}
    </RecordCard>
  );
}
