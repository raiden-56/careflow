'use client';

import { use } from 'react';
import { DischargeDetailPage } from '@/components/discharge/DischargeDetailPage';

export default function NurseDischargeDetail({ params }: { params: Promise<{ admissionId: string }> }) {
  const { admissionId } = use(params);
  return (
    <DischargeDetailPage
      admissionId={admissionId}
      title="Discharge checklist"
      showBed
      emphasizeCompletion
      afterCompleteHref="/nurse/discharge"
      breadcrumbs={[{ label: 'Nursing', href: '/nurse' }, { label: 'Discharge checklist', href: '/nurse/discharge' }]}
    />
  );
}
