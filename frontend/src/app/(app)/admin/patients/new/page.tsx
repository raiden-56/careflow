'use client';

import { useRouter } from 'next/navigation';
import { useQueryClient } from '@tanstack/react-query';
import { patientApi } from '@/lib/api/core';
import { useToast } from '@/lib/hooks';
import { PageHeader } from '@/components/ui/PageHeader';
import { SectionCard } from '@/components/ui/SectionCard';
import { PatientForm } from '@/components/patient/PatientForm';

export default function NewPatientPage() {
  const router = useRouter();
  const toast = useToast();
  const queryClient = useQueryClient();
  return (
    <>
      <PageHeader
        title="Register patient"
        subtitle="A medical record number (MRN) is generated automatically. Admissions are created separately."
        breadcrumbs={[{ label: 'Administration', href: '/admin' }, { label: 'Patients', href: '/admin/patients' }, { label: 'Register' }]}
      />
      <SectionCard>
        <PatientForm
          submitLabel="Register patient"
          onCancel={() => router.back()}
          onSubmit={async (body) => {
            const p = await patientApi.register(body);
            queryClient.invalidateQueries({ queryKey: ['patients'] });
            toast.success(`Registered ${p.fullName} (${p.mrn})`);
            router.push(`/admin/patients/${p.id}`);
          }}
        />
      </SectionCard>
    </>
  );
}
