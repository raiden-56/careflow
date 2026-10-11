'use client';

import { useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Alert,
  Box,
  Button,
  Card,
  Grid,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { Activity, BedDouble, ChevronDown, ClipboardCheck, Clock, Download, FileText, Hospital, LogOut, Repeat2, Syringe } from 'lucide-react';
import dayjs from 'dayjs';
import { adminApi, ClinicalQualityReport, Definition, OperationalReport, ReportParams } from '@/lib/api/admin';
import { facilityApi, qk } from '@/lib/api/core';
import { useAuth } from '@/lib/auth/AuthProvider';
import { useToast } from '@/lib/hooks';
import { formatDateTime, humanize, todayLocalDate } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { KpiCard } from '@/components/ui/KpiCard';
import { SectionCard } from '@/components/ui/SectionCard';
import { DataTable } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';
import { CardsSkeleton, EmptyState, ErrorState } from '@/components/ui/States';
import { AdmissionsTrendChart, CountBars, OccupancyTrendChart, SERIES } from '@/components/admin/Charts';

const PRESETS = [
  { label: '7 days', days: 7 },
  { label: '30 days', days: 30 },
  { label: '90 days', days: 90 },
];

export default function AnalyticsPage() {
  const toast = useToast();
  const { hasPermission } = useAuth();
  const today = todayLocalDate();
  const [from, setFrom] = useState(dayjs(today).subtract(29, 'day').format('YYYY-MM-DD'));
  const [to, setTo] = useState(today);
  const [departmentId, setDepartmentId] = useState('');
  const [wardId, setWardId] = useState('');
  const [exporting, setExporting] = useState<string | null>(null);
  const invalid = !from || !to || from > to;
  const params: ReportParams = { from, to, departmentId: departmentId || undefined, wardId: wardId || undefined };
  const report = useQuery({
    queryKey: ['reports', 'operational', params],
    queryFn: () => adminApi.operationalReport(params),
    enabled: !invalid,
    placeholderData: keepPreviousData,
  });
  const clinical = useQuery({
    queryKey: ['reports', 'clinical', params],
    queryFn: () => adminApi.clinicalQuality(params),
    enabled: !invalid && hasPermission('REPORT_CLINICAL'),
    placeholderData: keepPreviousData,
  });
  const departments = useQuery({ queryKey: qk.departments, queryFn: facilityApi.departments });
  const wards = useQuery({ queryKey: qk.wards, queryFn: facilityApi.wards });

  const doExport = async (kind: 'operational' | 'clinical-quality', format: 'csv' | 'pdf') => {
    setExporting(kind + format);
    try {
      await adminApi.exportReport(kind, format, params);
      toast.success(`${format.toUpperCase()} export downloaded`);
    } catch (e) {
      toast.error(e);
    } finally {
      setExporting(null);
    }
  };

  const r = report.data;
  return (
    <>
      <PageHeader
        title="Operational analytics"
        subtitle="Throughput, bed utilisation, discharge flow and pending work. Every metric is defined at the bottom of the page."
        breadcrumbs={[{ label: 'Administration', href: '/admin' }, { label: 'Operational analytics' }]}
        actions={
          <>
            <Button variant="outlined" startIcon={<Download size={16} />} disabled={invalid || !!exporting} onClick={() => doExport('operational', 'csv')}>
              {exporting === 'operationalcsv' ? 'Preparing…' : 'CSV'}
            </Button>
            <Button variant="outlined" startIcon={<FileText size={16} />} disabled={invalid || !!exporting} onClick={() => doExport('operational', 'pdf')}>
              {exporting === 'operationalpdf' ? 'Preparing…' : 'PDF'}
            </Button>
          </>
        }
      />
      <Card sx={{ p: 4, mb: 5 }}>
        <Stack direction={{ xs: 'column', lg: 'row' }} spacing={3} alignItems={{ lg: 'center' }}>
          <Stack direction="row" spacing={1}>
            {PRESETS.map((p) => {
              const f = dayjs(today).subtract(p.days - 1, 'day').format('YYYY-MM-DD');
              const active = from === f && to === today;
              return (
                <Button key={p.label} size="small" variant={active ? 'contained' : 'outlined'} onClick={() => { setFrom(f); setTo(today); }}>
                  {p.label}
                </Button>
              );
            })}
          </Stack>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3} sx={{ flex: 1 }}>
            <TextField label="From" type="date" value={from} onChange={(e) => setFrom(e.target.value)} slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: to } }} error={invalid} helperText={invalid ? 'Start must be before end' : ' '} />
            <TextField label="To" type="date" value={to} onChange={(e) => setTo(e.target.value)} slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: today } }} helperText=" " />
            <TextField select label="Department" value={departmentId} onChange={(e) => setDepartmentId(e.target.value)} sx={{ minWidth: 180 }} helperText=" ">
              <MenuItem value="">All departments</MenuItem>
              {departments.data?.map((d) => (
                <MenuItem key={d.id} value={d.id}>
                  {d.name}
                </MenuItem>
              ))}
            </TextField>
            <TextField select label="Ward" value={wardId} onChange={(e) => setWardId(e.target.value)} sx={{ minWidth: 180 }} helperText=" ">
              <MenuItem value="">All wards</MenuItem>
              {wards.data?.map((w) => (
                <MenuItem key={w.id} value={w.id}>
                  {w.name}
                </MenuItem>
              ))}
            </TextField>
          </Stack>
        </Stack>
      </Card>

      {report.isLoading ? (
        <CardsSkeleton count={8} />
      ) : report.error ? (
        <ErrorState error={report.error} onRetry={() => report.refetch()} />
      ) : r ? (
        <Box sx={{ opacity: report.isFetching ? 0.6 : 1, transition: 'opacity 120ms' }}>
          <OperationalSections r={r} />
          {hasPermission('REPORT_CLINICAL') && (
            <Box sx={{ mt: 5 }}>
              <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={2} sx={{ mb: 3 }}>
                <Typography variant="h3" component="h2">
                  Clinical quality summary
                </Typography>
                <Stack direction="row" spacing={2}>
                  <Button size="small" variant="outlined" startIcon={<Download size={14} />} disabled={!!exporting} onClick={() => doExport('clinical-quality', 'csv')}>
                    CSV
                  </Button>
                  <Button size="small" variant="outlined" startIcon={<FileText size={14} />} disabled={!!exporting} onClick={() => doExport('clinical-quality', 'pdf')}>
                    PDF
                  </Button>
                </Stack>
              </Stack>
              {clinical.error ? <ErrorState error={clinical.error} onRetry={() => clinical.refetch()} /> : clinical.data ? <ClinicalSections c={clinical.data} /> : <CardsSkeleton count={3} />}
            </Box>
          )}
          <Definitions defs={[...r.definitions, ...(clinical.data?.definitions ?? [])]} generatedAt={r.generatedAt} timezone={r.filter.timezone} />
        </Box>
      ) : null}
    </>
  );
}

