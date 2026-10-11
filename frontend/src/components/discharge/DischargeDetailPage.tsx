'use client';

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Card, Stack, Tab, Tabs } from '@mui/material';
import { dischargeApi, dqk } from '@/lib/api/discharge';
import { PageHeader, type Crumb } from '@/components/ui/PageHeader';
import { SectionCard } from '@/components/ui/SectionCard';
import { ErrorState, PageSkeleton } from '@/components/ui/States';
import { PatientBanner } from '@/components/patient/PatientBanner';
import { AdmissionTimeline } from '@/components/timeline/AdmissionTimeline';
import { DischargePanel } from './DischargePanel';
import { SummaryEditor } from './SummaryEditor';
import { FollowUpPlan } from './FollowUpPlan';

type TabKey = 'checklist' | 'summary' | 'followups' | 'timeline';

/**
 * Staff discharge detail for one admission. Tabs depend on what the role may see: the summary and follow-up
 * plan are clinical (care team only); the checklist, decisions and bed turnover are shared.
 */
export function DischargeDetailPage({
  admissionId,
  breadcrumbs,
  title,
  showBed,
  emphasizeCompletion,
  afterCompleteHref,
  defaultTab = 'checklist',
}: {
  afterCompleteHref?: string;
  admissionId: string;
  breadcrumbs: Crumb[];
  title: string;
  showBed?: boolean;
  emphasizeCompletion?: boolean;
  defaultTab?: TabKey;
}) {
  const q = useQuery({ queryKey: dqk.detail(admissionId), queryFn: () => dischargeApi.detail(admissionId) });
  const [tab, setTab] = useState<TabKey>(defaultTab);
  if (q.isLoading) return <PageSkeleton />;
  if (q.error || !q.data) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const d = q.data;
  const tabs: { key: TabKey; label: string }[] = [{ key: 'checklist', label: 'Checklist & decisions' }];
  if (d.clinicalAccess) {
    tabs.push({ key: 'summary', label: 'Discharge summary' }, { key: 'followups', label: `Follow-up (${d.followUpCount})` });
  }
  tabs.push({ key: 'timeline', label: 'Timeline' });
  const current = tabs.some((t) => t.key === tab) ? tab : 'checklist';

  return (
    <>
      <PageHeader title={title} breadcrumbs={[...breadcrumbs, { label: d.admission.patient.fullName }]} />
      <PatientBanner admission={d.admission} />
      <Card sx={{ mb: 5 }}>
        <Tabs value={current} onChange={(_, v) => setTab(v)} variant="scrollable" allowScrollButtonsMobile sx={{ px: 4 }}>
          {tabs.map((t) => (
            <Tab key={t.key} value={t.key} label={t.label} />
          ))}
        </Tabs>
      </Card>
      <Stack spacing={5}>
        {current === 'checklist' && <DischargePanel detail={d} showBed={showBed} emphasizeCompletion={emphasizeCompletion} afterCompleteHref={afterCompleteHref} />}
        {current === 'summary' && <SummaryEditor admissionId={admissionId} admissionNumber={d.admission.admissionNumber} />}
        {current === 'followups' && <FollowUpPlan admissionId={admissionId} canManage={d.actions.canManageFollowUps} />}
        {current === 'timeline' && (
          <SectionCard title="Admission timeline" subtitle="Events visible to your role.">
            <AdmissionTimeline admissionId={admissionId} />
          </SectionCard>
        )}
      </Stack>
    </>
  );
}
