'use client';

import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useFieldArray, useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import {
  Alert,
  AlertTitle,
  Box,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  Grid,
  IconButton,
  InputAdornment,
  List,
  ListItemButton,
  MenuItem,
  Radio,
  Stack,
  Tab,
  Tabs,
  TextField,
  Tooltip,
  Typography,
  useMediaQuery,
  useTheme,
} from '@mui/material';
import { OctagonAlert, Plus, Search, Trash2 } from 'lucide-react';
import { CatalogueItem, ck, clinicalApi, LabOrderView, LabPriority, LabResultView, ResultFlag } from '@/lib/api/clinical';
import { errorMessage } from '@/lib/api/client';
import { applyServerErrors, useDebouncedValue, useToast } from '@/lib/hooks';
import { formatDateTime, formatINR } from '@/lib/format';
import { RHFSelect, RHFTextField } from '@/components/forms/Fields';
import { DataTable } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { EmptyState, TableSkeleton } from '@/components/ui/States';
import { colors } from '@/theme/tokens';
import { useClinicalInvalidate } from '../shared';

export const FLAG_OPTIONS: { value: ResultFlag; label: string }[] = [
  { value: 'NORMAL', label: 'Normal' },
  { value: 'LOW', label: 'Low' },
  { value: 'HIGH', label: 'High' },
  { value: 'CRITICAL_LOW', label: 'Critical low' },
  { value: 'CRITICAL_HIGH', label: 'Critical high' },
  { value: 'ABNORMAL', label: 'Abnormal' },
];

const isCriticalFlag = (f: ResultFlag) => f === 'CRITICAL_LOW' || f === 'CRITICAL_HIGH';

/** Result flag chip: critical in red, other abnormal flags amber, normal green. */
export function ResultFlagChip({ flag }: { flag: ResultFlag }) {
  const tone = isCriticalFlag(flag) ? 'error' : flag === 'NORMAL' ? 'success' : 'warning';
  const label = FLAG_OPTIONS.find((f) => f.value === flag)?.label ?? flag;
  return (
    <StatusChip
      value={flag}
      tone={tone}
      label={isCriticalFlag(flag) ? `${label} !` : label}
      sx={isCriticalFlag(flag) ? { fontWeight: 700 } : undefined}
    />
  );
}

export function sourceLabel(r: Pick<LabResultView, 'source'>) {
  return r.source === 'LIS_ADAPTER' ? 'LIS (dev adapter)' : 'Manual entry';
}

/** Results of one investigation; critical rows are emphasised. */
export function ResultsTable({ order }: { order: LabOrderView }) {
  const results = order.results ?? [];
  const reviewed = results.find((r) => r.reviewedAt);
  return (
    <Box>
      <DataTable<LabResultView>
        dense
        ariaLabel={`${order.testName} results`}
        rows={results}
        rowKey={(r) => r.id}
        emptyTitle="No results entered yet"
        rowSx={(r) => (isCriticalFlag(r.flag) ? { bgcolor: colors.dangerSoft } : undefined)}
        columns={[
          {
            key: 'c',
            header: 'Component',
            primary: true,
            render: (r) => (
              <Typography variant="body2" fontWeight={600}>
                {r.component}
              </Typography>
            ),
          },
          {
            key: 'v',
            header: 'Result',
            render: (r) => (
              <Typography
                variant="body2"
                component="span"
                sx={{
                  fontVariantNumeric: 'tabular-nums',
                  fontWeight: r.flag === 'NORMAL' ? 400 : 700,
                  color: isCriticalFlag(r.flag) ? colors.danger : undefined,
                }}
              >
                {r.valueText}
                {r.unit ? ` ${r.unit}` : ''}
              </Typography>
            ),
          },
          { key: 'ref', header: 'Reference', render: (r) => r.referenceRange ?? '—' },
          { key: 'f', header: 'Flag', render: (r) => <ResultFlagChip flag={r.flag} /> },
          { key: 's', header: 'Status', hideOnMobile: true, render: (r) => <StatusChip value={r.status} /> },
          {
            key: 'src',
            header: 'Source',
            hideOnMobile: true,
            render: (r) => (
              <Tooltip title={`${r.resultedBy ?? '—'} · ${formatDateTime(r.resultedAt)}`}>
                <Typography variant="caption" color={r.source === 'LIS_ADAPTER' ? 'info.main' : 'text.secondary'} sx={{ whiteSpace: 'nowrap' }}>
                  {sourceLabel(r)}
                </Typography>
              </Tooltip>
            ),
          },
        ]}
      />
      {results.some((r) => r.interpretation) && (
        <Stack spacing={0.5} sx={{ px: { xs: 3, md: 4 }, pt: 2 }}>
          {results
            .filter((r) => r.interpretation)
            .map((r) => (
              <Typography key={r.id} variant="caption" color="text.secondary">
                <strong>{r.component}:</strong> {r.interpretation}
              </Typography>
            ))}
        </Stack>
      )}
      {results.length > 0 && (
        <Typography variant="caption" color="text.secondary" component="div" sx={{ px: { xs: 3, md: 4 }, pt: 1.5 }}>
          Resulted {formatDateTime(results[0].resultedAt)} by {results[0].resultedBy ?? '—'} · {sourceLabel(results[0])}
          {results.some((r) => r.source === 'LIS_ADAPTER') && ' — received from the development LIS mock adapter, not a live laboratory interface'}
        </Typography>
      )}
      {reviewed && (
        <Alert severity="success" variant="outlined" sx={{ mt: 2, mx: { xs: 3, md: 4 } }}>
          <Typography variant="caption" component="div">
            Reviewed by {reviewed.reviewedBy ?? '—'} {formatDateTime(reviewed.reviewedAt)}
            {reviewed.reviewComment ? ` — “${reviewed.reviewComment}”` : ''}
          </Typography>
        </Alert>
      )}
    </Box>
  );
}