function fmt(v: number | undefined | null, suffix = '') {
  return v === undefined || v === null ? '—' : `${v}${suffix}`;
}

function OperationalSections({ r }: { r: OperationalReport }) {
  const s = r.summary;
  const noActivity = s.admissions === 0 && s.discharges === 0;
  return (
    <Stack spacing={5}>
      <Grid container spacing={4}>
        {[
          { label: 'Admissions', value: s.admissions, icon: Hospital, tone: 'primary' as const },
          { label: 'Discharges', value: s.discharges, icon: LogOut, tone: 'info' as const },
          { label: 'Average length of stay', value: fmt(s.averageLengthOfStayDays, ' d'), icon: Clock, tone: 'secondary' as const, hint: 'Discharges in period' },
          { label: 'Average occupancy', value: `${s.averageOccupancyPercent}%`, icon: BedDouble, tone: 'primary' as const, hint: `${s.bedsInScope} active beds in scope` },
          { label: 'Average cleaning time', value: fmt(s.averageCleaningMinutes, ' min'), icon: Activity, tone: 'warning' as const, hint: `Turnover ${s.bedTurnoverPerBed} per bed` },
          { label: 'Transfers', value: `${s.transfersCompleted}/${s.transfersRequested}`, icon: Repeat2, tone: 'neutral' as const, hint: 'Completed / requested' },
          { label: 'Follow-up completion', value: fmt(s.followUpCompletionPercent, '%'), icon: ClipboardCheck, tone: 'success' as const, hint: 'Due follow-ups completed' },
          { label: 'Overdue doses (now)', value: s.overdueMedicationDoses, icon: Syringe, tone: s.overdueMedicationDoses ? ('error' as const) : ('neutral' as const), hint: `${s.medicationExceptions} exceptions in period` },
        ].map((k) => (
          <Grid key={k.label} size={{ xs: 12, sm: 6, lg: 3 }}>
            <KpiCard label={k.label} value={k.value} icon={k.icon} tone={k.tone} hint={k.hint} />
          </Grid>
        ))}
      </Grid>

      <Grid container spacing={5}>
        <Grid size={{ xs: 12, lg: 7 }}>
          <SectionCard title="Daily admissions and discharges">
            {noActivity ? <EmptyState title="No admissions or discharges in this period" compact /> : <AdmissionsTrendChart data={r.daily} />}
          </SectionCard>
        </Grid>
        <Grid size={{ xs: 12, lg: 5 }}>
          <SectionCard title="Bed occupancy" subtitle="Midnight census as % of active beds">
            {r.summary.bedsInScope === 0 ? <EmptyState title="No beds in scope" compact /> : <OccupancyTrendChart data={r.daily} />}
          </SectionCard>
        </Grid>
      </Grid>

      <SectionCard title="Ward distribution and turnover" flush>
        <DataTable
          ariaLabel="Ward distribution"
          rows={r.wards}
          rowKey={(w) => w.wardId}
          emptyTitle="No wards in scope"
          columns={[
            { key: 'ward', header: 'Ward', primary: true, render: (w) => <Typography variant="body2" fontWeight={600}>{w.wardName}</Typography> },
            { key: 'beds', header: 'Beds', align: 'right', render: (w) => w.beds },
            { key: 'occ', header: 'Occupied now', align: 'right', render: (w) => `${w.occupiedNow} (${w.occupancyNowPercent}%)` },
            { key: 'assign', header: 'Bed assignments', align: 'right', render: (w) => w.bedAssignments },
            { key: 'rel', header: 'Releases', align: 'right', render: (w) => w.bedReleases },
            { key: 'turn', header: 'Turnover / bed', align: 'right', render: (w) => w.turnoverPerBed },
            { key: 'clean', header: 'Avg cleaning', align: 'right', render: (w) => fmt(w.averageCleaningMinutes, ' min') },
          ]}
        />
      </SectionCard>

      <Grid container spacing={5}>
        <Grid size={{ xs: 12, lg: 5 }}>
          <SectionCard title="Length of stay by department" flush>
            <DataTable
              ariaLabel="Length of stay"
              rows={r.lengthOfStay}
              rowKey={(l) => l.department}
              emptyTitle="No discharges in this period"
              columns={[
                { key: 'dept', header: 'Department', primary: true, render: (l) => l.department },
                { key: 'n', header: 'Discharges', align: 'right', render: (l) => l.discharges },
                { key: 'avg', header: 'Average', align: 'right', render: (l) => fmt(l.averageDays, ' d') },
                { key: 'max', header: 'Longest', align: 'right', render: (l) => fmt(l.maxDays, ' d') },
              ]}
            />
          </SectionCard>
        </Grid>
        <Grid size={{ xs: 12, lg: 7 }}>
          <SectionCard
            title="Discharge delays by workflow stage"
            subtitle={`${r.discharge.workflowsStarted} started · ${r.discharge.workflowsCompleted} completed · avg ${fmt(r.discharge.averageHoursToDeparture, ' h')} to departure · ${fmt(r.discharge.averageHoursBeyondPlanned, ' h')} beyond planned`}
          >
            {r.discharge.steps.length === 0 ? (
              <EmptyState title="No discharge workflows started in this period" compact />
            ) : (
              <>
                <CountBars
                  label="Average hours from initiation to completion"
                  valueSuffix=" h"
                  color={SERIES.orange}
                  data={r.discharge.steps.filter((x) => x.averageHoursToComplete !== undefined && x.averageHoursToComplete !== null).map((x) => ({ name: x.stepName.length > 28 ? x.stepName.slice(0, 27) + '…' : x.stepName, value: x.averageHoursToComplete! }))}
                />
                <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 2 }}>
                  Open steps: {r.discharge.steps.reduce((a, x) => a + x.open, 0)} · blocked: {r.discharge.steps.reduce((a, x) => a + x.blocked, 0)}
                </Typography>
              </>
            )}
          </SectionCard>
        </Grid>
      </Grid>

      <Grid container spacing={5}>
        <Grid size={{ xs: 12, md: 6 }}>
          <SectionCard title="Pending tasks & medication events" subtitle={`Overdue = not recorded ${r.tasks.overdueGraceMinutes} min after the scheduled time`}>
            <Stack spacing={2}>
              <Row label="Open care tasks" value={r.tasks.openCareTasks} />
              <Row label="Overdue care tasks" value={r.tasks.overdueCareTasks} warn />
              {r.tasks.openTasksByType.map((x) => (
                <Row key={x.name} label={`· ${humanize(x.name)}`} value={x.count} muted />
              ))}
              <Row label="Overdue scheduled doses" value={r.tasks.overdueMedicationDoses} warn />
              {r.tasks.medicationExceptions.map((x) => (
                <Row key={x.name} label={`Doses ${humanize(x.name).toLowerCase()} in period`} value={x.count} />
              ))}
              <Row label="Open exception follow-ups" value={r.tasks.openExceptionFollowUps} />
            </Stack>
          </SectionCard>
        </Grid>
        <Grid size={{ xs: 12, md: 6 }}>
          <SectionCard title="Follow-up completion" subtitle="Follow-ups scheduled inside the period">
            <Stack spacing={2}>
              <Row label="Scheduled in period" value={r.followUps.scheduledInRange} />
              <Row label="Due (time has passed)" value={r.followUps.due} />
              <Row label="Completed" value={r.followUps.completed} />
              <Row label="Missed" value={r.followUps.missed} warn />
              <Row label="Cancelled" value={r.followUps.cancelled} muted />
              <Row label="Upcoming" value={r.followUps.upcoming} muted />
              <Row label="Completion rate" value={fmt(r.followUps.completionPercent, '%')} />
            </Stack>
          </SectionCard>
        </Grid>
      </Grid>

      <SectionCard title="Transfer history" subtitle="Requests made in the period (latest 200)" flush>
        <DataTable
          ariaLabel="Transfer history"
          rows={r.transfers}
          rowKey={(t) => t.id}
          emptyTitle="No transfers in this period"
          columns={[
            { key: 'req', header: 'Requested', primary: true, render: (t) => formatDateTime(t.requestedAt) },
            { key: 'adm', header: 'Admission', render: (t) => t.admissionNumber },
            { key: 'from', header: 'From', render: (t) => t.fromLocation ?? '—' },
            { key: 'to', header: 'To', render: (t) => t.toLocation ?? '—' },
            { key: 'prio', header: 'Priority', render: (t) => <StatusChip value={t.priority} /> },
            { key: 'status', header: 'Status', render: (t) => <StatusChip value={t.status} /> },
            { key: 'tat', header: 'Turnaround', align: 'right', render: (t) => fmt(t.turnaroundHours, ' h') },
          ]}
        />
      </SectionCard>
    </Stack>
  );
}

