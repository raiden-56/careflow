'use client';

import { useMemo, useState } from 'react';
import Link from 'next/link';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Box,
  Button,
  Card,
  CardActionArea,
  Chip,
  Divider,
  Drawer,
  Grid,
  IconButton,
  MenuItem,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import { BedDouble, Plus, ShieldAlert, Wind, X } from 'lucide-react';
import { facilityApi, qk } from '@/lib/api/core';
import type { BedStatus, BedType, BedView } from '@/lib/api/types';
import { useAuth } from '@/lib/auth/AuthProvider';
import { useToast } from '@/lib/hooks';
import { formatDateTime, fromNow, humanize } from '@/lib/format';
import { statusMeta } from '@/lib/status';
import { PageHeader } from '@/components/ui/PageHeader';
import { KpiCard } from '@/components/ui/KpiCard';
import { StatusChip, toneColors } from '@/components/ui/StatusChip';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { CardsSkeleton, EmptyState, ErrorState } from '@/components/ui/States';
import { DefinitionList } from '@/components/ui/Display';

const STATUSES: BedStatus[] = ['AVAILABLE', 'OCCUPIED', 'RESERVED', 'CLEANING', 'MAINTENANCE'];
const MANUAL_TARGETS: Record<BedStatus, BedStatus[]> = {
  AVAILABLE: ['RESERVED', 'CLEANING', 'MAINTENANCE'],
  RESERVED: ['AVAILABLE', 'CLEANING', 'MAINTENANCE'],
  CLEANING: ['AVAILABLE', 'MAINTENANCE'],
  MAINTENANCE: ['AVAILABLE', 'CLEANING'],
  OCCUPIED: [],
};

