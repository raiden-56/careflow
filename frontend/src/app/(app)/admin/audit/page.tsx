'use client';

import { useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Box, Button, Card, Divider, Drawer, IconButton, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { Download, X } from 'lucide-react';
import dayjs from 'dayjs';
import { adminApi, AuditOutcome, AuditRow } from '@/lib/api/admin';
import { useDebouncedValue, useToast } from '@/lib/hooks';
import { formatDateTime, humanize, todayLocalDate } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { DataTable, SortState, sortParam } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';
import { DefinitionList } from '@/components/ui/Display';
import { ErrorState, TableSkeleton } from '@/components/ui/States';

export default function AuditPage() {
  const toast = useToast();
  const today = todayLocalDate();
  const [actor, setActor] = useState('');
  const [mrn, setMrn] = useState('');
  const [action, setAction] = useState('');
  const [outcome, setOutcome] = useState<AuditOutcome | ''>('');
  const [from, setFrom] = useState(dayjs(today).subtract(30, 'day').format('YYYY-MM-DD'));
  const [to, setTo] = useState(today);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);
  const [sort, setSort] = useState<SortState | undefined>({ field: 'occurredAt', direction: 'desc' });
  const [selected, setSelected] = useState<string | null>(null);
  const [exporting, setExporting] = useState(false);
  const dActor = useDebouncedValue(actor, 350);
  const dMrn = useDebouncedValue(mrn, 350);
  const invalid = !from || !to || from > to;
  const filters = { actor: dActor || undefined, patientMrn: dMrn || undefined, action: action || undefined, outcome: outcome || undefined, from, to };
  const params = { ...filters, page, size, sort: sortParam(sort) };
  const q = useQuery({ queryKey: ['audit', params], queryFn: () => adminApi.audit(params), placeholderData: keepPreviousData, enabled: !invalid });
  const actions = useQuery({ queryKey: ['audit', 'actions'], queryFn: adminApi.auditActions });
  const detail = useQuery({ queryKey: ['audit', 'event', selected], queryFn: () => adminApi.auditEvent(selected!), enabled: !!selected });
  const reset = () => setPage(0);

  const doExport = async () => {
    setExporting(true);
    try {
      await adminApi.exportAudit(filters);
      toast.success('Audit export downloaded (maximum 10,000 rows)');
    } catch (e) {
      toast.error(e);
    } finally {
      setExporting(false);
    }
  };

  return (
    <>
      <PageHeader
        title="Audit logs"
        subtitle="Append-only record of sign-ins, sensitive reads, denied access and configuration changes in this hospital."
        breadcrumbs={[{ label: 'Administration', href: '/admin' }, { label: 'Audit logs' }]}
        actions={
          <Button variant="outlined" startIcon={<Download size={16} />} onClick={doExport} disabled={exporting || invalid}>
            {exporting ? 'Preparing…' : 'Export CSV'}
          </Button>
        }
      />
      <Card>
        <Box sx={{ p: 4, display: 'grid', gap: 3, gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr', lg: 'repeat(6, minmax(0, 1fr))' } }}>
          <TextField label="Actor name" value={actor} onChange={(e) => { setActor(e.target.value); reset(); }} />
          <TextField label="Patient MRN" value={mrn} onChange={(e) => { setMrn(e.target.value); reset(); }} placeholder="MRN0000001" />
          <TextField select label="Action" value={action} onChange={(e) => { setAction(e.target.value); reset(); }}>
            <MenuItem value="">All actions</MenuItem>
            {(actions.data ?? []).map((a) => (
              <MenuItem key={a} value={a}>
                {humanize(a)}
              </MenuItem>
            ))}
          </TextField>
          <TextField select label="Outcome" value={outcome} onChange={(e) => { setOutcome(e.target.value as AuditOutcome); reset(); }}>
            <MenuItem value="">Any outcome</MenuItem>
            <MenuItem value="SUCCESS">Success</MenuItem>
            <MenuItem value="DENIED">Denied</MenuItem>
            <MenuItem value="FAILURE">Failed</MenuItem>
          </TextField>
          <TextField label="From" type="date" value={from} onChange={(e) => { setFrom(e.target.value); reset(); }} slotProps={{ inputLabel: { shrink: true } }} error={invalid} />
          <TextField label="To" type="date" value={to} onChange={(e) => { setTo(e.target.value); reset(); }} slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: today } }} error={invalid} />
        </Box>
        <DataTable<AuditRow>
          ariaLabel="Audit events"
          dense
          rows={q.data?.content}
          loading={q.isFetching}
          error={q.error}
          onRetry={() => q.refetch()}
          rowKey={(e) => e.id}
          onRowClick={(e) => setSelected(e.id)}
          page={page}
          pageSize={size}
          total={q.data?.totalElements}
          onPageChange={setPage}
          onPageSizeChange={(s) => { setSize(s); setPage(0); }}
          pageSizeOptions={[25, 50, 100]}
          sort={sort}
          onSortChange={setSort}
          emptyTitle="No audit events match these filters"
          rowSx={(e) => (e.outcome === 'DENIED' ? { bgcolor: 'rgba(180,35,24,0.04)' } : undefined)}
          columns={[
            { key: 'time', header: 'When', sortKey: 'occurredAt', render: (e) => formatDateTime(e.occurredAt, 'DD MMM YYYY, HH:mm:ss') },
            {
              key: 'actor',
              header: 'Actor',
              sortKey: 'actor',
              primary: true,
              render: (e) => (
                <Box>
                  <Typography variant="body2" fontWeight={500}>
                    {e.actorName ?? 'Unknown'}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {e.actorRole ? humanize(e.actorRole) : ''}
                  </Typography>
                </Box>
              ),
            },
            { key: 'action', header: 'Action', sortKey: 'action', render: (e) => humanize(e.action) },
            { key: 'entity', header: 'Record', hideOnMobile: true, render: (e) => (e.entityType ? humanize(e.entityType) : '—') },
            { key: 'mrn', header: 'Patient', render: (e) => e.patientMrn ?? '—' },
            { key: 'outcome', header: 'Outcome', sortKey: 'outcome', render: (e) => <StatusChip value={e.outcome} /> },
            { key: 'summary', header: 'Summary', hideOnMobile: true, render: (e) => <Typography variant="body2" sx={{ maxWidth: 360, wordBreak: 'break-word' }}>{e.summary ?? '—'}</Typography> },
          ]}
        />
      </Card>

      <Drawer anchor="right" open={!!selected} onClose={() => setSelected(null)} slotProps={{ paper: { sx: { width: { xs: '100%', sm: 520 } } } }}>
        <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ px: 5, py: 4 }}>
          <Typography variant="h4" component="h2">
            Audit event
          </Typography>
          <IconButton aria-label="Close" onClick={() => setSelected(null)}>
            <X size={18} />
          </IconButton>
        </Stack>
        <Divider />
        <Box sx={{ p: 5 }}>
          {detail.isLoading ? (
            <TableSkeleton rows={6} columns={2} />
          ) : detail.error ? (
            <ErrorState error={detail.error} />
          ) : detail.data ? (
            <Stack spacing={4}>
              <DefinitionList
                items={[
                  { label: 'When', value: formatDateTime(detail.data.occurredAt, 'DD MMM YYYY, HH:mm:ss') },
                  { label: 'Outcome', value: <StatusChip value={detail.data.outcome} /> },
                  { label: 'Actor', value: `${detail.data.actorName ?? 'Unknown'}${detail.data.actorRole ? ' (' + humanize(detail.data.actorRole) + ')' : ''}` },
                  { label: 'Action', value: humanize(detail.data.action) },
                  { label: 'Record type', value: detail.data.entityType ? humanize(detail.data.entityType) : '—' },
                  { label: 'Record id', value: detail.data.entityId ? <Box component="span" sx={{ fontFamily: 'monospace', fontSize: 12 }}>{detail.data.entityId}</Box> : '—' },
                  { label: 'Patient', value: detail.data.patientMrn ? `${detail.data.patientName ?? ''} (${detail.data.patientMrn})` : '—' },
                  { label: 'IP address', value: detail.data.ipAddress ?? '—' },
                  { label: 'Summary', value: detail.data.summary ?? '—', span: 2 },
                  { label: 'Trace id', value: detail.data.traceId ?? '—', span: 2 },
                ]}
              />
              <Box>
                <Typography variant="subtitle2" sx={{ mb: 2 }}>
                  Details
                </Typography>
                {detail.data.details && Object.keys(detail.data.details).length ? (
                  <Box
                    component="pre"
                    sx={{ m: 0, p: 3, bgcolor: 'background.default', border: 1, borderColor: 'divider', borderRadius: 2, fontSize: 12, overflowX: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}
                  >
                    {JSON.stringify(detail.data.details, null, 2)}
                  </Box>
                ) : (
                  <Typography variant="body2" color="text.secondary">
                    No additional details were recorded for this event.
                  </Typography>
                )}
              </Box>
            </Stack>
          ) : null}
        </Box>
      </Drawer>
    </>
  );
}
