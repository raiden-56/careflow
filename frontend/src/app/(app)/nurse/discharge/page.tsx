'use client';

import { PageHeader } from '@/components/ui/PageHeader';
import { DischargeWorklist } from '@/components/discharge/Worklist';

export default function NurseDischargePage() {
  return (
    <>
      <PageHeader
        title="Discharge checklist"
        subtitle="Discharges on your wards: nursing education, patient acknowledgement and departure."
        breadcrumbs={[{ label: 'Nursing', href: '/nurse' }, { label: 'Discharge checklist' }]}
      />
      <DischargeWorklist basePath="/nurse/discharge" audience="nurse" />
    </>
  );
}
