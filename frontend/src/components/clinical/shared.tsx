'use client';

import { ReactNode, useCallback } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Box, Chip, ChipProps, Stack, Typography } from '@mui/material';
import { alpha } from '@mui/material/styles';
import { colors } from '@/theme/tokens';

/**
 * Small helpers shared by the clinical record panels (orders, investigations, record, trends).
 */

/** Decimal values arrive from the API as JSON strings; convert for display/charting only. */
export function num(v: unknown): number | undefined {
  if (v === null || v === undefined || v === '') return undefined;
  const n = typeof v === 'number' ? v : Number(v);
  return Number.isFinite(n) ? n : undefined;
}

/** Trims trailing zeros from a numeric string/number for display ("6.4000" -> "6.4"). */
export function fmtNum(v: unknown, maxDecimals = 2): string {
  const n = num(v);
  if (n === undefined) return '—';
  return String(Number(n.toFixed(maxDecimals)));
}

/**
 * Invalidates everything that can change after a clinical mutation on an admission: the admission's
 * record, cross-patient worklists and the admission timeline.
 */
export function useClinicalInvalidate() {
  const queryClient = useQueryClient();
  return useCallback(
    (admissionId?: string, extra?: { patientId?: string }) => {
      if (admissionId) {
        queryClient.invalidateQueries({ queryKey: ['clinical', admissionId] });
        queryClient.invalidateQueries({ queryKey: ['admission', admissionId, 'timeline'] });
      }
      if (extra?.patientId) queryClient.invalidateQueries({ queryKey: ['clinical-patient', extra.patientId] });
      queryClient.invalidateQueries({ queryKey: ['clinical-lists'] });
    },
    [queryClient],
  );
}

/** Outlined flag chip with a semantic colour (used for order/result flags). */
export function FlagChip({
  label,
  tone = 'neutral',
  icon,
  title,
  ...rest
}: { label: ReactNode; tone?: 'danger' | 'warning' | 'info' | 'success' | 'neutral' | 'primary'; icon?: ReactNode; title?: string } & Omit<
  ChipProps,
  'label' | 'color' | 'icon'
>) {
  const fg = {
    danger: colors.danger,
    warning: colors.warning,
    info: colors.info,
    success: colors.success,
    neutral: colors.neutral,
    primary: colors.primaryDark,
  }[tone];
  return (
    <Chip
      size="small"
      variant="outlined"
      label={label}
      title={title}
      icon={icon ? <Box component="span" sx={{ display: 'inline-flex', color: `${fg} !important`, ml: '6px !important' }}>{icon}</Box> : undefined}
      sx={{ color: fg, borderColor: alpha(fg, 0.35), bgcolor: 'background.paper', maxWidth: '100%', fontWeight: 500 }}
      {...rest}
    />
  );
}

/** Filter chip row used at the top of the clinical panels. */
export function FilterChips<T extends string>({
  options,
  value,
  onChange,
  ariaLabel,
}: {
  options: { value: T; label: string }[];
  value: T;
  onChange: (v: T) => void;
  ariaLabel: string;
}) {
  return (
    <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap role="group" aria-label={ariaLabel}>
      {options.map((o) => (
        <Chip
          key={o.value}
          label={o.label}
          clickable
          color={value === o.value ? 'primary' : 'default'}
          variant={value === o.value ? 'filled' : 'outlined'}
          aria-pressed={value === o.value}
          onClick={() => onChange(o.value)}
        />
      ))}
    </Stack>
  );
}

/** Bordered record card used for list items inside panels. */
export function RecordCard({ children, accent, muted, sx }: { children: ReactNode; accent?: string; muted?: boolean; sx?: object }) {
  return (
    <Box
      sx={{
        border: 1,
        borderColor: 'divider',
        borderLeft: accent ? `4px solid ${accent}` : undefined,
        borderRadius: 2,
        p: { xs: 3, sm: 4 },
        bgcolor: muted ? colors.surfaceAlt : 'background.paper',
        minWidth: 0,
        ...sx,
      }}
    >
      {children}
    </Box>
  );
}

/** Caption line "Label value · Label value" that wraps nicely on mobile. */
export function MetaLine({ items }: { items: Array<[string, ReactNode] | null | false | undefined> }) {
  const list = items.filter(Boolean) as Array<[string, ReactNode]>;
  if (!list.length) return null;
  return (
    <Stack direction="row" flexWrap="wrap" useFlexGap columnGap={3} rowGap={0.5} sx={{ mt: 1 }}>
      {list.map(([label, value]) => (
        <Typography key={label} variant="caption" color="text.secondary" component="span">
          {label}{' '}
          <Box component="span" sx={{ color: 'text.primary' }}>
            {value}
          </Box>
        </Typography>
      ))}
    </Stack>
  );
}
