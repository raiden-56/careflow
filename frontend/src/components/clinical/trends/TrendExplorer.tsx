'use client';

import { ReactNode, useMemo, useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Button,
  Chip,
  Collapse,
  Grid,
  Link as MuiLink,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material';
import { ArrowDownRight, ArrowRight, ArrowUpRight, Clock3, Info } from 'lucide-react';
import {
  CartesianGrid,
  ComposedChart,
  Line,
  ReferenceArea,
  ReferenceDot,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { ck, clinicalApi, CompareRow, Gap, TrendSeries, TrendsView, VitalParameter } from '@/lib/api/clinical';
import { formatDateTime, inHospitalTz, isoToLocalInput, localInputToIso, nowInHospitalTz } from '@/lib/format';
import { SectionCard } from '@/components/ui/SectionCard';
import { DataTable } from '@/components/ui/DataTable';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { StatusChip } from '@/components/ui/StatusChip';
import { colors } from '@/theme/tokens';
import { FilterChips, fmtNum, num } from '../shared';

type Preset = '24h' | '72h' | '7d' | 'stay' | 'custom';
const PRESETS: { value: Preset; label: string; hours?: number }[] = [
  { value: '24h', label: '24 h', hours: 24 },
  { value: '72h', label: '72 h', hours: 72 },
  { value: '7d', label: '7 days', hours: 168 },
  { value: 'stay', label: 'Whole stay' },
  { value: 'custom', label: 'Custom' },
];

const DEFAULT_FULL: VitalParameter[] = ['TEMPERATURE_C', 'PULSE_BPM', 'RESPIRATORY_RATE', 'SYSTOLIC_BP', 'SPO2'];
const DEFAULT_COMPACT: VitalParameter[] = ['TEMPERATURE_C', 'PULSE_BPM', 'SPO2', 'SYSTOLIC_BP'];

/** Upper physical bounds for a few parameters so the shaded band never runs past a meaningful value. */
const HARD_MAX: Partial<Record<VitalParameter, number>> = { SPO2: 100, PAIN_SCORE: 10 };
const HARD_MIN: Partial<Record<VitalParameter, number>> = { SPO2: 0, PAIN_SCORE: 0 };

const BREACH_COLOR = { NORMAL: colors.primary, WARNING: colors.warning, CRITICAL: colors.danger } as const;

interface RangeParams {
  from?: string;
  to?: string;
}

function rangeFor(preset: Preset): RangeParams {
  const p = PRESETS.find((x) => x.value === preset);
  if (!p?.hours) return {};
  // Round to the minute so the query key is stable while the user stays on the preset.
  return { from: nowInHospitalTz().subtract(p.hours, 'hour').startOf('minute').toISOString() };
}

/**
 * Recovery Trend Explorer: vital-sign trends against hospital-configured threshold bands, with observation
 * gaps, threshold crossings, descriptive statistics and a period comparison. Decision support only.
 */
export function TrendExplorer({ admissionId, compact }: { admissionId: string; compact?: boolean }) {
  const [preset, setPreset] = useState<Preset>('72h');
  const [range, setRange] = useState<RangeParams>(() => rangeFor('72h'));
  const [customFrom, setCustomFrom] = useState(() => isoToLocalInput(nowInHospitalTz().subtract(3, 'day').toISOString()));
  const [customTo, setCustomTo] = useState(() => isoToLocalInput(null));
  const [customError, setCustomError] = useState<string | null>(null);
  const [selected, setSelected] = useState<VitalParameter[]>(compact ? DEFAULT_COMPACT : DEFAULT_FULL);

  const params = { from: range.from, to: range.to };
  const q = useQuery({
    queryKey: ck.trends(admissionId, params),
    queryFn: () => clinicalApi.trends(admissionId, params),
    placeholderData: keepPreviousData,
  });

  const choosePreset = (p: Preset) => {
    setPreset(p);
    setCustomError(null);
    if (p !== 'custom') setRange(rangeFor(p));
  };
  const applyCustom = () => {
    if (!customFrom || !customTo) {
      setCustomError('Choose both a start and an end time.');
      return;
    }
    const from = localInputToIso(customFrom);
    const to = localInputToIso(customTo);
    if (Date.parse(from) >= Date.parse(to)) {
      setCustomError('The start must be before the end.');
      return;
    }
    setCustomError(null);
    setRange({ from, to });
  };

  const toggle = (p: VitalParameter) =>
    setSelected((s) => (s.includes(p) ? s.filter((x) => x !== p) : [...s, p]));

  const data = q.data;
  const shown = useMemo(() => {
    if (!data) return [];
    const order = data.series.map((s) => s.parameter);
    return data.series.filter((s) => selected.includes(s.parameter)).sort((a, b) => order.indexOf(a.parameter) - order.indexOf(b.parameter));
  }, [data, selected]);

  const ongoing = data?.gaps.find((g) => g.ongoing);
  const pastGaps = data?.gaps.filter((g) => !g.ongoing) ?? [];

  return (
    <Stack spacing={compact ? 3 : 4} sx={{ minWidth: 0 }}>
      {/* Controls: one row of filters scopes every chart below. */}
      <Stack spacing={2}>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems={{ sm: 'center' }} justifyContent="space-between">
          <FilterChips
            ariaLabel="Time range"
            options={(compact ? PRESETS.filter((p) => p.value !== 'custom') : PRESETS).map((p) => ({ value: p.value, label: p.label }))}
            value={preset}
            onChange={choosePreset}
          />
          {data && (
            <Typography variant="caption" color="text.secondary">
              {formatDateTime(data.from)} – {formatDateTime(data.to)} · {data.recordings} recording{data.recordings === 1 ? '' : 's'}
            </Typography>
          )}
        </Stack>
        {preset === 'custom' && !compact && (
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems={{ sm: 'flex-start' }}>
            <TextField
              type="datetime-local"
              label="From"
              size="small"
              value={customFrom}
              onChange={(e) => setCustomFrom(e.target.value)}
              slotProps={{ inputLabel: { shrink: true } }}
            />
            <TextField
              type="datetime-local"
              label="To"
              size="small"
              value={customTo}
              onChange={(e) => setCustomTo(e.target.value)}
              slotProps={{ inputLabel: { shrink: true } }}
              error={!!customError}
              helperText={customError ?? undefined}
            />
            <Button variant="outlined" onClick={applyCustom} sx={{ alignSelf: { xs: 'flex-start', sm: 'center' } }}>
              Apply range
            </Button>
          </Stack>
        )}
        {data && (
          <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap role="group" aria-label="Parameters">
            {data.series.map((s) => {
              const on = selected.includes(s.parameter);
              return (
                <Chip
                  key={s.parameter}
                  size={compact ? 'small' : 'medium'}
                  label={`${s.label}${s.points.length ? '' : ' (no data)'}`}
                  clickable
                  aria-pressed={on}
                  color={on ? 'primary' : 'default'}
                  variant={on ? 'filled' : 'outlined'}
                  onClick={() => toggle(s.parameter)}
                />
              );
            })}
          </Stack>
        )}
      </Stack>

      {q.error ? (
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      ) : !data ? (
        <Grid container spacing={3} aria-busy="true" aria-label="Loading trends">
          {(compact ? DEFAULT_COMPACT : DEFAULT_FULL).map((p) => (
            <Grid key={p} size={{ xs: 12, md: 6 }}>
              <Box sx={{ height: compact ? 210 : 260, borderRadius: 2, bgcolor: colors.neutralSoft }} />
            </Grid>
          ))}
        </Grid>
      ) : (
        <>
          {ongoing && (
            <Alert severity="warning" icon={<Clock3 size={18} />}>
              No observations recorded for {fmtNum(ongoing.hours, 1)} h (expected every {data.expectedIntervalHours} h). Observations are overdue.
            </Alert>
          )}
          {data.recordings === 0 ? (
            <EmptyState title="No observations in this period" description="Choose a longer range or record a set of vital signs." compact />
          ) : shown.length === 0 ? (
            <EmptyState title="No parameters selected" description="Choose one or more parameters above to plot their trend." compact />
          ) : (
            <>
              <ChartLegend compact={compact} hasGaps={data.gaps.length > 0} />
              <Grid container spacing={3} sx={{ opacity: q.isFetching ? 0.6 : 1, transition: 'opacity 120ms' }}>
                {shown.map((s) => (
                  <Grid key={s.parameter} size={{ xs: 12, md: 6 }} sx={{ minWidth: 0 }}>
                    <TrendChart series={s} view={data} compact={compact} />
                  </Grid>
                ))}
              </Grid>
              {pastGaps.length > 0 && (
                <Typography variant="caption" color="text.secondary">
                  Shaded grey: {pastGaps.length} period{pastGaps.length === 1 ? '' : 's'} longer than the expected {data.expectedIntervalHours} h interval with
                  no observations (
                  {pastGaps.map((g, i) => (
                    <span key={g.from}>
                      {i > 0 ? '; ' : ''}
                      {formatDateTime(g.from, 'DD MMM HH:mm')} – {formatDateTime(g.to, 'DD MMM HH:mm')}, {fmtNum(g.hours, 1)} h
                    </span>
                  ))}
                  ).
                </Typography>
              )}
              {!compact && <CrossingsTable series={shown} />}
            </>
          )}
          {!compact && <ComparePeriods admissionId={admissionId} />}
          <Alert severity="info" variant="outlined" icon={<Info size={18} />} sx={{ '& .MuiAlert-message': { fontSize: '0.8125rem' } }}>
            {data.disclaimer}
          </Alert>
        </>
      )}
    </Stack>
  );
}

// ------------------------------------------------------------------ legend

function ChartLegend({ compact, hasGaps }: { compact?: boolean; hasGaps: boolean }) {
  const item = (swatch: ReactNode, label: string) => (
    <Stack direction="row" spacing={1} alignItems="center" key={label}>
      {swatch}
      <Typography variant="caption" color="text.secondary">
        {label}
      </Typography>
    </Stack>
  );
  const dot = (c: string) => <Box sx={{ width: 9, height: 9, borderRadius: 999, bgcolor: c, boxShadow: `0 0 0 2px ${colors.surface}` }} aria-hidden />;
  return (
    <Stack direction="row" flexWrap="wrap" useFlexGap columnGap={compact ? 2.5 : 4} rowGap={1} aria-label="Chart legend">
      {item(dot(BREACH_COLOR.NORMAL), 'Within range')}
      {item(dot(BREACH_COLOR.WARNING), 'Outside normal range')}
      {item(dot(BREACH_COLOR.CRITICAL), 'Critical')}
      {item(<Box sx={{ width: 16, height: 10, bgcolor: alpha(colors.success, 0.12), border: `1px solid ${alpha(colors.success, 0.3)}` }} aria-hidden />, 'Normal range')}
      {item(<Box sx={{ width: 16, borderTop: `2px dashed ${colors.danger}` }} aria-hidden />, 'Critical threshold')}
      {item(<Box sx={{ width: 10, height: 10, borderRadius: 999, border: `1.5px solid ${colors.ink}` }} aria-hidden />, 'Threshold crossing')}
      {hasGaps && item(<Box sx={{ width: 16, height: 10, bgcolor: alpha(colors.neutral, 0.16) }} aria-hidden />, 'No observations')}
    </Stack>
  );
}

function alpha(hex: string, a: number) {
  const h = hex.replace('#', '');
  const r = parseInt(h.slice(0, 2), 16);
  const g = parseInt(h.slice(2, 4), 16);
  const b = parseInt(h.slice(4, 6), 16);
  return `rgba(${r}, ${g}, ${b}, ${a})`;
}

// ------------------------------------------------------------------ one chart

interface ChartPoint {
  x: number;
  value: number | null;
  breach?: 'WARNING' | 'CRITICAL';
}

function TrendChart({ series, view, compact }: { series: TrendSeries; view: TrendsView; compact?: boolean }) {
  const [showTable, setShowTable] = useState(false);
  const x0 = Date.parse(view.from);
  const x1 = Math.max(Date.parse(view.to), x0 + 60_000);
  const warn = series.bands.find((b) => b.severity === 'WARNING');
  const crit = series.bands.find((b) => b.severity === 'CRITICAL');
  const warnMin = num(warn?.min);
  const warnMax = num(warn?.max);
  const critMin = num(crit?.min);
  const critMax = num(crit?.max);

  const { points, yDomain, yTicks, showCritMin, showCritMax } = useMemo(() => {
    const pts: ChartPoint[] = series.points
      .map((p) => ({ x: Date.parse(p.t), value: num(p.value) ?? null, breach: p.breach }))
      .filter((p) => p.value !== null);
    // Break the line across observation gaps so it never implies continuous data where none exists.
    const breaks: ChartPoint[] = view.gaps
      .map((g) => ({ x: (Date.parse(g.from) + Date.parse(g.to)) / 2, value: null }))
      .filter((b) => pts.some((p) => p.x < b.x) && pts.some((p) => p.x > b.x));
    const all = [...pts, ...breaks].sort((a, b) => a.x - b.x);
    const values = pts.map((p) => p.value as number);
    let lo = Math.min(...values, ...(warnMin !== undefined ? [warnMin] : []), ...(warnMax !== undefined && !values.length ? [warnMax] : []));
    let hi = Math.max(...values, ...(warnMax !== undefined ? [warnMax] : []), ...(warnMin !== undefined && !values.length ? [warnMin] : []));
    if (!Number.isFinite(lo) || !Number.isFinite(hi)) {
      lo = 0;
      hi = 1;
    }
    // Critical thresholds are drawn when they sit reasonably near the data; otherwise they are listed in the header.
    const span = Math.max(hi - lo, 1);
    const scMin = critMin !== undefined && critMin >= lo - span * 0.6;
    const scMax = critMax !== undefined && critMax <= hi + span * 0.6;
    if (scMin) lo = Math.min(lo, critMin!);
    if (scMax) hi = Math.max(hi, critMax!);
    const pad = Math.max((hi - lo) * 0.1, 0.5);
    let dLo = lo - pad;
    let dHi = hi + pad;
    const hMin = HARD_MIN[series.parameter];
    const hMax = HARD_MAX[series.parameter];
    if (hMin !== undefined) dLo = Math.max(dLo, hMin);
    if (hMax !== undefined) dHi = Math.min(dHi, hMax);
    const step = niceStep(dHi - dLo);
    const yLo = Math.floor(dLo / step) * step;
    const yHi = Math.ceil(dHi / step) * step;
    const ticks: number[] = [];
    for (let v = yLo; v <= yHi + step / 2; v += step) ticks.push(Number(v.toFixed(6)));
    return {
      points: all,
      yDomain: [yLo, yHi] as [number, number],
      yTicks: ticks,
      showCritMin: scMin,
      showCritMax: scMax,
    };
  }, [series, view.gaps, warnMin, warnMax, critMin, critMax]);

  const xTicks = useMemo(() => {
    const n = compact ? 3 : 4;
    return Array.from({ length: n + 1 }, (_, i) => x0 + ((x1 - x0) * i) / n);
  }, [x0, x1, compact]);

  const unit = series.unit;
  const st = series.stats;
  const latestPoint = series.points[series.points.length - 1];
  const thresholdText = [
    warn
      ? warnMin !== undefined && warnMax !== undefined
        ? `Normal ${fmtNum(warnMin)}–${fmtNum(warnMax)}`
        : warnMin !== undefined
          ? `Normal ≥${fmtNum(warnMin)}`
          : `Normal ≤${fmtNum(warnMax)}`
      : null,
    crit ? `Critical ${critMin !== undefined ? `<${fmtNum(critMin)}` : ''}${critMin !== undefined && critMax !== undefined ? ' or ' : ''}${critMax !== undefined ? `>${fmtNum(critMax)}` : ''}` : null,
  ]
    .filter(Boolean)
    .join(' · ');
  const gapWide = (g: Gap) => (Math.min(Date.parse(g.to), x1) - Math.max(Date.parse(g.from), x0)) / (x1 - x0) > 0.14;
  const height = compact ? 190 : 230;

  return (
    <Box
      component="figure"
      sx={{ m: 0, border: 1, borderColor: 'divider', borderRadius: 2, p: { xs: 2.5, sm: 3 }, bgcolor: 'background.paper', minWidth: 0 }}
      aria-label={`${series.label} trend`}
    >
      <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={2} sx={{ mb: 1 }}>
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="subtitle2" component="figcaption" fontWeight={600}>
            {series.label} <Typography component="span" variant="caption" color="text.secondary">({unit})</Typography>
          </Typography>
          {thresholdText && (
            <Typography variant="caption" color="text.secondary" component="div">
              {thresholdText}
            </Typography>
          )}
        </Box>
        {st.count > 0 && (
          <Box sx={{ textAlign: 'right', flexShrink: 0 }}>
            <Typography variant="h4" component="div" sx={{ lineHeight: 1.1 }}>
              {fmtNum(st.latest, 1)}
              <Typography component="span" variant="caption" color="text.secondary" sx={{ ml: 0.5 }}>
                {unit}
              </Typography>
            </Typography>
            <Typography variant="caption" color="text.secondary">
              latest{latestPoint ? ` · ${formatDateTime(latestPoint.t, 'DD MMM HH:mm')}` : ''}
            </Typography>
          </Box>
        )}
      </Stack>
      {st.count === 0 ? (
        <Box sx={{ height, display: 'grid', placeItems: 'center' }}>
          <Typography variant="body2" color="text.secondary">
            No {series.label.toLowerCase()} readings in this period
          </Typography>
        </Box>
      ) : (
        <Box sx={{ width: '100%', height, minWidth: 0 }}>
          <ResponsiveContainer width="100%" height="100%">
            <ComposedChart data={points} margin={{ top: 10, right: compact ? 8 : 12, bottom: 4, left: 0 }}>
              <CartesianGrid stroke={colors.border} strokeDasharray="" vertical={false} />
              {/* Normal range band (between the WARNING min/max). */}
              {(warnMin !== undefined || warnMax !== undefined) && (
                <ReferenceArea
                  y1={warnMin ?? yDomain[0]}
                  y2={warnMax ?? yDomain[1]}
                  fill={colors.success}
                  fillOpacity={0.08}
                  stroke="none"
                  ifOverflow="hidden"
                />
              )}
              {/* Observation gaps. */}
              {view.gaps.map((g) => (
                <ReferenceArea
                  key={g.from}
                  x1={Math.max(Date.parse(g.from), x0)}
                  x2={Math.min(Date.parse(g.to), x1)}
                  fill={colors.neutral}
                  fillOpacity={0.14}
                  stroke="none"
                  ifOverflow="hidden"
                  label={
                    gapWide(g)
                      ? { value: g.ongoing ? 'No observations (overdue)' : 'No observations', position: 'insideTop', fontSize: 10, fill: colors.inkMuted }
                      : undefined
                  }
                />
              ))}
              {showCritMin && (
                <ReferenceLine
                  y={critMin}
                  stroke={colors.danger}
                  strokeDasharray="5 4"
                  strokeWidth={1.25}
                  label={{ value: `Critical <${fmtNum(critMin)}`, position: 'insideBottomRight', fontSize: 10, fill: colors.inkMuted }}
                />
              )}
              {showCritMax && (
                <ReferenceLine
                  y={critMax}
                  stroke={colors.danger}
                  strokeDasharray="5 4"
                  strokeWidth={1.25}
                  label={{ value: `Critical >${fmtNum(critMax)}`, position: 'insideTopRight', fontSize: 10, fill: colors.inkMuted }}
                />
              )}
              <XAxis
                dataKey="x"
                type="number"
                scale="time"
                domain={[x0, x1]}
                ticks={xTicks}
                tick={<TimeTick />}
                height={34}
                stroke={colors.borderStrong}
                tickLine={false}
                interval={0}
              />
              <YAxis
                domain={yDomain}
                ticks={yTicks}
                interval={0}
                allowDataOverflow
                tickFormatter={(v: number) => fmtNum(v, 1)}
                width={compact ? 34 : 40}
                tick={{ fontSize: 11, fill: colors.inkMuted }}
                stroke={colors.borderStrong}
                tickLine={false}
                axisLine={false}
              />
              <Tooltip
                content={<TrendTooltip unit={unit} seriesLabel={series.label} />}
                cursor={{ stroke: colors.borderStrong, strokeWidth: 1 }}
                isAnimationActive={false}
              />
              <Line
                type="linear"
                dataKey="value"
                stroke={colors.primary}
                strokeWidth={2}
                strokeLinecap="round"
                strokeLinejoin="round"
                connectNulls={false}
                isAnimationActive={false}
                dot={<BreachDot />}
                activeDot={{ r: 6, stroke: colors.surface, strokeWidth: 2, fill: colors.ink }}
              />
              {series.crossings.map((c) => (
                <ReferenceDot
                  key={c.t + c.toLevel}
                  x={Date.parse(c.t)}
                  y={num(c.value)}
                  r={8}
                  fill="none"
                  stroke={colors.ink}
                  strokeWidth={1.5}
                  ifOverflow="hidden"
                />
              ))}
            </ComposedChart>
          </ResponsiveContainer>
        </Box>
      )}
      {st.count > 0 && (
        <Stack direction="row" flexWrap="wrap" useFlexGap columnGap={3} rowGap={0.5} sx={{ mt: 1.5 }}>
          {[
            ['Min', fmtNum(st.min, 1)],
            ['Max', fmtNum(st.max, 1)],
            ['Mean', fmtNum(st.mean, 1)],
            ['First', fmtNum(st.first, 1)],
            ['Readings', String(st.count)],
            ['Crossings', String(series.crossings.length)],
          ].map(([k, v]) => (
            <Typography key={k} variant="caption" color="text.secondary" sx={{ fontVariantNumeric: 'tabular-nums' }}>
              {k}{' '}
              <Box component="span" sx={{ color: 'text.primary', fontWeight: 600 }}>
                {v}
              </Box>
            </Typography>
          ))}
          {!compact && (
            <MuiLink component="button" type="button" variant="caption" onClick={() => setShowTable((s) => !s)} aria-expanded={showTable} sx={{ ml: 'auto' }}>
              {showTable ? 'Hide readings' : 'View readings'}
            </MuiLink>
          )}
        </Stack>
      )}
      {!compact && (
        <Collapse in={showTable} unmountOnExit>
          <Box sx={{ maxHeight: 240, overflow: 'auto', mt: 2, border: 1, borderColor: 'divider', borderRadius: 1.5 }}>
            <Table size="small" aria-label={`${series.label} readings`} stickyHeader>
              <TableHead>
                <TableRow>
                  <TableCell>Time</TableCell>
                  <TableCell align="right">Value ({unit})</TableCell>
                  <TableCell>Range</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {[...series.points].reverse().map((p) => (
                  <TableRow key={p.vitalId + p.t}>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatDateTime(p.t, 'DD MMM HH:mm')}</TableCell>
                    <TableCell align="right" sx={{ fontVariantNumeric: 'tabular-nums', fontWeight: p.breach ? 600 : 400 }}>
                      {fmtNum(p.value, 1)}
                    </TableCell>
                    <TableCell>{p.breach ? <StatusChip value={p.breach} label={p.breach === 'CRITICAL' ? 'Critical' : 'Outside range'} /> : 'Within range'}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Box>
        </Collapse>
      )}
    </Box>
  );
}

function niceStep(span: number) {
  const raw = span / 5;
  const pow = Math.pow(10, Math.floor(Math.log10(raw || 1)));
  const n = raw / pow;
  return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * pow;
}

/** Two-line time tick: date over time, in hospital time. */
function TimeTick(props: { x?: number; y?: number; payload?: { value: number }; index?: number; visibleTicksCount?: number }) {
  const { x = 0, y = 0, payload, index = 0, visibleTicksCount = 0 } = props;
  if (!payload) return null;
  const d = inHospitalTz(new Date(payload.value));
  // Anchor the outermost ticks inward so they are never clipped at the plot edges.
  const anchor = index === 0 ? 'start' : index === visibleTicksCount - 1 ? 'end' : 'middle';
  return (
    <g transform={`translate(${x},${y})`}>
      <text dy={12} textAnchor={anchor} fontSize={10.5} fill={colors.inkMuted}>
        <tspan x={0}>{d.format('DD MMM')}</tspan>
        <tspan x={0} dy={13}>
          {d.format('HH:mm')}
        </tspan>
      </text>
    </g>
  );
}

/** Point marker coloured by breach level, with a surface ring so it stays legible on the line. */
function BreachDot(props: { cx?: number; cy?: number; payload?: ChartPoint; index?: number }) {
  const { cx, cy, payload, index } = props;
  if (cx === undefined || cy === undefined || !payload || payload.value === null) return <g key={`d-${index}`} />;
  const level = payload.breach ?? 'NORMAL';
  return (
    <circle
      key={`d-${index}`}
      cx={cx}
      cy={cy}
      r={level === 'CRITICAL' ? 5 : 4}
      fill={BREACH_COLOR[level]}
      stroke={colors.surface}
      strokeWidth={2}
    />
  );
}

function TrendTooltip({
  active,
  payload,
  unit,
  seriesLabel,
}: {
  active?: boolean;
  payload?: Array<{ payload: ChartPoint }>;
  unit: string;
  seriesLabel: string;
}) {
  const p = payload?.[0]?.payload;
  if (!active || !p || p.value === null) return null;
  const level = p.breach ?? 'NORMAL';
  return (
    <Box sx={{ bgcolor: 'background.paper', border: 1, borderColor: 'divider', borderRadius: 1.5, px: 2, py: 1.5, boxShadow: 2 }}>
      <Typography variant="caption" color="text.secondary" component="div">
        {formatDateTime(new Date(p.x).toISOString())}
      </Typography>
      <Typography variant="body2" fontWeight={600} sx={{ fontVariantNumeric: 'tabular-nums' }}>
        {seriesLabel}: {fmtNum(p.value, 1)} {unit}
      </Typography>
      <Stack direction="row" spacing={1} alignItems="center" sx={{ mt: 0.5 }}>
        <Box sx={{ width: 8, height: 8, borderRadius: 999, bgcolor: BREACH_COLOR[level] }} aria-hidden />
        <Typography variant="caption" color="text.secondary">
          {level === 'NORMAL' ? 'Within configured range' : level === 'WARNING' ? 'Outside normal range' : 'Critical threshold breached'}
        </Typography>
      </Stack>
    </Box>
  );
}

// ------------------------------------------------------------------ crossings

function CrossingsTable({ series }: { series: TrendSeries[] }) {
  const [open, setOpen] = useState(false);
  const rows = series
    .flatMap((s) => s.crossings.map((c) => ({ ...c, label: s.label, unit: s.unit, parameter: s.parameter })))
    .sort((a, b) => Date.parse(b.t) - Date.parse(a.t));
  if (!rows.length) return null;
  return (
    <Box>
      <MuiLink component="button" type="button" variant="body2" onClick={() => setOpen((o) => !o)} aria-expanded={open}>
        {open ? 'Hide' : 'Show'} threshold crossings ({rows.length})
      </MuiLink>
      <Collapse in={open} unmountOnExit>
        <Box sx={{ mt: 2, border: 1, borderColor: 'divider', borderRadius: 2, overflow: 'hidden' }}>
          <DataTable
            dense
            ariaLabel="Threshold crossings"
            rows={rows}
            rowKey={(r) => r.parameter + r.t + r.toLevel}
            columns={[
              { key: 't', header: 'Time', primary: true, render: (r) => formatDateTime(r.t) },
              { key: 'p', header: 'Parameter', render: (r) => r.label },
              { key: 'v', header: 'Value', render: (r) => `${fmtNum(r.value, 1)} ${r.unit}` },
              {
                key: 'change',
                header: 'Change',
                render: (r) => (
                  <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap justifyContent={{ xs: 'flex-end', md: 'flex-start' }}>
                    <StatusChip value={r.fromLevel} />
                    <ArrowRight size={14} aria-label="to" />
                    <StatusChip value={r.toLevel} />
                  </Stack>
                ),
              },
              { key: 'th', header: 'Threshold', hideOnMobile: true, render: (r) => <Typography variant="caption">{r.threshold}</Typography> },
            ]}
          />
        </Box>
      </Collapse>
    </Box>
  );
}

// ------------------------------------------------------------------ compare periods

function ComparePeriods({ admissionId }: { admissionId: string }) {
  const now = nowInHospitalTz().startOf('hour');
  const [a, setA] = useState({ from: isoToLocalInput(now.subtract(48, 'hour').toISOString()), to: isoToLocalInput(now.subtract(24, 'hour').toISOString()) });
  const [b, setB] = useState({ from: isoToLocalInput(now.subtract(24, 'hour').toISOString()), to: isoToLocalInput(now.toISOString()) });
  const [params, setParams] = useState<{ aFrom: string; aTo: string; bFrom: string; bTo: string } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const q = useQuery({
    queryKey: ck.compare(admissionId, params ?? {}),
    queryFn: () => clinicalApi.compare(admissionId, params!),
    enabled: !!params,
  });

  const run = () => {
    if (![a.from, a.to, b.from, b.to].every(Boolean)) {
      setError('Choose start and end times for both periods.');
      return;
    }
    const p = { aFrom: localInputToIso(a.from), aTo: localInputToIso(a.to), bFrom: localInputToIso(b.from), bTo: localInputToIso(b.to) };
    if (Date.parse(p.aFrom) >= Date.parse(p.aTo) || Date.parse(p.bFrom) >= Date.parse(p.bTo)) {
      setError('Each period must start before it ends.');
      return;
    }
    setError(null);
    setParams(p);
  };

  const period = (label: string, v: { from: string; to: string }, set: (v: { from: string; to: string }) => void) => (
    <Box sx={{ minWidth: 0 }}>
      <Typography variant="caption" color="text.secondary" fontWeight={600} component="div" sx={{ mb: 1 }}>
        {label}
      </Typography>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
        <TextField
          type="datetime-local"
          size="small"
          label="From"
          value={v.from}
          onChange={(e) => set({ ...v, from: e.target.value })}
          slotProps={{ inputLabel: { shrink: true } }}
        />
        <TextField
          type="datetime-local"
          size="small"
          label="To"
          value={v.to}
          onChange={(e) => set({ ...v, to: e.target.value })}
          slotProps={{ inputLabel: { shrink: true } }}
        />
      </Stack>
    </Box>
  );

  const direction = (r: CompareRow) => {
    const map = {
      HIGHER: { icon: <ArrowUpRight size={14} />, label: 'Higher in B' },
      LOWER: { icon: <ArrowDownRight size={14} />, label: 'Lower in B' },
      UNCHANGED: { icon: <ArrowRight size={14} />, label: 'Unchanged' },
      INSUFFICIENT_DATA: { icon: null, label: 'Insufficient data' },
    }[r.observedDirection];
    return (
      <Stack direction="row" spacing={0.75} alignItems="center" justifyContent={{ xs: 'flex-end', md: 'flex-start' }} sx={{ color: 'text.secondary' }}>
        {map.icon}
        <Typography variant="body2" color="text.primary">
          {map.label}
        </Typography>
      </Stack>
    );
  };
  const statCell = (s: CompareRow['a'], unit: string) =>
    s.count === 0 ? (
      <Typography variant="body2" color="text.secondary">
        No readings
      </Typography>
    ) : (
      <Box sx={{ fontVariantNumeric: 'tabular-nums' }}>
        <Typography variant="body2" fontWeight={600} component="span">
          {fmtNum(s.mean, 1)} {unit}
        </Typography>
        <Typography variant="caption" color="text.secondary" component="div">
          {fmtNum(s.min, 1)}–{fmtNum(s.max, 1)} · n={s.count}
        </Typography>
      </Box>
    );

  return (
    <SectionCard
      title="Compare periods"
      subtitle="Descriptive statistics for two time windows. Differences are observations, not clinical conclusions."
      flush
      sx={{ boxShadow: 'none', border: 1, borderColor: 'divider' }}
    >
      <Stack spacing={3} sx={{ px: { xs: 3, sm: 5 }, pb: 4 }}>
        <Stack direction={{ xs: 'column', lg: 'row' }} spacing={3} alignItems={{ lg: 'flex-end' }}>
          {period('Period A', a, setA)}
          {period('Period B', b, setB)}
          <Button variant="outlined" onClick={run} disabled={q.isFetching} sx={{ alignSelf: { xs: 'flex-start', lg: 'flex-end' } }}>
            {q.isFetching ? 'Comparing…' : 'Compare'}
          </Button>
        </Stack>
        {error && <Alert severity="error">{error}</Alert>}
      </Stack>
      {params &&
        (q.error ? (
          <Box sx={{ p: 4 }}>
            <ErrorState error={q.error} onRetry={() => q.refetch()} />
          </Box>
        ) : (
          <>
            <DataTable<CompareRow>
              dense
              ariaLabel="Period comparison"
              rows={q.data?.rows}
              loading={q.isFetching}
              rowKey={(r) => r.parameter}
              emptyTitle="No observations in either period"
              columns={[
                { key: 'p', header: 'Parameter', primary: true, render: (r) => <Typography variant="body2" fontWeight={600}>{r.label}</Typography> },
                { key: 'a', header: 'Period A mean (range)', render: (r) => statCell(r.a, r.unit) },
                { key: 'b', header: 'Period B mean (range)', render: (r) => statCell(r.b, r.unit) },
                {
                  key: 'd',
                  header: 'Change in mean',
                  align: 'right',
                  render: (r) =>
                    r.meanChange === undefined || r.meanChange === null ? '—' : (
                      <Box component="span" sx={{ fontVariantNumeric: 'tabular-nums' }}>
                        {(num(r.meanChange) ?? 0) > 0 ? '+' : ''}
                        {fmtNum(r.meanChange, 1)} {r.unit}
                      </Box>
                    ),
                },
                { key: 'dir', header: 'Observed', render: direction },
              ]}
            />
            {q.data && (
              <Typography variant="caption" color="text.secondary" component="p" sx={{ px: { xs: 3, sm: 5 }, py: 3, m: 0 }}>
                {q.data.disclaimer}
              </Typography>
            )}
          </>
        ))}
    </SectionCard>
  );
}