// ------------------------------------------------------------------ order

type OrderCategory = 'LAB' | 'IMAGING';

/** Catalogue search and investigation ordering (posts a charge at the listed tariff price). */
export function OrderInvestigationDialog({ open, onClose, admissionId }: { open: boolean; onClose: () => void; admissionId: string }) {
  const toast = useToast();
  const invalidate = useClinicalInvalidate();
  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down('sm'));
  const [category, setCategory] = useState<OrderCategory>('LAB');
  const [search, setSearch] = useState('');
  const q = useDebouncedValue(search, 250);
  const [item, setItem] = useState<CatalogueItem | null>(null);
  const [priority, setPriority] = useState<LabPriority>('ROUTINE');
  const [clinicalInfo, setClinicalInfo] = useState('');
  const [specimen, setSpecimen] = useState('');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (open) {
      setCategory('LAB');
      setSearch('');
      setItem(null);
      setPriority('ROUTINE');
      setClinicalInfo('');
      setSpecimen('');
      setError(null);
    }
  }, [open]);

  const params = { category, q: q || undefined };
  const cat = useQuery({ queryKey: ck.catalogue(params), queryFn: () => clinicalApi.catalogue(params), enabled: open, staleTime: 60_000 });

  const order = useMutation({
    mutationFn: () =>
      clinicalApi.orderInvestigation(admissionId, {
        tariffId: item!.tariffId,
        priority,
        clinicalInfo: clinicalInfo.trim() || null,
        specimen: category === 'LAB' ? specimen.trim() || null : null,
      }),
    onSuccess: (o) => {
      toast.success(`${o.testName} ordered (${o.orderNumber})`);
      invalidate(admissionId);
      onClose();
    },
    onError: (e) => setError(errorMessage(e)),
  });

  return (
    <Dialog open={open} onClose={order.isPending ? undefined : onClose} maxWidth="sm" fullWidth fullScreen={fullScreen} aria-labelledby="order-inv-title">
      <DialogTitle id="order-inv-title">Order investigation</DialogTitle>
      <DialogContent dividers>
        <Stack spacing={3}>
          <Tabs
            value={category}
            onChange={(_, v) => {
              setCategory(v);
              setItem(null);
            }}
            aria-label="Investigation category"
          >
            <Tab value="LAB" label="Laboratory" />
            <Tab value="IMAGING" label="Imaging & diagnostics" />
          </Tabs>
          <TextField
            placeholder={category === 'LAB' ? 'Search tests, e.g. CBC, potassium' : 'Search, e.g. chest X-ray, ECG'}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            size="small"
            slotProps={{
              input: { startAdornment: <InputAdornment position="start"><Search size={16} /></InputAdornment> },
              htmlInput: { 'aria-label': 'Search catalogue' },
            }}
          />
          <Box sx={{ border: 1, borderColor: 'divider', borderRadius: 2, maxHeight: 260, overflow: 'auto' }}>
            {cat.isLoading ? (
              <TableSkeleton rows={4} columns={2} />
            ) : !cat.data?.length ? (
              <EmptyState title="No matching items" description="Try a different search term." compact />
            ) : (
              <List dense disablePadding role="radiogroup" aria-label="Catalogue items">
                {cat.data.map((c) => (
                  <ListItemButton
                    key={c.tariffId}
                    selected={item?.tariffId === c.tariffId}
                    onClick={() => setItem(c)}
                    role="radio"
                    aria-checked={item?.tariffId === c.tariffId}
                    sx={{ borderBottom: 1, borderColor: 'divider', gap: 1 }}
                  >
                    <Radio size="small" checked={item?.tariffId === c.tariffId} tabIndex={-1} sx={{ p: 0.5 }} />
                    <Box sx={{ flex: 1, minWidth: 0 }}>
                      <Typography variant="body2" fontWeight={600}>
                        {c.name}
                      </Typography>
                      <Typography variant="caption" color="text.secondary">
                        {c.code}
                        {c.description ? ` · ${c.description}` : ''}
                      </Typography>
                    </Box>
                    <Typography variant="body2" sx={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
                      {formatINR(c.unitPrice)}
                    </Typography>
                  </ListItemButton>
                ))}
              </List>
            )}
          </Box>
          <Grid container spacing={3}>
            <Grid size={{ xs: 12, sm: 5 }}>
              <TextField select label="Priority" value={priority} onChange={(e) => setPriority(e.target.value as LabPriority)}>
                <MenuItem value="ROUTINE">Routine</MenuItem>
                <MenuItem value="URGENT">Urgent</MenuItem>
                <MenuItem value="STAT">STAT (immediate)</MenuItem>
              </TextField>
            </Grid>
            {category === 'LAB' && (
              <Grid size={{ xs: 12, sm: 7 }}>
                <TextField label="Specimen (optional)" value={specimen} onChange={(e) => setSpecimen(e.target.value)} placeholder="e.g. Venous blood" />
              </Grid>
            )}
            <Grid size={12}>
              <TextField
                label="Clinical information"
                value={clinicalInfo}
                onChange={(e) => setClinicalInfo(e.target.value)}
                multiline
                minRows={2}
                placeholder="Relevant history and the question to answer"
                slotProps={{ htmlInput: { maxLength: 1000 } }}
              />
            </Grid>
          </Grid>
          {item && (
            <Alert severity="info">
              Ordering <strong>{item.name}</strong> posts a charge of <strong>{formatINR(item.unitPrice)}</strong> to the patient&apos;s bill. The charge
              is reversed automatically if the order is cancelled before collection.
            </Alert>
          )}
          {error && <Alert severity="error">{error}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, py: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={order.isPending}>
          Cancel
        </Button>
        <Button variant="contained" disabled={!item || order.isPending} onClick={() => order.mutate()}>
          {order.isPending ? 'Ordering…' : item ? `Order · ${formatINR(item.unitPrice)}` : 'Order'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

// ------------------------------------------------------------------ results entry

const componentSchema = z.object({
  component: z.string().trim().min(1, 'Required').max(200),
  valueText: z.string().trim().min(1, 'Required').max(500),
  unit: z.string().trim().max(50).optional(),
  referenceRange: z.string().trim().max(100).optional(),
  flag: z.enum(['NORMAL', 'LOW', 'HIGH', 'CRITICAL_LOW', 'CRITICAL_HIGH', 'ABNORMAL']),
  interpretation: z.string().trim().max(2000).optional(),
});
const resultsSchema = z.object({
  status: z.enum(['PRELIMINARY', 'FINAL']),
  components: z.array(componentSchema).min(1, 'Add at least one result component'),
});
type ResultsForm = z.infer<typeof resultsSchema>;

const emptyRow = (): ResultsForm['components'][number] => ({ component: '', valueText: '', unit: '', referenceRange: '', flag: 'NORMAL', interpretation: '' });

/** Enter results for an investigation (dynamic component rows). Critical flags raise alerts on the server. */
export function ResultEntryDialog({ order, onClose }: { order: LabOrderView | null; onClose: () => void }) {
  const toast = useToast();
  const invalidate = useClinicalInvalidate();
  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down('sm'));
  const [error, setError] = useState<string | null>(null);
  const defaults = useMemo<ResultsForm>(
    () => ({
      status: 'FINAL',
      components: order?.results?.length
        ? order.results
            .filter((r) => r.status !== 'ENTERED_IN_ERROR')
            .map((r) => ({
              component: r.component,
              valueText: r.valueText,
              unit: r.unit ?? '',
              referenceRange: r.referenceRange ?? '',
              flag: r.flag,
              interpretation: r.interpretation ?? '',
            }))
        : [emptyRow()],
    }),
    [order],
  );
  const { control, handleSubmit, reset, watch, setError: setFieldError, formState } = useForm<ResultsForm>({
    resolver: zodResolver(resultsSchema),
    defaultValues: defaults,
  });
  const rows = useFieldArray({ control, name: 'components' });
  useEffect(() => {
    if (order) {
      reset(defaults);
      setError(null);
    }
  }, [order, defaults, reset]);

  const save = useMutation({
    mutationFn: (v: ResultsForm) =>
      clinicalApi.enterResults(order!.id, {
        status: v.status,
        components: v.components.map((c) => {
          const t = c.valueText.trim();
          return {
            component: c.component.trim(),
            valueText: t,
            valueNumeric: /^-?\d+(\.\d+)?$/.test(t) ? Number(t) : null,
            unit: c.unit?.trim() || null,
            referenceRange: c.referenceRange?.trim() || null,
            flag: c.flag,
            interpretation: c.interpretation?.trim() || null,
          };
        }),
      }),
    onSuccess: (o) => {
      toast.success(o.hasCritical ? `Results saved — critical value alert raised for ${o.testName}` : `Results saved for ${o.testName}`);
      invalidate(o.admissionId);
      onClose();
    },
    onError: (e) => {
      if (!applyServerErrors(e, setFieldError)) setError(errorMessage(e));
    },
  });

  const anyCritical = watch('components').some((c) => isCriticalFlag(c.flag as ResultFlag));

  return (
    <Dialog open={!!order} onClose={save.isPending ? undefined : onClose} maxWidth="md" fullWidth fullScreen={fullScreen} aria-labelledby="results-title">
      <DialogTitle id="results-title">Enter results: {order?.testName}</DialogTitle>
      <DialogContent dividers>
        <Stack spacing={3} component="form" id="results-form" noValidate onSubmit={handleSubmit((v) => save.mutate(v))}>
          {order && (
            <Typography variant="body2" color="text.secondary">
              {order.orderNumber} · {order.patient?.patientName ?? ''} {order.specimen ? `· ${order.specimen}` : ''}
              {order.collectedAt ? ` · collected ${formatDateTime(order.collectedAt)}` : ''}
            </Typography>
          )}
          {rows.fields.map((f, i) => (
            <Box key={f.id} sx={{ border: 1, borderColor: 'divider', borderRadius: 2, p: 3 }}>
              <Grid container spacing={2}>
                <Grid size={{ xs: 12, sm: 4 }}>
                  <RHFTextField control={control} name={`components.${i}.component`} label="Component" required size="small" />
                </Grid>
                <Grid size={{ xs: 6, sm: 3 }}>
                  <RHFTextField control={control} name={`components.${i}.valueText`} label="Value" required size="small" />
                </Grid>
                <Grid size={{ xs: 6, sm: 2 }}>
                  <RHFTextField control={control} name={`components.${i}.unit`} label="Unit" size="small" />
                </Grid>
                <Grid size={{ xs: 10, sm: 3 }}>
                  <RHFSelect control={control} name={`components.${i}.flag`} label="Flag" size="small" options={FLAG_OPTIONS} />
                </Grid>
                <Grid size={{ xs: 12, sm: 4 }}>
                  <RHFTextField control={control} name={`components.${i}.referenceRange`} label="Reference range" size="small" placeholder="e.g. 3.5-5.1" />
                </Grid>
                <Grid size={{ xs: 12, sm: 7 }}>
                  <RHFTextField control={control} name={`components.${i}.interpretation`} label="Interpretation (optional)" size="small" />
                </Grid>
                <Grid size={{ xs: 2, sm: 1 }} sx={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end' }}>
                  <Tooltip title="Remove component">
                    <span>
                      <IconButton aria-label={`Remove component ${i + 1}`} onClick={() => rows.remove(i)} disabled={rows.fields.length === 1} size="small">
                        <Trash2 size={16} />
                      </IconButton>
                    </span>
                  </Tooltip>
                </Grid>
              </Grid>
            </Box>
          ))}
          <Box>
            <Button size="small" startIcon={<Plus size={14} />} onClick={() => rows.append(emptyRow())}>
              Add component
            </Button>
          </Box>
          {formState.errors.components?.message && <Alert severity="error">{formState.errors.components.message}</Alert>}
          <Box sx={{ maxWidth: 280 }}>
            <RHFSelect
              control={control}
              name="status"
              label="Result status"
              options={[
                { value: 'FINAL', label: 'Final' },
                { value: 'PRELIMINARY', label: 'Preliminary' },
              ]}
            />
          </Box>
          {anyCritical && (
            <Alert severity="error" icon={<OctagonAlert size={20} />}>
              Critical values will raise an urgent alert to the treating doctor. Inform the clinician directly as per policy.
            </Alert>
          )}
          {error && <Alert severity="error">{error}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, py: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={save.isPending}>
          Cancel
        </Button>
        <Button type="submit" form="results-form" variant="contained" disabled={save.isPending}>
          {save.isPending ? 'Saving…' : 'Save results'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

// ------------------------------------------------------------------ review

/** Clinician review of resulted investigations, with optional release to the patient portal. */
export function ReviewResultsDialog({
  order,
  onClose,
  canRelease,
}: {
  order: LabOrderView | null;
  onClose: () => void;
  /** Offer "also release to patient" (RELEASE_TO_PATIENT). */
  canRelease?: boolean;
}) {
  const toast = useToast();
  const invalidate = useClinicalInvalidate();
  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down('sm'));
  const [comment, setComment] = useState('');
  const [release, setRelease] = useState(false);
  const [touched, setTouched] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (order) {
      setComment('');
      setRelease(false);
      setTouched(false);
      setError(null);
    }
  }, [order]);
  const needsComment = !!order && (order.hasCritical || order.hasAbnormal);
  const invalid = needsComment && comment.trim().length < 3;

  const review = useMutation({
    mutationFn: async () => {
      const reviewed = await clinicalApi.reviewResults(order!.id, comment.trim() || undefined);
      if (release) {
        try {
          return { order: await clinicalApi.releaseResults(order!.id), released: true };
        } catch (e) {
          toast.error(`Reviewed, but release failed: ${errorMessage(e)}`);
          return { order: reviewed, released: false };
        }
      }
      return { order: reviewed, released: false };
    },
    onSuccess: ({ order: o, released }) => {
      toast.success(released ? `${o.testName} reviewed and released to the patient` : `${o.testName} reviewed`);
      invalidate(o.admissionId);
      onClose();
    },
    onError: (e) => setError(errorMessage(e)),
  });

  return (
    <Dialog open={!!order} onClose={review.isPending ? undefined : onClose} maxWidth="md" fullWidth fullScreen={fullScreen} aria-labelledby="review-title">
      <DialogTitle id="review-title">Review results: {order?.testName}</DialogTitle>
      <DialogContent dividers sx={{ px: { xs: 0, sm: 0 } }}>
        {order && (
          <Stack spacing={3}>
            <Box sx={{ px: { xs: 4, sm: 6 } }}>
              <Typography variant="body2" color="text.secondary">
                {order.patient ? `${order.patient.patientName} · ${order.patient.mrn} · ` : ''}
                {order.orderNumber} · ordered {formatDateTime(order.orderedAt)} by {order.orderedBy ?? '—'}
              </Typography>
              {order.clinicalInfo && (
                <Typography variant="body2" sx={{ mt: 0.5 }}>
                  Clinical information: {order.clinicalInfo}
                </Typography>
              )}
              {order.hasCritical && (
                <Alert severity="error" icon={<OctagonAlert size={20} />} sx={{ mt: 2 }}>
                  <AlertTitle>Critical value</AlertTitle>
                  One or more components are in the critical range. Document the action taken in your review comment.
                </Alert>
              )}
            </Box>
            <ResultsTable order={order} />
            <Stack spacing={2} sx={{ px: { xs: 4, sm: 6 } }}>
              <TextField
                label={needsComment ? 'Review comment (action taken)' : 'Review comment (optional)'}
                value={comment}
                onChange={(e) => setComment(e.target.value)}
                onBlur={() => setTouched(true)}
                multiline
                minRows={2}
                required={needsComment}
                error={touched && invalid}
                helperText={touched && invalid ? 'Abnormal or critical results need a short review comment.' : ' '}
              />
              {canRelease && (
                <FormControlLabel
                  control={<Checkbox checked={release} onChange={(e) => setRelease(e.target.checked)} />}
                  label="Also release these results to the patient portal"
                />
              )}
              {error && <Alert severity="error">{error}</Alert>}
            </Stack>
          </Stack>
        )}
      </DialogContent>
      <DialogActions sx={{ px: 6, py: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={review.isPending}>
          Cancel
        </Button>
        <Button
          variant="contained"
          disabled={review.isPending}
          onClick={() => {
            setTouched(true);
            if (!invalid) review.mutate();
          }}
        >
          {review.isPending ? 'Saving…' : release ? 'Mark reviewed & release' : 'Mark reviewed'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

// ------------------------------------------------------------------ collect / cancel / release

export type InvestigationActionKind = 'collect' | 'cancel' | 'release';

/** Confirmation dialogs for collect / cancel / release. Returns `request(kind, order)` and the `dialogs` element. */
export function useInvestigationActions() {
  const toast = useToast();
  const invalidate = useClinicalInvalidate();
  const [pending, setPending] = useState<{ kind: InvestigationActionKind; order: LabOrderView } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const mutation = useMutation({
    mutationFn: ({ kind, order, reason }: { kind: InvestigationActionKind; order: LabOrderView; reason?: string }) =>
      kind === 'collect'
        ? clinicalApi.collect(order.id)
        : kind === 'cancel'
          ? clinicalApi.cancelInvestigation(order.id, reason!)
          : clinicalApi.releaseResults(order.id),
    onSuccess: (o, v) => {
      toast.success(
        v.kind === 'collect'
          ? `Specimen collected for ${o.testName}`
          : v.kind === 'cancel'
            ? `${o.testName} cancelled${v.order.status === 'ORDERED' && v.order.chargePosted ? '; charge reversed' : ''}`
            : `${o.testName} results released to the patient`,
      );
      invalidate(o.admissionId);
      setPending(null);
    },
    onError: (e) => setError(errorMessage(e)),
  });
  const request = (kind: InvestigationActionKind, order: LabOrderView) => {
    setError(null);
    setPending({ kind, order });
  };
  const close = () => !mutation.isPending && setPending(null);
  const o = pending?.order;
  const beforeCollection = o?.status === 'ORDERED';
  const dialogs = (
    <>
      <ConfirmDialog
        open={pending?.kind === 'collect'}
        title="Record specimen collection?"
        description={<>Confirms the specimen for <strong>{o?.testName}</strong> ({o?.orderNumber}) was collected now{o?.specimen ? ` (${o.specimen})` : ''}.</>}
        confirmLabel="Mark collected"
        loading={mutation.isPending}
        error={error}
        onConfirm={() => o && mutation.mutate({ kind: 'collect', order: o })}
        onClose={close}
      />
      <ConfirmDialog
        open={pending?.kind === 'cancel'}
        title="Cancel investigation?"
        description={
          <>
            <strong>{o?.testName}</strong> ({o?.orderNumber}) will be cancelled and kept in the record.{' '}
            {beforeCollection
              ? 'No specimen has been collected, so the charge is reversed automatically.'
              : 'The specimen has already been collected, so the charge is not reversed automatically.'}
          </>
        }
        requireReason
        reasonLabel="Reason for cancelling"
        tone="error"
        confirmLabel="Cancel investigation"
        cancelLabel="Keep order"
        loading={mutation.isPending}
        error={error}
        onConfirm={(reason) => o && mutation.mutate({ kind: 'cancel', order: o, reason })}
        onClose={close}
      />
      <ConfirmDialog
        open={pending?.kind === 'release'}
        title="Release results to patient?"
        description={
          <>
            The reviewed results of <strong>{o?.testName}</strong> and the review comment become visible in the patient portal. This cannot be undone.
          </>
        }
        confirmLabel="Release"
        loading={mutation.isPending}
        error={error}
        onConfirm={() => o && mutation.mutate({ kind: 'release', order: o })}
        onClose={close}
      />
    </>
  );
  return { request, dialogs };
}

/** Compact one-line summary of abnormal components ("Potassium 6.4 mmol/L (critical high)"). */
export function abnormalSummary(order: LabOrderView): string | null {
  const ab = (order.results ?? []).filter((r) => r.flag !== 'NORMAL');
  if (!ab.length) return null;
  return ab
    .map((r) => `${r.component} ${r.valueText}${r.unit ? ` ${r.unit}` : ''} (${FLAG_OPTIONS.find((f) => f.value === r.flag)?.label.toLowerCase()})`)
    .join(', ');
}

