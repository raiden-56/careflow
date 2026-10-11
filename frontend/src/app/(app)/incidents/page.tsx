'use client';

import { Suspense, useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useRouter, useSearchParams } from 'next/navigation';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Autocomplete, Box, Button, Card, Grid, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { ShieldAlert } from 'lucide-react';
import { incidentApi, IncidentSeverity, IncidentType, PatientOption } from '@/lib/api/admin';
import { facilityApi, qk } from '@/lib/api/core';
import { ApiError, errorMessage } from '@/lib/api/client';
import { useAuth } from '@/lib/auth/AuthProvider';
import { applyServerErrors, useDebouncedValue, useTableState, useToast } from '@/lib/hooks';
import { formatDateTime, humanize, isoToLocalInput, localInputToIso, nowInHospitalTz } from '@/lib/format';
import { optionalText } from '@/lib/validation';
import { PageHeader } from '@/components/ui/PageHeader';
import { SectionCard } from '@/components/ui/SectionCard';
import { sortParam } from '@/components/ui/DataTable';
import { RHFSelect, RHFTextField } from '@/components/forms/Fields';
import { IncidentDrawer } from '@/components/admin/IncidentDrawer';
import { INCIDENT_TYPES, IncidentTable, SEVERITIES } from '@/components/admin/IncidentTable';

const SEVERITY_HELP: Record<IncidentSeverity, string> = {
  NO_HARM: 'Reached the patient or nearly did, no harm',
  MINOR: 'Minimal harm, first aid or extra observation',
  MODERATE: 'Additional treatment needed',
  SEVERE: 'Major or permanent harm',
  DEATH: 'Death related to the incident',
};

const schema = z.object({
  incidentType: z.enum(INCIDENT_TYPES as [IncidentType, ...IncidentType[]], { message: 'Select the type of incident' }),
  severity: z.enum(SEVERITIES as [IncidentSeverity, ...IncidentSeverity[]], { message: 'Select the severity' }),
  occurredAt: z
    .string()
    .min(1, 'When did it happen?')
    .refine((v) => v <= nowInHospitalTz().add(5, 'minute').format('YYYY-MM-DDTHH:mm'), 'Cannot be in the future'),
  wardId: z.string().optional(),
  location: optionalText(200, 'Location'),
  medicationAdministrationId: z.string().optional(),
  description: z.string().trim().min(10, 'Describe what happened (at least 10 characters)').max(5000),
  immediateActions: optionalText(3000, 'Immediate actions'),
});
type Values = z.infer<typeof schema>;

export default function IncidentsPage() {
  return (
    <Suspense>
      <Content />
    </Suspense>
  );
}

