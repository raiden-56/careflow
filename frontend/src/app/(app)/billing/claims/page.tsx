'use client';

import { useState } from 'react';
import Link from 'next/link';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Box, Card, Chip, Grid, InputAdornment, Stack, TextField, Typography } from '@mui/material';
import { Search } from 'lucide-react';
import { billingApi, billingKeys, type ClaimStatus, type ClaimView } from '@/lib/api/billing';
import { useTableState } from '@/lib/hooks';
import { formatDate, humanize } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { DataTable } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';
import { Money } from '@/components/ui/Display';
import { ClaimActions } from '@/components/billing/ClaimDialogs';

const VIEWS: { label: string; status: ClaimStatus[] }[] = [
  { label: 'In progress', status: ['DRAFT', 'SUBMITTED', 'UNDER_REVIEW'] },
  { label: 'Awaiting settlement', status: ['APPROVED', 'PARTIALLY_APPROVED'] },
  { label: 'Settled', status: ['SETTLED'] },
  { label: 'Rejected', status: ['REJECTED'] },
  { label: 'All', status: [] },
];

export default function ClaimsPage() {
  const t = useTableState();
  const [view, setView] = useState(4);
  const params = { status: VIEWS[view].status, q: t.q, page: t.page, size: t.size };
  const q = useQuery({ queryKey: billingKeys.claims(params), queryFn: () => billingApi.claims(params), placeholderData: keepPreviousData });

  return (
    <>
      <PageHeader
        title="Insurance & third-party claims"
        subtitle="Track each claim from draft to settlement. Approved amounts reduce a patient's balance only when the payer settles."
        breadcrumbs={[{ label: 'Billing', href: '/billing' }, { label: 'Claims' }]}
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
          <Grid container>
            <Grid size={{ xs: 12, md: 5 }}>
              <TextField
                fullWidth
                placeholder="Claim, payer, policy, invoice, patient or MRN"
                value={t.search}
                onChange={(e) => t.setSearch(e.target.value)}
                slotProps={{ input: { startAdornment: <InputAdornment position="start"><Search size={16} /></InputAdornment> }, htmlInput: { 'aria-label': 'Search claims' } }}
              />
            </Grid>
          </Grid>
        </Stack>
        <DataTable<ClaimView>
          ariaLabel="Claims"
          rows={q.data?.content}
          loading={q.isFetching}
          error={q.error}
          onRetry={() => q.refetch()}
          rowKey={(c) => c.id}
          page={t.page}
          pageSize={t.size}
          total={q.data?.totalElements}
          onPageChange={t.setPage}
          onPageSizeChange={t.setSize}
          emptyTitle="No claims in this view"
          emptyDescription="Claims are created from an invoice's Claims tab."
          columns={[
            {
              key: 'claim',
              header: 'Claim',
              primary: true,
              render: (c) => (
                <Box sx={{ minWidth: 0 }}>
                  <Typography variant="body2" fontWeight={600}>
                    {c.payerName}
                  </Typography>
                  <Typography variant="caption" color="text.secondary" component="div">
                    {c.claimNumber} · {humanize(c.payerType)} · policy {c.policyNumber}
                    {c.preAuthNumber ? ` · pre-auth ${c.preAuthNumber}` : ''}
                  </Typography>
                </Box>
              ),
            },
            {
              key: 'patient',
              header: 'Patient / invoice',
              render: (c) => (
                <Box>
                  <Typography variant="body2">{c.patientName}</Typography>
                  <Typography component={Link} href={`/billing/invoices/${c.invoiceId}`} variant="caption" color="primary">
                    {c.invoiceNumber}
                  </Typography>
                </Box>
              ),
            },
            { key: 'status', header: 'Status', render: (c) => <StatusChip value={c.status} /> },
            { key: 'claimed', header: 'Claimed', align: 'right', render: (c) => <Money value={c.claimedAmount} /> },
            { key: 'approved', header: 'Approved', align: 'right', render: (c) => <Money value={c.approvedAmount} /> },
            { key: 'settled', header: 'Settled', align: 'right', render: (c) => <Money value={c.settledAmount} /> },
            { key: 'pending', header: 'Pending settlement', align: 'right', hideOnMobile: true, render: (c) => <Money value={c.pendingSettlement} strong /> },
            { key: 'updated', header: 'Last update', hideOnMobile: true, render: (c) => formatDate(c.updatedAt ?? c.createdAt) },
            { key: 'actions', header: '', render: (c) => <ClaimActions claim={c} /> },
          ]}
        />
      </Card>
    </>
  );
}
