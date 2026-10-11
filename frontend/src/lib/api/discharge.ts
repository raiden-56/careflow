import { api } from './client';
import type { AdmissionStatus, AdmissionSummary, Decimal, ISODateTime, PatientBrief, Role, UUID } from './types';

/** Discharge workflow, blockers, summaries, follow-ups and the patient-portal discharge view. */

export type WorkflowStatus = 'IN_PROGRESS' | 'READY' | 'COMPLETED' | 'CANCELLED';
export type StepStatus = 'NOT_STARTED' | 'IN_PROGRESS' | 'BLOCKED' | 'COMPLETED' | 'NOT_APPLICABLE';
export type StepPriority = 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT';
export type StepCategory = 'CLINICAL' | 'NURSING' | 'PHARMACY' | 'ADMINISTRATIVE' | 'FINANCIAL' | 'PATIENT';
export type ClinicalDecision = 'AUTHORIZED' | 'REVOKED';
export type FinancialClearance = 'CLEARED' | 'EXCEPTION';
export type Disposition = 'HOME' | 'TRANSFERRED' | 'LAMA' | 'DECEASED' | 'ABSCONDED' | 'OTHER';
export type FollowUpType = 'APPOINTMENT' | 'RESULT_REVIEW' | 'INSTRUCTION' | 'TASK' | 'TELECONSULT';
export type FollowUpStatus = 'PLANNED' | 'SCHEDULED' | 'COMPLETED' | 'MISSED' | 'CANCELLED';
export type SummaryStatus = 'DRAFT' | 'FINAL' | 'AMENDED';

export interface StepView {
  id: UUID;
  code: string;
  order: number;
  name: string;
  description?: string;
  category: StepCategory;
  responsibleRole: Role;
  required: boolean;
  autoCheck?: string;
  status: StepStatus;
  priority: StepPriority;
  assignedToId?: UUID;
  assignedToName?: string;
  blockerReason?: string;
  nextAction?: string;
  notes?: string;
  notApplicableReason?: string;
  updatedByName?: string;
  updatedAt: ISODateTime;
  completedByName?: string;
  completedAt?: ISODateTime;
  systemCompleted: boolean;
  terminal: boolean;
  check?: { met: boolean; explanation: string };
  canUpdate: boolean;
  allowedStatuses: StepStatus[];
  canMarkNotApplicable: boolean;
  version: number;
}

export interface BlockerView {
  stepId: UUID;
  code: string;
  title: string;
  category: StepCategory;
  ownerRole: Role;
  priority: StepPriority;
  explanation: string;
  nextAction: string;
  automatic: boolean;
  stepStatus: StepStatus;
  since: ISODateTime;
}

export interface ClinicalPanel {
  decision?: ClinicalDecision;
  decidedByName?: string;
  decidedAt?: ISODateTime;
  notes?: string;
  notesVisible: boolean;
}

export interface FinancialPanel {
  clearance?: FinancialClearance;
  clearedByName?: string;
  clearedAt?: ISODateTime;
  exceptionReason?: string;
  balanceAtClearance?: Decimal;
  invoiceId?: UUID;
  invoiceNumber?: string;
  invoiceStatus?: 'OPEN' | 'FINALIZED' | 'CANCELLED';
  currentBalance?: Decimal;
  paymentStatus?: string;
  amountsVisible: boolean;
  requiredForDischarge: boolean;
  exceptionAllowed: boolean;
}

export interface Progress {
  completed: number;
  total: number;
  percent: number;
}

export interface WorkflowView {
  id: UUID;
  admissionId: UUID;
  status: WorkflowStatus;
  plannedDischargeAt?: ISODateTime;
  initiatedByName?: string;
  initiatedAt: ISODateTime;
  stage: string;
  stageLabel: string;
  ageHours: number;
  progress: Progress;
  steps: StepView[];
  blockers: BlockerView[];
  clinical: ClinicalPanel;
  financial: FinancialPanel;
  disposition?: Disposition;
  actualDepartureAt?: ISODateTime;
  completedByName?: string;
  completedAt?: ISODateTime;
  cancelledReason?: string;
  readiness: { canComplete: boolean; outstanding: string[] };
  version: number;
}

