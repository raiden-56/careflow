/**
 * Clinical records, nursing workflows, investigations, medication orders and MAR.
 * Types mirror the backend DTOs (com.careflow.hms.clinical.ClinicalDtos / medication.MedicationDtos).
 */
import { api, Query } from './client';
import type { AdmissionStatus, AdmissionSummary, ISODate, ISODateTime, PageResponse, UUID } from './types';

// ------------------------------------------------------------------ shared

/** Patient + location context attached to list rows. */
export interface PatientRef {
  admissionId: UUID;
  admissionNumber: string;
  patientId: UUID;
  patientName: string;
  mrn: string;
  location?: string;
  wardId?: UUID;
  wardName?: string;
  admissionStatus: AdmissionStatus;
  attendingDoctorId: UUID;
  attendingDoctorName?: string;
  allergies?: string[];
}

// ------------------------------------------------------------------ allergies & history

export type AllergyType = 'DRUG' | 'FOOD' | 'ENVIRONMENTAL' | 'OTHER';
export type AllergySeverity = 'MILD' | 'MODERATE' | 'SEVERE' | 'LIFE_THREATENING';
export type AllergyStatus = 'ACTIVE' | 'INACTIVE' | 'ENTERED_IN_ERROR';

export interface AllergyView {
  id: UUID;
  patientId: UUID;
  allergen: string;
  allergyType: AllergyType;
  reaction?: string;
  severity: AllergySeverity;
  status: AllergyStatus;
  recordedBy?: string;
  recordedAt: ISODateTime;
  statusReason?: string;
  statusChangedBy?: string;
  statusChangedAt?: ISODateTime;
  version: number;
}

export type HistoryCategory = 'MEDICAL' | 'SURGICAL' | 'FAMILY' | 'SOCIAL' | 'MEDICATION';
export interface HistoryView {
  id: UUID;
  patientId: UUID;
  category: HistoryCategory;
  conditionName: string;
  details?: string;
  onsetDate?: ISODate;
  status: 'ACTIVE' | 'RESOLVED' | 'ENTERED_IN_ERROR';
  recordedBy?: string;
  recordedAt: ISODateTime;
  statusReason?: string;
  version: number;
}

// ------------------------------------------------------------------ diagnoses & plans

export type DiagnosisType = 'PROVISIONAL' | 'CONFIRMED' | 'DIFFERENTIAL';
export interface DiagnosisView {
  id: UUID;
  admissionId: UUID;
  icdCode?: string;
  description: string;
  diagnosisType: DiagnosisType;
  primary: boolean;
  status: 'ACTIVE' | 'RESOLVED' | 'ENTERED_IN_ERROR';
  notes?: string;
  recordedBy?: string;
  recordedAt: ISODateTime;
  statusReason?: string;
  statusChangedAt?: ISODateTime;
  version: number;
}

export interface TreatmentPlanView {
  id: UUID;
  admissionId: UUID;
  planVersion: number;
  goals: string;
  planDetails: string;
  monitoring?: string;
  diet?: string;
  activity?: string;
  status: 'ACTIVE' | 'SUPERSEDED' | 'COMPLETED';
  patientSummary?: string;
  releasedToPatient: boolean;
  releasedAt?: ISODateTime;
  createdBy?: string;
  createdAt: ISODateTime;
  supersededById?: UUID;
  revisionReason?: string;
  version: number;
}

export interface TreatmentPlanRequest {
  goals: string;
  planDetails: string;
  monitoring?: string | null;
  diet?: string | null;
  activity?: string | null;
  patientSummary?: string | null;
  revisionReason?: string | null;
  /** Id of the ACTIVE plan this revision is based on (required when a plan exists). */
  basedOnPlanId?: UUID | null;
}

// ------------------------------------------------------------------ notes

export type NoteType =
  | 'ADMISSION_ASSESSMENT'
  | 'PROGRESS'
  | 'DOCTOR_REVIEW'
  | 'NURSING_ASSESSMENT'
  | 'NURSING_OBSERVATION'
  | 'PROCEDURE'
  | 'CONSULTATION'
  | 'EDUCATION';

export const DOCTOR_NOTE_TYPES: NoteType[] = ['ADMISSION_ASSESSMENT', 'PROGRESS', 'DOCTOR_REVIEW', 'PROCEDURE', 'CONSULTATION'];
export const NURSING_NOTE_TYPES: NoteType[] = ['NURSING_ASSESSMENT', 'NURSING_OBSERVATION', 'EDUCATION'];

export interface NoteView {
  id: UUID;
  admissionId: UUID;
  noteType: NoteType;
  title: string;
  subjective?: string;
  objective?: string;
  assessment?: string;
  plan?: string;
  content: string;
  patientSummary?: string;
  releasedToPatient: boolean;
  releasedAt?: ISODateTime;
  status: 'FINAL' | 'AMENDED' | 'ENTERED_IN_ERROR';
  amendsNoteId?: UUID;
  correctionReason?: string;
  authorId: UUID;
  authorName?: string;
  authoredAt: ISODateTime;
  version: number;
}

export interface NoteRequest {
  noteType: NoteType;
  title: string;
  subjective?: string | null;
  objective?: string | null;
  assessment?: string | null;
  plan?: string | null;
  content: string;
  patientSummary?: string | null;
}

// ------------------------------------------------------------------ vitals & trends

export type Consciousness = 'ALERT' | 'VOICE' | 'PAIN' | 'UNRESPONSIVE' | 'CONFUSED';
export type VitalParameter =
  | 'TEMPERATURE_C'
  | 'PULSE_BPM'
  | 'RESPIRATORY_RATE'
  | 'SYSTOLIC_BP'
  | 'DIASTOLIC_BP'
  | 'SPO2'
  | 'PAIN_SCORE'
  | 'BLOOD_GLUCOSE';

