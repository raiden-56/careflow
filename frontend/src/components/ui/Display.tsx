'use client';

import { ReactNode } from 'react';
import { Box, Stack, Tooltip, Typography } from '@mui/material';
import { formatDate, formatDateTime, formatINR, fromNow, isNegative } from '@/lib/format';
import { colors } from '@/theme/tokens';

/** INR amount from an API decimal string. Display only. */
export function Money({ value, strong, colorize }: { value?: string | null; strong?: boolean; colorize?: boolean }) {
  const neg = isNegative(value ?? undefined);
  return (
    <Box
      component="span"
      sx={{
        fontVariantNumeric: 'tabular-nums',
        fontWeight: strong ? 600 : undefined,
        color: colorize && neg ? colors.success : undefined,
        whiteSpace: 'nowrap',
      }}
    >
      {formatINR(value)}
    </Box>
  );
}

/** Timestamp in hospital time with a relative tooltip. */
export function DateTimeText({ value, dateOnly, relative }: { value?: string | null; dateOnly?: boolean; relative?: boolean }) {
  if (!value) return <span>—</span>;
  const text = relative ? fromNow(value) : dateOnly ? formatDate(value) : formatDateTime(value);
  return (
    <Tooltip title={relative ? formatDateTime(value) : fromNow(value)}>
      <Box component="time" dateTime={value} sx={{ whiteSpace: 'nowrap' }}>
        {text}
      </Box>
    </Tooltip>
  );
}

export interface DefinitionItem {
  label: string;
  value: ReactNode;
  span?: 1 | 2;
}

/** Two-column label/value list for detail panels. */
export function DefinitionList({ items, columns = 2 }: { items: DefinitionItem[]; columns?: 1 | 2 | 3 }) {
  return (
    <Box
      component="dl"
      sx={{
        m: 0,
        display: 'grid',
        gap: 4,
        gridTemplateColumns: { xs: '1fr', sm: `repeat(${columns}, minmax(0, 1fr))` },
      }}
    >
      {items.map((it) => (
        <Box key={it.label} sx={{ gridColumn: it.span === 2 ? { sm: 'span 2' } : undefined, minWidth: 0 }}>
          <Typography component="dt" variant="caption" color="text.secondary" sx={{ display: 'block', mb: 0.5 }}>
            {it.label}
          </Typography>
          <Typography component="dd" variant="body2" sx={{ m: 0, wordBreak: 'break-word' }}>
            {it.value === null || it.value === undefined || it.value === '' ? '—' : it.value}
          </Typography>
        </Box>
      ))}
    </Box>
  );
}

/** Small label + value pair inline. */
export function Meta({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Stack direction="row" spacing={1} alignItems="baseline">
      <Typography variant="caption" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="body2" component="span">
        {children}
      </Typography>
    </Stack>
  );
}