export interface DischargeActions {
  canInitiate: boolean;
  canCancel: boolean;
  canEditPlan: boolean;
  canAuthorize: boolean;
  canClearFinancially: boolean;
  canComplete: boolean;
  canWriteSummary: boolean;
  canManageFollowUps: boolean;
  initiateBlockedReason?: string;
}

export interface DischargeDetail {
  admission: AdmissionSummary;
  workflow?: WorkflowView;
  history: { id: UUID; status: WorkflowStatus; initiatedAt: ISODateTime; initiatedByName?: string; completedAt?: ISODateTime; cancelledReason?: string }[];
  actions: DischargeActions;
  bed?: { bedId: UUID; bedLabel?: string; status: string; statusChangedAt: ISODateTime; released: boolean };
  summary?: { id: UUID; status: SummaryStatus; released: boolean; finalizedByName?: string; finalizedAt?: ISODateTime };
  followUpCount: number;
  clinicalAccess: boolean;
  financialAccess: boolean;
}

export interface WorkflowListItem {
  workflowId: UUID;
  admissionId: UUID;
  admissionNumber: string;
  patient: PatientBrief;
  wardName?: string;
  bedLabel?: string;
  departmentName?: string;
  attendingDoctorName?: string;
  status: WorkflowStatus;
  stage: string;
  stageLabel: string;
  ageHours: number;
  initiatedAt: ISODateTime;
  plannedDischargeAt?: ISODateTime;
  blockerCount: number;
  blockedSteps: number;
  urgentBlockers: number;
  progress: Progress;
  clinicalDecision?: ClinicalDecision;
  financialClearance?: FinancialClearance;
  topBlockers: string[];
  completedAt?: ISODateTime;
  disposition?: Disposition;
}

export interface Worklist {
  active: { admission: AdmissionSummary; workflow?: WorkflowListItem }[];
  recentlyCompleted: WorkflowListItem[];
}

export interface FinancialQueueItem {
  workflow: WorkflowListItem;
  invoiceId?: UUID;
  invoiceNumber?: string;
  invoiceStatus?: 'OPEN' | 'FINALIZED' | 'CANCELLED';
  balance?: Decimal;
  paymentStatus?: string;
  clearance?: FinancialClearance;
  clearedByName?: string;
  clearedAt?: ISODateTime;
  exceptionReason?: string;
  balanceAtClearance?: Decimal;
}

export interface DelayReport {
  from: ISODateTime;
  to: ISODateTime;
  workflows: number;
  completed: number;
  avgHoursToDischarge: number;
  medianHoursToDischarge: number;
  byStage: { stage: string; label: string; steps: number; avgOpenHours: number; medianOpenHours: number; currentlyOpen: number; currentlyBlocked: number }[];
  bySteps: { code: string; name: string; stage: string; count: number; avgOpenHours: number; medianOpenHours: number; currentlyOpen: number; currentlyBlocked: number }[];
}

export interface SummaryFields {
  presentingComplaint?: string;
  diagnosesText?: string;
  treatmentSummary?: string;
  proceduresText?: string;
  relevantResults?: string;
  conditionAtDischarge?: string;
  dischargeMedications?: string;
  dischargeInstructions?: string;
  followUpPlan?: string;
  warningSigns?: string;
}

export interface SummaryView extends SummaryFields {
  id: UUID;
  admissionId: UUID;
  status: SummaryStatus;
  preparedByName?: string;
  preparedAt: ISODateTime;
  finalizedByName?: string;
  finalizedAt?: ISODateTime;
  released: boolean;
  releasedByName?: string;
  releasedAt?: ISODateTime;
  updatedByName?: string;
  updatedAt: ISODateTime;
  amendments: { reason: string; amendedByName?: string; amendedAt: ISODateTime }[];
  version: number;
}