export interface VitalsRequest {
  recordedAt?: ISODateTime | null;
  temperatureC?: number | null;
  pulseBpm?: number | null;
  respiratoryRate?: number | null;
  systolicBp?: number | null;
  diastolicBp?: number | null;
  spo2?: number | null;
  onOxygen: boolean;
  painScore?: number | null;
  bloodGlucose?: number | null;
  weightKg?: number | null;
  consciousness?: Consciousness | null;
  notes?: string | null;
}

export interface VitalView {
  id: UUID;
  admissionId: UUID;
  recordedAt: ISODateTime;
  recordedBy?: string;
  temperatureC?: number;
  pulseBpm?: number;
  respiratoryRate?: number;
  systolicBp?: number;
  diastolicBp?: number;
  spo2?: number;
  onOxygen: boolean;
  painScore?: number;
  bloodGlucose?: number;
  weightKg?: number;
  consciousness?: Consciousness;
  notes?: string;
  enteredInError: boolean;
  errorReason?: string;
  /** e.g. "SPO2:WARNING", "PULSE_BPM:CRITICAL" */
  breaches: string[];
}

export interface VitalsRecorded {
  vital: VitalView;
  alertsRaised: AlertView[];
}

export interface TrendPoint {
  t: ISODateTime;
  value: number;
  vitalId: UUID;
  /** WARNING | CRITICAL when outside the configured range */
  breach?: 'WARNING' | 'CRITICAL';
}
export interface ThresholdBand {
  severity: 'WARNING' | 'CRITICAL';
  min?: number;
  max?: number;
  description: string;
}
export interface Crossing {
  t: ISODateTime;
  value: number;
  fromLevel: 'NORMAL' | 'WARNING' | 'CRITICAL';
  toLevel: 'NORMAL' | 'WARNING' | 'CRITICAL';
  direction: 'ABOVE' | 'BELOW' | 'RETURNED_TO_RANGE';
  threshold: string;
}
export interface SeriesStats {
  min?: number;
  max?: number;
  mean?: number;
  first?: number;
  latest?: number;
  count: number;
}
export interface TrendSeries {
  parameter: VitalParameter;
  label: string;
  unit: string;
  points: TrendPoint[];
  bands: ThresholdBand[];
  crossings: Crossing[];
  stats: SeriesStats;
}
export interface Gap {
  from: ISODateTime;
  to: ISODateTime;
  hours: number;
  ongoing: boolean;
}
export interface TrendsView {
  admissionId: UUID;
  from: ISODateTime;
  to: ISODateTime;
  expectedIntervalHours: number;
  series: TrendSeries[];
  gaps: Gap[];
  recordings: number;
  disclaimer: string;
}
export interface CompareRow {
  parameter: VitalParameter;
  label: string;
  unit: string;
  a: SeriesStats;
  b: SeriesStats;
  meanChange?: number;
  observedDirection: 'HIGHER' | 'LOWER' | 'UNCHANGED' | 'INSUFFICIENT_DATA';
}
export interface CompareView {
  admissionId: UUID;
  aFrom: ISODateTime;
  aTo: ISODateTime;
  bFrom: ISODateTime;
  bTo: ISODateTime;
  rows: CompareRow[];
  disclaimer: string;
}

// ------------------------------------------------------------------ alerts

export type AlertType = 'VITAL_THRESHOLD' | 'ESCALATION' | 'MEDICATION' | 'ALLERGY' | 'LAB_CRITICAL' | 'OTHER';
export type AlertSeverity = 'INFO' | 'WARNING' | 'CRITICAL';
export type AlertStatus = 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED';

export interface AlertView {
  id: UUID;
  admissionId: UUID;
  patient?: PatientRef;
  alertType: AlertType;
  severity: AlertSeverity;
  title: string;
  message: string;
  sourceType?: string;
  sourceId?: UUID;
  parameter?: string;
  observedValue?: string;
  thresholdRuleId?: UUID;
  thresholdText?: string;
  status: AlertStatus;
  assignedTo?: UUID;
  assignedToName?: string;
  raisedBy?: string;
  raisedAt: ISODateTime;
  acknowledgedBy?: string;
  acknowledgedAt?: ISODateTime;
  resolvedBy?: string;
  resolvedAt?: ISODateTime;
  resolutionNotes?: string;
  escalationLevel: number;
  lastEscalatedAt?: ISODateTime;
  version: number;
}

export type Urgency = 'ROUTINE' | 'URGENT' | 'EMERGENCY';
export interface EscalationRequest {
  concern: string;
  observations: string;
  urgency: Urgency;
  assignToUserId?: UUID | null;
}

// ------------------------------------------------------------------ investigations

export interface CatalogueItem {
  tariffId: UUID;
  code: string;
  name: string;
  category: 'LAB' | 'IMAGING' | 'PROCEDURE';
  /** Decimal string */
  unitPrice: string;
  description?: string;
}

export type LabPriority = 'ROUTINE' | 'URGENT' | 'STAT';
export type LabStatus = 'ORDERED' | 'COLLECTED' | 'IN_PROGRESS' | 'RESULTED' | 'REVIEWED' | 'CANCELLED';
export type ResultFlag = 'NORMAL' | 'LOW' | 'HIGH' | 'CRITICAL_LOW' | 'CRITICAL_HIGH' | 'ABNORMAL';

export interface LabResultView {
  id: UUID;
  component: string;
  valueText: string;
  valueNumeric?: number;
  unit?: string;
  referenceRange?: string;
  flag: ResultFlag;
  interpretation?: string;
  resultedBy?: string;
  resultedAt: ISODateTime;
  source: 'MANUAL' | 'LIS_ADAPTER';
  reviewedBy?: string;
  reviewedAt?: ISODateTime;
  reviewComment?: string;
  releasedToPatient: boolean;
  releasedAt?: ISODateTime;
  status: 'PRELIMINARY' | 'FINAL' | 'CORRECTED' | 'ENTERED_IN_ERROR';
}

