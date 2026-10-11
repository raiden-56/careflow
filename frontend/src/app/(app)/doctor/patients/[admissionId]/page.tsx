'use client';

import { use, useEffect, useState } from 'react';
import Link from 'next/link';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Card, Dialog, DialogActions, DialogContent, DialogTitle, Stack, Tab, Tabs, TextField, Typography } from '@mui/material';
import { CalendarClock, FileCheck2 } from 'lucide-react';
import { admissionApi, qk } from '@/lib/api/core';
import { ck, clinicalApi } from '@/lib/api/clinical';
import { useAuth } from '@/lib/auth/AuthProvider';
import { useToast } from '@/lib/hooks';
import { errorMessage } from '@/lib/api/client';
import { formatDate, todayLocalDate } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { SectionCard } from '@/components/ui/SectionCard';
import { ErrorState, PageSkeleton } from '@/components/ui/States';
import { PatientBanner } from '@/components/patient/PatientBanner';
import { AdmissionTimeline } from '@/components/timeline/AdmissionTimeline';
import { ClinicalSummaryView } from '@/components/clinical/summary/ClinicalSummaryView';
import { NotesPanel } from '@/components/clinical/notes/NotesPanel';
import { AlertsPanel } from '@/components/clinical/alerts/AlertsPanel';
import { VitalsPanel } from '@/components/clinical/vitals/VitalsPanel';
import { OrdersPanel } from '@/components/clinical/orders/OrdersPanel';
import { InvestigationsPanel } from '@/components/clinical/investigations/InvestigationsPanel';
import { TrendExplorer } from '@/components/clinical/trends/TrendExplorer';
import { DiagnosesPanel } from '@/components/clinical/record/DiagnosesPanel';
import { TreatmentPlanPanel } from '@/components/clinical/record/TreatmentPlanPanel';
import { ProceduresPanel } from '@/components/clinical/record/ProceduresPanel';
import { AllergiesHistoryPanel } from '@/components/clinical/record/AllergiesHistoryPanel';

const TABS = [
  { value: 'summary', label: 'Summary' },
  { value: 'notes', label: 'Assessment & progress notes' },
  { value: 'plan', label: 'Diagnoses & treatment plan' },
  { value: 'prescriptions', label: 'Prescriptions' },
  { value: 'investigations', label: 'Investigations' },
  { value: 'procedures', label: 'Procedures' },
  { value: 'trends', label: 'Recovery trends' },
  { value: 'observations', label: 'Nursing observations & alerts' },
  { value: 'history', label: 'Allergies & history' },
  { value: 'timeline', label: 'Timeline' },
] as const;
type TabKey = (typeof TABS)[number]['value'];

/** Tabs referenced by the summary view's "view more" links. */
const SUMMARY_LINKS: Record<string, TabKey> = {
  diagnoses: 'plan',
  medications: 'prescriptions',
  notes: 'notes',
  vitals: 'observations',
  alerts: 'observations',
  investigations: 'investigations',
  tasks: 'observations',
  history: 'history',
};

function ExpectedDischargeDialog({
  open,
  onClose,
  admissionId,
  current,
  admittedAt,
}: {
  open: boolean;
  onClose: () => void;
  admissionId: string;
  current?: string;
  admittedAt: string;
}) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [date, setDate] = useState(current ?? '');
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (open) {
      setDate(current ?? '');
      setError(null);
    }
  }, [open, current]);
  const m = useMutation({
    mutationFn: (value: string | null) => admissionApi.setExpectedDischarge(admissionId, value),
    onSuccess: (d) => {
      toast.success(d.summary.expectedDischargeDate ? `Expected discharge set to ${formatDate(d.summary.expectedDischargeDate)}` : 'Expected discharge date cleared');
      queryClient.invalidateQueries({ queryKey: qk.admission(admissionId) });
      queryClient.invalidateQueries({ queryKey: ['clinical', admissionId] });
      queryClient.invalidateQueries({ queryKey: ['clinical-lists'] });
      queryClient.invalidateQueries({ queryKey: ['admissions'] });
      queryClient.invalidateQueries({ queryKey: ['admission', admissionId, 'timeline'] });
      onClose();
    },
    onError: (e) => setError(errorMessage(e)),
  });
  const min = formatDate(admittedAt, 'YYYY-MM-DD');
  return (
    <Dialog open={open} onClose={m.isPending ? undefined : onClose} maxWidth="xs" fullWidth aria-labelledby="edd-title">
      <DialogTitle id="edd-title">Expected discharge date</DialogTitle>
      <DialogContent>
        <Stack spacing={3} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <Typography variant="body2" color="text.secondary">
            Shared with the care team, bed management and the patient portal as a planning estimate. It does not start the discharge workflow.
          </Typography>
          <TextField
            type="date"
            label="Expected discharge"
            value={date}
            onChange={(e) => setDate(e.target.value)}
            slotProps={{ inputLabel: { shrink: true }, htmlInput: { min } }}
            helperText={date && date < todayLocalDate() ? 'This date is in the past' : ' '}
          />
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        {current && (
          <Button color="error" onClick={() => m.mutate(null)} disabled={m.isPending} sx={{ mr: 'auto' }}>
            Clear
          </Button>
        )}
        <Button color="inherit" onClick={onClose} disabled={m.isPending}>
          Cancel
        </Button>
        <Button variant="contained" onClick={() => m.mutate(date)} disabled={!date || m.isPending}>
          Save
        </Button>
      </DialogActions>
    </Dialog>
  );
}

