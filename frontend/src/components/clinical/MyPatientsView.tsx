'use client';

import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Card, Chip, InputAdornment, MenuItem, Stack, TextField } from '@mui/material';
import { Search } from 'lucide-react';
import { ck, clinicalApi, PatientRow } from '@/lib/api/clinical';
import { PatientWorklist } from './PatientWorklist';

type Focus = 'all' | 'attention' | 'alerts' | 'meds' | 'obs' | 'discharge';

const FOCUS: { value: Focus; label: string; test: (r: PatientRow) => boolean }[] = [
  { value: 'all', label: 'All', test: () => true },
  {
    value: 'attention',
    label: 'Needs attention',
    test: (r) => r.openAlerts > 0 || r.overdueDoses > 0 || r.observationOverdue || r.pendingResults > 0,
  },
  { value: 'alerts', label: 'Open alerts', test: (r) => r.openAlerts > 0 },
  { value: 'meds', label: 'Doses due / overdue', test: (r) => r.dueDoses + r.overdueDoses > 0 },
  { value: 'obs', label: 'Observations overdue', test: (r) => r.observationOverdue },
  { value: 'discharge', label: 'Discharge today', test: (r) => r.expectedDischargeToday },
];

/** Searchable, filterable worklist of the signed-in clinician's patients. */
export function MyPatientsView({ basePath, showWardFilter }: { basePath: string; showWardFilter?: boolean }) {
  const q = useQuery({ queryKey: ck.myPatients, queryFn: clinicalApi.myPatients, refetchInterval: 60_000 });
  const [search, setSearch] = useState('');
  const [focus, setFocus] = useState<Focus>('all');
  const [ward, setWard] = useState('');
  const wards = useMemo(() => {
    const m = new Map<string, string>();
    (q.data ?? []).forEach((r) => r.patient.wardId && m.set(r.patient.wardId, r.patient.wardName ?? 'Ward'));
    return [...m.entries()];
  }, [q.data]);
  const rows = useMemo(() => {
    const needle = search.trim().toLowerCase();
    const f = FOCUS.find((x) => x.value === focus)!;
    return (q.data ?? []).filter(
      (r) =>
        f.test(r) &&
        (!ward || r.patient.wardId === ward) &&
        (!needle ||
          r.patient.patientName.toLowerCase().includes(needle) ||
          r.patient.mrn.toLowerCase().includes(needle) ||
          (r.primaryDiagnosis ?? '').toLowerCase().includes(needle) ||
          (r.patient.location ?? '').toLowerCase().includes(needle)),
    );
  }, [q.data, search, focus, ward]);
  return (
    <Card>
      <Stack spacing={3} sx={{ p: 4 }}>
        <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap role="group" aria-label="Filter patients">
          {FOCUS.map((f) => {
            const count = (q.data ?? []).filter(f.test).length;
            return (
              <Chip
                key={f.value}
                label={`${f.label}${q.data ? ` (${count})` : ''}`}
                clickable
                color={focus === f.value ? 'primary' : 'default'}
                variant={focus === f.value ? 'filled' : 'outlined'}
                onClick={() => setFocus(f.value)}
              />
            );
          })}
        </Stack>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3}>
          <TextField
            placeholder="Search name, MRN, diagnosis or bed"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            slotProps={{ input: { startAdornment: <InputAdornment position="start"><Search size={16} /></InputAdornment> }, htmlInput: { 'aria-label': 'Search patients' } }}
            sx={{ maxWidth: { sm: 360 } }}
          />
          {showWardFilter && wards.length > 1 && (
            <TextField select label="Ward" value={ward} onChange={(e) => setWard(e.target.value)} slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }} sx={{ maxWidth: { sm: 240 } }}>
              <MenuItem value="">All my wards</MenuItem>
              {wards.map(([id, name]) => (
                <MenuItem key={id} value={id}>
                  {name}
                </MenuItem>
              ))}
            </TextField>
          )}
        </Stack>
      </Stack>
      <PatientWorklist
        rows={q.data ? rows : undefined}
        loading={q.isLoading}
        error={q.error}
        onRetry={() => q.refetch()}
        basePath={basePath}
        emptyTitle={q.data?.length ? 'No patients match these filters' : 'No patients assigned'}
      />
    </Card>
  );
}
