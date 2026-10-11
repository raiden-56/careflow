'use client';

import { useState } from 'react';
import Link from 'next/link';
import dayjs from 'dayjs';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Box, Button, Card, Chip, Grid, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { ShieldAlert } from 'lucide-react';
import { AdministrationStatus, ck, DoseView, medicationApi } from '@/lib/api/clinical';
import { admissionApi, qk } from '@/lib/api/core';
import { formatDateTime, hospitalTimeZone, todayLocalDate } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { DataTable } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';
import { statusMeta } from '@/lib/status';
import { colors } from '@/theme/tokens';

const STATUSES: AdministrationStatus[] = ['ADMINISTERED', 'DELAYED', 'REFUSED', 'MISSED', 'HELD'];

/** Start/end of a hospital-local calendar day as UTC instants. */
const dayStart = (d: string) => dayjs.tz(d, 'YYYY-MM-DD', hospitalTimeZone()).startOf('day').toISOString();
const dayEnd = (d: string) => dayjs.tz(d, 'YYYY-MM-DD', hospitalTimeZone()).endOf('day').toISOString();

export default function AdministrationRecordsPage() {
  const [admissionId, setAdmissionId] = useState('');
  const [statuses, setStatuses] = useState<AdministrationStatus[]>([]);
  const [from, setFrom] = useState(() => dayjs(todayLocalDate()).subtract(6, 'day').format('YYYY-MM-DD'));
  const [to, setTo] = useState(todayLocalDate());
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);

  const patients = useQuery({ queryKey: qk.myAdmissions, queryFn: admissionApi.my });
  const rangeInvalid = !!from && !!to && from > to;
  const params = {
    admissionId: admissionId || undefined,
    status: statuses,
    from: from ? dayStart(from) : undefined,
    to: to ? dayEnd(to) : undefined,
    page,
    size,
  };
  const q = useQuery({
    queryKey: ck.marHistory(params),
    queryFn: () => medicationApi.history(params),
    placeholderData: keepPreviousData,
    enabled: !rangeInvalid,
  });

  const toggle = (s: AdministrationStatus) => {
    setStatuses((cur) => (cur.includes(s) ? cur.filter((x) => x !== s) : [...cur, s]));
    setPage(0);
  };
  const reset = () => {
    setAdmissionId('');
    setStatuses([]);
    setFrom(dayjs(todayLocalDate()).subtract(6, 'day').format('YYYY-MM-DD'));
    setTo(todayLocalDate());
    setPage(0);
  };

  return (
    <>
      <PageHeader
        title="Administration records"
        subtitle="Medication administration history for your patients: doses given and exceptions, newest first."
        breadcrumbs={[{ label: 'Nursing', href: '/nurse' }, { label: 'Administration records' }]}
      />
      <Card>
        <Stack spacing={3} sx={{ p: 4 }}>
          <Grid container spacing={3}>
            <Grid size={{ xs: 12, md: 4 }}>
              <TextField
                select
                fullWidth
                size="small"
                label="Patient"
                value={admissionId}
                slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }}
                onChange={(e) => {
                  setAdmissionId(e.target.value);
                  setPage(0);
                }}
                helperText={patients.error ? 'Could not load your patients' : undefined}
              >
                <MenuItem value="">All my patients</MenuItem>
                {(patients.data ?? []).map((a) => (
                  <MenuItem key={a.id} value={a.id}>
                    {a.patient.fullName} · {a.admissionNumber}
                  </MenuItem>
                ))}
              </TextField>
            </Grid>
            <Grid size={{ xs: 6, md: 3 }}>
              <TextField
                fullWidth
                size="small"
                type="date"
                label="From"
                value={from}
                onChange={(e) => {
                  setFrom(e.target.value);
                  setPage(0);
                }}
                slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: to || undefined } }}
              />
            </Grid>
            <Grid size={{ xs: 6, md: 3 }}>
              <TextField
                fullWidth
                size="small"
                type="date"
                label="To"
                value={to}
                onChange={(e) => {
                  setTo(e.target.value);
                  setPage(0);
                }}
                error={rangeInvalid}
                helperText={rangeInvalid ? 'Must be on or after From' : undefined}
                slotProps={{ inputLabel: { shrink: true }, htmlInput: { min: from || undefined } }}
              />
            </Grid>
            <Grid size={{ xs: 12, md: 2 }} sx={{ display: 'flex', alignItems: 'flex-start' }}>
              <Button color="inherit" onClick={reset}>
                Reset filters
              </Button>
            </Grid>
          </Grid>
          <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap role="group" aria-label="Status filter">
            <Chip
              label="All statuses"
              clickable
              color={statuses.length === 0 ? 'primary' : 'default'}
              variant={statuses.length === 0 ? 'filled' : 'outlined'}
              onClick={() => {
                setStatuses([]);
                setPage(0);
              }}
            />
            {STATUSES.map((s) => {
              const on = statuses.includes(s);
              return (
                <Chip
                  key={s}
                  label={statusMeta(s).label}
                  clickable
                  color={on ? 'primary' : 'default'}
                  variant={on ? 'filled' : 'outlined'}
                  onClick={() => toggle(s)}
                  aria-pressed={on}
                />
              );
            })}
          </Stack>
        </Stack>
        <DataTable<DoseView>
          rows={rangeInvalid ? [] : q.data?.content}
          loading={q.isFetching}
          error={q.error}
          onRetry={() => q.refetch()}
          rowKey={(d) => d.id}
          page={page}
          pageSize={size}
          total={q.data?.totalElements}
          onPageChange={setPage}
          onPageSizeChange={(s) => {
            setSize(s);
            setPage(0);
          }}
          ariaLabel="Medication administration records"
          emptyTitle="No records match"
          emptyDescription="Try a wider date range or clear the status filter."
          columns={[
            {
              key: 'scheduled',
              header: 'Scheduled',
              render: (d) => <Typography variant="body2" sx={{ whiteSpace: 'nowrap' }}>{formatDateTime(d.scheduledAt)}</Typography>,
            },
            {
              key: 'patient',
              header: 'Patient',
              render: (d) =>
                d.patient ? (
                  <Box>
                    <Typography
                      variant="body2"
                      fontWeight={600}
                      component={Link}
                      href={`/nurse/patients/${d.admissionId}?tab=medications`}
                      sx={{ color: 'primary.main', textDecoration: 'none' }}
                    >
                      {d.patient.patientName}
                    </Typography>
                    <Typography variant="caption" color="text.secondary" component="div">
                      {d.patient.mrn} · {d.patient.location ?? 'Awaiting bed'}
                    </Typography>
                  </Box>
                ) : (
                  '—'
                ),
            },
            {
              key: 'med',
              header: 'Medication',
              primary: true,
              render: (d) => (
                <Box sx={{ minWidth: 0 }}>
                  <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                    <Typography variant="body2" fontWeight={600}>
                      {d.medicationName}
                    </Typography>
                    {d.highAlert && <ShieldAlert size={14} color={colors.danger} aria-label="High alert" />}
                  </Stack>
                  <Typography variant="caption" color="text.secondary">
                    {d.doseGiven ?? (d.dose ? `${d.dose} ${d.doseUnit ?? ''}` : '')} · {d.routeGiven ?? d.route ?? ''}
                    {d.site ? ` · ${d.site}` : ''}
                  </Typography>
                </Box>
              ),
            },
            { key: 'status', header: 'Status', render: (d) => <StatusChip value={d.status} /> },
            {
              key: 'given',
              header: 'Given / recorded',
              render: (d) => (
                <Box>
                  <Typography variant="body2" sx={{ whiteSpace: 'nowrap' }}>
                    {formatDateTime(d.administeredAt ?? d.recordedAt)}
                  </Typography>
                  <Typography variant="caption" color="text.secondary" component="div">
                    {d.recordedBy ?? ''}
                    {d.witness ? ` · witness ${d.witness}` : ''}
                  </Typography>
                </Box>
              ),
            },
            {
              key: 'reason',
              header: 'Reason / notes',
              render: (d) => (
                <Typography variant="caption" component="div" sx={{ maxWidth: 260, wordBreak: 'break-word' }}>
                  {[d.reason, d.notes].filter(Boolean).join(' · ') || '—'}
                </Typography>
              ),
            },
          ]}
        />
      </Card>
    </>
  );
}

