'use client';

import { Suspense, useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Alert, Autocomplete, Box, Button, Grid, Stack, Step, StepLabel, Stepper, TextField, Typography } from '@mui/material';
import { UserPlus } from 'lucide-react';
import { admissionApi, facilityApi, patientApi, qk, staffApi } from '@/lib/api/core';
import type { BedView, PatientSummary } from '@/lib/api/types';
import { useDebouncedValue, useToast } from '@/lib/hooks';
import { genderShort, isoToLocalInput, localInputToIso, todayLocalDate } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { SectionCard } from '@/components/ui/SectionCard';
import { DefinitionList } from '@/components/ui/Display';
import { StatusChip } from '@/components/ui/StatusChip';
import { RHFCheckbox, RHFSelect, RHFTextField } from '@/components/forms/Fields';
import { BedPicker } from '@/components/beds/BedPicker';

const schema = z.object({
  admissionType: z.enum(['ELECTIVE', 'EMERGENCY', 'TRANSFER_IN', 'DAYCARE']),
  admittedAt: z.string().min(1, 'Admission time is required'),
  departmentId: z.string().min(1, 'Select a department'),
  attendingDoctorId: z.string().min(1, 'Select the treating doctor'),
  admissionReason: z.string().trim().min(5, 'Describe the reason for admission').max(2000),
  referringDoctor: z.string().trim().max(200).optional(),
  expectedDischargeDate: z.string().optional(),
  requiresIsolation: z.boolean(),
  bedRequirements: z.string().trim().max(500).optional(),
});
type Values = z.infer<typeof schema>;

