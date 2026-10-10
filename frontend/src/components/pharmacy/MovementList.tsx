'use client';

import { Box, Stack, Typography } from '@mui/material';
import type { MovementView } from '@/lib/api/pharmacy';
import { StatusChip } from '@/components/ui/StatusChip';
import { EmptyState } from '@/components/ui/States';
import { formatDateTime } from '@/lib/format';
import { colors } from '@/theme/tokens';

/** Append-only stock ledger entries, newest first as provided. */
export function MovementList({ movements, showBatch }: { movements: MovementView[]; showBatch?: boolean }) {
  if (movements.length === 0) {
    return <EmptyState title="No stock movements" compact />;
  }
  return (
    <Stack divider={<Box sx={{ borderTop: 1, borderColor: 'divider' }} />}>
      {movements.map((m) => (
        <Stack key={m.id} direction="row" spacing={3} sx={{ py: 2.5 }} alignItems="flex-start">
          <Box sx={{ minWidth: 0, flex: 1 }}>
            <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap" useFlexGap>
              <StatusChip value={m.type} />
              <Typography variant="caption" color="text.secondary">
                {formatDateTime(m.performedAt)} · {m.performedByName ?? 'System'}
              </Typography>
            </Stack>
            {showBatch && (
              <Typography variant="body2" sx={{ mt: 1 }}>
                {m.medicationName} · batch {m.batchNumber}
              </Typography>
            )}
            {m.reason && (
              <Typography variant="body2" color="text.secondary" sx={{ mt: 1, wordBreak: 'break-word' }}>
                {m.reason}
              </Typography>
            )}
          </Box>
          <Box sx={{ textAlign: 'right', flexShrink: 0 }}>
            <Typography
              variant="body2"
              fontWeight={700}
              sx={{ fontVariantNumeric: 'tabular-nums', color: m.quantity < 0 ? colors.danger : colors.success }}
            >
              {m.quantity > 0 ? '+' : ''}
              {m.quantity}
            </Typography>
            <Typography variant="caption" color="text.secondary" sx={{ whiteSpace: 'nowrap' }}>
              balance {m.balanceAfter}
            </Typography>
          </Box>
        </Stack>
      ))}
    </Stack>
  );
}
