'use client';

import { useQuery } from '@tanstack/react-query';
import { Box, Grid, Stack, Typography } from '@mui/material';
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { dischargeApi, dqk, type DelayReport } from '@/lib/api/discharge';
import { formatDate } from '@/lib/format';
import { SectionCard } from '@/components/ui/SectionCard';
import { DataTable } from '@/components/ui/DataTable';
import { ErrorState, TableSkeleton } from '@/components/ui/States';
import { colors } from '@/theme/tokens';

type StageRow = DelayReport['byStage'][number];

function StageTooltip({ active, payload }: { active?: boolean; payload?: { payload: StageRow }[] }) {
  if (!active || !payload?.length) return null;
  const r = payload[0].payload;
  return (
    <Box sx={{ bgcolor: colors.surface, border: `1px solid ${colors.border}`, borderRadius: 2, p: 2, boxShadow: 2 }}>
      <Typography variant="body2" fontWeight={600}>
        {r.label}
      </Typography>
      <Typography variant="caption" component="div" color="text.secondary">
        Average open {r.avgOpenHours} h · median {r.medianOpenHours} h
      </Typography>
      <Typography variant="caption" component="div" color="text.secondary">
        {r.steps} steps measured · {r.currentlyOpen} open now · {r.currentlyBlocked} blocked
      </Typography>
    </Box>
  );
}

/** "Discharge delays by workflow stage": how long steps stay open from initiation, and what is blocked now. */
export function DelayReportCard() {
  const q = useQuery({ queryKey: dqk.delays, queryFn: dischargeApi.delays });
  return (
    <SectionCard
      title="Discharge delays by stage"
      subtitle={q.data ? `Workflows started ${formatDate(q.data.from)} – ${formatDate(q.data.to)}. Hours from discharge initiation until each step was completed (or now, if still open).` : 'Last 30 days'}
    >
      {q.isLoading ? (
        <TableSkeleton rows={4} columns={3} />
      ) : q.error || !q.data ? (
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      ) : q.data.workflows === 0 ? (
        <Typography variant="body2" color="text.secondary">
          No discharge workflows were started in this period.
        </Typography>
      ) : (
        <Grid container spacing={5}>
          <Grid size={{ xs: 12, lg: 6 }}>
            <Stack direction="row" spacing={5} sx={{ mb: 3 }} flexWrap="wrap" useFlexGap>
              <Box>
                <Typography variant="caption" color="text.secondary">
                  Workflows
                </Typography>
                <Typography variant="h3" component="p">
                  {q.data.workflows}
                </Typography>
              </Box>
              <Box>
                <Typography variant="caption" color="text.secondary">
                  Completed
                </Typography>
                <Typography variant="h3" component="p">
                  {q.data.completed}
                </Typography>
              </Box>
              <Box>
                <Typography variant="caption" color="text.secondary">
                  Median initiation → departure
                </Typography>
                <Typography variant="h3" component="p">
                  {q.data.completed ? `${q.data.medianHoursToDischarge} h` : '—'}
                </Typography>
              </Box>
            </Stack>
            <Box sx={{ width: '100%', height: Math.max(180, q.data.byStage.length * 44) }} role="img" aria-label="Average hours steps stay open, by stage">
              <ResponsiveContainer>
                <BarChart data={q.data.byStage} layout="vertical" margin={{ top: 4, right: 24, bottom: 4, left: 8 }} barCategoryGap={10}>
                  <CartesianGrid horizontal={false} stroke={colors.border} />
                  <XAxis type="number" tick={{ fontSize: 12, fill: colors.inkMuted }} axisLine={false} tickLine={false} unit=" h" />
                  <YAxis type="category" dataKey="label" width={150} tick={{ fontSize: 12, fill: colors.ink }} axisLine={false} tickLine={false} />
                  <Tooltip content={<StageTooltip />} cursor={{ fill: colors.primarySoft }} />
                  <Bar dataKey="avgOpenHours" fill={colors.primary} radius={[0, 4, 4, 0]} maxBarSize={18} isAnimationActive={false} />
                </BarChart>
              </ResponsiveContainer>
            </Box>
          </Grid>
          <Grid size={{ xs: 12, lg: 6 }}>
            <DataTable
              dense
              ariaLabel="Delays by step"
              rows={q.data.bySteps.slice(0, 8)}
              rowKey={(r) => r.code}
              columns={[
                { key: 'name', header: 'Slowest steps', primary: true, render: (r) => r.name },
                { key: 'avg', header: 'Avg h', align: 'right', render: (r) => r.avgOpenHours },
                { key: 'median', header: 'Median h', align: 'right', render: (r) => r.medianOpenHours },
                { key: 'open', header: 'Open / blocked', align: 'right', render: (r) => `${r.currentlyOpen} / ${r.currentlyBlocked}` },
              ]}
            />
          </Grid>
        </Grid>
      )}
    </SectionCard>
  );
}
