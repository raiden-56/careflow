'use client';

import { Box, Stack, Typography } from '@mui/material';
import {
  Bar,
  BarChart,
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
  type TooltipProps,
} from 'recharts';
import { formatDate, formatINR } from '@/lib/format';
import { colors } from '@/theme/tokens';

/**
 * Billing charts. Values arrive as decimal strings from the API and are converted to numbers only
 * for plotting; every figure shown in text uses the server's string value.
 * Single-series charts use the primary teal; multi-series charts use validated categorical slots
 * (blue / orange / aqua) in fixed order, always with a legend.
 */
export const SERIES = ['#2a78d6', '#eb6834', '#1baf7a'] as const;

const axisTick = { fill: colors.inkMuted, fontSize: 12 };

function compactInr(v: number) {
  return formatINR(v, { compact: true });
}

function ChartTooltip({ active, payload, label, labelFormatter }: TooltipProps<number, string> & { labelFormatter?: (l: string) => string }) {
  if (!active || !payload || payload.length === 0) return null;
  return (
    <Box sx={{ bgcolor: colors.surface, border: `1px solid ${colors.border}`, borderRadius: 2, px: 3, py: 2, boxShadow: 2, minWidth: 160 }}>
      <Typography variant="caption" color="text.secondary" component="div" sx={{ mb: 1 }}>
        {labelFormatter ? labelFormatter(String(label)) : label}
      </Typography>
      {payload.map((p) => (
        <Stack key={String(p.dataKey)} direction="row" spacing={2} justifyContent="space-between" alignItems="center">
          <Stack direction="row" spacing={1} alignItems="center">
            <Box sx={{ width: 10, height: 10, borderRadius: 0.5, bgcolor: p.color }} aria-hidden />
            <Typography variant="caption">{p.name}</Typography>
          </Stack>
          <Typography variant="caption" fontWeight={600} sx={{ fontVariantNumeric: 'tabular-nums' }}>
            {formatINR(p.payload?.[`${String(p.dataKey)}Label`] ?? p.value)}
          </Typography>
        </Stack>
      ))}
    </Box>
  );
}

export function Legend({ items }: { items: { label: string; color: string }[] }) {
  return (
    <Stack direction="row" spacing={3} flexWrap="wrap" useFlexGap sx={{ mb: 2 }} aria-hidden>
      {items.map((i) => (
        <Stack key={i.label} direction="row" spacing={1} alignItems="center">
          <Box sx={{ width: 12, height: 3, borderRadius: 2, bgcolor: i.color }} />
          <Typography variant="caption" color="text.secondary">
            {i.label}
          </Typography>
        </Stack>
      ))}
    </Stack>
  );
}

/** Daily amounts as thin vertical bars (single series). */
export function DailyBars({ data, name, height = 220 }: { data: { date: string; amount: string }[]; name: string; height?: number }) {
  const rows = data.map((d) => ({ date: d.date, value: Number(d.amount), valueLabel: d.amount }));
  return (
    <Box sx={{ width: '100%', height }} role="img" aria-label={`${name} by day`}>
      <ResponsiveContainer>
        <BarChart data={rows} margin={{ top: 8, right: 8, bottom: 0, left: 0 }} barCategoryGap="28%">
          <CartesianGrid vertical={false} stroke={colors.border} strokeDasharray="0" />
          <XAxis dataKey="date" tickFormatter={(d) => formatDate(d, 'DD MMM')} tick={axisTick} tickLine={false} axisLine={{ stroke: colors.border }} minTickGap={16} />
          <YAxis tickFormatter={compactInr} tick={axisTick} tickLine={false} axisLine={false} width={64} />
          <Tooltip cursor={{ fill: colors.primarySoft, opacity: 0.5 }} content={<ChartTooltip labelFormatter={(l) => formatDate(l, 'ddd, DD MMM YYYY')} />} />
          <Bar dataKey="value" name={name} fill={colors.primary} radius={[4, 4, 0, 0]} maxBarSize={28} />
        </BarChart>
      </ResponsiveContainer>
    </Box>
  );
}

/** Horizontal bars for a ranked breakdown (single series). */
export function RankedBars({ data, name, height }: { data: { label: string; amount: string }[]; name: string; height?: number }) {
  const rows = data.map((d) => ({ label: d.label, value: Number(d.amount), valueLabel: d.amount }));
  const h = height ?? Math.max(120, rows.length * 38 + 30);
  return (
    <Box sx={{ width: '100%', height: h }} role="img" aria-label={name}>
      <ResponsiveContainer>
        <BarChart data={rows} layout="vertical" margin={{ top: 0, right: 16, bottom: 0, left: 0 }} barCategoryGap="30%">
          <CartesianGrid horizontal={false} stroke={colors.border} />
          <XAxis type="number" tickFormatter={compactInr} tick={axisTick} tickLine={false} axisLine={false} />
          <YAxis type="category" dataKey="label" tick={axisTick} tickLine={false} axisLine={false} width={120} />
          <Tooltip cursor={{ fill: colors.primarySoft, opacity: 0.5 }} content={<ChartTooltip />} />
          <Bar dataKey="value" name={name} fill={colors.primary} radius={[0, 4, 4, 0]} maxBarSize={20} />
        </BarChart>
      </ResponsiveContainer>
    </Box>
  );
}

/** Up to three daily series on one axis (same unit: INR), with a legend and crosshair tooltip. */
export function DailyLines({
  data,
  series,
  height = 260,
}: {
  data: Array<Record<string, string>>;
  series: { key: string; label: string }[];
  height?: number;
}) {
  const rows = data.map((d) => {
    const r: Record<string, string | number> = { date: d.date };
    series.forEach((s) => {
      r[s.key] = Number(d[s.key]);
      r[`${s.key}Label`] = d[s.key];
    });
    return r;
  });
  return (
    <Box>
      <Legend items={series.map((s, i) => ({ label: s.label, color: SERIES[i] }))} />
      <Box sx={{ width: '100%', height }} role="img" aria-label={series.map((s) => s.label).join(', ') + ' by day'}>
        <ResponsiveContainer>
          <LineChart data={rows} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
            <CartesianGrid vertical={false} stroke={colors.border} />
            <XAxis dataKey="date" tickFormatter={(d) => formatDate(d, 'DD MMM')} tick={axisTick} tickLine={false} axisLine={{ stroke: colors.border }} minTickGap={20} />
            <YAxis tickFormatter={compactInr} tick={axisTick} tickLine={false} axisLine={false} width={64} />
            <Tooltip cursor={{ stroke: colors.borderStrong }} content={<ChartTooltip labelFormatter={(l) => formatDate(l, 'ddd, DD MMM YYYY')} />} />
            {series.map((s, i) => (
              <Line
                key={s.key}
                type="monotone"
                dataKey={s.key}
                name={s.label}
                stroke={SERIES[i]}
                strokeWidth={2}
                dot={false}
                activeDot={{ r: 4, stroke: colors.surface, strokeWidth: 2 }}
              />
            ))}
          </LineChart>
        </ResponsiveContainer>
      </Box>
    </Box>
  );
}
