'use client';

import Link from 'next/link';
import { useQuery } from '@tanstack/react-query';
import { Alert, AlertTitle, Box, Button, Card, CardActionArea, Divider, Grid, LinearProgress, Stack, Tooltip, Typography } from '@mui/material';
import {
  AlertTriangle,
  BarChart3,
  BedDouble,
  ClipboardList,
  FileCheck2,
  Hospital,
  Info,
  RefreshCcw,
  ScrollText,
  ShieldAlert,
  ShieldCheck,
  Timer,
  UserPlus,
  Users,
  Wallet,
} from 'lucide-react';
import { adminApi } from '@/lib/api/admin';
import { useAuth } from '@/lib/auth/AuthProvider';
import { formatDateTime, formatINR } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { KpiCard } from '@/components/ui/KpiCard';
import { SectionCard } from '@/components/ui/SectionCard';
import { EmptyState, ErrorState, PageSkeleton } from '@/components/ui/States';
import { AdmissionsTrendChart, OccupancyByWardChart } from '@/components/admin/Charts';
import { colors } from '@/theme/tokens';

export default function AdminDashboardPage() {
  const { hasPermission, me } = useAuth();
  const canReport = hasPermission('REPORT_OPERATIONAL');
  const q = useQuery({ queryKey: ['dashboard', 'admin'], queryFn: adminApi.dashboard, enabled: canReport, refetchInterval: 60_000 });

  if (!canReport) {
    return (
      <>
        <PageHeader title="Hospital dashboard" breadcrumbs={[{ label: 'Administration' }]} />
        <EmptyState
          icon={ShieldAlert} title="Operational reporting is not part of your role" description="Ask an administrator for the operational reporting permission." />
      </>
    );
  }
  if (q.isLoading) return <PageSkeleton />;
  if (q.error) {
    return (
      <>
        <PageHeader title="Hospital dashboard" breadcrumbs={[{ label: 'Administration' }]} />
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      </>
    );
  }
  const d = q.data!;
  const pending = [
    { label: 'Patients awaiting a bed', value: d.pending.pendingBedAssignments, href: '/admin/admissions', icon: Hospital, show: hasPermission('ADMISSION_MANAGE') },
    { label: 'Pending transfers', value: d.pending.pendingTransfers, href: '/admin/transfers', icon: RefreshCcw, show: hasPermission('TRANSFER_COMPLETE') },
    { label: 'Discharges in progress', value: d.pending.dischargeWorkflowsInProgress, href: '/admin/discharges', icon: FileCheck2, show: hasPermission('DISCHARGE_VIEW') },
    { label: 'Open patient requests', value: d.pending.openPatientRequests, href: '/admin/privacy', icon: ShieldCheck, show: hasPermission('PATIENT_REQUEST_MANAGE') },
    { label: 'Open safety incidents', value: d.pending.openIncidents, href: '/admin/incidents', icon: ShieldAlert, show: hasPermission('INCIDENT_REVIEW') },
  ].filter((p) => p.show);

  return (
    <>
      <PageHeader
        title="Hospital dashboard"
        subtitle={`${me?.organization.name ?? ''} · updated ${formatDateTime(d.generatedAt, 'HH:mm')}`}
        breadcrumbs={[{ label: 'Administration' }]}
        actions={
          <>
            <Button component={Link} href="/admin/beds" variant="outlined" startIcon={<BedDouble size={16} />}>
              Bed board
            </Button>
            <Button component={Link} href="/admin/analytics" variant="contained" startIcon={<BarChart3 size={16} />}>
              Analytics
            </Button>
          </>
        }
      />

      {d.alerts.length > 0 && (
        <Stack spacing={2} sx={{ mb: 5 }}>
          {d.alerts.map((a, i) => (
            <Alert
              key={a.code + i}
              severity={a.severity === 'CRITICAL' ? 'error' : a.severity === 'WARNING' ? 'warning' : 'info'}
              action={
                a.link && (
                  <Button component={Link} href={a.link} color="inherit" size="small">
                    Open
                  </Button>
                )
              }
            >
              <AlertTitle sx={{ mb: 0 }}>{a.title}</AlertTitle>
              {a.message}
            </Alert>
          ))}
        </Stack>
      )}

      <Grid container spacing={4} sx={{ mb: 5 }}>
        <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
          <KpiCard label="Registered patients" value={d.patients.totalRegistered} icon={Users} tone="neutral" href={hasPermission('PATIENT_DEMOGRAPHICS_READ') ? '/admin/patients' : undefined} />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
          <KpiCard
            label="Currently admitted"
            value={d.patients.currentlyAdmitted}
            icon={Hospital}
            hint={`${d.patients.awaitingBed} awaiting bed · ${d.patients.dischargeInProgress} discharging`}
            href={hasPermission('ADMISSION_MANAGE') ? '/admin/admissions' : undefined}
          />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
          <KpiCard
            label="Bed occupancy"
            value={`${d.beds.occupancyPercent}%`}
            icon={BedDouble}
            tone={d.beds.occupancyPercent >= 90 ? 'error' : 'info'}
            hint={`${d.beds.occupied} of ${d.beds.total} active beds occupied`}
            href="/admin/beds"
          />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
          <KpiCard label="Available beds" value={d.beds.available} icon={BedDouble} tone="success" hint={`${d.beds.reserved} reserved`} href="/admin/beds" />
        </Grid>
      </Grid>

      <Grid container spacing={5} sx={{ mb: 5 }}>
        <Grid size={{ xs: 12, lg: 7 }}>
          <SectionCard title="Admissions and discharges" subtitle="Last 14 days, hospital time">
            {d.trend.every((t) => t.admissions === 0 && t.discharges === 0) ? (
              <EmptyState title="No admissions or discharges in the last 14 days" compact />
            ) : (
              <AdmissionsTrendChart data={d.trend} />
            )}
          </SectionCard>
        </Grid>
        <Grid size={{ xs: 12, lg: 5 }}>
          <SectionCard title="Bed status" subtitle="All active beds">
            <BedStatusBreakdown beds={d.beds} />
          </SectionCard>
        </Grid>
      </Grid>

      <Grid container spacing={5} sx={{ mb: 5 }}>
        <Grid size={{ xs: 12, lg: 7 }}>
          <SectionCard title="Occupancy by ward" subtitle="Dashed line marks the 90% alert threshold">
            {d.wards.length === 0 ? (
              <EmptyState title="No wards configured" description="Add wards, rooms and beds to see occupancy." action={<Button component={Link} href="/admin/facility">Configure facility</Button>} compact />
            ) : (
              <OccupancyByWardChart data={d.wards.map((w) => ({ name: w.name, occupancyPercent: w.occupancyPercent, occupied: w.occupied, total: w.total }))} />
            )}
          </SectionCard>
        </Grid>
        <Grid size={{ xs: 12, lg: 5 }}>
          <SectionCard title="Pending work" subtitle="Items waiting on administration">
            {pending.length === 0 ? (
              <EmptyState title="Nothing assigned to your role" compact />
            ) : (
              <Stack divider={<Divider flexItem />}>
                {pending.map((p) => (
                  <CardActionArea key={p.label} component={Link} href={p.href} sx={{ borderRadius: 2, px: 2, py: 2.5 }}>
                    <Stack direction="row" alignItems="center" spacing={3}>
                      <Box sx={{ color: p.value > 0 ? colors.warning : colors.inkSubtle, display: 'grid' }} aria-hidden>
                        <p.icon size={18} />
                      </Box>
                      <Typography variant="body2" sx={{ flex: 1 }}>
                        {p.label}
                      </Typography>
                      <Typography variant="h4" component="span" sx={{ fontVariantNumeric: 'tabular-nums' }}>
                        {p.value}
                      </Typography>
                    </Stack>
                  </CardActionArea>
                ))}
              </Stack>
            )}
          </SectionCard>
        </Grid>
      </Grid>

      {d.financial && (
        <Grid container spacing={4} sx={{ mb: 5 }}>
          <Grid size={{ xs: 12, md: 6 }}>
            <KpiCard
              label="Today's collections"
              value={formatINR(d.financial.todayCollections)}
              icon={Wallet}
              tone="success"
              hint={
                <Stack direction="row" spacing={1} alignItems="center">
                  <span>Cash received today, net of reversals</span>
                  <Tooltip title={d.financial.collectionsDefinition}>
                    <Box component="span" sx={{ display: 'inline-flex' }} tabIndex={0} aria-label="Definition of collections">
                      <Info size={13} />
                    </Box>
                  </Tooltip>
                </Stack>
              }
            />
          </Grid>
          <Grid size={{ xs: 12, md: 6 }}>
            <KpiCard
              label="Outstanding receivables"
              value={formatINR(d.financial.outstandingReceivables)}
              icon={Timer}
              tone="warning"
              hint={
                <Stack direction="row" spacing={1} alignItems="center">
                  <span>Still owed across {d.financial.invoicesWithBalance} invoice{d.financial.invoicesWithBalance === 1 ? '' : 's'} (not revenue)</span>
                  <Tooltip title={d.financial.receivablesDefinition}>
                    <Box component="span" sx={{ display: 'inline-flex' }} tabIndex={0} aria-label="Definition of receivables">
                      <Info size={13} />
                    </Box>
                  </Tooltip>
                </Stack>
              }
            />
          </Grid>
        </Grid>
      )}

      <Grid container spacing={5} sx={{ mb: 5 }}>
        <Grid size={{ xs: 12, md: 6 }}>
          <SectionCard title="Doctor workload" subtitle="Open admissions per attending doctor">
            {d.workload.doctors.length === 0 ? (
              <EmptyState title="No active doctors" compact />
            ) : (
              <Stack spacing={3}>
                {d.workload.doctors.map((doc) => (
                  <Box key={doc.userId}>
                    <Stack direction="row" justifyContent="space-between" spacing={2}>
                      <Box sx={{ minWidth: 0 }}>
                        <Typography variant="body2" fontWeight={500} noWrap>
                          {doc.name}
                        </Typography>
                        <Typography variant="caption" color="text.secondary">
                          {doc.department ?? 'No department'}
                        </Typography>
                      </Box>
                      <Typography variant="body2" fontWeight={600} sx={{ fontVariantNumeric: 'tabular-nums' }}>
                        {doc.openPatients}
                      </Typography>
                    </Stack>
                    <LinearProgress
                      variant="determinate"
                      value={Math.min(100, (doc.openPatients / Math.max(1, ...d.workload.doctors.map((x) => x.openPatients))) * 100)}
                      sx={{ mt: 1, height: 6, borderRadius: 3 }}
                      aria-label={`${doc.name}: ${doc.openPatients} open patients`}
                    />
                  </Box>
                ))}
              </Stack>
            )}
          </SectionCard>
        </Grid>
        <Grid size={{ xs: 12, md: 6 }}>
          <SectionCard title="Nursing cover by ward" subtitle="Occupied beds per assigned nurse">
            {d.workload.nurseWards.length === 0 ? (
              <EmptyState title="No wards configured" compact />
            ) : (
              <Stack divider={<Divider flexItem />}>
                {d.workload.nurseWards.map((w) => (
                  <Stack key={w.wardId} direction="row" alignItems="center" spacing={2} sx={{ py: 2 }}>
                    <Box sx={{ flex: 1, minWidth: 0 }}>
                      <Typography variant="body2" fontWeight={500} noWrap>
                        {w.wardName}
                      </Typography>
                      <Typography variant="caption" color="text.secondary">
                        {w.nursesAssigned} nurse{w.nursesAssigned === 1 ? '' : 's'} · {w.occupiedBeds} occupied
                      </Typography>
                    </Box>
                    {w.nursesAssigned === 0 ? (
                      <Stack direction="row" spacing={1} alignItems="center" sx={{ color: w.occupiedBeds > 0 ? colors.danger : colors.inkSubtle }}>
                        <AlertTriangle size={14} aria-hidden />
                        <Typography variant="caption" fontWeight={600}>
                          No nurse assigned
                        </Typography>
                      </Stack>
                    ) : (
                      <Typography variant="body2" fontWeight={600} sx={{ fontVariantNumeric: 'tabular-nums' }}>
                        {w.patientsPerNurse ?? 0} per nurse
                      </Typography>
                    )}
                  </Stack>
                ))}
              </Stack>
            )}
          </SectionCard>
        </Grid>
      </Grid>

      <SectionCard title="Quick links">
        <Grid container spacing={3}>
          {[
            { label: 'Register patient', href: '/admin/patients/new', icon: UserPlus, perm: 'PATIENT_REGISTER' },
            { label: 'Staff', href: '/admin/staff', icon: Users, perm: 'USER_MANAGE' },
            { label: 'Wards, rooms & beds', href: '/admin/facility', icon: BedDouble, perm: 'FACILITY_MANAGE' },
            { label: 'Settings & rules', href: '/admin/settings', icon: ClipboardList, perm: 'SETTINGS_MANAGE' },
            { label: 'Safety incidents', href: '/admin/incidents', icon: ShieldAlert, perm: 'INCIDENT_REVIEW' },
            { label: 'Audit logs', href: '/admin/audit', icon: ScrollText, perm: 'AUDIT_READ' },
          ]
            .filter((l) => hasPermission(l.perm))
            .map((l) => (
              <Grid key={l.href} size={{ xs: 6, md: 4, lg: 2 }}>
                <Card variant="outlined" sx={{ height: '100%' }}>
                  <CardActionArea component={Link} href={l.href} sx={{ p: 3, height: '100%' }}>
                    <Stack spacing={1.5} alignItems="flex-start">
                      <l.icon size={18} color={colors.primary} aria-hidden />
                      <Typography variant="body2" fontWeight={500}>
                        {l.label}
                      </Typography>
                    </Stack>
                  </CardActionArea>
                </Card>
              </Grid>
            ))}
        </Grid>
      </SectionCard>
    </>
  );
}

