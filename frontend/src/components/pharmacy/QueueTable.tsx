'use client';

import { Box, Button, Stack, Typography } from '@mui/material';
import { ShieldAlert } from 'lucide-react';
import type { QueueItem } from '@/lib/api/pharmacy';
import { DataTable, DataTableProps } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';
import { formatDateTime, fromNow, genderShort, humanize } from '@/lib/format';
import { colors } from '@/theme/tokens';
import { AvailabilityChip, MedicationFlags, useDesktopRowClick, WarningSummary } from './PharmacyBits';

type Passthrough = Pick<
  DataTableProps<QueueItem>,
  'rows' | 'loading' | 'error' | 'onRetry' | 'page' | 'pageSize' | 'total' | 'onPageChange' | 'onPageSizeChange' | 'emptyTitle' | 'emptyDescription'
>;

export interface QueueTableProps extends Passthrough {
  onReview: (item: QueueItem) => void;
  onDispense: (item: QueueItem) => void;
  canAct: boolean;
  compact?: boolean;
}

/** Prescription work queue with patient identity, safety warnings and stock. */
export function QueueTable({ onReview, onDispense, canAct, compact, ...table }: QueueTableProps) {
  const rowClick = useDesktopRowClick(onReview);
  return (
    <DataTable<QueueItem>
      {...table}
      ariaLabel="Prescription queue"
      rowKey={(i) => i.orderId}
      onRowClick={rowClick}
      rowSx={(i) => (i.warnings.some((w) => w.type === 'ALLERGY') ? { boxShadow: `inset 3px 0 0 ${colors.danger}` } : undefined)}
      columns={[
        {
          key: 'patient',
          header: 'Patient',
          primary: true,
          render: (i) => (
            <Box sx={{ minWidth: 0 }}>
              <Stack direction="row" spacing={1} alignItems="center">
                <Typography variant="body2" fontWeight={600}>
                  {i.patient.fullName}
                </Typography>
                {i.allergies.length > 0 && (
                  <Box component="span" sx={{ color: colors.danger, display: 'inline-flex' }} aria-label={`Allergies: ${i.allergies.join(', ')}`} title={`Allergies: ${i.allergies.join(', ')}`}>
                    <ShieldAlert size={14} />
                  </Box>
                )}
              </Stack>
              <Typography variant="caption" color="text.secondary" component="div">
                {i.patient.mrn} · {i.patient.age}y {genderShort(i.patient.gender)} · {i.location}
              </Typography>
            </Box>
          ),
        },
        {
          key: 'med',
          header: 'Prescription',
          render: (i) => (
            <Box sx={{ minWidth: 0 }}>
              <Typography variant="body2" fontWeight={500} component="div">
                {i.medicationName} <MedicationFlags highAlert={i.isHighAlert} controlled={i.isControlled} />
              </Typography>
              <Typography variant="caption" color="text.secondary">
                {i.dose} {i.doseUnit} · {i.route} · {humanize(i.frequency)}
              </Typography>
            </Box>
          ),
        },
        {
          key: 'prescriber',
          header: 'Prescriber',
          hideOnMobile: true,
          render: (i) => (
            <Box>
              <Typography variant="body2">{i.prescribedByName ?? '—'}</Typography>
              <Typography variant="caption" color="text.secondary" title={formatDateTime(i.prescribedAt)}>
                {fromNow(i.prescribedAt)}
              </Typography>
            </Box>
          ),
        },
        { key: 'warnings', header: 'Warnings', render: (i) => <WarningSummary warnings={i.warnings} /> },
        ...(compact
          ? []
          : [
              {
                key: 'stock',
                header: 'Stock',
                hideOnMobile: true,
                render: (i: QueueItem) => <AvailabilityChip value={i.availability} stock={i.stockOnHand} unit={i.dispenseUnit} />,
              },
            ]),
        { key: 'status', header: 'Status', render: (i) => <StatusChip value={i.supplyStatus} /> },
        {
          key: 'actions',
          header: '',
          align: 'right',
          render: (i) => (
            <Stack direction="row" spacing={1} justifyContent="flex-end" onClick={(e) => e.stopPropagation()}>
              {canAct && i.supplyStatus === 'READY_TO_DISPENSE' ? (
                <Button size="small" variant="contained" onClick={() => onDispense(i)}>
                  Dispense
                </Button>
              ) : (
                <Button size="small" variant="outlined" onClick={() => onReview(i)}>
                  {i.supplyStatus === 'AWAITING_VERIFICATION' ? 'Review' : 'Open'}
                </Button>
              )}
            </Stack>
          ),
        },
      ]}
    />
  );
}
