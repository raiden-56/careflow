'use client';

import { Box, Typography } from '@mui/material';
import { IncidentSeverity, IncidentSummary, IncidentType } from '@/lib/api/admin';
import { useTableState } from '@/lib/hooks';
import { formatDateTime, humanize } from '@/lib/format';
import { DataTable } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';

export const INCIDENT_TYPES: IncidentType[] = ['MEDICATION_ERROR', 'NEAR_MISS', 'FALL', 'PRESSURE_INJURY', 'EQUIPMENT', 'INFECTION', 'PATIENT_IDENTIFICATION', 'BEHAVIOURAL', 'OTHER'];
export const SEVERITIES: IncidentSeverity[] = ['NO_HARM', 'MINOR', 'MODERATE', 'SEVERE', 'DEATH'];

export function IncidentTable({
  rows,
  loading,
  error,
  onRetry,
  onOpen,
  table,
  total,
  showReporter,
  emptyTitle = 'No incidents here',
  emptyDescription = 'Reported incidents appear here for review.',
}: {
  rows?: IncidentSummary[];
  loading: boolean;
  error: unknown;
  onRetry: () => void;
  onOpen: (id: string) => void;
  table: ReturnType<typeof useTableState>;
  total?: number;
  showReporter?: boolean;
  emptyTitle?: string;
  emptyDescription?: string;
}) {
  return (
    <DataTable<IncidentSummary>
      ariaLabel="Incidents"
      rows={rows}
      loading={loading}
      error={error}
      onRetry={onRetry}
      rowKey={(i) => i.id}
      onRowClick={(i) => onOpen(i.id)}
      page={table.page}
      pageSize={table.size}
      total={total}
      onPageChange={table.setPage}
      onPageSizeChange={table.setSize}
      sort={table.sort}
      onSortChange={table.setSort}
      emptyTitle={emptyTitle}
      emptyDescription={emptyDescription}
      columns={[
        {
          key: 'num',
          header: 'Incident',
          sortKey: 'incidentNumber',
          primary: true,
          render: (i) => (
            <Box>
              <Typography variant="body2" fontWeight={600}>
                {i.incidentNumber} · {humanize(i.incidentType)}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                {[i.wardName, i.location].filter(Boolean).join(' · ') || 'Location not given'}
              </Typography>
            </Box>
          ),
        },
        { key: 'sev', header: 'Severity', sortKey: 'severity', render: (i) => <StatusChip value={i.severity} /> },
        { key: 'occ', header: 'Occurred', sortKey: 'occurredAt', render: (i) => formatDateTime(i.occurredAt) },
        { key: 'patient', header: 'Patient', render: (i) => (i.patientName ? `${i.patientName}${i.patientMrn ? ' · ' + i.patientMrn : ''}` : i.patientLinked ? 'Linked' : '—') },
        ...(showReporter ? [{ key: 'rep', header: 'Reported by', hideOnMobile: true, render: (i: IncidentSummary) => i.reportedByName ?? '—' }] : []),
        { key: 'rev', header: 'Reviewer', hideOnMobile: true, render: (i) => i.reviewerName ?? 'Unassigned' },
        { key: 'status', header: 'Status', sortKey: 'status', render: (i) => <StatusChip value={i.status} /> },
      ]}
    />
  );
}
