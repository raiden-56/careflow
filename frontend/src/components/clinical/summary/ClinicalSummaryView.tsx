'use client';

import { ReactNode } from 'react';
import { Alert, Box, Button, Chip, Grid, Stack, Typography } from '@mui/material';
import { AlertTriangle, HeartPulse } from 'lucide-react';
import { ClinicalSummary, noteTypeLabel, orderSig } from '@/lib/api/clinical';
import { formatDate, formatDateTime, fromNow, humanize } from '@/lib/format';
import { SectionCard } from '@/components/ui/SectionCard';
import { StatusChip } from '@/components/ui/StatusChip';
import { colors } from '@/theme/tokens';

function More({ label, onClick }: { label: string; onClick?: () => void }) {
  if (!onClick) return null;
  return (
    <Button size="small" onClick={onClick}>
      {label}
    </Button>
  );
}

function Empty({ children }: { children: ReactNode }) {
  return (
    <Typography variant="body2" color="text.secondary">
      {children}
    </Typography>
  );
}

function Vital({ label, value, unit, warn }: { label: string; value?: number | string; unit?: string; warn?: 'WARNING' | 'CRITICAL' }) {
  return (
    <Box
      sx={{
        p: 2,
        borderRadius: 2,
        border: 1,
        borderColor: warn === 'CRITICAL' ? colors.danger : warn === 'WARNING' ? colors.warning : 'divider',
        bgcolor: warn === 'CRITICAL' ? colors.dangerSoft : warn === 'WARNING' ? colors.warningSoft : 'background.paper',
        minWidth: 0,
      }}
    >
      <Typography variant="caption" color="text.secondary" noWrap component="div">
        {label}
      </Typography>
      <Typography variant="h4" component="div" sx={{ fontVariantNumeric: 'tabular-nums' }}>
        {value ?? '—'}
        {value !== undefined && unit ? (
          <Typography component="span" variant="caption" color="text.secondary">
            {' '}
            {unit}
          </Typography>
        ) : null}
      </Typography>
    </Box>
  );
}