function Content() {
  const toast = useToast();
  const qc = useQueryClient();
  const router = useRouter();
  const search = useSearchParams();
  const { hasPermission, hasAnyPermission } = useAuth();
  const canSeeMeds = hasAnyPermission('CLINICAL_READ', 'DISPENSE');
  const canReport = hasPermission('INCIDENT_REPORT');
  const canLookup = hasPermission('PATIENT_DEMOGRAPHICS_READ');
  const t = useTableState({ size: 10, sort: { field: 'reportedAt', direction: 'desc' } });
  const [open, setOpen] = useState<string | null>(search.get('id'));
  const [patient, setPatient] = useState<PatientOption | null>(null);
  const [patientQuery, setPatientQuery] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [created, setCreated] = useState<string | null>(null);
  const dq = useDebouncedValue(patientQuery, 300);
  useEffect(() => {
    const id = search.get('id');
    if (id) setOpen(id);
  }, [search]);

  const wards = useQuery({ queryKey: qk.wards, queryFn: facilityApi.wards });
  const lookup = useQuery({
    queryKey: ['incidents', 'patient-lookup', dq],
    queryFn: () => incidentApi.patientLookup(dq),
    enabled: canLookup && dq.trim().length >= 2,
  });
  const medEvents = useQuery({
    queryKey: ['incidents', 'med-events', patient?.admissionId],
    queryFn: () => incidentApi.medicationEvents(patient!.admissionId),
    // Only roles with medication access to the admission may list its doses (others would be denied and audited).
    enabled: !!patient && canSeeMeds,
    retry: false,
  });
  const medsForbidden = !canSeeMeds || (medEvents.error instanceof ApiError && (medEvents.error.status === 403 || medEvents.error.status === 404));
  const params = { page: t.page, size: t.size, sort: sortParam(t.sort) };
  const mine = useQuery({ queryKey: ['incidents', 'mine', params], queryFn: () => incidentApi.mine(params), placeholderData: keepPreviousData, enabled: canReport });

  const defaults: Values = {
    incidentType: '' as IncidentType,
    severity: '' as IncidentSeverity,
    occurredAt: isoToLocalInput(),
    wardId: '',
    location: '',
    medicationAdministrationId: '',
    description: '',
    immediateActions: '',
  };
  const { control, handleSubmit, reset, watch, setError: setFieldError } = useForm<Values>({ resolver: zodResolver(schema), defaultValues: defaults });
  const severity = watch('severity');

  const m = useMutation({
    mutationFn: (v: Values) =>
      incidentApi.report({
        incidentType: v.incidentType,
        severity: v.severity,
        occurredAt: localInputToIso(v.occurredAt),
        wardId: v.wardId || null,
        location: v.location || null,
        patientId: patient?.patientId ?? null,
        admissionId: patient?.admissionId ?? null,
        medicationAdministrationId: v.medicationAdministrationId || null,
        description: v.description,
        immediateActions: v.immediateActions || null,
      }),
    onSuccess: (d) => {
      toast.success(`Incident ${d.incidentNumber} reported. Thank you.`);
      setCreated(d.incidentNumber);
      setError(null);
      setPatient(null);
      reset({ ...defaults, occurredAt: isoToLocalInput() });
      qc.invalidateQueries({ queryKey: ['incidents'] });
    },
    onError: (e) => {
      if (!applyServerErrors(e, setFieldError)) setError(errorMessage(e));
    },
  });

  return (
    <>
      <PageHeader
        title="Safety incidents"
        subtitle="Report incidents and near misses in a just, learning culture. Reports are visible only to you and the safety review team."
        breadcrumbs={[{ label: 'Safety' }, { label: 'Incidents' }]}
      />
      {!canReport ? (
        <Alert severity="info">Your role cannot file incident reports. Speak to your supervisor or the safety team.</Alert>
      ) : (
        <Grid container spacing={5}>
          <Grid size={{ xs: 12, lg: 7 }}>
            <SectionCard title="Report an incident" subtitle="If anyone is at immediate risk, act first and escalate in person. Then record what happened.">
              <form onSubmit={handleSubmit((v) => m.mutate(v))} noValidate>
                <Grid container spacing={3}>
                  {created && (
                    <Grid size={12}>
                      <Alert severity="success" onClose={() => setCreated(null)}>
                        {created} has been sent to the safety review team. You can follow its progress under &quot;My reports&quot;.
                      </Alert>
                    </Grid>
                  )}
                  {error && (
                    <Grid size={12}>
                      <Alert severity="error">{error}</Alert>
                    </Grid>
                  )}
                  <Grid size={{ xs: 12, sm: 6 }}>
                    <RHFSelect control={control} name="incidentType" label="Type of incident" required fullWidth options={INCIDENT_TYPES.map((x) => ({ value: x, label: humanize(x) }))} />
                  </Grid>
                  <Grid size={{ xs: 12, sm: 6 }}>
                    <RHFSelect
                      control={control}
                      name="severity"
                      label="Severity"
                      required
                      fullWidth
                      options={SEVERITIES.map((x) => ({ value: x, label: humanize(x) }))}
                      helperText={severity ? SEVERITY_HELP[severity] : 'Severe incidents alert reviewers immediately.'}
                    />
                  </Grid>
                  <Grid size={{ xs: 12, sm: 6 }}>
                    <RHFTextField control={control} name="occurredAt" label="When did it happen?" type="datetime-local" required fullWidth />
                  </Grid>
                  <Grid size={{ xs: 12, sm: 6 }}>
                    <RHFSelect control={control} name="wardId" label="Ward" fullWidth emptyLabel="Not on a ward" options={(wards.data ?? []).filter((w) => w.active).map((w) => ({ value: w.id, label: w.name }))} />
                  </Grid>
                  <Grid size={12}>
                    <RHFTextField control={control} name="location" label="Exact location" fullWidth placeholder="e.g. Room 202 bathroom, pharmacy dispensing bench" />
                  </Grid>
                  {canLookup && (
                    <Grid size={12}>
                      <Autocomplete
                        options={lookup.data ?? []}
                        value={patient}
                        onChange={(_, v) => setPatient(v)}
                        inputValue={patientQuery}
                        onInputChange={(_, v) => setPatientQuery(v)}
                        filterOptions={(x) => x}
                        loading={lookup.isFetching}
                        getOptionLabel={(o) => `${o.patientName} · ${o.mrn} · ${o.admissionNumber}`}
                        isOptionEqualToValue={(a, b) => a.admissionId === b.admissionId}
                        noOptionsText={patientQuery.length < 2 ? 'Type at least 2 characters' : 'No current admissions match'}
                        renderOption={(props, o) => {
                          const { key, ...rest } = props as typeof props & { key: string };
                          return (
                            <li key={key} {...rest}>
                              <Box>
                                <Typography variant="body2" fontWeight={500}>
                                  {o.patientName} · {o.mrn}
                                </Typography>
                                <Typography variant="caption" color="text.secondary">
                                  {o.admissionNumber} · {o.location}
                                </Typography>
                              </Box>
                            </li>
                          );
                        }}
                        renderInput={(p) => <TextField {...p} label="Patient involved (optional)" helperText="Search current admissions by name, MRN or admission number." />}
                      />
                    </Grid>
                  )}
                  {patient && !medsForbidden && (
                    <Grid size={12}>
                      <RHFSelect
                        control={control}
                        name="medicationAdministrationId"
                        label="Related medication event (optional)"
                        fullWidth
                        emptyLabel="None"
                        disabled={medEvents.isLoading}
                        helperText={medEvents.data && medEvents.data.length === 0 ? 'No medication events in the last 7 days.' : 'Link the dose involved, if any.'}
                        options={(medEvents.data ?? []).map((e) => ({
                          value: e.id,
                          label: `${formatDateTime(e.scheduledAt)} · ${e.medication} ${e.dose} · ${humanize(e.status)}`,
                        }))}
                      />
                    </Grid>
                  )}
                  <Grid size={12}>
                    <RHFTextField control={control} name="description" label="What happened?" required fullWidth multiline minRows={4} helperText="Facts only: what you saw, in order. Avoid blame." />
                  </Grid>
                  <Grid size={12}>
                    <RHFTextField control={control} name="immediateActions" label="Immediate actions taken" fullWidth multiline minRows={2} />
                  </Grid>
                  <Grid size={12}>
                    <Stack direction="row" justifyContent="flex-end">
                      <Button type="submit" variant="contained" startIcon={<ShieldAlert size={16} />} disabled={m.isPending}>
                        {m.isPending ? 'Submitting…' : 'Submit report'}
                      </Button>
                    </Stack>
                  </Grid>
                </Grid>
              </form>
            </SectionCard>
          </Grid>
          <Grid size={{ xs: 12, lg: 5 }}>
            <Card>
              <Box sx={{ px: 5, pt: 4, pb: 2 }}>
                <Typography variant="h4" component="h2">
                  My reports
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  Track the review of incidents you reported.
                </Typography>
              </Box>
              <IncidentTable
                rows={mine.data?.content}
                loading={mine.isFetching}
                error={mine.error}
                onRetry={() => mine.refetch()}
                onOpen={setOpen}
                table={t}
                total={mine.data?.totalElements}
                emptyTitle="You have not reported any incidents"
                emptyDescription="Reports you submit will appear here."
              />
            </Card>
          </Grid>
        </Grid>
      )}
      <IncidentDrawer
        id={open}
        onClose={() => {
          setOpen(null);
          if (search.get('id')) router.replace('/incidents', { scroll: false });
        }}
      />
    </>
  );
}
