'use client';

import { Suspense, useEffect, useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { useQuery } from '@tanstack/react-query';
import { Alert, Box, Button, Card, CardActionArea, Chip, Grid, Stack, Typography } from '@mui/material';
import { Check, HeartPulse, LineChart, TriangleAlert } from 'lucide-react';
import { ck, clinicalApi, PatientRow } from '@/lib/api/clinical';
import { useAuth } from '@/lib/auth/AuthProvider';
import { formatDateTime, fromNow } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { SectionCard } from '@/components/ui/SectionCard';
import { EmptyState, ErrorState, PageSkeleton } from '@/components/ui/States';
import { VitalsForm } from '@/components/clinical/vitals/VitalsForm';
import { colors } from '@/theme/tokens';

function PatientPick({ row, selected, onSelect }: { row: PatientRow; selected: boolean; onSelect: () => void }) {
  const p = row.patient;
  return (
    <Card
      sx={{
        boxShadow: 'none',
        borderColor: selected ? colors.primary : row.observationOverdue ? colors.warning : undefined,
        borderWidth: selected ? 2 : 1,
        bgcolor: selected ? colors.primarySoft : undefined,
      }}
    >
      <CardActionArea onClick={onSelect} sx={{ p: 3 }} aria-pressed={selected}>
        <Stack direction="row" spacing={2} justifyContent="space-between" alignItems="flex-start">
          <Box sx={{ minWidth: 0 }}>
            <Typography variant="body2" fontWeight={700} sx={{ wordBreak: 'break-word' }}>
              {p.patientName}
            </Typography>
            <Typography variant="caption" color="text.secondary" component="div">
              {p.location ?? 'Awaiting bed'} · {p.mrn}
            </Typography>
          </Box>
          {selected && <Check size={18} color={colors.primary} aria-hidden />}
        </Stack>
        <Stack direction="row" spacing={1} sx={{ mt: 1.5 }} flexWrap="wrap" useFlexGap>
          {row.observationOverdue && <Chip size="small" color="warning" icon={<TriangleAlert size={13} />} label="Observations due" />}
          {row.criticalAlerts > 0 && <Chip size="small" color="error" label={`${row.criticalAlerts} critical alert${row.criticalAlerts === 1 ? '' : 's'}`} />}
          {row.criticalAlerts === 0 && row.openAlerts > 0 && <Chip size="small" color="warning" variant="outlined" label={`${row.openAlerts} open alert${row.openAlerts === 1 ? '' : 's'}`} />}
        </Stack>
        <Typography variant="caption" color={row.observationOverdue ? 'warning.main' : 'text.secondary'} component="div" sx={{ mt: 1 }}>
          Last set: {row.lastVitalsAt ? `${formatDateTime(row.lastVitalsAt)} (${fromNow(row.lastVitalsAt)})` : 'none recorded'}
        </Typography>
        {row.latestNews && (
          <Typography variant="caption" color="text.secondary" component="div" sx={{ wordBreak: 'break-word' }}>
            {row.latestNews}
          </Typography>
        )}
      </CardActionArea>
    </Card>
  );
}

function VitalsEntryInner() {
  const { hasPermission } = useAuth();
  const canWrite = hasPermission('VITALS_WRITE');
  const search = useSearchParams();
  const q = useQuery({ queryKey: ck.myPatients, queryFn: clinicalApi.myPatients, refetchInterval: 120_000 });
  const [selectedId, setSelectedId] = useState<string | null>(search.get('admissionId'));
  const formRef = useRef<HTMLDivElement>(null);

  const rows = useMemo(
    () =>
      (q.data ?? [])
        .filter((r) => r.patient.admissionStatus !== 'DISCHARGED')
        .slice()
        .sort(
          (a, b) =>
            Number(b.observationOverdue) - Number(a.observationOverdue) ||
            b.criticalAlerts - a.criticalAlerts ||
            (a.lastVitalsAt ?? '').localeCompare(b.lastVitalsAt ?? '') ||
            a.patient.patientName.localeCompare(b.patient.patientName),
        ),
    [q.data],
  );
  const selected = rows.find((r) => r.patient.admissionId === selectedId);
  const overdue = rows.filter((r) => r.observationOverdue).length;

  useEffect(() => {
    if (selectedId && formRef.current && window.matchMedia('(max-width: 899px)').matches) {
      formRef.current.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  }, [selectedId]);

  if (q.isLoading) return <PageSkeleton />;

  return (
    <>
      <PageHeader
        title="Vital signs"
        subtitle={
          rows.length
            ? `${rows.length} patient${rows.length === 1 ? '' : 's'}${overdue ? ` · ${overdue} with observations due` : ' · all observations up to date'}`
            : 'Quick observation entry for your patients.'
        }
        breadcrumbs={[{ label: 'Nursing', href: '/nurse' }, { label: 'Vital signs' }]}
      />
      {q.error ? (
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      ) : rows.length === 0 ? (
        <Card>
          <EmptyState icon={HeartPulse} title="No assigned patients" description="Patients on your wards appear here for observation entry." />
        </Card>
      ) : (
        <Grid container spacing={5}>
          <Grid size={{ xs: 12, md: 4 }}>
            <Typography variant="overline" color="text.secondary" component="h2" sx={{ display: 'block', mb: 2 }}>
              Patients · observations due first
            </Typography>
            <Stack spacing={2} role="list" aria-label="Patients">
              {rows.map((r) => (
                <Box key={r.patient.admissionId} role="listitem">
                  <PatientPick
                    row={r}
                    selected={r.patient.admissionId === selectedId}
                    onSelect={() => setSelectedId(r.patient.admissionId)}
                  />
                </Box>
              ))}
            </Stack>
          </Grid>
          <Grid size={{ xs: 12, md: 8 }} ref={formRef} sx={{ scrollMarginTop: 80 }}>
            {!selected ? (
              <Card>
                <EmptyState icon={HeartPulse} title="Choose a patient" description="Select a patient to record a set of observations." />
              </Card>
            ) : !canWrite ? (
              <Alert severity="info">Your role can view observations but not record them.</Alert>
            ) : (
              <SectionCard
                title={selected.patient.patientName}
                subtitle={
                  <span>
                    {selected.patient.location ?? 'Awaiting bed'} · {selected.patient.mrn}
                    {selected.patient.allergies && selected.patient.allergies.length > 0 ? ` · Allergies: ${selected.patient.allergies.join(', ')}` : ''}
                  </span>
                }
                action={
                  <Button
                    component={Link}
                    href={`/nurse/patients/${selected.patient.admissionId}?tab=vitals`}
                    size="small"
                    startIcon={<LineChart size={15} />}
                  >
                    Trends
                  </Button>
                }
              >
                <VitalsForm key={selected.patient.admissionId} admissionId={selected.patient.admissionId} />
              </SectionCard>
            )}
          </Grid>
        </Grid>
      )}
    </>
  );
}

export default function VitalsEntryPage() {
  return (
    <Suspense fallback={<PageSkeleton />}>
      <VitalsEntryInner />
    </Suspense>
  );
}
