'use client';

import { useState } from 'react';
import Link from 'next/link';
import { useQuery } from '@tanstack/react-query';
import { Box, Button, Chip, Grid, LinearProgress, Stack, Tab, Tabs, Typography } from '@mui/material';
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { AlertTriangle, CalendarCheck, FileWarning, FlaskConical, Pill, Stethoscope } from 'lucide-react';
import { ck, clinicalApi } from '@/lib/api/clinical';
import { useAuth, useMe } from '@/lib/auth/AuthProvider';
import { formatDate, formatDateTime, fromNow, humanize } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { SectionCard } from '@/components/ui/SectionCard';
import { KpiCard } from '@/components/ui/KpiCard';
import { StatusChip } from '@/components/ui/StatusChip';
import { DataTable } from '@/components/ui/DataTable';
import { EmptyState, ErrorState, PageSkeleton, TableSkeleton } from '@/components/ui/States';
import { PatientWorklist } from '@/components/clinical/PatientWorklist';
import { AlertCard, useAlertActions } from '@/components/clinical/alerts/AlertsPanel';
import { colors } from '@/theme/tokens';

function ReportsSection() {
  const [tab, setTab] = useState<'documentation' | 'outstanding' | 'alerts'>('documentation');
  const doc = useQuery({ queryKey: ck.reports('documentation'), queryFn: clinicalApi.documentationReport, enabled: tab === 'documentation' });
  const out = useQuery({ queryKey: ck.reports('outstanding'), queryFn: clinicalApi.outstandingReport, enabled: tab === 'outstanding' });
  const alerts = useQuery({ queryKey: ck.reports('alerts', { days: 7 }), queryFn: () => clinicalApi.alertHistoryReport(7), enabled: tab === 'alerts' });
  return (
    <SectionCard title="Clinical quality reports" subtitle="For the patients on your worklist" flush>
      <Tabs value={tab} onChange={(_, v) => setTab(v)} variant="scrollable" allowScrollButtonsMobile sx={{ px: 3 }}>
        <Tab value="documentation" label="Documentation completeness" />
        <Tab value="outstanding" label="Outstanding investigations" />
        <Tab value="alerts" label="Alert history (7 days)" />
      </Tabs>
      <Box sx={{ borderTop: 1, borderColor: 'divider' }}>
        {tab === 'documentation' &&
          (doc.isLoading ? (
            <TableSkeleton />
          ) : doc.error || !doc.data ? (
            <Box sx={{ p: 4 }}>
              <ErrorState error={doc.error} onRetry={() => doc.refetch()} />
            </Box>
          ) : (
            <>
              <Stack direction="row" spacing={5} sx={{ px: 5, py: 3 }} flexWrap="wrap" useFlexGap>
                <Typography variant="body2">
                  Average completeness <strong>{doc.data.averageCompleteness}%</strong>
                </Typography>
                <Typography variant="body2">
                  Fully documented <strong>{doc.data.fullyDocumented}</strong> of {doc.data.admissions}
                </Typography>
              </Stack>
              <DataTable
                rows={doc.data.rows}
                rowKey={(r) => r.patient.admissionId}
                emptyTitle="No admissions in scope"
                columns={[
                  {
                    key: 'p',
                    header: 'Patient',
                    primary: true,
                    render: (r) => (
                      <Box component={Link} href={`/doctor/patients/${r.patient.admissionId}`} sx={{ color: 'inherit', fontWeight: 600 }}>
                        {r.patient.patientName}
                      </Box>
                    ),
                  },
                  {
                    key: 'c',
                    header: 'Completeness',
                    render: (r) => (
                      <Stack direction="row" spacing={2} alignItems="center" sx={{ minWidth: 120 }}>
                        <LinearProgress
                          variant="determinate"
                          value={r.completenessPercent}
                          color={r.completenessPercent >= 80 ? 'success' : r.completenessPercent >= 60 ? 'warning' : 'error'}
                          sx={{ flex: 1, height: 6, borderRadius: 3 }}
                          aria-label={`${r.completenessPercent}% complete`}
                        />
                        <Typography variant="caption">{r.completenessPercent}%</Typography>
                      </Stack>
                    ),
                  },
                  {
                    key: 'g',
                    header: 'Gaps',
                    render: (r) =>
                      r.gaps.length ? (
                        <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ justifyContent: { xs: 'flex-end', md: 'flex-start' } }}>
                          {r.gaps.map((g) => (
                            <Chip key={g} size="small" label={g} color="warning" variant="outlined" />
                          ))}
                        </Stack>
                      ) : (
                        <StatusChip value="COMPLETED" label="Complete" />
                      ),
                  },
                  {
                    key: 'n',
                    header: 'Last progress note',
                    hideOnMobile: true,
                    render: (r) => (r.lastProgressNoteAt ? fromNow(r.lastProgressNoteAt) : '—'),
                  },
                ]}
              />
            </>
          ))}
        {tab === 'outstanding' &&
          (out.isLoading ? (
            <TableSkeleton />
          ) : out.error || !out.data ? (
            <Box sx={{ p: 4 }}>
              <ErrorState error={out.error} onRetry={() => out.refetch()} />
            </Box>
          ) : (
            <>
              <Stack direction="row" spacing={4} sx={{ px: 5, py: 3 }} flexWrap="wrap" useFlexGap>
                <Typography variant="body2">
                  Awaiting collection <strong>{out.data.awaitingCollection}</strong>
                </Typography>
                <Typography variant="body2">
                  Awaiting result <strong>{out.data.awaitingResult}</strong>
                </Typography>
                <Typography variant="body2">
                  Awaiting review <strong>{out.data.awaitingReview}</strong>
                </Typography>
                <Typography variant="body2" color={out.data.overdue ? 'error.main' : undefined}>
                  Overdue <strong>{out.data.overdue}</strong>
                </Typography>
              </Stack>
              <DataTable
                rows={out.data.rows}
                rowKey={(r) => r.order.id}
                emptyTitle="No outstanding investigations"
                rowSx={(r) => (r.overdue ? { boxShadow: `inset 3px 0 0 ${colors.warning}` } : undefined)}
                columns={[
                  {
                    key: 't',
                    header: 'Investigation',
                    primary: true,
                    render: (r) => (
                      <Box>
                        <Typography variant="body2" fontWeight={600}>
                          {r.order.testName}
                        </Typography>
                        <Typography variant="caption" color="text.secondary">
                          {r.order.orderNumber}
                        </Typography>
                      </Box>
                    ),
                  },
                  {
                    key: 'p',
                    header: 'Patient',
                    render: (r) => (
                      <Box component={Link} href={`/doctor/patients/${r.order.admissionId}?tab=investigations`} sx={{ color: 'inherit' }}>
                        {r.order.patient?.patientName}
                      </Box>
                    ),
                  },
                  { key: 's', header: 'Status', render: (r) => <StatusChip value={r.order.status} /> },
                  {
                    key: 'r',
                    header: 'Waiting',
                    render: (r) => (
                      <Typography variant="body2" color={r.overdue ? 'warning.main' : undefined}>
                        {r.reason} · {Math.round(r.ageHours)} h
                      </Typography>
                    ),
                  },
                ]}
              />
            </>
          ))}
        {tab === 'alerts' &&
          (alerts.isLoading ? (
            <TableSkeleton />
          ) : alerts.error || !alerts.data ? (
            <Box sx={{ p: 4 }}>
              <ErrorState error={alerts.error} onRetry={() => alerts.refetch()} />
            </Box>
          ) : (
            <Grid container spacing={4} sx={{ p: 5 }}>
              <Grid size={{ xs: 12, md: 8 }}>
                <Typography variant="subtitle2" sx={{ mb: 2 }}>
                  Alerts raised per day
                </Typography>
                <Box sx={{ height: 220 }} role="img" aria-label={`Alerts per day: ${alerts.data.byDay.map((d) => `${d.key} ${d.count}`).join(', ')}`}>
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart data={alerts.data.byDay.map((d) => ({ day: formatDate(d.key, 'DD MMM'), count: d.count }))} margin={{ top: 8, right: 8, left: -20, bottom: 0 }}>
                      <CartesianGrid vertical={false} stroke={colors.border} />
                      <XAxis dataKey="day" tick={{ fontSize: 12, fill: colors.inkMuted }} tickLine={false} axisLine={{ stroke: colors.border }} />
                      <YAxis allowDecimals={false} tick={{ fontSize: 12, fill: colors.inkMuted }} tickLine={false} axisLine={false} />
                      <Tooltip cursor={{ fill: colors.primarySoft }} formatter={(v) => [v as number, 'Alerts']} />
                      <Bar dataKey="count" fill={colors.primary} radius={[4, 4, 0, 0]} maxBarSize={28} />
                    </BarChart>
                  </ResponsiveContainer>
                </Box>
              </Grid>
              <Grid size={{ xs: 12, md: 4 }}>
                <Stack spacing={2}>
                  <Typography variant="body2">
                    Total <strong>{alerts.data.total}</strong> · critical <strong>{alerts.data.critical}</strong> · resolved <strong>{alerts.data.resolved}</strong>
                  </Typography>
                  <Typography variant="body2">
                    Median time to acknowledge:{' '}
                    <strong>{alerts.data.medianMinutesToAcknowledge !== undefined && alerts.data.medianMinutesToAcknowledge !== null ? `${alerts.data.medianMinutesToAcknowledge} min` : '—'}</strong>
                  </Typography>
                  <Typography variant="subtitle2" sx={{ pt: 2 }}>
                    By type
                  </Typography>
                  {alerts.data.byType.map((t) => (
                    <Stack key={t.key} direction="row" justifyContent="space-between">
                      <Typography variant="body2">{humanize(t.key)}</Typography>
                      <Typography variant="body2" fontWeight={600}>
                        {t.count}
                      </Typography>
                    </Stack>
                  ))}
                </Stack>
              </Grid>
            </Grid>
          ))}
      </Box>
    </SectionCard>
  );
}

