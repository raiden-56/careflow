'use client';

import { ReactNode } from 'react';
import { Box, Stack, Typography } from '@mui/material';
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  Line,
  LineChart,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { colors } from '@/theme/tokens';
import { formatDate } from '@/lib/format';

/**
 * Chart palette (validated: categorical slots 1-3 pass CVD/normal-vision separation on the light
 * surface). Series identity is never colour-alone: every multi-series chart has a legend and tooltip.
 */
export const SERIES = {
  primary: colors.primary,
  blue: '#2a78d6',
  orange: '#eb6834',
  aqua: '#1baf7a',
} as const;

const axisTick = { fill: colors.inkMuted, fontSize: 12 };

function ChartTooltip({ active, payload, label, labelFormatter, valueSuffix }: {
  active?: boolean;
  payload?: Array<{ name?: string; value?: number | string; color?: string; dataKey?: string }>;
  label?: string;
  labelFormatter?: (l: string) => ReactNode;
  valueSuffix?: string;
}) {
  if (!active || !payload?.length) return null;
  return (
    <Box sx={{ bgcolor: 'background.paper', border: 1, borderColor: 'divider', borderRadius: 2, px: 3, py: 2, boxShadow: 2 }}>
      <Typography variant="caption" fontWeight={600} component="div" sx={{ mb: 1 }}>
        {labelFormatter ? labelFormatter(label ?? '') : label}
      </Typography>
      {payload.map((p) => (
        <Stack key={String(p.dataKey)} direction="row" spacing={1.5} alignItems="center">
          <Box sx={{ width: 10, height: 10, borderRadius: 0.5, bgcolor: p.color }} aria-hidden />
          <Typography variant="caption" color="text.secondary">
            {p.name}
          </Typography>
          <Typography variant="caption" fontWeight={600} sx={{ fontVariantNumeric: 'tabular-nums' }}>
            {p.value}
            {valueSuffix}
          </Typography>
        </Stack>
      ))}
    </Box>
  );
}

/** Ward occupancy (%) as horizontal bars; wards at/above the alert threshold are emphasised. */
export function OccupancyByWardChart({
  data,
  threshold = 90,
  height,
}: {
  data: Array<{ name: string; occupancyPercent: number; occupied: number; total: number }>;
  threshold?: number;
  height?: number;
}) {
  const h = height ?? Math.max(160, data.length * 44 + 40);
  return (
    <Box role="img" aria-label="Bed occupancy by ward" sx={{ width: '100%', height: h }}>
      <ResponsiveContainer>
        <BarChart data={data} layout="vertical" margin={{ top: 4, right: 24, bottom: 4, left: 4 }} barCategoryGap={10}>
          <CartesianGrid horizontal={false} stroke={colors.border} strokeDasharray="3 3" />
          <XAxis type="number" domain={[0, 100]} tickFormatter={(v) => `${v}%`} tick={axisTick} axisLine={false} tickLine={false} />
          <YAxis type="category" dataKey="name" width={130} tick={axisTick} axisLine={false} tickLine={false} />
          <ReferenceLine x={threshold} stroke={colors.danger} strokeDasharray="4 4" />
          <Tooltip
            cursor={{ fill: colors.primarySoft, opacity: 0.6 }}
            content={({ active, payload, label }) => {
              if (!active || !payload?.length) return null;
              const row = payload[0].payload as { occupied: number; total: number; occupancyPercent: number };
              return (
                <Box sx={{ bgcolor: 'background.paper', border: 1, borderColor: 'divider', borderRadius: 2, px: 3, py: 2, boxShadow: 2 }}>
                  <Typography variant="caption" fontWeight={600} component="div">
                    {label}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {row.occupied} of {row.total} beds occupied · {row.occupancyPercent}%
                  </Typography>
                </Box>
              );
            }}
          />
          <Bar dataKey="occupancyPercent" name="Occupancy" radius={[0, 4, 4, 0]} maxBarSize={22} isAnimationActive={false}>
            {data.map((d) => (
              <Cell key={d.name} fill={d.occupancyPercent >= threshold ? colors.danger : SERIES.primary} />
            ))}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </Box>
  );
}