export default function BedBoardPage() {
  const toast = useToast();
  const queryClient = useQueryClient();
  const { hasPermission } = useAuth();
  const [wardId, setWardId] = useState('');
  const [departmentId, setDepartmentId] = useState('');
  const [status, setStatus] = useState<BedStatus | ''>('');
  const [bedType, setBedType] = useState<BedType | ''>('');
  const [selected, setSelected] = useState<BedView | null>(null);
  const [target, setTarget] = useState<BedStatus | null>(null);

  const params = { wardId: wardId || undefined, departmentId: departmentId || undefined, status: status || undefined, bedType: bedType || undefined };
  const board = useQuery({ queryKey: qk.bedBoard(params), queryFn: () => facilityApi.bedBoard(params), refetchInterval: 60_000 });
  const wards = useQuery({ queryKey: qk.wards, queryFn: facilityApi.wards });
  const departments = useQuery({ queryKey: qk.departments, queryFn: facilityApi.departments });
  const history = useQuery({
    queryKey: ['beds', selected?.id, 'history'],
    queryFn: () => facilityApi.bedHistory(selected!.id),
    enabled: !!selected && hasPermission('BED_STATUS_MANAGE'),
  });

  const totals = useMemo(() => {
    const w = wards.data ?? [];
    return {
      total: w.reduce((s, x) => s + x.totalBeds, 0),
      available: w.reduce((s, x) => s + x.available, 0),
      occupied: w.reduce((s, x) => s + x.occupied, 0),
      turnover: w.reduce((s, x) => s + x.cleaning + x.maintenance, 0),
      reserved: w.reduce((s, x) => s + x.reserved, 0),
    };
  }, [wards.data]);

  const change = useMutation({
    mutationFn: ({ reason }: { reason?: string }) => facilityApi.changeBedStatus(selected!.id, target!, reason, selected!.version),
    onSuccess: (bed) => {
      toast.success(`Bed ${bed.roomNumber}-${bed.bedNumber} is now ${statusMeta(bed.status).label.toLowerCase()}`);
      setTarget(null);
      setSelected(bed);
      queryClient.invalidateQueries({ queryKey: ['beds'] });
      queryClient.invalidateQueries({ queryKey: qk.wards });
    },
    onError: (e) => {
      toast.error(e);
      queryClient.invalidateQueries({ queryKey: ['beds'] });
    },
  });

  return (
    <>
      <PageHeader
        title="Bed board"
        subtitle="Live ward map. Occupied beds change only through admission, transfer and discharge."
        breadcrumbs={[{ label: 'Administration', href: '/admin' }, { label: 'Bed board' }]}
        actions={
          hasPermission('ADMISSION_MANAGE') && (
            <Button component={Link} href="/admin/admissions/new" variant="contained" startIcon={<Plus size={16} />}>
              New admission
            </Button>
          )
        }
      />
      <Grid container spacing={4} sx={{ mb: 5 }}>
        {[
          { label: 'Total beds', value: totals.total, tone: 'neutral' as const },
          { label: 'Available', value: totals.available, tone: 'success' as const },
          { label: 'Occupied', value: totals.occupied, tone: 'primary' as const, hint: totals.total ? `${Math.round((totals.occupied / totals.total) * 100)}% occupancy` : undefined },
          { label: 'Reserved', value: totals.reserved, tone: 'info' as const },
          { label: 'Cleaning / maintenance', value: totals.turnover, tone: 'warning' as const },
        ].map((k) => (
          <Grid key={k.label} size={{ xs: 6, md: 4, lg: 2.4 }}>
            <KpiCard label={k.label} value={k.value} tone={k.tone} hint={k.hint} icon={BedDouble} loading={wards.isLoading} />
          </Grid>
        ))}
      </Grid>
      <Card sx={{ mb: 5, p: 4 }}>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={3}>
          <TextField select label="Ward" value={wardId} onChange={(e) => setWardId(e.target.value)}>
            <MenuItem value="">All wards</MenuItem>
            {wards.data?.map((w) => (
              <MenuItem key={w.id} value={w.id}>
                {w.name}
              </MenuItem>
            ))}
          </TextField>
          <TextField select label="Department" value={departmentId} onChange={(e) => setDepartmentId(e.target.value)}>
            <MenuItem value="">All departments</MenuItem>
            {departments.data?.map((d) => (
              <MenuItem key={d.id} value={d.id}>
                {d.name}
              </MenuItem>
            ))}
          </TextField>
          <TextField select label="Status" value={status} onChange={(e) => setStatus(e.target.value as BedStatus)}>
            <MenuItem value="">Any status</MenuItem>
            {STATUSES.map((s) => (
              <MenuItem key={s} value={s}>
                {statusMeta(s).label}
              </MenuItem>
            ))}
          </TextField>
          <TextField select label="Bed type" value={bedType} onChange={(e) => setBedType(e.target.value as BedType)}>
            <MenuItem value="">Any type</MenuItem>
            {(['STANDARD', 'ICU', 'ISOLATION', 'PAEDIATRIC', 'BARIATRIC', 'MATERNITY'] as BedType[]).map((t) => (
              <MenuItem key={t} value={t}>
                {humanize(t)}
              </MenuItem>
            ))}
          </TextField>
        </Stack>
        <Stack direction="row" spacing={2} sx={{ mt: 3 }} flexWrap="wrap" useFlexGap aria-label="Legend">
          {STATUSES.map((s) => (
            <StatusChip key={s} value={s} dot />
          ))}
        </Stack>
      </Card>

      {board.isLoading ? (
        <CardsSkeleton count={8} />
      ) : board.error ? (
        <ErrorState error={board.error} onRetry={() => board.refetch()} />
      ) : !board.data?.length ? (
        <EmptyState icon={BedDouble} title="No beds match these filters" />
      ) : (
        <Stack spacing={5}>
          {board.data.map((w) => (
            <Card key={w.id} component="section" aria-labelledby={`ward-${w.id}`}>
              <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ px: 5, py: 3, borderBottom: 1, borderColor: 'divider' }}>
                <Box>
                  <Typography id={`ward-${w.id}`} variant="h4" component="h2">
                    {w.name}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {humanize(w.wardType)} · Floor {w.floor ?? '—'}
                    {w.departmentName ? ` · ${w.departmentName}` : ''}
                    {w.genderPolicy !== 'MIXED' ? ` · ${humanize(w.genderPolicy)} only` : ''}
                  </Typography>
                </Box>
              </Stack>
              <Box sx={{ p: 5, display: 'grid', gap: 4, gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr', xl: 'repeat(3, 1fr)' } }}>
                {w.rooms.map((r) => (
                  <Box key={r.id}>
                    <Typography variant="subtitle2" sx={{ mb: 2 }}>
                      Room {r.roomNumber} · {humanize(r.roomType)}
                    </Typography>
                    <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: 'repeat(auto-fill, minmax(118px, 1fr))' }}>
                      {r.beds.map((b) => {
                        const tone = toneColors(statusMeta(b.status).tone);
                        return (
                          <Card key={b.id} sx={{ borderLeft: `4px solid ${tone.fg}`, boxShadow: 'none', bgcolor: b.status === 'AVAILABLE' ? 'background.paper' : tone.bg }}>
                            <CardActionArea onClick={() => setSelected(b)} sx={{ p: 2.5, minHeight: 92 }} aria-label={`Bed ${r.roomNumber}-${b.bedNumber}, ${statusMeta(b.status).label}`}>
                              <Stack direction="row" justifyContent="space-between" alignItems="center">
                                <Typography variant="body2" fontWeight={700}>
                                  {b.bedNumber}
                                </Typography>
                                <Stack direction="row" spacing={0.5} sx={{ color: 'text.secondary' }}>
                                  {b.isolationCapable && (
                                    <Tooltip title="Isolation-capable">
                                      <ShieldAlert size={13} />
                                    </Tooltip>
                                  )}
                                  {b.oxygenSupported && (
                                    <Tooltip title="Oxygen supported">
                                      <Wind size={13} />
                                    </Tooltip>
                                  )}
                                </Stack>
                              </Stack>
                              <Typography variant="caption" sx={{ color: tone.fg, fontWeight: 600 }} component="div">
                                {statusMeta(b.status).label}
                              </Typography>
                              {b.occupant ? (
                                <Typography variant="caption" color="text.primary" noWrap component="div" title={b.occupant.patientName}>
                                  {b.occupant.patientName}
                                </Typography>
                              ) : (
                                <Typography variant="caption" color="text.secondary" component="div">
                                  {b.status !== 'AVAILABLE' ? fromNow(b.statusChangedAt) : ' '}
                                </Typography>
                              )}
                            </CardActionArea>
                          </Card>
                        );
                      })}
                    </Box>
                  </Box>
                ))}
              </Box>
            </Card>
          ))}
        </Stack>
      )}

      <Drawer anchor="right" open={!!selected} onClose={() => setSelected(null)} slotProps={{ paper: { sx: { width: { xs: '100%', sm: 420 } } } }}>
        {selected && (
          <Box sx={{ p: 5 }}>
            <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 4 }}>
              <Typography variant="h3">
                {selected.wardName} · {selected.roomNumber}-{selected.bedNumber}
              </Typography>
              <IconButton onClick={() => setSelected(null)} aria-label="Close">
                <X size={18} />
              </IconButton>
            </Stack>
            <Stack spacing={4}>
              <DefinitionList
                items={[
                  { label: 'Status', value: <StatusChip value={selected.status} /> },
                  { label: 'Since', value: formatDateTime(selected.statusChangedAt) },
                  { label: 'Bed type', value: humanize(selected.bedType) },
                  { label: 'Room type', value: humanize(selected.roomType) },
                  { label: 'Features', value: [selected.isolationCapable && 'Isolation', selected.oxygenSupported && 'Oxygen'].filter(Boolean).join(', ') || '—' },
                  { label: 'Notes', value: selected.notes },
                ]}
              />
              {selected.occupant && (
                <Card sx={{ p: 4, bgcolor: 'background.default' }}>
                  <Typography variant="subtitle2" sx={{ mb: 2 }}>
                    Occupant
                  </Typography>
                  <Typography variant="body2" fontWeight={600}>
                    {selected.occupant.patientName}
                  </Typography>
                  <Typography variant="caption" color="text.secondary" component="div">
                    {selected.occupant.mrn} · {selected.occupant.admissionNumber} · since {formatDateTime(selected.occupant.since)}
                  </Typography>
                  <Typography variant="caption" color="text.secondary" component="div">
                    {selected.occupant.attendingDoctor}
                  </Typography>
                  {hasPermission('ADMISSION_MANAGE') && (
                    <Button component={Link} href={`/admin/admissions/${selected.occupant.admissionId}`} size="small" sx={{ mt: 2 }}>
                      Open admission
                    </Button>
                  )}
                </Card>
              )}
              {hasPermission('BED_STATUS_MANAGE') && MANUAL_TARGETS[selected.status].length > 0 && (
                <Box>
                  <Typography variant="subtitle2" sx={{ mb: 2 }}>
                    Housekeeping status
                  </Typography>
                  <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
                    {MANUAL_TARGETS[selected.status].map((t) => (
                      <Chip key={t} label={`Mark ${statusMeta(t).label.toLowerCase()}`} onClick={() => setTarget(t)} clickable variant="outlined" />
                    ))}
                  </Stack>
                </Box>
              )}
              {history.data && history.data.length > 0 && (
                <Box>
                  <Divider sx={{ mb: 3 }} />
                  <Typography variant="subtitle2" sx={{ mb: 2 }}>
                    History
                  </Typography>
                  <Stack spacing={2}>
                    {history.data.slice(0, 12).map((h, i) => (
                      <Box key={i}>
                        <Typography variant="body2">{h.description}</Typography>
                        <Typography variant="caption" color="text.secondary">
                          {formatDateTime(h.at)} · {h.actor}
                        </Typography>
                      </Box>
                    ))}
                  </Stack>
                </Box>
              )}
            </Stack>
          </Box>
        )}
      </Drawer>

      <ConfirmDialog
        open={!!target}
        title={`Mark bed as ${target ? statusMeta(target).label.toLowerCase() : ''}?`}
        description={target === 'AVAILABLE' ? 'Confirm the bed is clean and ready for the next patient.' : undefined}
        requireReason={target === 'MAINTENANCE' || target === 'RESERVED'}
        loading={change.isPending}
        onConfirm={(reason) => change.mutate({ reason })}
        onClose={() => setTarget(null)}
      />
    </>
  );
}
