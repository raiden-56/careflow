'use client';

import { PageHeader } from '@/components/ui/PageHeader';
import { MyPatientsView } from '@/components/clinical/MyPatientsView';

export default function DoctorPatientsPage() {
  return (
    <>
      <PageHeader
        title="My patients"
        subtitle="Patients you attend, consult on, or who are admitted to your department. Critical alerts are listed first."
        breadcrumbs={[{ label: 'Clinician', href: '/doctor' }, { label: 'My patients' }]}
      />
      <MyPatientsView basePath="/doctor/patients" showWardFilter />
    </>
  );
}