export interface SummaryEnvelope {
  summary?: SummaryView;
  canWrite: boolean;
  canRelease: boolean;
}

export interface DeliveryView {
  event: 'SCHEDULED' | 'RESCHEDULED' | 'REMINDER' | 'CANCELLED' | 'RESENT';
  channel: string;
  provider: string;
  destination?: string;
  status: string;
  error?: string;
  attemptedAt: ISODateTime;
}

export interface FollowUpView {
  id: UUID;
  admissionId?: UUID;
  admissionNumber?: string;
  patientId: UUID;
  patientName?: string;
  patientMrn?: string;
  type: FollowUpType;
  title: string;
  description?: string;
  scheduledAt?: ISODateTime;
  departmentId?: UUID;
  departmentName?: string;
  clinicianId?: UUID;
  clinicianName?: string;
  responsibleUserId?: UUID;
  responsibleName?: string;
  location?: string;
  labOrderId?: UUID;
  labOrderLabel?: string;
  status: FollowUpStatus;
  patientNotifiedAt?: ISODateTime;
  reminderSentAt?: ISODateTime;
  completedAt?: ISODateTime;
  outcomeNotes?: string;
  outcomeRecordedByName?: string;
  createdByName?: string;
  createdAt: ISODateTime;
  outcomeDue: boolean;
  deliveries: DeliveryView[];
  version: number;
}

export interface FollowUpRequest {
  type: FollowUpType;
  title: string;
  description?: string | null;
  scheduledAt?: string | null;
  departmentId?: UUID | null;
  clinicianId?: UUID | null;
  responsibleUserId?: UUID | null;
  location?: string | null;
  labOrderId?: UUID | null;
  notifyPatient?: boolean;
  version?: number;
}

export interface OptionView {
  id: UUID;
  label: string;
  detail?: string;
}

export interface FollowUpOptions {
  clinicians: OptionView[];
  staff: OptionView[];
  departments: OptionView[];
  labOrders: OptionView[];
}

export interface PendingResultView {
  labOrderId: UUID;
  orderNumber: string;
  testName: string;
  category: string;
  status: string;
  priority: string;
  orderedAt: ISODateTime;
  admissionId: UUID;
  admissionNumber: string;
  patientId: UUID;
  patientName?: string;
  patientMrn?: string;
  admissionStatus: AdmissionStatus;
  dischargedAt?: ISODateTime;
  abnormal: boolean;
  followUpId?: UUID;
  followUpResponsibleName?: string;
  followUpStatus?: FollowUpStatus;
}

export interface StepUpdateRequest {
  status?: StepStatus;
  priority?: StepPriority;
  assignedTo?: UUID;
  clearAssignee?: boolean;
  blockerReason?: string;
  nextAction?: string;
  notes?: string;
  notApplicableReason?: string;
  confirmRequiredNotApplicable?: boolean;
  version: number;
}

// ------------------------------------------------------------------ portal

export interface PortalDischarge {
  admissionId: UUID;
  admissionNumber: string;
  admissionStatus: AdmissionStatus;
  admittedAt: ISODateTime;
  dischargedAt?: ISODateTime;
  departmentName?: string;
  doctorName?: string;
  workflowStatus: WorkflowStatus;
  headline: string;
  plannedDischargeAt?: ISODateTime;
  initiatedAt?: ISODateTime;
  completedAt?: ISODateTime;
  progress?: Progress;
  doctorApproved: boolean;
  doctorApprovedAt?: ISODateTime;
  steps: { title: string; status: 'DONE' | 'IN_PROGRESS' | 'WAITING'; statusLabel: string; completedAt?: ISODateTime }[];
  summary?: SummaryFields & { admissionId: UUID; status: SummaryStatus; finalizedAt?: ISODateTime; finalizedByName?: string };
  medications: { name: string; dose: string; route: string; frequency: string; durationDays?: number; instructions?: string }[];
  warningSigns?: string;
  acknowledgement: { available: boolean; acknowledged: boolean; acknowledgedAt?: ISODateTime; acknowledgedByName?: string; unavailableReason?: string };
  upcomingFollowUps: number;
}

