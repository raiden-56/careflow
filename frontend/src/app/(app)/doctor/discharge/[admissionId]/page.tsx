'use client';

import { use } from 'react';
import { DischargeDetailPage } from '@/components/discharge/DischargeDetailPage';

export default function DoctorDischargeDetail({ params }: { params: Promise<{ admissionId: string }> }) {
  const { admissionId } = use(params);
  return (
    <DischargeDetailPage
      admissionId={admissionId}
      title="Discharge"
      breadcrumbs={[{ label: 'Clinician', href: '/doctor' }, { label: 'Discharge planning', href: '/doctor/discharge' }]}
    />
  );
}