function BedStatusBreakdown({ beds }: { beds: { total: number; available: number; occupied: number; reserved: number; cleaning: number; maintenance: number } }) {
  const rows = [
    { label: 'Occupied', value: beds.occupied, color: colors.primary },
    { label: 'Available', value: beds.available, color: colors.success },
    { label: 'Reserved', value: beds.reserved, color: colors.info },
    { label: 'Cleaning', value: beds.cleaning, color: colors.warning },
    { label: 'Maintenance', value: beds.maintenance, color: colors.danger },
  ];
  if (beds.total === 0) return <EmptyState title="No active beds" compact />;
  return (
    <Stack spacing={4}>
      <Box sx={{ display: 'flex', height: 14, borderRadius: 2, overflow: 'hidden', gap: '2px', bgcolor: 'background.paper' }} role="img" aria-label="Bed status distribution">
        {rows
          .filter((r) => r.value > 0)
          .map((r) => (
            <Tooltip key={r.label} title={`${r.label}: ${r.value}`}>
              <Box sx={{ flex: r.value, bgcolor: r.color }} />
            </Tooltip>
          ))}
      </Box>
      <Stack spacing={2}>
        {rows.map((r) => (
          <Stack key={r.label} direction="row" alignItems="center" spacing={2}>
            <Box sx={{ width: 10, height: 10, borderRadius: 0.5, bgcolor: r.color }} aria-hidden />
            <Typography variant="body2" sx={{ flex: 1 }}>
              {r.label}
            </Typography>
            <Typography variant="body2" fontWeight={600} sx={{ fontVariantNumeric: 'tabular-nums' }}>
              {r.value}
            </Typography>
            <Typography variant="caption" color="text.secondary" sx={{ width: 44, textAlign: 'right' }}>
              {Math.round((r.value / beds.total) * 100)}%
            </Typography>
          </Stack>
        ))}
      </Stack>
    </Stack>
  );
}