export interface PortalFollowUp {
  id: UUID;
  type: FollowUpType;
  title: string;
  instructions?: string;
  scheduledAt?: ISODateTime;
  departmentName?: string;
  clinicianName?: string;
  location?: string;
  status: FollowUpStatus;
  patientNotifiedAt?: ISODateTime;
  completedAt?: ISODateTime;
}

// ------------------------------------------------------------------ query keys + API

export const dqk = {
  detail: (admissionId: UUID) => ['discharge', 'admission', admissionId] as const,
  board: (params: object) => ['discharge', 'board', params] as const,
  worklist: ['discharge', 'worklist'] as const,
  financialQueue: (filter: string) => ['discharge', 'financial-queue', filter] as const,
  delays: ['discharge', 'delays'] as const,
  summary: (admissionId: UUID) => ['discharge', 'summary', admissionId] as const,
  followUps: (admissionId: UUID) => ['follow-ups', 'admission', admissionId] as const,
  followUpOptions: (admissionId: UUID) => ['follow-ups', 'options', admissionId] as const,
  myFollowUps: (includeClosed: boolean) => ['follow-ups', 'my', includeClosed] as const,
  pendingResults: ['follow-ups', 'pending-results'] as const,
  portal: (patientId: UUID) => ['portal', patientId, 'discharge'] as const,
  portalFollowUps: (patientId: UUID) => ['portal', patientId, 'follow-ups'] as const,
};

