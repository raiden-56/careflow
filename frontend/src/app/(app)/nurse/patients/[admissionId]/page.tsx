'use client';

import { use, useState } from 'react';
import Link from 'next/link';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Card, Tab, Tabs } from '@mui/material';
import { ArrowLeftRight, ClipboardCheck, Siren } from 'lucide-react';
import { admissionApi, qk } from '@/lib/api/core';
import { ck, clinicalApi } from '@/lib/api/clinical';
import { useAuth } from '@/lib/auth/AuthProvider';
import { PageHeader } from '@/components/ui/PageHeader';
import { SectionCard } from '@/components/ui/SectionCard';
import { ErrorState, PageSkeleton } from '@/components/ui/States';
import { PatientBanner } from '@/components/patient/PatientBanner';
import { AdmissionTimeline } from '@/components/timeline/AdmissionTimeline';
import { ClinicalSummaryView } from '@/components/clinical/summary/ClinicalSummaryView';
import { NotesPanel } from '@/components/clinical/notes/NotesPanel';
import { AlertsPanel } from '@/components/clinical/alerts/AlertsPanel';
import { EscalateDialog } from '@/components/clinical/alerts/EscalateDialog';
import { TransferRequestDialog } from '@/components/clinical/TransferRequestDialog';
import { MarPanel } from '@/components/clinical/mar/MarPanel';
import { VitalsPanel } from '@/components/clinical/vitals/VitalsPanel';
import { TasksPanel } from '@/components/clinical/tasks/TasksPanel';
import { InvestigationsPanel } from '@/components/clinical/investigations/InvestigationsPanel';
import { AllergiesHistoryPanel } from '@/components/clinical/record/AllergiesHistoryPanel';

const TABS = [
  { value: 'overview', label: 'Overview' },
  { value: 'medications', label: 'Medications / MAR' },
  { value: 'vitals', label: 'Vitals' },
  { value: 'notes', label: 'Notes & assessments' },
  { value: 'tasks', label: 'Tasks' },
  { value: 'investigations', label: 'Investigations' },
  { value: 'alerts', label: 'Alerts' },
  { value: 'history', label: 'Allergies & history' },
  { value: 'timeline', label: 'Timeline' },
] as const;
type TabKey = (typeof TABS)[number]['value'];

export default function NursePatientPage({ params }: { params: Promise<{ admissionId: string }> }) {
  const { admissionId } = use(params);
  const router = useRouter();
  const pathname = usePathname();
  const search = useSearchParams();
  const { hasPermission } = useAuth();
  const requested = search.get('tab') as TabKey | null;
  const tab: TabKey = TABS.some((t) => t.value === requested) ? (requested as TabKey) : 'overview';
  const setTab = (t: string) => router.replace(`${pathname}?tab=${t}`, { scroll: false });
  const [escalating, setEscalating] = useState(false);
  const [transferring, setTransferring] = useState(false);

  const admission = useQuery({ queryKey: qk.admission(admissionId), queryFn: () => admissionApi.get(admissionId) });
  const summary = useQuery({ queryKey: ck.summary(admissionId), queryFn: () => clinicalApi.summary(admissionId) });

  if (admission.isLoading || summary.isLoading) return <PageSkeleton />;
  if (admission.error || summary.error || !admission.data || !summary.data) {
    return <ErrorState error={admission.error ?? summary.error} onRetry={() => { admission.refetch(); summary.refetch(); }} />;
  }
  const a = summary.data.admission;
  const open = ['PENDING_BED', 'ADMITTED', 'DISCHARGE_IN_PROGRESS'].includes(a.status);
  const pendingTransfer = admission.data.transfers.find((t) => t.status === 'REQUESTED' || t.status === 'APPROVED');
  const allergyStrings = summary.data.allergies
    .filter((x) => x.status === 'ACTIVE')
    .map((x) => `${x.allergen} (${x.severity.toLowerCase().replace('_', '-')})`);

  return (
    <>
      <PageHeader
        title={a.patient.fullName}
        breadcrumbs={[{ label: 'Nursing', href: '/nurse' }, { label: 'Assigned patients', href: '/nurse/patients' }, { label: a.patient.fullName }]}
        actions={
          <>
            {open && hasPermission('ALERT_MANAGE') && (
              <Button variant="contained" color="error" startIcon={<Siren size={16} />} onClick={() => setEscalating(true)}>
                Escalate to doctor
              </Button>
            )}
            {open && a.location && hasPermission('TRANSFER_REQUEST') && !pendingTransfer && (
              <Button variant="outlined" startIcon={<ArrowLeftRight size={16} />} onClick={() => setTransferring(true)}>
                Request transfer
              </Button>
            )}
            {a.status !== 'PENDING_BED' && a.status !== 'CANCELLED' && (
              <Button component={Link} href={`/nurse/discharge/${a.id}`} variant="outlined" startIcon={<ClipboardCheck size={16} />}>
                Discharge checklist
              </Button>
            )}
          </>
        }
      />
      <PatientBanner admission={a} allergies={allergyStrings} />
      {pendingTransfer && (
        <Alert severity="info" sx={{ mb: 5 }} action={<Button component={Link} href="/nurse/transfers" color="inherit" size="small">View</Button>}>
          Transfer {pendingTransfer.status.toLowerCase()}: {pendingTransfer.fromLocation} → {pendingTransfer.toLocation ?? pendingTransfer.toWardName} ({pendingTransfer.reason})
        </Alert>
      )}
      <Card sx={{ mb: 5 }}>
        <Tabs value={tab} onChange={(_, v) => setTab(v)} variant="scrollable" scrollButtons="auto" allowScrollButtonsMobile sx={{ px: 2 }} aria-label="Patient record sections">
          {TABS.map((t) => (
            <Tab key={t.value} value={t.value} label={t.label} />
          ))}
        </Tabs>
      </Card>
      {tab === 'overview' && <ClinicalSummaryView summary={summary.data} onNavigate={setTab} />}
      {tab === 'medications' && <MarPanel admissionId={admissionId} />}
      {tab === 'vitals' && <VitalsPanel admissionId={admissionId} />}
      {tab === 'notes' && (
        <SectionCard title="Notes & assessments" subtitle="Nursing assessments, observations and education, alongside medical notes.">
          <NotesPanel admissionId={admissionId} defaultFilter="nursing" />
        </SectionCard>
      )}
      {tab === 'tasks' && <TasksPanel admissionId={admissionId} />}
      {tab === 'investigations' && <InvestigationsPanel admissionId={admissionId} />}
      {tab === 'alerts' && (
        <SectionCard title="Alerts & escalations">
          <AlertsPanel admissionId={admissionId} />
        </SectionCard>
      )}
      {tab === 'history' && <AllergiesHistoryPanel patientId={a.patient.id} />}
      {tab === 'timeline' && (
        <SectionCard title="Admission timeline" subtitle="Clinical, nursing, medication and administrative events for this stay.">
          <AdmissionTimeline admissionId={admissionId} />
        </SectionCard>
      )}
      <EscalateDialog
        open={escalating}
        onClose={() => setEscalating(false)}
        admissionId={admissionId}
        patientName={a.patient.fullName}
        attendingName={a.attendingDoctorName}
      />
      {a.location && <TransferRequestDialog admission={a} open={transferring} onClose={() => setTransferring(false)} />}
    </>
  );
}