export interface LabOrderView {
  id: UUID;
  admissionId: UUID;
  patient?: PatientRef;
  orderNumber: string;
  tariffId?: UUID;
  testCode: string;
  testName: string;
  category: 'LAB' | 'IMAGING' | 'OTHER';
  specimen?: string;
  priority: LabPriority;
  status: LabStatus;
  clinicalInfo?: string;
  orderedById: UUID;
  orderedBy?: string;
  orderedAt: ISODateTime;
  dueAt?: ISODateTime;
  collectedBy?: string;
  collectedAt?: ISODateTime;
  cancelledBy?: string;
  cancelledAt?: ISODateTime;
  cancelReason?: string;
  chargePosted: boolean;
  hasCritical: boolean;
  hasAbnormal: boolean;
  released: boolean;
  results: LabResultView[];
  version: number;
}

export interface ResultComponentInput {
  component: string;
  valueText: string;
  valueNumeric?: number | null;
  unit?: string | null;
  referenceRange?: string | null;
  flag: ResultFlag;
  interpretation?: string | null;
}

export type InvestigationWorklist = 'PENDING_COLLECTION' | 'AWAITING_RESULT' | 'AWAITING_REVIEW' | 'OUTSTANDING' | 'ALL';

// ------------------------------------------------------------------ procedures

export interface ProcedureView {
  id: UUID;
  admissionId: UUID;
  tariffId?: UUID;
  tariffName?: string;
  name: string;
  status: 'PLANNED' | 'COMPLETED' | 'CANCELLED';
  scheduledAt?: ISODateTime;
  performedAt?: ISODateTime;
  performedBy?: string;
  anaesthesia?: string;
  findings?: string;
  complications?: string;
  notes?: string;
  createdBy?: string;
  createdAt: ISODateTime;
  chargePosted: boolean;
  version: number;
}

// ------------------------------------------------------------------ care tasks

export type TaskType = 'LAB_COLLECTION' | 'WOUND_CARE' | 'MOBILISATION' | 'OBSERVATION' | 'EDUCATION' | 'HYGIENE' | 'DISCHARGE_PREP' | 'TRANSFER' | 'OTHER';
export type TaskPriority = 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT';
export type TaskStatus = 'OPEN' | 'IN_PROGRESS' | 'DONE' | 'CANCELLED';

export interface TaskView {
  id: UUID;
  admissionId: UUID;
  patient?: PatientRef;
  taskType: TaskType;
  title: string;
  description?: string;
  priority: TaskPriority;
  status: TaskStatus;
  dueAt?: ISODateTime;
  overdue: boolean;
  assignedTo?: UUID;
  assignedToName?: string;
  assignedRole?: string;
  sourceType?: string;
  sourceId?: UUID;
  createdBy?: string;
  createdAt: ISODateTime;
  completedBy?: string;
  completedAt?: ISODateTime;
  completionNotes?: string;
  version: number;
}

export interface TaskRequest {
  taskType: TaskType;
  title: string;
  description?: string | null;
  priority?: TaskPriority;
  dueAt?: ISODateTime | null;
  assignedTo?: UUID | null;
}

// ------------------------------------------------------------------ handover

export type Shift = 'MORNING' | 'EVENING' | 'NIGHT';
export type HandoverPriority = 'NORMAL' | 'WATCH' | 'CRITICAL';

export interface HandoverItemView {
  id: UUID;
  admissionId: UUID;
  patient?: PatientRef;
  conditionSummary?: string;
  importantObservations?: string;
  pendingMedications?: string;
  pendingTasks?: string;
  outstandingItems?: string;
  escalations?: string;
  priority: HandoverPriority;
}

export interface HandoverView {
  id: UUID;
  wardId: UUID;
  wardName?: string;
  shiftDate: ISODate;
  shift: Shift;
  fromUserId: UUID;
  fromUserName?: string;
  toUserId?: UUID;
  toUserName?: string;
  generalNotes?: string;
  status: 'DRAFT' | 'SUBMITTED' | 'ACKNOWLEDGED';
  requiresAck: boolean;
  submittedAt?: ISODateTime;
  acknowledgedBy?: string;
  acknowledgedAt?: ISODateTime;
  ackComment?: string;
  createdAt: ISODateTime;
  itemCount: number;
  criticalCount: number;
  /** Only populated on detail calls. */
  items: HandoverItemView[];
  version: number;
}

export interface HandoverItemUpdate {
  id: UUID;
  conditionSummary?: string | null;
  importantObservations?: string | null;
  pendingMedications?: string | null;
  pendingTasks?: string | null;
  outstandingItems?: string | null;
  escalations?: string | null;
  priority?: HandoverPriority;
}

// ------------------------------------------------------------------ drafts

export type DraftType = 'PROGRESS_SUMMARY' | 'HANDOVER';
export interface DraftSource {
  type: string;
  id?: string;
  label?: string;
  at?: string;
}
export interface DraftRef {
  index: number;
  statement: string;
  sources: DraftSource[];
}
export interface DraftView {
  id: UUID;
  admissionId: UUID;
  draftType: DraftType;
  generator: string;
  content: string;
  sourceRefs: DraftRef[];
  status: 'DRAFT' | 'ACCEPTED' | 'REJECTED';
  createdBy?: string;
  createdAt: ISODateTime;
  reviewedBy?: string;
  reviewedAt?: ISODateTime;
  publishedNoteId?: UUID;
  disclaimer: string;
}

// ------------------------------------------------------------------ medication orders & MAR

