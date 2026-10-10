'use client';

import { ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Box, Divider, Drawer, IconButton, Skeleton, Stack, Typography } from '@mui/material';
import { X } from 'lucide-react';
import { pharmacyApi, pharmacyKeys } from '@/lib/api/pharmacy';
import type { UUID } from '@/lib/api/types';
import { DefinitionList, Money } from '@/components/ui/Display';
import { StatusChip } from '@/components/ui/StatusChip';
import { ErrorState } from '@/components/ui/States';
import { formatDate, formatDateTime } from '@/lib/format';
import { MovementList } from './MovementList';

function SideDrawer({ open, onClose, title, children }: { open: boolean; onClose: () => void; title: string; children: ReactNode }) {
  return (
    <Drawer anchor="right" open={open} onClose={onClose} slotProps={{ paper: { sx: { width: { xs: '100%', sm: 480 } } } }}>
      <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ px: 5, py: 4, borderBottom: 1, borderColor: 'divider' }}>
        <Typography variant="h3" component="h2">
          {title}
        </Typography>
        <IconButton onClick={onClose} aria-label="Close">
          <X size={18} />
        </IconButton>
      </Stack>
      <Box sx={{ p: 5, overflowY: 'auto' }}>{children}</Box>
    </Drawer>
  );
}

/** Batch details and its complete movement ledger. */
export function BatchDrawer({ batchId, onClose }: { batchId: UUID | null; onClose: () => void }) {
  const q = useQuery({ queryKey: pharmacyKeys.batch(batchId ?? 'none'), queryFn: () => pharmacyApi.batch(batchId!), enabled: !!batchId });
  const b = q.data?.batch;
  return (
    <SideDrawer open={!!batchId} onClose={onClose} title={b ? `Batch ${b.batchNumber}` : 'Batch'}>
      {q.isLoading && <Skeleton height={240} />}
      {q.error && <ErrorState error={q.error} onRetry={() => q.refetch()} />}
      {b && q.data && (
        <Stack spacing={5}>
          <Box>
            <Typography variant="body1" fontWeight={600}>
              {b.medicationName}
            </Typography>
            <Stack direction="row" spacing={1} sx={{ mt: 1 }}>
              <StatusChip value={b.expired && b.status === 'ACTIVE' ? 'EXPIRED' : b.status} />
              {b.usable && <StatusChip value="USABLE" label="Usable" tone="success" />}
            </Stack>
          </Box>
          <DefinitionList
            items={[
              { label: 'On hand', value: `${b.quantityOnHand} of ${b.quantityReceived} ${b.dispenseUnit ?? ''} received` },
              { label: 'Expiry', value: `${formatDate(b.expiryDate)} (${b.expired ? 'expired' : `${b.daysToExpiry} days left`})` },
              { label: 'Unit cost', value: <Money value={b.unitCost} /> },
              { label: 'Stock value at cost', value: <Money value={b.stockValue} /> },
              { label: 'Supplier', value: b.supplier },
              { label: 'Received', value: `${formatDateTime(b.receivedAt)}${b.receivedByName ? ' · ' + b.receivedByName : ''}` },
            ]}
          />
          <Divider />
          <Box>
            <Typography variant="h4" component="h3" sx={{ mb: 2 }}>
              Movement history
            </Typography>
            <MovementList movements={q.data.movements} />
          </Box>
        </Stack>
      )}
    </SideDrawer>
  );
}

/** Dispensation with its stock movements and billing lines (posted, reversed, re-posted). */
export function DispensationDrawer({ dispensationId, onClose }: { dispensationId: UUID | null; onClose: () => void }) {
  const q = useQuery({
    queryKey: pharmacyKeys.dispensation(dispensationId ?? 'none'),
    queryFn: () => pharmacyApi.dispensation(dispensationId!),
    enabled: !!dispensationId,
  });
  const d = q.data?.dispensation;
  return (
    <SideDrawer open={!!dispensationId} onClose={onClose} title="Dispensation">
      {q.isLoading && <Skeleton height={240} />}
      {q.error && <ErrorState error={q.error} onRetry={() => q.refetch()} />}
      {d && q.data && (
        <Stack spacing={5}>
          <Box>
            <Typography variant="body1" fontWeight={600}>
              {d.medicationName}
            </Typography>
            <Typography variant="body2" color="text.secondary">
              {d.patientName} · {d.patientMrn} · {d.admissionNumber}
            </Typography>
            <Box sx={{ mt: 1 }}>
              <StatusChip value={d.status} />
            </Box>
          </Box>
          <DefinitionList
            items={[
              { label: 'Dispensed', value: `${d.quantity} ${d.dispenseUnit ?? ''}` },
              { label: 'Returned', value: d.returnedQuantity },
              { label: 'Batch', value: `${d.batchNumber} (exp ${formatDate(d.batchExpiry)})` },
              { label: 'Current charge', value: <Money value={d.chargedAmount} /> },
              { label: 'Dispensed by', value: `${d.dispensedByName ?? '—'} · ${formatDateTime(d.dispensedAt)}`, span: 2 },
              { label: 'Notes', value: d.notes, span: 2 },
            ]}
          />
          <Divider />
          <Box>
            <Typography variant="h4" component="h3" sx={{ mb: 2 }}>
              Billing lines
            </Typography>
            {q.data.charges.length === 0 ? (
              <Typography variant="body2" color="text.secondary">
                No charge lines.
              </Typography>
            ) : (
              <Stack spacing={2}>
                {q.data.charges.map((c) => (
                  <Stack key={c.id} direction="row" spacing={3} justifyContent="space-between" alignItems="flex-start">
                    <Box sx={{ minWidth: 0 }}>
                      <Typography variant="body2" sx={{ wordBreak: 'break-word' }}>
                        {c.description}
                      </Typography>
                      <Typography variant="caption" color="text.secondary">
                        {formatDateTime(c.postedAt)}
                        {c.reason ? ` · ${c.reason}` : ''}
                      </Typography>
                    </Box>
                    <Stack alignItems="flex-end" spacing={0.5} sx={{ flexShrink: 0 }}>
                      <Money value={c.totalAmount} strong />
                      <StatusChip value={c.status} />
                    </Stack>
                  </Stack>
                ))}
              </Stack>
            )}
          </Box>
          <Divider />
          <Box>
            <Typography variant="h4" component="h3" sx={{ mb: 2 }}>
              Stock movements
            </Typography>
            <MovementList movements={q.data.movements} />
          </Box>
        </Stack>
      )}
    </SideDrawer>
  );
}