export default function DoctorPatientPage({ params }: { params: Promise<{ admissionId: string }> }) {
  const { admissionId } = use(params);
  const router = useRouter();
  const pathname = usePathname();
  const search = useSearchParams();
  const { hasPermission } = useAuth();
  const requested = search.get('tab') as TabKey | null;
  const tab: TabKey = TABS.some((t) => t.value === requested) ? (requested as TabKey) : 'summary';
  const setTab = (t: string) => router.replace(`${pathname}?tab=${SUMMARY_LINKS[t] ?? t}`, { scroll: false });
  const [eddOpen, setEddOpen] = useState(false);

  const summary = useQuery({ queryKey: ck.summary(admissionId), queryFn: () => clinicalApi.summary(admissionId) });

  if (summary.isLoading) return <PageSkeleton />;
  if (summary.error || !summary.data) return <ErrorState error={summary.error} onRetry={() => summary.refetch()} />;
  const a = summary.data.admission;
  const open = ['PENDING_BED', 'ADMITTED', 'DISCHARGE_IN_PROGRESS'].includes(a.status);
  const allergyStrings = summary.data.allergies
    .filter((x) => x.status === 'ACTIVE')
    .map((x) => `${x.allergen} (${x.severity.toLowerCase().replace('_', '-')})`);

  return (
    <>
      <PageHeader
        title={a.patient.fullName}
        breadcrumbs={[{ label: 'Clinician', href: '/doctor' }, { label: 'My patients', href: '/doctor/patients' }, { label: a.patient.fullName }]}
        actions={
          <>
            {open && hasPermission('DISCHARGE_CLINICAL_AUTHORIZE') && (
              <Button variant="outlined" startIcon={<CalendarClock size={16} />} onClick={() => setEddOpen(true)}>
                {a.expectedDischargeDate ? `EDD ${formatDate(a.expectedDischargeDate)}` : 'Set expected discharge'}
              </Button>
            )}
            {a.status !== 'PENDING_BED' && a.status !== 'CANCELLED' && (
              <Button component={Link} href={`/doctor/discharge/${a.id}`} variant="contained" startIcon={<FileCheck2 size={16} />}>
                Discharge planning
              </Button>
            )}
          </>
        }
      />
      <PatientBanner admission={a} allergies={allergyStrings} />
      <Card sx={{ mb: 5 }}>
        <Tabs value={tab} onChange={(_, v) => setTab(v)} variant="scrollable" scrollButtons="auto" allowScrollButtonsMobile sx={{ px: 2 }} aria-label="Clinical record sections">
          {TABS.map((t) => (
            <Tab key={t.value} value={t.value} label={t.label} />
          ))}
        </Tabs>
      </Card>
      {tab === 'summary' && <ClinicalSummaryView summary={summary.data} onNavigate={setTab} />}
      {tab === 'notes' && (
        <SectionCard title="Assessment & progress notes" subtitle="Doctor and nursing documentation. Amendments keep the original version.">
          <NotesPanel admissionId={admissionId} defaultFilter="doctor" />
        </SectionCard>
      )}
      {tab === 'plan' && (
        <Stack spacing={5}>
          <DiagnosesPanel admissionId={admissionId} />
          <TreatmentPlanPanel admissionId={admissionId} />
        </Stack>
      )}
      {tab === 'prescriptions' && <OrdersPanel admissionId={admissionId} />}
      {tab === 'investigations' && <InvestigationsPanel admissionId={admissionId} />}
      {tab === 'procedures' && <ProceduresPanel admissionId={admissionId} />}
      {tab === 'trends' && (
        <SectionCard title="Recovery Trend Explorer" subtitle="Observations over time against hospital-configured ranges. Decision support only.">
          <TrendExplorer admissionId={admissionId} />
        </SectionCard>
      )}
      {tab === 'observations' && (
        <Stack spacing={5}>
          <SectionCard title="Alerts & escalations">
            <AlertsPanel admissionId={admissionId} />
          </SectionCard>
          <VitalsPanel admissionId={admissionId} />
        </Stack>
      )}
      {tab === 'history' && <AllergiesHistoryPanel patientId={a.patient.id} />}
      {tab === 'timeline' && (
        <SectionCard title="Admission timeline">
          <AdmissionTimeline admissionId={admissionId} />
        </SectionCard>
      )}
      <ExpectedDischargeDialog
        open={eddOpen}
        onClose={() => setEddOpen(false)}
        admissionId={admissionId}
        current={a.expectedDischargeDate}
        admittedAt={a.admittedAt}
      />
    </>
  );
}