export type Route = 'ORAL' | 'IV' | 'IM' | 'SC' | 'INHALED' | 'TOPICAL' | 'SUBLINGUAL' | 'RECTAL' | 'NASAL' | 'OPHTHALMIC' | 'OTIC';
export const ROUTES: Route[] = ['ORAL', 'IV', 'IM', 'SC', 'INHALED', 'TOPICAL', 'SUBLINGUAL', 'RECTAL', 'NASAL', 'OPHTHALMIC', 'OTIC'];
export type Frequency = 'ONCE' | 'STAT' | 'OD' | 'BD' | 'TDS' | 'QID' | 'Q4H' | 'Q6H' | 'Q8H' | 'Q12H' | 'HS' | 'PRN' | 'WEEKLY';
export const FREQUENCIES: Frequency[] = ['OD', 'BD', 'TDS', 'QID', 'Q4H', 'Q6H', 'Q8H', 'Q12H', 'HS', 'WEEKLY', 'PRN', 'STAT', 'ONCE'];
/** Default administration times per frequency (mirrors MedicationOrder.Frequency.defaultTimes). */
export const DEFAULT_TIMES: Record<Frequency, string> = {
  OD: '09:00',
  BD: '09:00,21:00',
  TDS: '08:00,14:00,20:00',
  QID: '06:00,12:00,18:00,22:00',
  Q4H: '02:00,06:00,10:00,14:00,18:00,22:00',
  Q6H: '00:00,06:00,12:00,18:00',
  Q8H: '06:00,14:00,22:00',
  Q12H: '08:00,20:00',
  HS: '22:00',
  WEEKLY: '09:00',
  ONCE: '',
  STAT: '',
  PRN: '',
};
export type OrderStatus = 'ACTIVE' | 'ON_HOLD' | 'DISCONTINUED' | 'COMPLETED';

export interface MedicationOption {
  id: UUID;
  code: string;
  displayName: string;
  genericName: string;
  brandName?: string;
  form: string;
  strength: string;
  defaultRoute: Route;
  drugClass?: string;
  allergenGroup?: string;
  highAlert: boolean;
  controlled: boolean;
  /** Documented allergies of the patient that match this medication (when admissionId was given). */
  allergyConflicts: string[];
}

export interface OrderRequest {
  medicationId: UUID;
  dose: string;
  doseUnit: string;
  route: Route;
  frequency: Frequency;
  scheduleTimes?: string | null;
  startAt?: ISODateTime | null;
  durationDays?: number | null;
  endAt?: ISODateTime | null;
  indication?: string | null;
  instructions?: string | null;
  patientInstructions?: string | null;
  dischargeMedication: boolean;
  allergyOverrideReason?: string | null;
}

export interface OrderView {
  id: UUID;
  admissionId: UUID;
  patient?: PatientRef;
  medicationId: UUID;
  medicationName: string;
  genericName?: string;
  strength?: string;
  form?: string;
  highAlert: boolean;
  controlled: boolean;
  dose: string;
  doseUnit: string;
  route: Route;
  frequency: Frequency;
  scheduleTimes?: string;
  startAt: ISODateTime;
  endAt?: ISODateTime;
  durationDays?: number;
  indication?: string;
  instructions?: string;
  patientInstructions?: string;
  dischargeMedication: boolean;
  status: OrderStatus;
  allergyOverrideReason?: string;
  prescribedById?: UUID;
  prescribedBy?: string;
  prescribedAt: ISODateTime;
  verifiedBy?: string;
  verifiedAt?: ISODateTime;
  discontinuedBy?: string;
  discontinuedAt?: ISODateTime;
  discontinueReason?: string;
  holdReason?: string;
  heldAt?: ISODateTime;
  replacesOrderId?: UUID;
  releasedToPatient: boolean;
  nextDoseAt?: ISODateTime;
  lastAdministeredAt?: ISODateTime;
  version?: number;
}

export type AdministrationStatus = 'SCHEDULED' | 'ADMINISTERED' | 'DELAYED' | 'REFUSED' | 'MISSED' | 'HELD' | 'CANCELLED';
/** SCHEDULED doses are displayed as SCHEDULED (upcoming), DUE or OVERDUE. */
export type DoseDisplayStatus = AdministrationStatus | 'DUE' | 'OVERDUE';
export type FollowUpStatus = 'NONE' | 'OPEN' | 'IN_PROGRESS' | 'RESOLVED';

export interface DoseView {
  id: UUID;
  orderId: UUID;
  admissionId: UUID;
  patient?: PatientRef;
  medicationName: string;
  strength?: string;
  dose?: string;
  doseUnit?: string;
  route?: Route;
  frequency?: Frequency;
  instructions?: string;
  highAlert: boolean;
  controlled: boolean;
  orderStatus?: OrderStatus;
  scheduledAt: ISODateTime;
  status: AdministrationStatus;
  displayStatus: DoseDisplayStatus;
  administeredAt?: ISODateTime;
  doseGiven?: string;
  routeGiven?: string;
  site?: string;
  reason?: string;
  notes?: string;
  recordedBy?: string;
  recordedAt?: ISODateTime;
  witness?: string;
  overdueFlaggedAt?: ISODateTime;
  followUpStatus: FollowUpStatus;
  followUpNotes?: string;
  followUpBy?: string;
  followUpAt?: ISODateTime;
  responsibleTeam?: string;
  minutesLate: number;
  version: number;
}

export interface RoundGroup {
  slot: ISODateTime;
  doses: DoseView[];
}
export interface RoundsView {
  generatedAt: ISODateTime;
  dueWindowMinutes: number;
  graceMinutes: number;
  nurseMayHoldDose: boolean;
  due: number;
  overdue: number;
  upcoming: number;
  groups: RoundGroup[];
  prnOrders: OrderView[];
}

