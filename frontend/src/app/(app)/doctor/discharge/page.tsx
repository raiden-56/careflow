'use client';

import { PageHeader } from '@/components/ui/PageHeader';
import { DischargeWorklist } from '@/components/discharge/Worklist';

export default function DoctorDischargePage() {
  return (
    <>
      <PageHeader
        title="Discharge planning"
        subtitle="Your patients' discharge progress, blockers and clinical decisions. Open a patient to authorize, write the summary and plan follow-up."
        breadcrumbs={[{ label: 'Clinician', href: '/doctor' }, { label: 'Discharge planning' }]}
      />
      <DischargeWorklist basePath="/doctor/discharge" audience="doctor" />
    </>
  );
}