function Row({ label, value, warn, muted }: { label: string; value: number | string; warn?: boolean; muted?: boolean }) {
  return (
    <Stack direction="row" justifyContent="space-between" spacing={2}>
      <Typography variant="body2" color={muted ? 'text.secondary' : 'text.primary'}>
        {label}
      </Typography>
      <Typography variant="body2" fontWeight={600} color={warn && Number(value) > 0 ? 'error.main' : 'text.primary'} sx={{ fontVariantNumeric: 'tabular-nums' }}>
        {value}
      </Typography>
    </Stack>
  );
}

function ClinicalSections({ c }: { c: ClinicalQualityReport }) {
  return (
    <Grid container spacing={5}>
      <Grid size={{ xs: 12, md: 4 }}>
        <SectionCard title="Investigations">
          <Stack spacing={2}>
            <Row label="Ordered in period" value={c.investigations.orderedInRange} />
            <Row label="Outstanding (no result)" value={c.investigations.outstanding} />
            <Row label="Awaiting review" value={c.investigations.awaitingReview} warn />
            <Row label="Overdue" value={c.investigations.overdue} warn />
            <Row label="Average turnaround" value={fmt(c.investigations.averageTurnaroundHours, ' h')} />
            {c.investigations.outstandingByPriority.map((x) => (
              <Row key={x.name} label={`· ${humanize(x.name)}`} value={x.count} muted />
            ))}
          </Stack>
        </SectionCard>
      </Grid>
      <Grid size={{ xs: 12, md: 8 }}>
        <SectionCard title="Documentation completeness" flush>
          <DataTable
            ariaLabel="Documentation completeness"
            rows={c.documentation}
            rowKey={(d) => d.item}
            columns={[
              { key: 'item', header: 'Item', primary: true, render: (d) => d.item },
              { key: 'app', header: 'Applicable', align: 'right', render: (d) => d.applicable },
              { key: 'done', header: 'Complete', align: 'right', render: (d) => d.complete },
              { key: 'pct', header: '%', align: 'right', render: (d) => fmt(d.percent, '%') },
            ]}
          />
        </SectionCard>
      </Grid>
      <Grid size={12}>
        <SectionCard title="Safety alert history" subtitle={`${c.safety.raisedInRange} alerts raised · ${c.safety.openNow} open now · avg ${fmt(c.safety.averageMinutesToAcknowledge, ' min')} to acknowledge`}>
          {c.safety.raisedInRange === 0 && c.safety.incidentsBySeverity.length === 0 ? (
            <EmptyState title="No safety alerts or incidents in this period" compact />
          ) : (
            <Grid container spacing={4}>
              <Grid size={{ xs: 12, md: 6 }}>
                <CountBars label="Clinical alerts by type" data={c.safety.byType.map((x) => ({ name: humanize(x.name), value: x.count }))} />
              </Grid>
              <Grid size={{ xs: 12, md: 6 }}>
                <CountBars label="Incidents by severity" color={SERIES.orange} data={c.safety.incidentsBySeverity.map((x) => ({ name: humanize(x.name), value: x.count }))} />
              </Grid>
            </Grid>
          )}
        </SectionCard>
      </Grid>
    </Grid>
  );
}

function Definitions({ defs, generatedAt, timezone }: { defs: Definition[]; generatedAt: string; timezone: string }) {
  return (
    <Accordion sx={{ mt: 5 }} disableGutters>
      <AccordionSummary expandIcon={<ChevronDown size={18} />}>
        <Typography variant="subtitle2">Metric definitions</Typography>
      </AccordionSummary>
      <AccordionDetails>
        <Alert severity="info" sx={{ mb: 3 }}>
          Generated {formatDateTime(generatedAt)} · dates in {timezone}. Figures include only this hospital&apos;s records.
        </Alert>
        <Box component="dl" sx={{ m: 0, display: 'grid', gap: 3, gridTemplateColumns: { xs: '1fr', md: '240px 1fr' } }}>
          {defs.map((d) => (
            <Box key={d.metric} sx={{ display: 'contents' }}>
              <Typography component="dt" variant="body2" fontWeight={600}>
                {d.metric}
              </Typography>
              <Typography component="dd" variant="body2" color="text.secondary" sx={{ m: 0 }}>
                {d.definition}
              </Typography>
            </Box>
          ))}
        </Box>
      </AccordionDetails>
    </Accordion>
  );
}
