'use client';

import { Chip, ChipProps } from '@mui/material';
import { alpha } from '@mui/material/styles';
import { colors } from '@/theme/tokens';
import { statusMeta, StatusTone } from '@/lib/status';

const TONES: Record<StatusTone, { fg: string; bg: string }> = {
  success: { fg: colors.success, bg: colors.successSoft },
  warning: { fg: colors.warning, bg: colors.warningSoft },
  error: { fg: colors.danger, bg: colors.dangerSoft },
  info: { fg: colors.info, bg: colors.infoSoft },
  neutral: { fg: colors.neutral, bg: colors.neutralSoft },
  primary: { fg: colors.primaryDark, bg: colors.primarySoft },
  secondary: { fg: colors.secondary, bg: colors.secondarySoft },
};

export interface StatusChipProps extends Omit<ChipProps, 'color' | 'label'> {
  /** Backend status/enum value, e.g. "PARTIALLY_PAID" (label & colour come from lib/status). */
  value?: string | null;
  /** Override the label. */
  label?: string;
  /** Override the tone. */
  tone?: StatusTone;
  /** Adds a leading dot for dense tables. */
  dot?: boolean;
}

/** Consistent status badge used for every workflow state across the app. */
export function StatusChip({ value, label, tone, dot, size = 'small', sx, ...rest }: StatusChipProps) {
  const meta = statusMeta(value);
  const t = TONES[tone ?? meta.tone];
  return (
    <Chip
      size={size}
      label={
        dot ? (
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
            <span style={{ width: 6, height: 6, borderRadius: 999, background: t.fg, display: 'inline-block' }} />
            {label ?? meta.label}
          </span>
        ) : (
          label ?? meta.label
        )
      }
      sx={{
        color: t.fg,
        backgroundColor: t.bg,
        border: `1px solid ${alpha(t.fg, 0.18)}`,
        ...sx,
      }}
      {...rest}
    />
  );
}

export function toneColors(tone: StatusTone) {
  return TONES[tone];
}
