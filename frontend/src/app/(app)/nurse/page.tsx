'use client';

import Link from 'next/link';
import { useQuery } from '@tanstack/react-query';
import { Alert, Box, Button, Grid, Stack, Typography } from '@mui/material';
import {
  AlertTriangle,
  ClipboardList,
  FlaskConical,
  HeartPulse,
  ListChecks,
  MessageSquareWarning,
  Pill,
  Repeat2,
  Syringe,
  Users,
} from 'lucide-react';
import { ck, clinicalApi } from '@/lib/api/clinical';
import { useMe } from '@/lib/auth/AuthProvider';
import { formatDateTime } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { SectionCard } from '@/components/ui/SectionCard';
import { KpiCard } from '@/components/ui/KpiCard';
import { StatusChip } from '@/components/ui/StatusChip';
import { EmptyState, ErrorState, PageSkeleton } from '@/components/ui/States';
import { PatientWorklist } from '@/components/clinical/PatientWorklist';
import { AlertCard, useAlertActions } from '@/components/clinical/alerts/AlertsPanel';

export default function NurseDashboard() {
  const me = useMe();
  const q = useQuery({ queryKey: ck.nurseDashboard, queryFn: clinicalApi.nurseDashboard, refetchInterval: 60_000 });
  const { acknowledge } = useAlertActions();

  if (q.isLoading) return <PageSkeleton />;
  if (q.error || !q.data) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const d = q.data;
  const wards = me.staff?.wards.map((w) => w.name).join(' · ');
  const attention = d.patients.filter((r) => r.openAlerts > 0 || r.overdueDoses > 0 || r.observationOverdue || r.dueDoses > 0);

  return (
    <>
      <PageHeader
        title={`Good ${new Date().getHours() < 12 ? 'morning' : new Date().getHours() < 17 ? 'afternoon' : 'evening'}, ${me.fullName.split(' ')[0]}`}
        subtitle={wards ? `Your wards: ${wards}` : 'No ward assignment - contact the nursing office.'}
        actions={
          <>
            <Button component={Link} href="/nurse/medications" variant="contained" startIcon={<Syringe size={16} />}>
              Medication round
            </Button>
            <Button component={Link} href="/nurse/vitals" variant="outlined" startIcon={<HeartPulse size={16} />}>
              Record vitals
            </Button>
          </>
        }
      />
      {d.handoversToAcknowledge > 0 && (
        <Alert
          severity="warning"
          icon={<Repeat2 size={18} />}
          sx={{ mb: 5 }}
          action={
            <Button component={Link} href="/nurse/handover" color="inherit" size="small">
              Open handover
            </Button>
          }
        >
          {d.handoversToAcknowledge} shift handover{d.handoversToAcknowledge > 1 ? 's are' : ' is'} waiting for your acknowledgement.
        </Alert>
      )}
      <Grid container spacing={4} sx={{ mb: 5 }}>
        <Grid size={{ xs: 6, md: 4, lg: 2.4 }}>
          <KpiCard label="My patients" value={d.myPatients} icon={Users} href="/nurse/patients" hint={`${d.expectedDischargesToday} expected discharge today`} />
        </Grid>
        <Grid size={{ xs: 6, md: 4, lg: 2.4 }}>
          <KpiCard label="Doses overdue" value={d.dosesOverdue} icon={Pill} tone={d.dosesOverdue ? 'error' : 'success'} href="/nurse/medications" hint={`${d.dosesDue} due now`} />
        </Grid>
        <Grid size={{ xs: 6, md: 4, lg: 2.4 }}>
          <KpiCard label="Med exceptions" value={d.openExceptions} icon={MessageSquareWarning} tone={d.openExceptions ? 'warning' : 'success'} href="/nurse/exceptions" hint="Awaiting follow-up" />
        </Grid>
        <Grid size={{ xs: 6, md: 4, lg: 2.4 }}>
          <KpiCard label="Open alerts" value={d.openAlerts} icon={AlertTriangle} tone={d.openAlerts ? 'error' : 'success'} href="/nurse/patients" hint={`${d.observationsOverdue} observation set${d.observationsOverdue === 1 ? '' : 's'} overdue`} />
        </Grid>
        <Grid size={{ xs: 12, md: 4, lg: 2.4 }}>
          <KpiCard label="Tasks due (4 h)" value={d.tasksDue} icon={ListChecks} tone={d.tasksOverdue ? 'warning' : 'info'} href="/nurse/tasks" hint={`${d.tasksOverdue} overdue · ${d.pendingCollections} specimen collection${d.pendingCollections === 1 ? '' : 's'}`} />
        </Grid>
      </Grid>
      <Grid container spacing={5}>
        <Grid size={{ xs: 12, lg: 8 }}>
          <SectionCard
            title="Patients needing attention"
            subtitle="Open alerts, medications due or overdue, and overdue observations"
            action={<Button component={Link} href="/nurse/patients" size="small">All patients</Button>}
            flush
          >
            <PatientWorklist rows={attention} hideObservations basePath="/nurse/patients" emptyTitle="Nothing needs attention right now" />
          </SectionCard>
        </Grid>
        <Grid size={{ xs: 12, lg: 4 }}>
          <Stack spacing={5}>
            <SectionCard title="Alerts" action={<Button component={Link} href="/nurse/patients" size="small">Patients</Button>}>
              {d.alerts.length === 0 ? (
                <EmptyState title="No open alerts" compact icon={AlertTriangle} />
              ) : (
                <Stack spacing={2}>
                  {d.alerts.map((a) => (
                    <AlertCard
                      key={a.id}
                      alert={a}
                      patientLink={`/nurse/patients/${a.admissionId}?tab=alerts`}
                      busy={acknowledge.isPending}
                      onAcknowledge={(x) => acknowledge.mutate(x)}
                    />
                  ))}
                </Stack>
              )}
            </SectionCard>
            <SectionCard title="Upcoming tasks" action={<Button component={Link} href="/nurse/tasks" size="small">Task board</Button>}>
              {d.upcomingTasks.length === 0 ? (
                <EmptyState title="No tasks due" compact icon={ClipboardList} />
              ) : (
                <Stack spacing={2.5}>
                  {d.upcomingTasks.map((t) => (
                    <Box key={t.id}>
                      <Stack direction="row" spacing={1} alignItems="center" justifyContent="space-between">
                        <Typography variant="body2" fontWeight={600} sx={{ minWidth: 0, wordBreak: 'break-word' }}>
                          {t.title}
                        </Typography>
                        <StatusChip value={t.priority} />
                      </Stack>
                      <Typography variant="caption" color={t.overdue ? 'error.main' : 'text.secondary'} component="div">
                        <Box component={Link} href={`/nurse/patients/${t.admissionId}?tab=tasks`} sx={{ color: 'inherit' }}>
                          {t.patient?.patientName}
                        </Box>
                        {t.patient?.location ? ` · ${t.patient.location}` : ''} · {t.dueAt ? `${t.overdue ? 'overdue since' : 'due'} ${formatDateTime(t.dueAt, 'HH:mm')}` : 'no due time'}
                      </Typography>
                    </Box>
                  ))}
                </Stack>
              )}
            </SectionCard>
            {d.pendingCollections > 0 && (
              <Alert severity="info" icon={<FlaskConical size={18} />}>
                {d.pendingCollections} specimen collection{d.pendingCollections > 1 ? 's' : ''} pending - see the Tasks or Investigations tab of each patient.
              </Alert>
            )}
          </Stack>
        </Grid>
      </Grid>
    </>
  );
}
