'use client';

import { PageHeader } from '@/components/ui/PageHeader';
import { MyPatientsView } from '@/components/clinical/MyPatientsView';

export default function NursePatientsPage() {
  return (
    <>
      <PageHeader
        title="Assigned patients"
        subtitle="Patients in your wards and those you are primary nurse for. Highest-priority patients are listed first."
        breadcrumbs={[{ label: 'Nursing', href: '/nurse' }, { label: 'Assigned patients' }]}
      />
      <MyPatientsView basePath="/nurse/patients" showWardFilter />
    </>
  );
}