/** Daily admissions vs discharges (two series, legend + crosshair tooltip). */
export function AdmissionsTrendChart({
  data,
  height = 260,
}: {
  data: Array<{ date: string; admissions: number; discharges: number }>;
  height?: number;
}) {
  return (
    <Box role="img" aria-label="Daily admissions and discharges" sx={{ width: '100%', height }}>
      <ResponsiveContainer>
        <LineChart data={data} margin={{ top: 8, right: 16, bottom: 0, left: -16 }}>
          <CartesianGrid vertical={false} stroke={colors.border} strokeDasharray="3 3" />
          <XAxis dataKey="date" tickFormatter={(d) => formatDate(d, 'DD MMM')} tick={axisTick} axisLine={false} tickLine={false} minTickGap={16} />
          <YAxis allowDecimals={false} tick={axisTick} axisLine={false} tickLine={false} />
          <Tooltip
            cursor={{ stroke: colors.borderStrong }}
            content={<ChartTooltip labelFormatter={(l) => formatDate(l, 'ddd, DD MMM YYYY')} />}
          />
          <Legend iconType="plainline" wrapperStyle={{ fontSize: 12, color: colors.inkMuted }} />
          <Line type="linear" dataKey="admissions" name="Admissions" stroke={SERIES.blue} strokeWidth={2} dot={{ r: 3 }} activeDot={{ r: 5 }} isAnimationActive={false} />
          <Line type="linear" dataKey="discharges" name="Discharges" stroke={SERIES.orange} strokeWidth={2} dot={{ r: 3 }} activeDot={{ r: 5 }} isAnimationActive={false} />
        </LineChart>
      </ResponsiveContainer>
    </Box>
  );
}

/** Daily occupancy percentage (single series; title names it). */
export function OccupancyTrendChart({ data, height = 220 }: { data: Array<{ date: string; occupancyPercent: number; occupiedBeds: number }>; height?: number }) {
  return (
    <Box role="img" aria-label="Daily bed occupancy" sx={{ width: '100%', height }}>
      <ResponsiveContainer>
        <LineChart data={data} margin={{ top: 8, right: 16, bottom: 0, left: -12 }}>
          <CartesianGrid vertical={false} stroke={colors.border} strokeDasharray="3 3" />
          <XAxis dataKey="date" tickFormatter={(d) => formatDate(d, 'DD MMM')} tick={axisTick} axisLine={false} tickLine={false} minTickGap={16} />
          <YAxis domain={[0, 100]} tickFormatter={(v) => `${v}%`} tick={axisTick} axisLine={false} tickLine={false} />
          <Tooltip
            cursor={{ stroke: colors.borderStrong }}
            content={({ active, payload, label }) => {
              if (!active || !payload?.length) return null;
              const row = payload[0].payload as { occupancyPercent: number; occupiedBeds: number };
              return (
                <Box sx={{ bgcolor: 'background.paper', border: 1, borderColor: 'divider', borderRadius: 2, px: 3, py: 2, boxShadow: 2 }}>
                  <Typography variant="caption" fontWeight={600} component="div">
                    {formatDate(String(label), 'ddd, DD MMM YYYY')}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {row.occupiedBeds} beds occupied · {row.occupancyPercent}%
                  </Typography>
                </Box>
              );
            }}
          />
          <Line type="linear" dataKey="occupancyPercent" name="Occupancy" stroke={SERIES.primary} strokeWidth={2} dot={false} activeDot={{ r: 5 }} isAnimationActive={false} />
        </LineChart>
      </ResponsiveContainer>
    </Box>
  );
}

/** Simple horizontal bars for a named count breakdown (single series). */
export function CountBars({
  data,
  label,
  color = SERIES.blue,
  valueSuffix,
}: {
  data: Array<{ name: string; value: number }>;
  label: string;
  color?: string;
  valueSuffix?: string;
}) {
  const h = Math.max(140, data.length * 40 + 32);
  return (
    <Box role="img" aria-label={label} sx={{ width: '100%', height: h }}>
      <ResponsiveContainer>
        <BarChart data={data} layout="vertical" margin={{ top: 4, right: 24, bottom: 4, left: 4 }}>
          <CartesianGrid horizontal={false} stroke={colors.border} strokeDasharray="3 3" />
          <XAxis type="number" allowDecimals={false} tick={axisTick} axisLine={false} tickLine={false} />
          <YAxis type="category" dataKey="name" width={170} tick={axisTick} axisLine={false} tickLine={false} />
          <Tooltip cursor={{ fill: colors.primarySoft, opacity: 0.6 }} content={<ChartTooltip valueSuffix={valueSuffix} />} />
          <Bar dataKey="value" name={label} fill={color} radius={[0, 4, 4, 0]} maxBarSize={20} isAnimationActive={false} />
        </BarChart>
      </ResponsiveContainer>
    </Box>
  );
}
