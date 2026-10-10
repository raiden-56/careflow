'use client';

import { Box, Stack, Typography } from '@mui/material';
import type { QueueItem } from '@/lib/api/pharmacy';
import { DefinitionList } from '@/components/ui/Display';
import { StatusChip } from '@/components/ui/StatusChip';
import { formatDateTime, fromNow, humanize } from '@/lib/format';
import { MedicationFlags } from './PharmacyBits';

/** Prescription details as written by the prescriber (read-only for pharmacy). */
export function OrderSummary({ item }: { item: QueueItem }) {
  return (
    <Box>
      <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap" useFlexGap sx={{ mb: 1 }}>
        <Typography variant="h4" component="p">
          {item.medicationName}
        </Typography>
        <MedicationFlags highAlert={item.isHighAlert} controlled={item.isControlled} />
        <StatusChip value={item.supplyStatus} />
        {item.dischargeMedication && <StatusChip value="DISCHARGE" label="Discharge medication" tone="info" />}
      </Stack>
      <Typography variant="body1" sx={{ mb: 3 }}>
        <strong>
          {item.dose} {item.doseUnit}
        </strong>{' '}
        · {item.route} · {humanize(item.frequency)}
        {item.scheduleTimes ? ` (${item.scheduleTimes})` : ''}
      </Typography>
      <DefinitionList
        items={[
          { label: 'Indication', value: item.indication },
          { label: 'Prescriber', value: item.prescribedByName ? `${item.prescribedByName} · ${formatDateTime(item.prescribedAt)}` : formatDateTime(item.prescribedAt) },
          { label: 'Instructions', value: item.instructions, span: 2 },
          { label: 'Course', value: `${formatDateTime(item.startAt)} → ${item.endAt ? formatDateTime(item.endAt) : 'until reviewed'}` },
          {
            label: 'Pharmacist verification',
            value: item.verified ? `${item.verifiedByName ?? 'Verified'} · ${formatDateTime(item.verifiedAt)}` : 'Not yet verified',
          },
          {
            label: 'Supplied',
            value:
              item.netDispensedQuantity > 0
                ? `${item.netDispensedQuantity} ${item.dispenseUnit} in total · last ${fromNow(item.lastDispensedAt)}`
                : 'Nothing supplied yet',
          },
          { label: 'Usable stock', value: `${item.stockOnHand.toLocaleString('en-IN')} ${item.dispenseUnit}` },
        ]}
      />
    </Box>
  );
}
