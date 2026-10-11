'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { Box, Chip, Stack, Tooltip, Typography } from '@mui/material';
import { AlertTriangle, CalendarCheck, ClipboardList, FlaskConical, HeartPulse, Pill } from 'lucide-react';
import type { PatientRow } from '@/lib/api/clinical';
import { formatDate, fromNow } from '@/lib/format';
import { Column, DataTable } from '@/components/ui/DataTable';
import { colors } from '@/theme/tokens';

export interface PatientWorklistProps {
  rows: PatientRow[] | undefined;
  loading?: boolean;
  error?: unknown;
  onRetry?: () => void;
  /** Route prefix for the patient record, e.g. "/nurse/patients" or "/doctor/patients". */
  basePath: string;
  emptyTitle?: string;
  /** Hide the medication flags (e.g. doctor dashboard). */
  compact?: boolean;
  /** Hide the latest-observations column (narrow layouts). */
  hideObservations?: boolean;
}

function Flag({ icon: Icon, label, tone }: { icon: typeof Pill; label: string; tone: 'error' | 'warning' | 'info' }) {
  const fg = tone === 'error' ? colors.danger : tone === 'warning' ? colors.warning : colors.info;
  const bg = tone === 'error' ? colors.dangerSoft : tone === 'warning' ? colors.warningSoft : colors.infoSoft;
  return (
    <Chip
      size="small"
      icon={<Icon size={13} color={fg} />}
      label={label}
      sx={{ color: fg, bgcolor: bg, border: `1px solid ${fg}22`, '& .MuiChip-icon': { ml: 1 } }}
    />
  );
}

/** Clinician worklist: patients with alert, observation, medication, task and result indicators. */
export function PatientWorklist({ rows, loading, error, onRetry, basePath, emptyTitle = 'No patients assigned', compact, hideObservations }: PatientWorklistProps) {
  const router = useRouter();
  return (
    <DataTable<PatientRow>
      ariaLabel="Patients"
      rows={rows}
      loading={loading}
      error={error}
      onRetry={onRetry}
      rowKey={(r) => r.patient.admissionId}
      onRowClick={(r) => router.push(`${basePath}/${r.patient.admissionId}`)}
      emptyTitle={emptyTitle}
      emptyDescription="Patients appear here when they are admitted under your care or to your wards."
      rowSx={(r) => (r.criticalAlerts > 0 ? { boxShadow: `inset 3px 0 0 ${colors.danger}` } : undefined)}
      columns={(
        [
        {
          key: 'patient',
          header: 'Patient',
          primary: true,
          render: (r) => (
            <Box sx={{ minWidth: { md: 170 } }}>
              <Typography
                variant="body2"
                fontWeight={600}
                component={Link}
                href={`${basePath}/${r.patient.admissionId}`}
                onClick={(e: React.MouseEvent) => e.stopPropagation()}
                sx={{ color: 'inherit', textDecoration: 'none', '&:hover': { textDecoration: 'underline' } }}
              >
                {r.patient.patientName}
              </Typography>
              <Typography variant="caption" color="text.secondary" component="div">
                {r.patient.mrn} · {r.patient.location ?? 'Awaiting bed'}
              </Typography>
              {!!r.patient.allergies?.length && (
                <Typography variant="caption" color="error.main" component="div" sx={{ fontWeight: 600 }}>
                  Allergy: {r.patient.allergies.join(', ')}
                </Typography>
              )}
            </Box>
          ),
        },
        {
          key: 'dx',
          header: 'Diagnosis / stay',
          hideOnMobile: true,
          render: (r) => (
            <Box sx={{ maxWidth: 260 }}>
              <Typography variant="body2" noWrap title={r.primaryDiagnosis}>
                {r.primaryDiagnosis ?? '—'}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                Day {r.lengthOfStayDays + 1} · {r.patient.attendingDoctorName}
                {r.expectedDischargeDate ? ` · EDD ${formatDate(r.expectedDischargeDate)}` : ''}
              </Typography>
            </Box>
          ),
        },
        {
          key: 'flags',
          header: 'Needs attention',
          render: (r) => {
            const flags = [
              r.criticalAlerts > 0 && <Flag key="c" icon={AlertTriangle} tone="error" label={`${r.criticalAlerts} critical`} />,
              r.openAlerts - r.criticalAlerts > 0 && <Flag key="a" icon={AlertTriangle} tone="warning" label={`${r.openAlerts - r.criticalAlerts} alert${r.openAlerts - r.criticalAlerts > 1 ? 's' : ''}`} />,
              !compact && r.overdueDoses > 0 && <Flag key="o" icon={Pill} tone="error" label={`${r.overdueDoses} overdue dose${r.overdueDoses > 1 ? 's' : ''}`} />,
              !compact && r.dueDoses > 0 && <Flag key="d" icon={Pill} tone="info" label={`${r.dueDoses} due`} />,
              r.observationOverdue && <Flag key="v" icon={HeartPulse} tone="warning" label="Obs overdue" />,
              r.pendingResults > 0 && <Flag key="r" icon={FlaskConical} tone="info" label={`${r.pendingResults} to review`} />,
              r.openTasks > 0 && <Flag key="t" icon={ClipboardList} tone="info" label={`${r.openTasks} task${r.openTasks > 1 ? 's' : ''}`} />,
              r.expectedDischargeToday && <Flag key="e" icon={CalendarCheck} tone="info" label="Discharge today" />,
            ].filter(Boolean);
            return flags.length ? (
              <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ justifyContent: { xs: 'flex-end', md: 'flex-start' } }}>
                {flags}
              </Stack>
            ) : (
              <Typography variant="caption" color="text.secondary">
                Nothing outstanding
              </Typography>
            );
          },
        },
        {
          key: 'obs',
          header: 'Latest observations',
          hideOnMobile: true,
          render: (r) =>
            r.lastVitalsAt ? (
              <Tooltip title={r.latestNews ?? ''}>
                <Box sx={{ maxWidth: 240 }}>
                  <Typography variant="body2" noWrap>
                    {r.latestNews}
                  </Typography>
                  <Typography variant="caption" color={r.observationOverdue ? 'warning.main' : 'text.secondary'}>
                    {fromNow(r.lastVitalsAt)}
                  </Typography>
                </Box>
              </Tooltip>
            ) : (
              <Typography variant="caption" color="warning.main">
                None recorded
              </Typography>
            ),
        },
        ] as Column<PatientRow>[]
      ).filter((c) => !(hideObservations && c.key === 'obs'))}
    />
  );
}
