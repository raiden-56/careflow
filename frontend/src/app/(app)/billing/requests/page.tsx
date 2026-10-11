'use client';

import { useState } from 'react';
import Link from 'next/link';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Box, Button, Card, Chip, Grid, InputAdornment, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { Search } from 'lucide-react';
import { billingApi, billingKeys, REQUEST_TYPE_LABEL, type RequestStatus, type RequestType, type RequestView } from '@/lib/api/billing';
import { useAuth } from '@/lib/auth/AuthProvider';
import { useTableState } from '@/lib/hooks';
import { formatDateTime, fromNow } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { DataTable } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';
import { RespondDialog } from '@/components/billing/RespondDialog';

const VIEWS: { label: string; status: RequestStatus[] }[] = [
  { label: 'Needs response', status: ['OPEN', 'IN_REVIEW'] },
  { label: 'Resolved', status: ['RESOLVED'] },
  { label: 'Not upheld', status: ['REJECTED'] },
  { label: 'All', status: [] },
];

export default function BillingRequestsPage() {
  const { hasPermission } = useAuth();
  const t = useTableState();
  const [view, setView] = useState(0);
  const [type, setType] = useState<RequestType | ''>('');
  const [responding, setResponding] = useState<RequestView | null>(null);
  const params = { status: VIEWS[view].status, type: type || undefined, q: t.q, page: t.page, size: t.size };
  const q = useQuery({ queryKey: billingKeys.requests(params), queryFn: () => billingApi.requests(params), placeholderData: keepPreviousData });
  const canRespond = hasPermission('BILLING_WRITE');

  return (
    <>
      <PageHeader
        title="Patient billing queries"
        subtitle="Questions, disputes and itemization requests raised by patients and their caregivers from the portal."
        breadcrumbs={[{ label: 'Billing', href: '/billing' }, { label: 'Patient queries' }]}
      />
      <Card>
        <Stack spacing={3} sx={{ p: 4 }}>
          <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
            {VIEWS.map((v, i) => (
              <Chip
                key={v.label}
                label={v.label}
                clickable
                color={view === i ? 'primary' : 'default'}
                variant={view === i ? 'filled' : 'outlined'}
                onClick={() => {
                  setView(i);
                  t.setPage(0);
                }}
              />
            ))}
          </Stack>
          <Grid container spacing={3}>
            <Grid size={{ xs: 12, md: 5 }}>
              <TextField
                fullWidth
                placeholder="Subject, patient, MRN or invoice"
                value={t.search}
                onChange={(e) => t.setSearch(e.target.value)}
                slotProps={{ input: { startAdornment: <InputAdornment position="start"><Search size={16} /></InputAdornment> }, htmlInput: { 'aria-label': 'Search queries' } }}
              />
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <TextField select fullWidth label="Type" value={type} onChange={(e) => { setType(e.target.value as RequestType | ''); t.setPage(0); }}>
                <MenuItem value="">All types</MenuItem>
                {(Object.keys(REQUEST_TYPE_LABEL) as RequestType[]).map((k) => (
                  <MenuItem key={k} value={k}>
                    {REQUEST_TYPE_LABEL[k]}
                  </MenuItem>
                ))}
              </TextField>
            </Grid>
          </Grid>
        </Stack>
        <DataTable<RequestView>
          ariaLabel="Patient billing queries"
          rows={q.data?.content}
          loading={q.isFetching}
          error={q.error}
          onRetry={() => q.refetch()}
          rowKey={(r) => r.id}
          page={t.page}
          pageSize={t.size}
          total={q.data?.totalElements}
          onPageChange={t.setPage}
          onPageSizeChange={t.setSize}
          emptyTitle={view === 0 ? 'No queries are waiting for a response' : 'No queries in this view'}
          columns={[
            {
              key: 'subject',
              header: 'Query',
              primary: true,
              render: (r) => (
                <Box sx={{ minWidth: 0, maxWidth: 420 }}>
                  <Typography variant="body2" fontWeight={600}>
                    {r.subject}
                  </Typography>
                  <Typography variant="caption" color="text.secondary" component="div" sx={{ overflow: 'hidden', textOverflow: 'ellipsis', display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical' }}>
                    {r.description}
                  </Typography>
                  {r.lineItemDescription && (
                    <Typography variant="caption" color="text.secondary" component="div">
                      About: {r.lineItemDescription}
                    </Typography>
                  )}
                </Box>
              ),
            },
            { key: 'type', header: 'Type', render: (r) => <Chip size="small" variant="outlined" label={REQUEST_TYPE_LABEL[r.requestType]} /> },
            {
              key: 'patient',
              header: 'Patient / invoice',
              render: (r) => (
                <Box>
                  <Typography variant="body2">{r.patientName}</Typography>
                  <Typography component={Link} href={`/billing/invoices/${r.invoiceId}`} variant="caption" color="primary">
                    {r.invoiceNumber}
                  </Typography>
                </Box>
              ),
            },
            {
              key: 'raised',
              header: 'Raised',
              hideOnMobile: true,
              render: (r) => (
                <Box>
                  <Typography variant="body2">{r.raisedByName}</Typography>
                  <Typography variant="caption" color="text.secondary" title={formatDateTime(r.raisedAt)}>
                    {fromNow(r.raisedAt)}
                  </Typography>
                </Box>
              ),
            },
            { key: 'status', header: 'Status', render: (r) => <StatusChip value={r.status} /> },
            {
              key: 'act',
              header: '',
              align: 'right',
              render: (r) =>
                canRespond && (r.status === 'OPEN' || r.status === 'IN_REVIEW') ? (
                  <Button size="small" variant="outlined" onClick={() => setResponding(r)}>
                    Respond
                  </Button>
                ) : r.response ? (
                  <Typography variant="caption" color="text.secondary">
                    Answered {fromNow(r.respondedAt)}
                  </Typography>
                ) : null,
            },
          ]}
        />
      </Card>
      <RespondDialog request={responding} onClose={() => setResponding(null)} />
    </>
  );
}
