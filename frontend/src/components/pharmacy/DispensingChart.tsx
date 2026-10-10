'use client';

import { Box, Typography } from '@mui/material';
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import type { PharmacyDashboard } from '@/lib/api/pharmacy';
import { formatDate } from '@/lib/format';
import { colors } from '@/theme/tokens';

type Day = PharmacyDashboard['dispensedLast7Days'][number];

function DayTooltip({ active, payload }: { active?: boolean; payload?: Array<{ payload: Day }> }) {
  if (!active || !payload?.length) return null;
  const d = payload[0].payload;
  return (
    <Box sx={{ bgcolor: 'background.paper', border: 1, borderColor: 'divider', borderRadius: 2, px: 3, py: 2, boxShadow: 2 }}>
      <Typography variant="caption" color="text.secondary" component="div">
        {formatDate(d.date, 'ddd, DD MMM')}
      </Typography>
      <Typography variant="body2" fontWeight={600}>
        {d.dispensations} dispensation{d.dispensations === 1 ? '' : 's'}
      </Typography>
      <Typography variant="caption" color="text.secondary">
        {d.units} units net of returns
      </Typography>
    </Box>
  );
}

/** Dispensations per day (single series: no legend; the card title names it). */
export function DispensingChart({ days }: { days: Day[] }) {
  const data = days.map((d) => ({ ...d, label: formatDate(d.date, 'ddd D') }));
  return (
    <Box sx={{ width: '100%', height: 220 }} role="img" aria-label={`Dispensations per day: ${days.map((d) => `${formatDate(d.date, 'DD MMM')} ${d.dispensations}`).join(', ')}`}>
      <ResponsiveContainer>
        <BarChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: -16 }} barCategoryGap="28%">
          <CartesianGrid vertical={false} stroke={colors.border} strokeDasharray="0" />
          <XAxis dataKey="label" tickLine={false} axisLine={{ stroke: colors.borderStrong }} tick={{ fontSize: 12, fill: colors.inkMuted }} />
          <YAxis allowDecimals={false} tickLine={false} axisLine={false} tick={{ fontSize: 12, fill: colors.inkMuted }} width={40} />
          <Tooltip content={<DayTooltip />} cursor={{ fill: colors.primarySoft, opacity: 0.6 }} />
          <Bar dataKey="dispensations" fill={colors.primary} radius={[4, 4, 0, 0]} maxBarSize={36} />
        </BarChart>
      </ResponsiveContainer>
    </Box>
  );
}