export interface AdministerRequest {
  administeredAt?: ISODateTime | null;
  doseGiven?: string | null;
  routeGiven?: Route | null;
  site?: string | null;
  notes?: string | null;
  witnessUserId?: UUID | null;
  version?: number;
}
export type ExceptionStatus = 'DELAYED' | 'REFUSED' | 'MISSED' | 'HELD';
export interface ExceptionRequest {
  status: ExceptionStatus;
  reason: string;
  notes?: string | null;
  version?: number;
}
export interface PrnDoseRequest {
  administeredAt?: ISODateTime | null;
  doseGiven: string;
  routeGiven?: Route | null;
  site?: string | null;
  reason: string;
  notes?: string | null;
  witnessUserId?: UUID | null;
}

// ------------------------------------------------------------------ summary, dashboards, reports, portal

export interface ClinicalSummary {
  admission: AdmissionSummary;
  admissionReason: string;
  allergies: AllergyView[];
  history: HistoryView[];
  diagnoses: DiagnosisView[];
  activePlan?: TreatmentPlanView;
  activeMedications: OrderView[];
  latestVitals?: VitalView;
  observationOverdue: boolean;
  hoursSinceLastVitals?: number;
  expectedIntervalHours: number;
  pendingInvestigations: LabOrderView[];
  openAlerts: AlertView[];
  openTasks: TaskView[];
  recentNotes: NoteView[];
}

export interface PatientRow {
  patient: PatientRef;
  openAlerts: number;
  criticalAlerts: number;
  observationOverdue: boolean;
  lastVitalsAt?: ISODateTime;
  dueDoses: number;
  overdueDoses: number;
  openTasks: number;
  pendingResults: number;
  expectedDischargeToday: boolean;
  expectedDischargeDate?: ISODate;
  latestNews?: string;
  admittedAt: ISODateTime;
  lengthOfStayDays: number;
  primaryDiagnosis?: string;
}

export interface DoctorDashboard {
  myPatients: number;
  criticalAlerts: number;
  openAlerts: number;
  resultsToReview: number;
  pendingInvestigations: number;
  expectedDischargesToday: number;
  newAdmissions24h: number;
  activeOrders: number;
  documentationGaps: number;
  criticalAlertList: AlertView[];
  resultsAwaitingReview: LabOrderView[];
  patients: PatientRow[];
}

export interface NurseDashboard {
  myPatients: number;
  dosesDue: number;
  dosesOverdue: number;
  openExceptions: number;
  tasksDue: number;
  tasksOverdue: number;
  openAlerts: number;
  observationsOverdue: number;
  pendingCollections: number;
  handoversToAcknowledge: number;
  expectedDischargesToday: number;
  patients: PatientRow[];
  upcomingTasks: TaskView[];
  alerts: AlertView[];
}

export interface DocumentationRow {
  patient: PatientRef;
  admissionAssessment: boolean;
  lastProgressNoteAt?: ISODateTime;
  hoursSinceProgressNote?: number;
  activePlan: boolean;
  primaryDiagnosis: boolean;
  lastVitalsAt?: ISODateTime;
  vitalsOverdue: boolean;
  completenessPercent: number;
  gaps: string[];
}
export interface DocumentationReport {
  admissions: number;
  fullyDocumented: number;
  averageCompleteness: number;
  rows: DocumentationRow[];
}
export interface OutstandingRow {
  order: LabOrderView;
  ageHours: number;
  overdue: boolean;
  reason: string;
}
export interface OutstandingReport {
  total: number;
  awaitingCollection: number;
  awaitingResult: number;
  awaitingReview: number;
  overdue: number;
  rows: OutstandingRow[];
}
export interface CountRow {
  key: string;
  count: number;
}
export interface AlertHistoryReport {
  days: number;
  total: number;
  critical: number;
  resolved: number;
  medianMinutesToAcknowledge?: number;
  byType: CountRow[];
  bySeverity: CountRow[];
  byDay: CountRow[];
  recent: AlertView[];
}

export interface PortalNote {
  id: UUID;
  noteType: NoteType;
  title: string;
  patientSummary: string;
  authorName?: string;
  authoredAt: ISODateTime;
  releasedAt?: ISODateTime;
  admissionNumber?: string;
}
export interface PortalResult {
  orderId: UUID;
  testName: string;
  category: string;
  orderedAt: ISODateTime;
  resultedAt?: ISODateTime;
  reviewComment?: string;
  admissionNumber?: string;
  components: LabResultView[];
}
export interface PortalPlan {
  id: UUID;
  planVersion: number;
  patientSummary: string;
  status: string;
  createdAt: ISODateTime;
  releasedAt?: ISODateTime;
  createdBy?: string;
  admissionNumber?: string;
}
export interface PortalRecords {
  patientId: UUID;
  notes: PortalNote[];
  results: PortalResult[];
  plans: PortalPlan[];
}

// ------------------------------------------------------------------ query keys

