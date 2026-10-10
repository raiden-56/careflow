'use client';

import { ReactNode } from 'react';
import { Alert, Avatar, Box, Card, Chip, Stack, Typography } from '@mui/material';
import { BedDouble, ShieldAlert, Stethoscope } from 'lucide-react';
import type { AdmissionSummary } from '@/lib/api/types';
import { StatusChip } from '@/components/ui/StatusChip';
import { formatDate, genderShort, initials } from '@/lib/format';
import { colors } from '@/theme/tokens';

export interface PatientBannerProps {
  admission: AdmissionSummary;
  /** Allergy strings ("Penicillin (moderate)"); pass from clinical endpoints when available. */
  allergies?: string[];
  actions?: ReactNode;
}

/**
 * Persistent patient identification header for clinical screens: name, MRN, age/sex, location,
 * treating doctor and allergy warnings (patient-safety critical, always visible).
 */
export function PatientBanner({ admission, allergies, actions }: PatientBannerProps) {
  const p = admission.patient;
  // Allergies are only returned to users with clinical access; never imply "none" when unknown.
  const known = allergies !== undefined || admission.allergies !== undefined;
  const allergyList = allergies ?? admission.allergies ?? [];
  const severe = allergyList.some((a) => /severe|life/i.test(a));
  return (
    <Card sx={{ mb: 5 }}>
      <Stack direction={{ xs: 'column', md: 'row' }} spacing={4} sx={{ p: 4 }} alignItems={{ md: 'center' }}>
        <Stack direction="row" spacing={3} alignItems="center" sx={{ minWidth: 0, flex: 1 }}>
          <Avatar sx={{ bgcolor: colors.primarySoft, color: colors.primaryDark, width: 48, height: 48, fontWeight: 700 }}>
            {initials(p.fullName)}
          </Avatar>
          <Box sx={{ minWidth: 0 }}>
            <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap" useFlexGap>
              <Typography variant="h3" component="p" noWrap>
                {p.fullName}
              </Typography>
              <StatusChip value={admission.status} />
              {admission.requiresIsolation && <StatusChip value="ISOLATION" label="Isolation" tone="warning" />}
            </Stack>
            <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
              {p.mrn} · {p.age}y {genderShort(p.gender)}
              {p.bloodGroup ? ` · ${p.bloodGroup}` : ''} · {admission.admissionNumber} · Admitted {formatDate(admission.admittedAt)}
              {admission.expectedDischargeDate ? ` · EDD ${formatDate(admission.expectedDischargeDate)}` : ''}
            </Typography>
          </Box>
        </Stack>
        <Stack direction="row" spacing={4} flexWrap="wrap" useFlexGap sx={{ color: 'text.secondary' }}>
          <Stack direction="row" spacing={1.5} alignItems="center">
            <BedDouble size={16} aria-hidden />
            <Typography variant="body2">
              {admission.location
                ? `${admission.location.wardName} · Rm ${admission.location.roomNumber} · Bed ${admission.location.bedNumber}`
                : 'No bed assigned'}
            </Typography>
          </Stack>
          <Stack direction="row" spacing={1.5} alignItems="center">
            <Stethoscope size={16} aria-hidden />
            <Typography variant="body2">{admission.attendingDoctorName ?? '—'}</Typography>
          </Stack>
        </Stack>
        {actions && <Stack direction="row" spacing={2}>{actions}</Stack>}
      </Stack>
      {known && (
      <Alert
        icon={<ShieldAlert size={18} />}
        severity={allergyList.length === 0 ? 'success' : severe ? 'error' : 'warning'}
        sx={{ borderRadius: 0, borderTop: 1, borderColor: 'divider', py: 1 }}
      >
        {allergyList.length === 0 ? (
          <Typography variant="body2">No known allergies documented</Typography>
        ) : (
          <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
            <Typography variant="body2" fontWeight={600}>
              Allergies:
            </Typography>
            {allergyList.map((a) => (
              <Chip key={a} size="small" label={a} color={/severe|life/i.test(a) ? 'error' : 'warning'} variant="outlined" />
            ))}
          </Stack>
        )}
      </Alert>
      )}
    </Card>
  );
}