/** Overview tab: everything a clinician needs at a glance, with links to the detailed tabs. */
export function ClinicalSummaryView({ summary: s, onNavigate }: { summary: ClinicalSummary; onNavigate?: (tab: string) => void }) {
  const v = s.latestVitals;
  const breach = (p: string) => v?.breaches.find((b) => b.startsWith(p + ':'))?.split(':')[1] as 'WARNING' | 'CRITICAL' | undefined;
  const nav = (tab: string) => (onNavigate ? () => onNavigate(tab) : undefined);
  const critical = s.openAlerts.filter((a) => a.severity === 'CRITICAL');
  return (
    <Grid container spacing={5}>
      {critical.length > 0 && (
        <Grid size={12}>
          <Alert severity="error" icon={<AlertTriangle size={18} />} action={onNavigate && <Button color="inherit" size="small" onClick={() => onNavigate('alerts')}>Review</Button>}>
            {critical.length} critical alert{critical.length > 1 ? 's' : ''} open: {critical.map((a) => a.title).join(' · ')}
          </Alert>
        </Grid>
      )}
      <Grid size={{ xs: 12, lg: 7 }}>
        <Stack spacing={5}>
          <SectionCard title="Presentation & diagnoses" action={<More label="Manage" onClick={nav('diagnoses')} />}>
            <Typography variant="body2" sx={{ mb: 3, wordBreak: 'break-word' }}>
              {s.admissionReason}
            </Typography>
            {s.diagnoses.length === 0 ? (
              <Empty>No diagnosis recorded yet.</Empty>
            ) : (
              <Stack spacing={1.5}>
                {s.diagnoses.map((d) => (
                  <Stack key={d.id} direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
                    <Typography variant="body2" fontWeight={d.primary ? 600 : 400}>
                      {d.description}
                    </Typography>
                    {d.icdCode && <Chip size="small" variant="outlined" label={d.icdCode} />}
                    <StatusChip value={d.diagnosisType} />
                    {d.primary && <Chip size="small" color="primary" label="Primary" />}
                  </Stack>
                ))}
              </Stack>
            )}
          </SectionCard>
          <SectionCard
            title="Treatment plan"
            subtitle={s.activePlan ? `Version ${s.activePlan.planVersion} · ${s.activePlan.createdBy ?? ''} · ${formatDateTime(s.activePlan.createdAt)}` : undefined}
            action={<More label="Plan history" onClick={nav('diagnoses')} />}
          >
            {s.activePlan ? (
              <Stack spacing={2}>
                <Box>
                  <Typography variant="caption" color="text.secondary">
                    Goals
                  </Typography>
                  <Typography variant="body2" sx={{ whiteSpace: 'pre-line' }}>
                    {s.activePlan.goals}
                  </Typography>
                </Box>
                <Box>
                  <Typography variant="caption" color="text.secondary">
                    Plan
                  </Typography>
                  <Typography variant="body2" sx={{ whiteSpace: 'pre-line' }}>
                    {s.activePlan.planDetails}
                  </Typography>
                </Box>
                {s.activePlan.monitoring && (
                  <Box>
                    <Typography variant="caption" color="text.secondary">
                      Monitoring
                    </Typography>
                    <Typography variant="body2">{s.activePlan.monitoring}</Typography>
                  </Box>
                )}
                <Typography variant="caption" color="text.secondary">
                  {[s.activePlan.diet && `Diet: ${s.activePlan.diet}`, s.activePlan.activity && `Activity: ${s.activePlan.activity}`].filter(Boolean).join(' · ')}
                </Typography>
              </Stack>
            ) : (
              <Empty>No active treatment plan.</Empty>
            )}
          </SectionCard>
          <SectionCard title="Active medications" action={<More label="All medications" onClick={nav('medications')} />}>
            {s.activeMedications.length === 0 ? (
              <Empty>No active prescriptions.</Empty>
            ) : (
              <Stack spacing={2}>
                {s.activeMedications.map((o) => (
                  <Stack key={o.id} direction="row" justifyContent="space-between" spacing={2} alignItems="flex-start">
                    <Box sx={{ minWidth: 0 }}>
                      <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                        <Typography variant="body2" fontWeight={600}>
                          {o.medicationName}
                        </Typography>
                        {o.highAlert && <Chip size="small" color="error" variant="outlined" label="High-alert" />}
                        {o.status !== 'ACTIVE' && <StatusChip value={o.status} />}
                      </Stack>
                      <Typography variant="caption" color="text.secondary">
                        {orderSig(o)}
                        {o.indication ? ` · ${o.indication}` : ''}
                      </Typography>
                    </Box>
                    <Typography variant="caption" color="text.secondary" sx={{ flexShrink: 0, textAlign: 'right' }}>
                      {o.nextDoseAt ? `Next ${formatDateTime(o.nextDoseAt, 'DD MMM HH:mm')}` : o.frequency === 'PRN' ? 'As needed' : ''}
                    </Typography>
                  </Stack>
                ))}
              </Stack>
            )}
          </SectionCard>
          <SectionCard title="Recent notes" action={<More label="All notes" onClick={nav('notes')} />}>
            {s.recentNotes.length === 0 ? (
              <Empty>No notes yet.</Empty>
            ) : (
              <Stack spacing={2}>
                {s.recentNotes.map((n) => (
                  <Box key={n.id}>
                    <Typography variant="body2" fontWeight={600} sx={{ textDecoration: n.status === 'ENTERED_IN_ERROR' ? 'line-through' : undefined }}>
                      {n.title}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      {noteTypeLabel(n.noteType)} · {n.authorName} · {formatDateTime(n.authoredAt)}
                    </Typography>
                  </Box>
                ))}
              </Stack>
            )}
          </SectionCard>
        </Stack>
      </Grid>
      <Grid size={{ xs: 12, lg: 5 }}>
        <Stack spacing={5}>
          <SectionCard
            title="Latest observations"
            subtitle={v ? `${formatDateTime(v.recordedAt)} (${fromNow(v.recordedAt)}) · ${v.recordedBy ?? ''}` : undefined}
            action={<More label="Vitals" onClick={nav('vitals')} />}
          >
            {s.observationOverdue && (
              <Alert severity="warning" icon={<HeartPulse size={18} />} sx={{ mb: 3 }}>
                Observations overdue: {s.hoursSinceLastVitals !== undefined && s.hoursSinceLastVitals !== null ? `${s.hoursSinceLastVitals} h since the last set` : 'none recorded'} (expected
                every {s.expectedIntervalHours} h).
              </Alert>
            )}
            {v ? (
              <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: 'repeat(auto-fill, minmax(110px, 1fr))' }}>
                <Vital label="Temperature" value={v.temperatureC} unit="°C" warn={breach('TEMPERATURE_C')} />
                <Vital label="Pulse" value={v.pulseBpm} unit="bpm" warn={breach('PULSE_BPM')} />
                <Vital label="Resp. rate" value={v.respiratoryRate} unit="/min" warn={breach('RESPIRATORY_RATE')} />
                <Vital
                  label="Blood pressure"
                  value={v.systolicBp !== undefined ? `${v.systolicBp}/${v.diastolicBp}` : undefined}
                  unit="mmHg"
                  warn={breach('SYSTOLIC_BP') ?? breach('DIASTOLIC_BP')}
                />
                <Vital label={v.onOxygen ? 'SpO2 (on O2)' : 'SpO2'} value={v.spo2} unit="%" warn={breach('SPO2')} />
                <Vital label="Pain" value={v.painScore} unit="/10" warn={breach('PAIN_SCORE')} />
                {v.bloodGlucose !== undefined && <Vital label="Glucose" value={v.bloodGlucose} unit="mg/dL" warn={breach('BLOOD_GLUCOSE')} />}
                {v.consciousness && <Vital label="Consciousness" value={humanize(v.consciousness)} />}
              </Box>
            ) : (
              <Empty>No observations recorded.</Empty>
            )}
          </SectionCard>
          <SectionCard title="Open alerts" action={<More label="Alerts" onClick={nav('alerts')} />}>
            {s.openAlerts.length === 0 ? (
              <Empty>No unresolved alerts.</Empty>
            ) : (
              <Stack spacing={2}>
                {s.openAlerts.slice(0, 5).map((a) => (
                  <Stack key={a.id} direction="row" spacing={1.5} alignItems="flex-start">
                    <StatusChip value={a.severity} />
                    <Box sx={{ minWidth: 0 }}>
                      <Typography variant="body2" sx={{ wordBreak: 'break-word' }}>
                        {a.title}
                      </Typography>
                      <Typography variant="caption" color="text.secondary">
                        {humanize(a.status)} · {fromNow(a.raisedAt)}
                      </Typography>
                    </Box>
                  </Stack>
                ))}
              </Stack>
            )}
          </SectionCard>
          <SectionCard title="Pending investigations" action={<More label="Investigations" onClick={nav('investigations')} />}>
            {s.pendingInvestigations.length === 0 ? (
              <Empty>Nothing outstanding.</Empty>
            ) : (
              <Stack spacing={1.5}>
                {s.pendingInvestigations.map((o) => (
                  <Stack key={o.id} direction="row" justifyContent="space-between" spacing={2} alignItems="center">
                    <Box sx={{ minWidth: 0 }}>
                      <Typography variant="body2" noWrap>
                        {o.testName}
                      </Typography>
                      <Typography variant="caption" color="text.secondary">
                        {o.orderNumber} · {formatDateTime(o.orderedAt, 'DD MMM HH:mm')}
                      </Typography>
                    </Box>
                    <Stack direction="row" spacing={1} sx={{ flexShrink: 0 }}>
                      {o.hasCritical && <StatusChip value="CRITICAL" />}
                      <StatusChip value={o.status} />
                    </Stack>
                  </Stack>
                ))}
              </Stack>
            )}
          </SectionCard>
          <SectionCard title="Open care tasks" action={<More label="Tasks" onClick={nav('tasks')} />}>
            {s.openTasks.length === 0 ? (
              <Empty>No open tasks.</Empty>
            ) : (
              <Stack spacing={1.5}>
                {s.openTasks.slice(0, 6).map((t) => (
                  <Stack key={t.id} direction="row" justifyContent="space-between" spacing={2}>
                    <Typography variant="body2" sx={{ minWidth: 0, wordBreak: 'break-word' }}>
                      {t.title}
                    </Typography>
                    <Typography variant="caption" color={t.overdue ? 'error.main' : 'text.secondary'} sx={{ flexShrink: 0 }}>
                      {t.dueAt ? `${t.overdue ? 'Overdue · ' : 'Due '}${formatDateTime(t.dueAt, 'DD MMM HH:mm')}` : 'No due time'}
                    </Typography>
                  </Stack>
                ))}
              </Stack>
            )}
          </SectionCard>
          <SectionCard title="Medical history" action={<More label="Allergies & history" onClick={nav('history')} />}>
            {s.history.filter((h) => h.status !== 'ENTERED_IN_ERROR').length === 0 ? (
              <Empty>No history recorded.</Empty>
            ) : (
              <Stack spacing={1.5}>
                {s.history
                  .filter((h) => h.status !== 'ENTERED_IN_ERROR')
                  .map((h) => (
                    <Box key={h.id}>
                      <Typography variant="body2">
                        {h.conditionName}{' '}
                        <Typography component="span" variant="caption" color="text.secondary">
                          ({humanize(h.category)}
                          {h.onsetDate ? `, since ${formatDate(h.onsetDate, 'YYYY')}` : ''})
                        </Typography>
                      </Typography>
                      {h.details && (
                        <Typography variant="caption" color="text.secondary">
                          {h.details}
                        </Typography>
                      )}
                    </Box>
                  ))}
              </Stack>
            )}
          </SectionCard>
        </Stack>
      </Grid>
    </Grid>
  );
}
