'use client';

import { useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Alert, Box, Card, Chip, Grid, InputAdornment, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { Search } from 'lucide-react';
import { ADJUSTMENT_LABEL, billingApi, billingKeys, type AdjustmentStatus, type AdjustmentType } from '@/lib/api/billing';
import { useAuth } from '@/lib/auth/AuthProvider';
import { useTableState } from '@/lib/hooks';
import { PageHeader } from '@/components/ui/PageHeader';
import { AdjustmentsTable } from '@/components/billing/InvoiceSections';

const VIEWS: { label: string; status: AdjustmentStatus[] }[] = [
  { label: 'Pending approval', status: ['PENDING'] },
  { label: 'Approved', status: ['APPROVED'] },
  { label: 'Rejected', status: ['REJECTED'] },
  { label: 'All', status: [] },
];

export default function AdjustmentsPage() {
  const { hasPermission } = useAuth();
  const t = useTableState();
  const [view, setView] = useState(0);
  const [type, setType] = useState<AdjustmentType | ''>('');
  const params = { status: VIEWS[view].status, type: type ? [type] : undefined, q: t.q, page: t.page, size: t.size };
  const q = useQuery({ queryKey: billingKeys.adjustments(params), queryFn: () => billingApi.adjustments(params), placeholderData: keepPreviousData });

  return (
    <>
      <PageHeader
        title="Refunds & adjustments"
        subtitle="Discounts, write-offs, credit/debit notes and refunds. Nothing posts to the ledger until a second person approves it."
        breadcrumbs={[{ label: 'Billing', href: '/billing' }, { label: 'Adjustments' }]}
      />
      {hasPermission('ADJUSTMENT_APPROVE') && view === 0 && (
        <Alert severity="info" sx={{ mb: 5 }}>
          You can approve requests raised by colleagues. Requests you raised yourself must be approved by someone else. New requests are created from
          an invoice.
        </Alert>
      )}
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
                placeholder="Adjustment, invoice, patient or MRN"
                value={t.search}
                onChange={(e) => t.setSearch(e.target.value)}
                slotProps={{ input: { startAdornment: <InputAdornment position="start"><Search size={16} /></InputAdornment> }, htmlInput: { 'aria-label': 'Search adjustments' } }}
              />
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <TextField select fullWidth label="Type" value={type} onChange={(e) => { setType(e.target.value as AdjustmentType | ''); t.setPage(0); }}>
                <MenuItem value="">All types</MenuItem>
                {(Object.keys(ADJUSTMENT_LABEL) as AdjustmentType[]).map((k) => (
                  <MenuItem key={k} value={k}>
                    {ADJUSTMENT_LABEL[k]}
                  </MenuItem>
                ))}
              </TextField>
            </Grid>
          </Grid>
        </Stack>
        <AdjustmentsTable
          rows={q.data?.content}
          showInvoice
          loading={q.isFetching}
          error={q.error}
          onRetry={() => q.refetch()}
          page={t.page}
          pageSize={t.size}
          total={q.data?.totalElements}
          onPageChange={t.setPage}
          onPageSizeChange={t.setSize}
          emptyTitle={view === 0 ? 'No adjustments are waiting for approval' : 'No adjustments match these filters'}
        />
        <Box sx={{ px: 4, pb: 3 }}>
          <Typography variant="caption" color="text.secondary">
            Select an invoice number to open the invoice. Effect on balance: − reduces what the patient owes, + increases it.
          </Typography>
        </Box>
      </Card>
    </>
  );
}