/** Hierarchical query keys. Invalidate ['clinical', admissionId] after any change to an admission's record. */
export const ck = {
  admission: (admissionId: UUID) => ['clinical', admissionId] as const,
  summary: (admissionId: UUID) => ['clinical', admissionId, 'summary'] as const,
  allergies: (patientId: UUID) => ['clinical-patient', patientId, 'allergies'] as const,
  history: (patientId: UUID) => ['clinical-patient', patientId, 'history'] as const,
  diagnoses: (admissionId: UUID) => ['clinical', admissionId, 'diagnoses'] as const,
  plans: (admissionId: UUID) => ['clinical', admissionId, 'plans'] as const,
  notes: (admissionId: UUID, params?: Query) => ['clinical', admissionId, 'notes', params ?? {}] as const,
  vitals: (admissionId: UUID, params?: Query) => ['clinical', admissionId, 'vitals', params ?? {}] as const,
  trends: (admissionId: UUID, params?: Query) => ['clinical', admissionId, 'trends', params ?? {}] as const,
  compare: (admissionId: UUID, params?: Query) => ['clinical', admissionId, 'compare', params ?? {}] as const,
  alerts: (admissionId: UUID) => ['clinical', admissionId, 'alerts'] as const,
  investigations: (admissionId: UUID) => ['clinical', admissionId, 'investigations'] as const,
  procedures: (admissionId: UUID) => ['clinical', admissionId, 'procedures'] as const,
  tasks: (admissionId: UUID) => ['clinical', admissionId, 'tasks'] as const,
  orders: (admissionId: UUID, params?: Query) => ['clinical', admissionId, 'orders', params ?? {}] as const,
  mar: (admissionId: UUID, params?: Query) => ['clinical', admissionId, 'mar', params ?? {}] as const,
  drafts: (admissionId: UUID) => ['clinical', admissionId, 'drafts'] as const,
  // cross-patient worklists
  myPatients: ['clinical-lists', 'my-patients'] as const,
  myAlerts: (params?: Query) => ['clinical-lists', 'alerts', params ?? {}] as const,
  myNotes: (params?: Query) => ['clinical-lists', 'notes', params ?? {}] as const,
  worklist: (params?: Query) => ['clinical-lists', 'investigations', params ?? {}] as const,
  myOrders: (params?: Query) => ['clinical-lists', 'orders', params ?? {}] as const,
  rounds: (params?: Query) => ['clinical-lists', 'rounds', params ?? {}] as const,
  marHistory: (params?: Query) => ['clinical-lists', 'mar-history', params ?? {}] as const,
  exceptions: (params?: Query) => ['clinical-lists', 'exceptions', params ?? {}] as const,
  taskBoard: (params?: Query) => ['clinical-lists', 'tasks', params ?? {}] as const,
  handovers: (params?: Query) => ['clinical-lists', 'handovers', params ?? {}] as const,
  handover: (id: UUID) => ['clinical-lists', 'handover', id] as const,
  receivingNurses: (wardId: UUID) => ['clinical-lists', 'receiving-nurses', wardId] as const,
  catalogue: (params?: Query) => ['clinical-catalogue', params ?? {}] as const,
  medSearch: (params?: Query) => ['clinical-catalogue', 'medications', params ?? {}] as const,
  doctorDashboard: ['clinical-lists', 'dashboard', 'doctor'] as const,
  nurseDashboard: ['clinical-lists', 'dashboard', 'nurse'] as const,
  reports: (name: string, params?: Query) => ['clinical-lists', 'reports', name, params ?? {}] as const,
  portalRecords: (patientId: UUID) => ['portal-clinical', patientId, 'records'] as const,
  portalMedications: (patientId: UUID) => ['portal-clinical', patientId, 'medications'] as const,
};

// ------------------------------------------------------------------ API