export default function DoctorDashboard() {
  const me = useMe();
  const { hasPermission } = useAuth();
  const q = useQuery({ queryKey: ck.doctorDashboard, queryFn: clinicalApi.doctorDashboard, refetchInterval: 60_000 });
  const { acknowledge } = useAlertActions();

  if (q.isLoading) return <PageSkeleton />;
  if (q.error || !q.data) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const d = q.data;
  return (
    <>
      <PageHeader
        title={`Good ${new Date().getHours() < 12 ? 'morning' : new Date().getHours() < 17 ? 'afternoon' : 'evening'}, ${me.fullName}`}
        subtitle={[me.staff?.designation, me.staff?.departmentName].filter(Boolean).join(' · ')}
        actions={
          <Button component={Link} href="/doctor/patients" variant="contained" startIcon={<Stethoscope size={16} />}>
            My patients
          </Button>
        }
      />
      <Grid container spacing={4} sx={{ mb: 5 }}>
        <Grid size={{ xs: 6, md: 4, lg: 2 }}>
          <KpiCard label="My patients" value={d.myPatients} icon={Stethoscope} href="/doctor/patients" hint={`${d.newAdmissions24h} new in 24 h`} />
        </Grid>
        <Grid size={{ xs: 6, md: 4, lg: 2 }}>
          <KpiCard label="Critical alerts" value={d.criticalAlerts} icon={AlertTriangle} tone={d.criticalAlerts ? 'error' : 'success'} href="/doctor/alerts" hint={`${d.openAlerts} unresolved in total`} />
        </Grid>
        <Grid size={{ xs: 6, md: 4, lg: 2 }}>
          <KpiCard label="To review" value={d.resultsToReview} icon={FlaskConical} tone={d.resultsToReview ? 'warning' : 'success'} href="/doctor/investigations" hint={`${d.pendingInvestigations} pending`} />
        </Grid>
        <Grid size={{ xs: 6, md: 4, lg: 2 }}>
          <KpiCard label="Active orders" value={d.activeOrders} icon={Pill} tone="info" href="/doctor/prescriptions" />
        </Grid>
        <Grid size={{ xs: 6, md: 4, lg: 2 }}>
          <KpiCard label="Going home" value={d.expectedDischargesToday} icon={CalendarCheck} tone="secondary" href="/doctor/discharge" hint="EDD today or earlier" />
        </Grid>
        <Grid size={{ xs: 6, md: 4, lg: 2 }}>
          <KpiCard label="Notes due" value={d.documentationGaps} icon={FileWarning} tone={d.documentationGaps ? 'warning' : 'success'} hint="No medical note in 24 h" />
        </Grid>
      </Grid>
      <Grid container spacing={5}>
        <Grid size={{ xs: 12, lg: 7 }}>
          <SectionCard title="My patients" action={<Button component={Link} href="/doctor/patients" size="small">Open worklist</Button>} flush>
            <PatientWorklist rows={d.patients} basePath="/doctor/patients" compact hideObservations />
          </SectionCard>
        </Grid>
        <Grid size={{ xs: 12, lg: 5 }}>
          <Stack spacing={5}>
            <SectionCard title="Critical alerts" action={<Button component={Link} href="/doctor/alerts" size="small">All alerts</Button>}>
              {d.criticalAlertList.length === 0 ? (
                <EmptyState title="No critical alerts" compact icon={AlertTriangle} />
              ) : (
                <Stack spacing={2}>
                  {d.criticalAlertList.map((a) => (
                    <AlertCard
                      key={a.id}
                      alert={a}
                      patientLink={`/doctor/patients/${a.admissionId}?tab=observations`}
                      busy={acknowledge.isPending}
                      onAcknowledge={hasPermission('ALERT_MANAGE') ? (x) => acknowledge.mutate(x) : undefined}
                    />
                  ))}
                </Stack>
              )}
            </SectionCard>
            <SectionCard title="Results awaiting review" action={<Button component={Link} href="/doctor/investigations" size="small">Review results</Button>}>
              {d.resultsAwaitingReview.length === 0 ? (
                <EmptyState title="All results reviewed" compact icon={FlaskConical} />
              ) : (
                <Stack spacing={2.5}>
                  {d.resultsAwaitingReview.map((o) => (
                    <Stack key={o.id} direction="row" justifyContent="space-between" spacing={2} alignItems="flex-start">
                      <Box sx={{ minWidth: 0 }}>
                        <Typography variant="body2" fontWeight={600}>
                          {o.testName}
                        </Typography>
                        <Typography variant="caption" color="text.secondary" component="div">
                          <Box component={Link} href={`/doctor/patients/${o.admissionId}?tab=investigations`} sx={{ color: 'inherit' }}>
                            {o.patient?.patientName}
                          </Box>{' '}
                          · ordered {formatDateTime(o.orderedAt, 'DD MMM HH:mm')}
                        </Typography>
                      </Box>
                      {o.hasCritical ? <StatusChip value="CRITICAL" /> : o.hasAbnormal ? <StatusChip value="ABNORMAL" /> : <StatusChip value="NORMAL" />}
                    </Stack>
                  ))}
                </Stack>
              )}
            </SectionCard>
          </Stack>
        </Grid>
        {hasPermission('REPORT_CLINICAL') && (
          <Grid size={12}>
            <ReportsSection />
          </Grid>
        )}
      </Grid>
    </>
  );
}
