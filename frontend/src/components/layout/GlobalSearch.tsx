'use client';

import { useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useQuery } from '@tanstack/react-query';
import { Autocomplete, Box, InputAdornment, TextField, Typography } from '@mui/material';
import { Search } from 'lucide-react';
import { admissionApi, patientApi, qk } from '@/lib/api/core';
import { useAuth } from '@/lib/auth/AuthProvider';
import { useDebouncedValue } from '@/lib/hooks';

interface Hit {
  id: string;
  label: string;
  sub: string;
  href: string;
}

/**
 * Role-aware global search. Clinicians search their own worklist; registration/billing staff search
 * the patient index (demographics only). Results are always authorized by the API.
 */
export function GlobalSearch() {
  const { me, hasPermission } = useAuth();
  const router = useRouter();
  const [input, setInput] = useState('');
  const q = useDebouncedValue(input.trim(), 250);
  const clinician = me?.role === 'DOCTOR' || me?.role === 'NURSE';
  const canSearchPatients = hasPermission('PATIENT_DEMOGRAPHICS_READ') && !clinician && me?.role !== 'PHARMACIST';

  const worklist = useQuery({ queryKey: qk.myAdmissions, queryFn: admissionApi.my, enabled: clinician });
  const patients = useQuery({
    queryKey: qk.patients({ q, size: 8, src: 'global' }),
    queryFn: () => patientApi.search({ q, size: 8 }),
    enabled: canSearchPatients && q.length >= 2,
  });

  const hits: Hit[] = useMemo(() => {
    if (clinician) {
      const base = me?.role === 'DOCTOR' ? '/doctor/patients/' : '/nurse/patients/';
      const ql = q.toLowerCase();
      return (worklist.data ?? [])
        .filter((a) => !ql || a.patient.fullName.toLowerCase().includes(ql) || a.patient.mrn.toLowerCase().includes(ql) || a.admissionNumber.toLowerCase().includes(ql))
        .slice(0, 8)
        .map((a) => ({
          id: a.id,
          label: a.patient.fullName,
          sub: `${a.patient.mrn} · ${a.location ? `${a.location.wardName} ${a.location.bedNumber}` : a.status}`,
          href: base + a.id,
        }));
    }
    const base = me?.role === 'BILLING' ? '/billing/invoices?q=' : '/admin/patients/';
    return (patients.data?.content ?? []).map((p) => ({
      id: p.id,
      label: p.fullName,
      sub: `${p.mrn} · ${p.age}y${p.currentAdmission ? ` · ${p.currentAdmission.admissionNumber}` : ''}`,
      href: me?.role === 'BILLING' ? base + encodeURIComponent(p.mrn) : base + p.id,
    }));
  }, [clinician, me?.role, q, worklist.data, patients.data]);

  if (!me || (!clinician && !canSearchPatients)) return null;

  return (
    <Autocomplete<Hit, false, false, false>
      size="small"
      options={hits}
      filterOptions={(x) => x}
      getOptionLabel={(o) => o.label}
      inputValue={input}
      onInputChange={(_, v) => setInput(v)}
      onChange={(_, v) => {
        if (v) {
          router.push(v.href);
          setInput('');
        }
      }}
      noOptionsText={q.length < 2 && !clinician ? 'Type at least 2 characters' : 'No matching patients'}
      loading={patients.isFetching || worklist.isLoading}
      sx={{ width: { xs: '100%', md: 360 } }}
      renderOption={(props, o) => {
        const { key, ...rest } = props as typeof props & { key: string };
        return (
          <Box component="li" key={key} {...rest} sx={{ display: 'block !important' }}>
            <Typography variant="body2" fontWeight={600}>
              {o.label}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              {o.sub}
            </Typography>
          </Box>
        );
      }}
      renderInput={(params) => (
        <TextField
          {...params}
          placeholder={clinician ? 'Search my patients…' : 'Search patients by name, MRN or phone…'}
          slotProps={{
            input: {
              ...params.InputProps,
              startAdornment: (
                <InputAdornment position="start">
                  <Search size={16} />
                </InputAdornment>
              ),
            },
            htmlInput: { ...params.inputProps, 'aria-label': 'Global search' },
          }}
        />
      )}
    />
  );
}