export const clinicalApi = {
  summary: (admissionId: UUID) => api.get<ClinicalSummary>(`/api/admissions/${admissionId}/clinical-summary`),
  myPatients: () => api.get<PatientRow[]>('/api/clinical/my-patients'),

  // allergies & history (patient level)
  allergies: (patientId: UUID) => api.get<AllergyView[]>(`/api/patients/${patientId}/allergies`),
  addAllergy: (patientId: UUID, body: { allergen: string; allergyType: AllergyType; reaction?: string | null; severity: AllergySeverity }) =>
    api.post<AllergyView>(`/api/patients/${patientId}/allergies`, body),
  changeAllergyStatus: (id: UUID, status: AllergyStatus, reason?: string) =>
    api.post<AllergyView>(`/api/allergies/${id}/status`, { status, reason }),
  history: (patientId: UUID) => api.get<HistoryView[]>(`/api/patients/${patientId}/medical-history`),
  addHistory: (patientId: UUID, body: { category: HistoryCategory; conditionName: string; details?: string | null; onsetDate?: ISODate | null }) =>
    api.post<HistoryView>(`/api/patients/${patientId}/medical-history`, body),
  changeHistoryStatus: (id: UUID, status: HistoryView['status'], reason?: string) =>
    api.post<HistoryView>(`/api/medical-history/${id}/status`, { status, reason }),

  // diagnoses
  diagnoses: (admissionId: UUID) => api.get<DiagnosisView[]>(`/api/admissions/${admissionId}/diagnoses`),
  addDiagnosis: (
    admissionId: UUID,
    body: { description: string; icdCode?: string | null; diagnosisType: DiagnosisType; primary: boolean; notes?: string | null },
  ) => api.post<DiagnosisView>(`/api/admissions/${admissionId}/diagnoses`, body),
  resolveDiagnosis: (id: UUID, notes?: string) => api.post<DiagnosisView>(`/api/diagnoses/${id}/resolve`, { notes }),
  diagnosisInError: (id: UUID, reason: string) => api.post<DiagnosisView>(`/api/diagnoses/${id}/entered-in-error`, { reason }),

  // treatment plans
  plans: (admissionId: UUID) => api.get<TreatmentPlanView[]>(`/api/admissions/${admissionId}/treatment-plans`),
  revisePlan: (admissionId: UUID, body: TreatmentPlanRequest) =>
    api.post<TreatmentPlanView>(`/api/admissions/${admissionId}/treatment-plans`, body),
  releasePlan: (id: UUID, patientSummary?: string) => api.post<TreatmentPlanView>(`/api/treatment-plans/${id}/release`, { patientSummary }),

  // notes
  notes: (admissionId: UUID, params?: { type?: NoteType[]; page?: number; size?: number }) =>
    api.get<PageResponse<NoteView>>(`/api/admissions/${admissionId}/notes`, params),
  myNotes: (params?: { type?: NoteType[]; page?: number; size?: number }) => api.get<PageResponse<NoteView>>('/api/clinical/notes', params),
  addNote: (admissionId: UUID, body: NoteRequest) => api.post<NoteView>(`/api/admissions/${admissionId}/notes`, body),
  amendNote: (
    id: UUID,
    body: { title: string; subjective?: string | null; objective?: string | null; assessment?: string | null; plan?: string | null; content: string; reason: string },
  ) => api.post<NoteView>(`/api/notes/${id}/amend`, body),
  noteInError: (id: UUID, reason: string) => api.post<NoteView>(`/api/notes/${id}/entered-in-error`, { reason }),
  releaseNote: (id: UUID, patientSummary?: string) => api.post<NoteView>(`/api/notes/${id}/release`, { patientSummary }),

  // vitals & trends
  recordVitals: (admissionId: UUID, body: VitalsRequest) => api.post<VitalsRecorded>(`/api/admissions/${admissionId}/vitals`, body),
  vitals: (admissionId: UUID, params?: { from?: string; to?: string }) => api.get<VitalView[]>(`/api/admissions/${admissionId}/vitals`, params),
  vitalInError: (id: UUID, reason: string) => api.post<VitalView>(`/api/vitals/${id}/entered-in-error`, { reason }),
  trends: (admissionId: UUID, params?: { from?: string; to?: string; parameter?: VitalParameter[] }) =>
    api.get<TrendsView>(`/api/admissions/${admissionId}/vitals/trends`, params),
  compare: (admissionId: UUID, params: { aFrom: string; aTo: string; bFrom: string; bTo: string }) =>
    api.get<CompareView>(`/api/admissions/${admissionId}/vitals/compare`, params),

  // alerts & escalation
  admissionAlerts: (admissionId: UUID) => api.get<AlertView[]>(`/api/admissions/${admissionId}/alerts`),
  myAlerts: (params?: { status?: AlertStatus[]; severity?: AlertSeverity; type?: AlertType; page?: number; size?: number }) =>
    api.get<PageResponse<AlertView>>('/api/alerts', params),
  acknowledgeAlert: (id: UUID) => api.post<AlertView>(`/api/alerts/${id}/acknowledge`),
  resolveAlert: (id: UUID, notes: string) => api.post<AlertView>(`/api/alerts/${id}/resolve`, { notes }),
  escalate: (admissionId: UUID, body: EscalationRequest) => api.post<AlertView>(`/api/admissions/${admissionId}/escalations`, body),

  // investigations
  catalogue: (params?: { category?: 'LAB' | 'IMAGING' | 'PROCEDURE'; q?: string }) =>
    api.get<CatalogueItem[]>('/api/investigations/catalogue', params),
  investigations: (admissionId: UUID) => api.get<LabOrderView[]>(`/api/admissions/${admissionId}/investigations`),
  orderInvestigation: (
    admissionId: UUID,
    body: { tariffId: UUID; priority?: LabPriority; clinicalInfo?: string | null; specimen?: string | null; dueAt?: string | null },
  ) => api.post<LabOrderView>(`/api/admissions/${admissionId}/investigations`, body),
  worklist: (params: { view: InvestigationWorklist; page?: number; size?: number }) =>
    api.get<PageResponse<LabOrderView>>('/api/investigations/worklist', params),
  investigation: (id: UUID) => api.get<LabOrderView>(`/api/investigations/${id}`),
  collect: (id: UUID, body?: { collectedAt?: string | null; specimen?: string | null; notes?: string | null }) =>
    api.post<LabOrderView>(`/api/investigations/${id}/collect`, body ?? {}),
  cancelInvestigation: (id: UUID, reason: string) => api.post<LabOrderView>(`/api/investigations/${id}/cancel`, { reason }),
  enterResults: (id: UUID, body: { components: ResultComponentInput[]; status?: 'PRELIMINARY' | 'FINAL'; resultedAt?: string | null }) =>
    api.post<LabOrderView>(`/api/investigations/${id}/results`, body),
  reviewResults: (id: UUID, comment?: string) => api.post<LabOrderView>(`/api/investigations/${id}/review`, { comment }),
  releaseResults: (id: UUID) => api.post<LabOrderView>(`/api/investigations/${id}/release`),

  // procedures
  procedures: (admissionId: UUID) => api.get<ProcedureView[]>(`/api/admissions/${admissionId}/procedures`),
  planProcedure: (admissionId: UUID, body: { name: string; tariffId?: UUID | null; scheduledAt?: string | null; notes?: string | null }) =>
    api.post<ProcedureView>(`/api/admissions/${admissionId}/procedures`, body),
  completeProcedure: (
    id: UUID,
    body: { performedAt?: string | null; performedById?: UUID | null; anaesthesia?: string | null; findings?: string | null; complications?: string | null; notes?: string | null },
  ) => api.post<ProcedureView>(`/api/procedures/${id}/complete`, body),
  cancelProcedure: (id: UUID, reason: string) => api.post<ProcedureView>(`/api/procedures/${id}/cancel`, { reason }),

  // care tasks
  tasks: (admissionId: UUID) => api.get<TaskView[]>(`/api/admissions/${admissionId}/tasks`),
  createTask: (admissionId: UUID, body: TaskRequest) => api.post<TaskView>(`/api/admissions/${admissionId}/tasks`, body),
  taskBoard: (params?: { wardId?: UUID; mine?: boolean; status?: TaskStatus[] }) => api.get<TaskView[]>('/api/tasks/board', params),
  taskStatus: (id: UUID, status: TaskStatus, notes?: string) => api.post<TaskView>(`/api/tasks/${id}/status`, { status, notes }),
  completeTask: (id: UUID, notes?: string) => api.post<TaskView>(`/api/tasks/${id}/complete`, { notes }),
  assignTask: (id: UUID, userId: UUID | null) => api.post<TaskView>(`/api/tasks/${id}/assign`, { userId }),

  // handover
  handovers: (params?: { wardId?: UUID; toMe?: boolean; page?: number; size?: number }) =>
    api.get<PageResponse<HandoverView>>('/api/handovers', params),
  handover: (id: UUID) => api.get<HandoverView>(`/api/handovers/${id}`),
  createHandover: (body: { wardId: UUID; shiftDate: ISODate; shift: Shift }) => api.post<HandoverView>('/api/handovers', body),
  updateHandover: (id: UUID, body: { generalNotes?: string | null; toUserId?: UUID | null; items?: HandoverItemUpdate[]; version: number }) =>
    api.put<HandoverView>(`/api/handovers/${id}`, body),
  refreshHandoverItem: (id: UUID, itemId: UUID) => api.post<HandoverView>(`/api/handovers/${id}/items/${itemId}/refresh`),
  submitHandover: (id: UUID, toUserId: UUID) => api.post<HandoverView>(`/api/handovers/${id}/submit`, { toUserId }),
  acknowledgeHandover: (id: UUID, comment?: string) => api.post<HandoverView>(`/api/handovers/${id}/acknowledge`, { comment }),
  receivingNurses: (wardId: UUID) => api.get<Array<{ id: UUID; fullName: string }>>('/api/handovers/receiving-nurses', { wardId }),

  // drafts (rule-based, deterministic)
  drafts: (admissionId: UUID) => api.get<DraftView[]>(`/api/admissions/${admissionId}/drafts`),
  generateDraft: (admissionId: UUID, draftType: DraftType) => api.post<DraftView>(`/api/admissions/${admissionId}/drafts`, { draftType }),
  acceptDraft: (id: UUID, body?: { title?: string; editedContent?: string }) => api.post<DraftView>(`/api/drafts/${id}/accept`, body ?? {}),
  rejectDraft: (id: UUID, reason: string) => api.post<DraftView>(`/api/drafts/${id}/reject`, { reason }),

  // dashboards & reports
  doctorDashboard: () => api.get<DoctorDashboard>('/api/dashboard/doctor'),
  nurseDashboard: () => api.get<NurseDashboard>('/api/dashboard/nurse'),
  documentationReport: () => api.get<DocumentationReport>('/api/reports/clinical/documentation'),
  outstandingReport: () => api.get<OutstandingReport>('/api/reports/clinical/outstanding-investigations'),
  alertHistoryReport: (days = 7) => api.get<AlertHistoryReport>('/api/reports/clinical/alerts', { days }),

  // portal (released content only)
  portalRecords: (patientId: UUID) => api.get<PortalRecords>(`/api/portal/patients/${patientId}/records`),
  portalMedications: (patientId: UUID) => api.get<OrderView[]>(`/api/portal/patients/${patientId}/medications`),
};

