'use client';

import { useState } from 'react';
import { Alert, Box, Chip, IconButton, Menu, MenuItem, Stack, Tooltip, Typography, useMediaQuery, useTheme } from '@mui/material';
import { AlertTriangle, Lock, MoreVertical, ShieldAlert } from 'lucide-react';
import type { Availability, Warning } from '@/lib/api/pharmacy';
import { StatusChip } from '@/components/ui/StatusChip';
import { formatDate, pluralize } from '@/lib/format';
import { colors } from '@/theme/tokens';

export function AvailabilityChip({ value, stock, unit }: { value: Availability; stock?: number; unit?: string }) {
  return (
    <StatusChip
      value={value}
      dot
      label={stock !== undefined ? `${stock.toLocaleString('en-IN')}${unit ? ' ' + unit : ''}` : undefined}
    />
  );
}

/** High-alert / controlled flags shown next to a medication name. */
export function MedicationFlags({ highAlert, controlled }: { highAlert?: boolean; controlled?: boolean }) {
  if (!highAlert && !controlled) return null;
  return (
    <Stack direction="row" spacing={1} component="span" sx={{ display: 'inline-flex', verticalAlign: 'middle' }}>
      {highAlert && (
        <Tooltip title="High-alert medication: independent double check required">
          <Chip
            size="small"
            icon={<AlertTriangle size={12} />}
            label="High alert"
            sx={{ height: 20, fontSize: '0.6875rem', color: colors.danger, bgcolor: colors.dangerSoft, '& .MuiChip-icon': { color: colors.danger } }}
          />
        </Tooltip>
      )}
      {controlled && (
        <Tooltip title="Controlled drug: record in the controlled-drug register">
          <Chip
            size="small"
            icon={<Lock size={12} />}
            label="Controlled"
            sx={{ height: 20, fontSize: '0.6875rem', color: colors.warning, bgcolor: colors.warningSoft, '& .MuiChip-icon': { color: colors.warning } }}
          />
        </Tooltip>
      )}
    </Stack>
  );
}

/** Compact warning summary for tables (allergy first). */
export function WarningSummary({ warnings }: { warnings: Warning[] }) {
  const relevant = warnings.filter((w) => w.type !== 'HIGH_ALERT' && w.type !== 'CONTROLLED');
  if (relevant.length === 0) {
    return (
      <Typography variant="caption" color="text.secondary">
        None
      </Typography>
    );
  }
  const labels: Record<string, string> = {
    ALLERGY: 'Allergy',
    ALLERGY_OVERRIDE: 'Override documented',
    OUT_OF_STOCK: 'Out of stock',
    LOW_STOCK: 'Low stock',
    MEDICATION_INACTIVE: 'Inactive item',
    QUERY_OPEN: 'Query open',
  };
  return (
    <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap justifyContent={{ xs: 'flex-end', md: 'flex-start' }}>
      {relevant.map((w, i) => (
        <Tooltip key={w.type + i} title={w.message}>
          <span>
            <StatusChip
              value={w.type}
              label={labels[w.type] ?? w.type}
              tone={w.severity === 'error' ? 'error' : w.severity === 'warning' ? 'warning' : 'info'}
              icon={w.type === 'ALLERGY' ? <ShieldAlert size={12} /> : undefined}
            />
          </span>
        </Tooltip>
      ))}
    </Stack>
  );
}

/** Full warning list (dialogs). */
export function WarningList({ warnings }: { warnings: Warning[] }) {
  if (warnings.length === 0) {
    return (
      <Alert severity="success" variant="outlined">
        No allergy, stock or high-risk warnings for this prescription.
      </Alert>
    );
  }
  const order = { error: 0, warning: 1, info: 2 } as const;
  return (
    <Stack spacing={2}>
      {[...warnings]
        .sort((a, b) => order[a.severity] - order[b.severity])
        .map((w, i) => (
          <Alert key={w.type + i} severity={w.severity} sx={{ py: 0.5 }}>
            {w.message}
          </Alert>
        ))}
    </Stack>
  );
}

export function ExpiryText({ date, days, expired }: { date: string; days: number; expired: boolean }) {
  const tone = expired ? colors.danger : days <= 90 ? colors.warning : undefined;
  return (
    <Box>
      <Typography variant="body2" sx={{ color: tone, fontWeight: tone ? 600 : undefined, whiteSpace: 'nowrap' }}>
        {formatDate(date)}
      </Typography>
      <Typography variant="caption" color="text.secondary" sx={{ whiteSpace: 'nowrap' }}>
        {expired ? `Expired ${pluralize(Math.abs(days), 'day')} ago` : days === 0 ? 'Expires today' : `${pluralize(days, 'day')} left`}
      </Typography>
    </Box>
  );
}

/** Compact per-row action menu (events are contained so they never trigger the row's own click). */
export function RowMenu({ label, items }: { label: string; items: { label: string; onClick: () => void; danger?: boolean }[] }) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  return (
    <Box component="span" onClick={(e) => e.stopPropagation()} onKeyDown={(e) => e.stopPropagation()}>
      <IconButton size="small" aria-label={label} onClick={(e) => setAnchor(e.currentTarget)}>
        <MoreVertical size={16} />
      </IconButton>
      <Menu anchorEl={anchor} open={!!anchor} onClose={() => setAnchor(null)}>
        {items.map((it) => (
          <MenuItem
            key={it.label}
            sx={it.danger ? { color: 'error.main' } : undefined}
            onClick={() => {
              setAnchor(null);
              it.onClick();
            }}
          >
            {it.label}
          </MenuItem>
        ))}
      </Menu>
    </Box>
  );
}

/**
 * Row click handler for desktop tables only. On small screens the shared DataTable renders rows as
 * clickable cards, which must not contain the row's own action buttons; explicit buttons are used there.
 */
export function useDesktopRowClick<T>(handler: ((row: T) => void) | undefined): ((row: T) => void) | undefined {
  const theme = useTheme();
  const isMobile = useMediaQuery(theme.breakpoints.down('md'));
  return isMobile ? undefined : handler;
}

export function QuantityText({ value, unit, strong }: { value: number; unit?: string; strong?: boolean }) {
  return (
    <Box component="span" sx={{ fontVariantNumeric: 'tabular-nums', fontWeight: strong ? 600 : undefined, whiteSpace: 'nowrap' }}>
      {value.toLocaleString('en-IN')}
      {unit ? (
        <Typography component="span" variant="caption" color="text.secondary">
          {' '}
          {unit}
        </Typography>
      ) : null}
    </Box>
  );
}