function NewAdmission() {
  const router = useRouter();
  const params = useSearchParams();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [patient, setPatient] = useState<PatientSummary | null>(null);
  const [search, setSearch] = useState('');
  const q = useDebouncedValue(search, 250);
  const [bed, setBed] = useState<BedView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const preselect = params.get('patientId');
  const preselected = useQuery({ queryKey: qk.patient(preselect ?? ''), queryFn: () => patientApi.get(preselect!), enabled: !!preselect });
  useEffect(() => {
    if (preselected.data && !patient) setPatient(preselected.data);
  }, [preselected.data, patient]);

  const results = useQuery({
    queryKey: qk.patients({ q, size: 10, status: 'ACTIVE' }),
    queryFn: () => patientApi.search({ q, size: 10, status: 'ACTIVE' }),
    enabled: q.length >= 2,
  });
  const departments = useQuery({ queryKey: qk.departments, queryFn: facilityApi.departments });
  const doctors = useQuery({ queryKey: qk.directory({ role: 'DOCTOR' }), queryFn: () => staffApi.directory({ role: 'DOCTOR' }) });

  const { control, handleSubmit, setValue } = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      admissionType: 'EMERGENCY',
      admittedAt: isoToLocalInput(),
      departmentId: '',
      attendingDoctorId: '',
      admissionReason: '',
      referringDoctor: '',
      expectedDischargeDate: '',
      requiresIsolation: false,
      bedRequirements: '',
    },
  });
  const departmentId = useWatch({ control, name: 'departmentId' });
  const requiresIsolation = useWatch({ control, name: 'requiresIsolation' });
  const doctorOptions = (doctors.data ?? []).filter((d) => !departmentId || d.departmentId === departmentId);
  useEffect(() => {
    setValue('attendingDoctorId', '');
  }, [departmentId, setValue]);

  const step = !patient ? 0 : bed ? 2 : 1;
  const alreadyAdmitted = patient?.currentAdmission;

  const submit = handleSubmit(async (v) => {
    if (!patient) return;
    setError(null);
    setSubmitting(true);
    try {
      const detail = await admissionApi.admit({
        patientId: patient.id,
        admissionType: v.admissionType,
        admittedAt: localInputToIso(v.admittedAt),
        admissionReason: v.admissionReason,
        departmentId: v.departmentId,
        attendingDoctorId: v.attendingDoctorId,
        referringDoctor: v.referringDoctor || undefined,
        expectedDischargeDate: v.expectedDischargeDate || null,
        requiresIsolation: v.requiresIsolation,
        bedRequirements: v.bedRequirements || undefined,
        bedId: bed?.id ?? null,
      });
      queryClient.invalidateQueries({ queryKey: ['admissions'] });
      queryClient.invalidateQueries({ queryKey: ['beds'] });
      toast.success(`Admission ${detail.summary.admissionNumber} created${bed ? ' and bed assigned' : ' — awaiting bed'}`);
      router.push(`/admin/admissions/${detail.summary.id}`);
    } catch (e) {
      setError((e as Error).message);
      queryClient.invalidateQueries({ queryKey: ['beds'] });
    } finally {
      setSubmitting(false);
    }
  });

  return (
    <>
      <PageHeader
        title="New admission"
        breadcrumbs={[{ label: 'Administration', href: '/admin' }, { label: 'Admissions', href: '/admin/admissions' }, { label: 'New' }]}
      />
      <Stepper activeStep={step} alternativeLabel sx={{ mb: 6 }}>
        <Step>
          <StepLabel>Patient</StepLabel>
        </Step>
        <Step>
          <StepLabel>Admission details</StepLabel>
        </Step>
        <Step>
          <StepLabel optional={<Typography variant="caption">Optional</Typography>}>Bed</StepLabel>
        </Step>
      </Stepper>
      <Stack spacing={5}>
        <SectionCard
          title="1. Patient"
          action={
            <Button component={Link} href="/admin/patients/new" size="small" startIcon={<UserPlus size={14} />}>
              Register new patient
            </Button>
          }
        >
          {patient ? (
            <Stack spacing={3}>
              <DefinitionList
                columns={3}
                items={[
                  { label: 'Name', value: <b>{patient.fullName}</b> },
                  { label: 'MRN', value: patient.mrn },
                  { label: 'Age / sex', value: `${patient.age}y ${genderShort(patient.gender)}` },
                ]}
              />
              {alreadyAdmitted && (
                <Alert severity="warning" action={<Button component={Link} href={`/admin/admissions/${alreadyAdmitted.admissionId}`} color="inherit" size="small">Open</Button>}>
                  This patient already has an open admission ({alreadyAdmitted.admissionNumber}, <StatusChip value={alreadyAdmitted.status} />). A
                  patient can only have one open admission.
                </Alert>
              )}
              <Box>
                <Button size="small" onClick={() => setPatient(null)}>
                  Choose a different patient
                </Button>
              </Box>
            </Stack>
          ) : (
            <Autocomplete<PatientSummary>
              options={results.data?.content ?? []}
              filterOptions={(x) => x}
              getOptionLabel={(p) => `${p.fullName} (${p.mrn})`}
              onInputChange={(_, v) => setSearch(v)}
              onChange={(_, v) => setPatient(v)}
              loading={results.isFetching}
              noOptionsText={q.length < 2 ? 'Type at least 2 characters' : 'No active patients found'}
              renderOption={(props, p) => {
                const { key, ...rest } = props as typeof props & { key: string };
                return (
                  <Box component="li" key={key} {...rest} sx={{ display: 'block !important' }}>
                    <Typography variant="body2" fontWeight={600}>
                      {p.fullName}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      {p.mrn} · {p.age}y {genderShort(p.gender)} · {p.phone ?? 'no phone'}
                      {p.currentAdmission ? ' · currently admitted' : ''}
                    </Typography>
                  </Box>
                );
              }}
              renderInput={(p) => <TextField {...p} label="Search patient by name, MRN or phone" autoFocus />}
            />
          )}
        </SectionCard>

        <SectionCard title="2. Admission details">
          <Grid container spacing={4}>
            <Grid size={{ xs: 12, sm: 4 }}>
              <RHFSelect
                control={control}
                name="admissionType"
                label="Admission type"
                options={[
                  { value: 'EMERGENCY', label: 'Emergency' },
                  { value: 'ELECTIVE', label: 'Elective' },
                  { value: 'TRANSFER_IN', label: 'Transfer in' },
                  { value: 'DAYCARE', label: 'Day care' },
                ]}
              />
            </Grid>
            <Grid size={{ xs: 12, sm: 4 }}>
              <RHFTextField control={control} name="admittedAt" label="Admission date & time" type="datetime-local" required />
            </Grid>
            <Grid size={{ xs: 12, sm: 4 }}>
              <RHFTextField
                control={control}
                name="expectedDischargeDate"
                label="Expected discharge (optional)"
                type="date"
                slotProps={{ htmlInput: { min: todayLocalDate() } }}
              />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFSelect
                control={control}
                name="departmentId"
                label="Department"
                required
                options={(departments.data ?? []).filter((d) => d.active).map((d) => ({ value: d.id, label: d.name }))}
              />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFSelect
                control={control}
                name="attendingDoctorId"
                label="Treating doctor"
                required
                helperText={departmentId && doctorOptions.length === 0 ? 'No doctors in this department' : undefined}
                options={doctorOptions.map((d) => ({ value: d.id, label: `${d.fullName}${d.specialty ? ` — ${d.specialty}` : ''}` }))}
              />
            </Grid>
            <Grid size={12}>
              <RHFTextField control={control} name="admissionReason" label="Reason for admission / presenting complaint" required multiline minRows={3} />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFTextField control={control} name="referringDoctor" label="Referring doctor (optional)" />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFTextField control={control} name="bedRequirements" label="Special bed requirements (optional)" />
            </Grid>
            <Grid size={12}>
              <RHFCheckbox control={control} name="requiresIsolation" label="Patient requires an isolation-capable bed" />
            </Grid>
          </Grid>
        </SectionCard>

        <SectionCard title="3. Bed assignment" subtitle="Optional. Without a bed the admission is created as 'Awaiting bed'.">
          <BedPicker value={bed?.id} onChange={setBed} requireIsolation={requiresIsolation} />
        </SectionCard>

        {error && <Alert severity="error">{error}</Alert>}
        <Stack direction="row" spacing={2} justifyContent="flex-end">
          <Button color="inherit" onClick={() => router.back()}>
            Cancel
          </Button>
          <Button variant="contained" size="large" disabled={!patient || !!alreadyAdmitted || submitting} onClick={submit}>
            {submitting ? 'Admitting…' : bed ? `Admit to ${bed.wardName} ${bed.roomNumber}-${bed.bedNumber}` : 'Admit (awaiting bed)'}
          </Button>
        </Stack>
      </Stack>
    </>
  );
}

export default function NewAdmissionPage() {
  return (
    <Suspense>
      <NewAdmission />
    </Suspense>
  );
}