export const medicationApi = {
  search: (params: { q?: string; admissionId?: UUID }) => api.get<MedicationOption[]>('/api/prescribing/medications', params),
  orders: (admissionId: UUID, params?: { status?: OrderStatus[] }) =>
    api.get<OrderView[]>(`/api/admissions/${admissionId}/medication-orders`, params),
  myOrders: (params?: { status?: OrderStatus[]; page?: number; size?: number }) => api.get<PageResponse<OrderView>>('/api/medication-orders/my', params),
  order: (id: UUID) => api.get<OrderView>(`/api/medication-orders/${id}`),
  prescribe: (admissionId: UUID, body: OrderRequest) => api.post<OrderView>(`/api/admissions/${admissionId}/medication-orders`, body),
  modify: (id: UUID, order: OrderRequest, reason: string) => api.post<OrderView>(`/api/medication-orders/${id}/modify`, { order, reason }),
  hold: (id: UUID, reason: string) => api.post<OrderView>(`/api/medication-orders/${id}/hold`, { reason }),
  resume: (id: UUID) => api.post<OrderView>(`/api/medication-orders/${id}/resume`),
  discontinue: (id: UUID, reason: string) => api.post<OrderView>(`/api/medication-orders/${id}/discontinue`, { reason }),
  release: (id: UUID, patientInstructions?: string) => api.post<OrderView>(`/api/medication-orders/${id}/release`, { patientInstructions }),

  rounds: (params?: { hours?: number; wardId?: UUID }) => api.get<RoundsView>('/api/mar/rounds', params),
  admissionMar: (admissionId: UUID, params?: { from?: string; to?: string }) => api.get<DoseView[]>(`/api/admissions/${admissionId}/mar`, params),
  history: (params?: { admissionId?: UUID; status?: AdministrationStatus[]; from?: string; to?: string; page?: number; size?: number }) =>
    api.get<PageResponse<DoseView>>('/api/mar/history', params),
  administer: (id: UUID, body: AdministerRequest) => api.post<DoseView>(`/api/mar/${id}/administer`, body),
  recordException: (id: UUID, body: ExceptionRequest) => api.post<DoseView>(`/api/mar/${id}/exception`, body),
  prnDose: (orderId: UUID, body: PrnDoseRequest) => api.post<DoseView>(`/api/medication-orders/${orderId}/prn-doses`, body),
  exceptions: (params?: { followUp?: FollowUpStatus[]; page?: number; size?: number }) => api.get<PageResponse<DoseView>>('/api/mar/exceptions', params),
  followUp: (id: UUID, status: Exclude<FollowUpStatus, 'NONE'>, notes?: string) => api.post<DoseView>(`/api/mar/${id}/follow-up`, { status, notes }),
};

/** Human label for a note type. */
export function noteTypeLabel(t: NoteType): string {
  return {
    ADMISSION_ASSESSMENT: 'Admission assessment',
    PROGRESS: 'Progress note',
    DOCTOR_REVIEW: 'Doctor review',
    NURSING_ASSESSMENT: 'Nursing assessment',
    NURSING_OBSERVATION: 'Nursing observation',
    PROCEDURE: 'Procedure note',
    CONSULTATION: 'Consultation',
    EDUCATION: 'Patient education',
  }[t];
}

/** "1 g IV BD" style order summary. */
export function orderSig(o: Pick<OrderView, 'dose' | 'doseUnit' | 'route' | 'frequency'>): string {
  return `${o.dose} ${o.doseUnit} · ${o.route} · ${o.frequency}`;
}