export const dischargeApi = {
  detail: (admissionId: UUID) => api.get<DischargeDetail>(`/api/discharge/admissions/${admissionId}`),
  board: (params: { scope?: 'ACTIVE' | 'COMPLETED' | 'CANCELLED'; wardId?: UUID; departmentId?: UUID; q?: string; blockedOnly?: boolean; days?: number }) =>
    api.get<WorkflowListItem[]>('/api/discharge/workflows', params),
  worklist: () => api.get<Worklist>('/api/discharge/worklist'),
  financialQueue: (filter: 'PENDING' | 'CLEARED' | 'ALL') => api.get<FinancialQueueItem[]>('/api/discharge/financial-queue', { filter }),
  delays: () => api.get<DelayReport>('/api/discharge/reports/delays'),
  initiate: (admissionId: UUID, body: { plannedDischargeAt?: string | null; notes?: string }) =>
    api.post<DischargeDetail>(`/api/discharge/admissions/${admissionId}/workflow`, body),
  cancel: (workflowId: UUID, reason: string) => api.post<DischargeDetail>(`/api/discharge/workflows/${workflowId}/cancel`, { reason }),
  plan: (workflowId: UUID, plannedDischargeAt: string | null) =>
    api.put<DischargeDetail>(`/api/discharge/workflows/${workflowId}/plan`, { plannedDischargeAt }),
  updateStep: (workflowId: UUID, stepId: UUID, body: StepUpdateRequest) =>
    api.patch<DischargeDetail>(`/api/discharge/workflows/${workflowId}/steps/${stepId}`, body),
  clinicalDecision: (workflowId: UUID, decision: 'AUTHORIZE' | 'REVOKE', notes: string) =>
    api.post<DischargeDetail>(`/api/discharge/workflows/${workflowId}/clinical-decision`, { decision, notes }),
  financialClearance: (workflowId: UUID, type: FinancialClearance, reason?: string) =>
    api.post<DischargeDetail>(`/api/discharge/workflows/${workflowId}/financial-clearance`, { type, reason }),
  complete: (workflowId: UUID, body: { actualDepartureAt: string; disposition: Disposition; notes?: string }) =>
    api.post<DischargeDetail>(`/api/discharge/workflows/${workflowId}/complete`, body),

  summary: (admissionId: UUID) => api.get<SummaryEnvelope>(`/api/discharge/summaries/${admissionId}`),
  createSummary: (admissionId: UUID) => api.post<SummaryEnvelope>(`/api/discharge/summaries/${admissionId}`),
  updateSummary: (admissionId: UUID, body: SummaryFields & { amendmentReason?: string; version: number }) =>
    api.put<SummaryEnvelope>(`/api/discharge/summaries/${admissionId}`, body),
  finalizeSummary: (admissionId: UUID, version: number) =>
    api.post<SummaryEnvelope>(`/api/discharge/summaries/${admissionId}/finalize`, { version }),
  releaseSummary: (admissionId: UUID) => api.post<SummaryEnvelope>(`/api/discharge/summaries/${admissionId}/release`),
  downloadSummaryPdf: (admissionId: UUID, admissionNumber?: string) =>
    api.download(`/api/discharge/summaries/${admissionId}/summary.pdf`, `discharge-summary-${admissionNumber ?? admissionId}.pdf`),

  followUps: (admissionId: UUID) => api.get<FollowUpView[]>(`/api/discharge/admissions/${admissionId}/follow-ups`),
  followUpOptions: (admissionId: UUID) => api.get<FollowUpOptions>(`/api/discharge/admissions/${admissionId}/follow-up-options`),
  createFollowUp: (admissionId: UUID, body: FollowUpRequest) => api.post<FollowUpView>(`/api/discharge/admissions/${admissionId}/follow-ups`, body),
  updateFollowUp: (id: UUID, body: FollowUpRequest) => api.put<FollowUpView>(`/api/discharge/follow-ups/${id}`, body),
  followUpOutcome: (id: UUID, status: 'COMPLETED' | 'MISSED' | 'CANCELLED', outcomeNotes?: string) =>
    api.post<FollowUpView>(`/api/discharge/follow-ups/${id}/outcome`, { status, outcomeNotes }),
  resendFollowUp: (id: UUID) => api.post<FollowUpView>(`/api/discharge/follow-ups/${id}/resend`),
  myFollowUps: (includeClosed: boolean) => api.get<FollowUpView[]>('/api/discharge/follow-ups/my', { includeClosed }),
  pendingResults: () => api.get<PendingResultView[]>('/api/discharge/follow-ups/pending-results'),

  portal: (patientId: UUID) => api.get<{ discharge?: PortalDischarge }>(`/api/portal/patients/${patientId}/discharge`),
  portalFollowUps: (patientId: UUID) => api.get<{ upcoming: PortalFollowUp[]; past: PortalFollowUp[] }>(`/api/portal/patients/${patientId}/follow-ups`),
  acknowledge: (patientId: UUID, comment?: string) =>
    api.post<{ discharge?: PortalDischarge }>(`/api/portal/patients/${patientId}/discharge/acknowledge`, { confirmed: true, comment }),
};

export const DISPOSITION_LABEL: Record<Disposition, string> = {
  HOME: 'Home',
  TRANSFERRED: 'Transferred to another facility',
  LAMA: 'Left against medical advice',
  DECEASED: 'Deceased',
  ABSCONDED: 'Absconded',
  OTHER: 'Other',
};

export const ROLE_TEAM: Record<Role, string> = {
  DOCTOR: 'Doctor',
  NURSE: 'Nursing',
  PHARMACIST: 'Pharmacy',
  BILLING: 'Billing',
  HOSPITAL_ADMIN: 'Administration',
  PATIENT: 'Patient',
  SUPER_ADMIN: 'Platform',
};

export const FOLLOW_UP_TYPE_LABEL: Record<FollowUpType, string> = {
  APPOINTMENT: 'Appointment',
  RESULT_REVIEW: 'Result review',
  INSTRUCTION: 'Instruction',
  TASK: 'Task',
  TELECONSULT: 'Teleconsultation',
};

/** Invalidate everything the discharge screens show after a change. */
export const DISCHARGE_INVALIDATE = [['discharge'], ['follow-ups'], ['admissions'], ['admission'], ['beds']] as const;
