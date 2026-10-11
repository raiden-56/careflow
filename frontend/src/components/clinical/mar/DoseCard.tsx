'use client';

import Link from 'next/link';
import { Box, Button, Chip, Stack, Tooltip, Typography } from '@mui/material';
import { ShieldAlert, TriangleAlert } from 'lucide-react';
import type { DoseView } from '@/lib/api/clinical';
import { formatDateTime, formatTime } from '@/lib/format';
import { StatusChip } from '@/components/ui/StatusChip';
import { colors } from '@/theme/tokens';
import { doseSig, isPendingDose } from './DoseDialogs';

export interface DoseCardProps {
  dose: DoseView;
  /** Show the patient (with a link to their record). */
  patientHref?: string;
  onGive?: (d: DoseView) => void;
  onException?: (d: DoseView) => void;
  /** Show the full date instead of only the time. */
  showDate?: boolean;
}

/** One scheduled dose with status, safety flags and quick actions; stacks on narrow screens. */
export function DoseCard({ dose, patientHref, onGive, onException, showDate }: DoseCardProps) {
  const overdue = dose.displayStatus === 'OVERDUE';
  const due = dose.displayStatus === 'DUE';
  const pending = isPendingDose(dose);
  const canGive = pending && (!dose.orderStatus || dose.orderStatus === 'ACTIVE');
  const accent = overdue ? colors.danger : due ? colors.info : dose.status === 'ADMINISTERED' ? colors.success : dose.status === 'SCHEDULED' ? colors.border : colors.warning;
  const allergies = dose.patient?.allergies ?? [];
  return (
    <Box
      sx={{
        border: 1,
        borderColor: 'divider',
        borderLeft: `4px solid ${accent}`,
        borderRadius: 2,
        p: 3,
        bgcolor: overdue ? colors.dangerSoft : 'background.paper',
      }}
    >
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} justifyContent="space-between" alignItems={{ sm: 'center' }}>
        <Stack direction="row" spacing={3} sx={{ minWidth: 0, flex: 1 }}>
          <Box sx={{ width: { xs: 48, sm: showDate ? 92 : 52 }, flexShrink: 0 }}>
            <Typography variant="body2" fontWeight={700} sx={{ fontVariantNumeric: 'tabular-nums' }}>
              {showDate ? formatDateTime(dose.scheduledAt, 'DD MMM HH:mm') : formatTime(dose.scheduledAt)}
            </Typography>
            {overdue && dose.minutesLate > 0 && (
              <Typography variant="caption" color="error.main" component="div">
                +{dose.minutesLate} min
              </Typography>
            )}
          </Box>
          <Box sx={{ minWidth: 0 }}>
            {patientHref && dose.patient && (
              <Typography variant="body2" sx={{ mb: 0.5 }}>
                <Box component={Link} href={patientHref} sx={{ color: 'primary.main', fontWeight: 600, textDecoration: 'none' }}>
                  {dose.patient.patientName}
                </Box>{' '}
                <Typography component="span" variant="caption" color="text.secondary">
                  {dose.patient.location ?? 'Awaiting bed'}
                </Typography>
              </Typography>
            )}
            <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
              <Typography variant="body2" fontWeight={600} sx={{ wordBreak: 'break-word' }}>
                {dose.medicationName}
              </Typography>
              {dose.highAlert && <Chip size="small" color="error" icon={<ShieldAlert size={13} />} label="High alert" />}
              {dose.controlled && <Chip size="small" color="warning" variant="outlined" label="Controlled" />}
              {patientHref && allergies.length > 0 && (
                <Tooltip title={`Allergies: ${allergies.join(', ')}`}>
                  <Chip size="small" variant="outlined" color="warning" icon={<TriangleAlert size={13} />} label="Allergies" />
                </Tooltip>
              )}
            </Stack>
            <Typography variant="caption" color="text.secondary" component="div">
              {doseSig(dose)}
              {dose.instructions ? ` · ${dose.instructions}` : ''}
            </Typography>
            {dose.status === 'ADMINISTERED' && (
              <Typography variant="caption" component="div" sx={{ mt: 0.5 }}>
                Given {formatDateTime(dose.administeredAt)} · {dose.doseGiven} {dose.routeGiven}
                {dose.recordedBy ? ` · ${dose.recordedBy}` : ''}
                {dose.witness ? ` · witness ${dose.witness}` : ''}
              </Typography>
            )}
            {dose.reason && dose.status !== 'ADMINISTERED' && (
              <Typography variant="caption" component="div" sx={{ mt: 0.5, wordBreak: 'break-word' }}>
                Reason: {dose.reason}
                {dose.recordedBy ? ` · ${dose.recordedBy}` : ''}
              </Typography>
            )}
            {pending && dose.orderStatus === 'ON_HOLD' && (
              <Typography variant="caption" color="warning.main" component="div" sx={{ mt: 0.5 }}>
                Prescription on hold - do not give.
              </Typography>
            )}
          </Box>
        </Stack>
        <Stack direction="row" spacing={1} alignItems="center" sx={{ flexShrink: 0, pl: { xs: 15, sm: 0 } }} flexWrap="wrap" useFlexGap>
          <StatusChip value={dose.displayStatus} />
          {canGive && onGive && (
            <Button size="small" variant="contained" onClick={() => onGive(dose)} aria-label={`Give ${dose.medicationName}`}>
              Give
            </Button>
          )}
          {pending && onException && (
            <Button size="small" variant="outlined" color="warning" onClick={() => onException(dose)} aria-label={`Record exception for ${dose.medicationName}`}>
              Exception
            </Button>
          )}
        </Stack>
      </Stack>
    </Box>
  );
}
