'use client';

import { use } from 'react';
import { DischargeDetailPage } from '@/components/discharge/DischargeDetailPage';

export default function AdminDischargeDetail({ params }: { params: Promise<{ admissionId: string }> }) {
  const { admissionId } = use(params);
  return (
    <DischargeDetailPage
      admissionId={admissionId}
      title="Discharge coordination"
      showBed
      emphasizeCompletion
      breadcrumbs={[{ label: 'Administration', href: '/admin' }, { label: 'Discharge coordination', href: '/admin/discharges' }]}
    />
  );
}
