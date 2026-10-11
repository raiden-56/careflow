import { api, Query } from './client';
import type { BedStatus, BedType, Decimal, ISODate, ISODateTime, PageResponse, Role, RoomType, UUID, WardType } from './types';

// ------------------------------------------------------------------ dashboard

export interface WardOccupancy {
  wardId: UUID;
  code: string;
  name: string;
  total: number;
  occupied: number;
  available: number;
  reserved: number;
  cleaning: number;
  maintenance: number;
  occupancyPercent: number;
}

export interface AdminDashboard {
  generatedAt: ISODateTime;
  patients: { totalRegistered: number; currentlyAdmitted: number; awaitingBed: number; dischargeInProgress: number };
  beds: { total: number; available: number; occupied: number; reserved: number; cleaning: number; maintenance: number; occupancyPercent: number };
  wards: WardOccupancy[];
  trend: Array<{ date: ISODate; admissions: number; discharges: number }>;
  pending: {
    pendingTransfers: number;
    pendingBedAssignments: number;
    openPatientRequests: number;
    openIncidents: number;
    dischargeWorkflowsInProgress: number;
  };
  financial?: {
    todayCollections: Decimal;
    outstandingReceivables: Decimal;
    invoicesWithBalance: number;
    collectionsDefinition: string;
    receivablesDefinition: string;
  };
  workload: {
    doctors: Array<{ userId: UUID; name: string; department?: string; openPatients: number }>;
    nurseWards: Array<{ wardId: UUID; wardName: string; nursesAssigned: number; occupiedBeds: number; patientsPerNurse?: number }>;
  };
  alerts: Array<{ severity: 'WARNING' | 'CRITICAL' | 'INFO'; code: string; title: string; message: string; link?: string }>;
}

// ------------------------------------------------------------------ reports

export interface ReportFilter {
  from: ISODate;
  to: ISODate;
  departmentId?: UUID;
  departmentName?: string;
  wardId?: UUID;
  wardName?: string;
  timezone: string;
}

export interface Definition {
  metric: string;
  definition: string;
}

export interface NamedCount {
  name: string;
  count: number;
}

export interface OperationalReport {
  generatedAt: ISODateTime;
  filter: ReportFilter;
  summary: {
    admissions: number;
    discharges: number;
    averageLengthOfStayDays?: number;
    averageOccupancyPercent: number;
    bedsInScope: number;
    averageCleaningMinutes?: number;
    bedTurnoverPerBed: number;
    transfersRequested: number;
    transfersCompleted: number;
    followUpCompletionPercent?: number;
    overdueMedicationDoses: number;
    medicationExceptions: number;
    openCareTasks: number;
    overdueCareTasks: number;
  };
  daily: Array<{ date: ISODate; admissions: number; discharges: number; occupiedBeds: number; occupancyPercent: number }>;
  wards: Array<{
    wardId: UUID;
    wardName: string;
    beds: number;
    occupiedNow: number;
    occupancyNowPercent: number;
    bedAssignments: number;
    bedReleases: number;
    averageCleaningMinutes?: number;
    turnoverPerBed: number;
  }>;
  lengthOfStay: Array<{ department: string; discharges: number; averageDays?: number; maxDays?: number }>;
  discharge: {
    workflowsStarted: number;
    workflowsCompleted: number;
    averageHoursToDeparture?: number;
    averageHoursBeyondPlanned?: number;
    steps: Array<{
      stepCode: string;
      stepName: string;
      category: string;
      workflows: number;
      completed: number;
      open: number;
      blocked: number;
      averageHoursToComplete?: number;
    }>;
  };
  tasks: {
    openCareTasks: number;
    overdueCareTasks: number;
    openTasksByType: NamedCount[];
    overdueMedicationDoses: number;
    medicationExceptions: NamedCount[];
    openExceptionFollowUps: number;
    overdueGraceMinutes: number;
  };
  transfers: Array<{
    id: UUID;
    requestedAt: ISODateTime;
    admissionNumber: string;
    fromLocation?: string;
    toLocation?: string;
    status: string;
    priority: string;
    completedAt?: ISODateTime;
    turnaroundHours?: number;
  }>;
  followUps: {
    scheduledInRange: number;
    due: number;
    completed: number;
    missed: number;
    cancelled: number;
    upcoming: number;
    completionPercent?: number;
  };
  definitions: Definition[];
}

export interface ClinicalQualityReport {
  generatedAt: ISODateTime;
  filter: ReportFilter;
  investigations: {
    orderedInRange: number;
    outstanding: number;
    awaitingReview: number;
    overdue: number;
    outstandingByPriority: NamedCount[];
    averageTurnaroundHours?: number;
  };
  documentation: Array<{ item: string; applicable: number; complete: number; percent?: number }>;
  safety: {
    raisedInRange: number;
    openNow: number;
    averageMinutesToAcknowledge?: number;
    bySeverity: NamedCount[];
    byType: NamedCount[];
    incidentsBySeverity: NamedCount[];
  };
  definitions: Definition[];
}

export interface ReportParams {
  from?: string;
  to?: string;
  departmentId?: string;
  wardId?: string;
}

// ------------------------------------------------------------------ settings & configuration

export type SettingType = 'INTEGER' | 'BOOLEAN' | 'ENUM' | 'MULTI_ENUM' | 'TEXT';

export interface SettingView {
  key: string;
  group: string;
  label: string;
  value: string;
  defaultValue: string;
  description: string;
  type: SettingType;
  min?: number;
  max?: number;
  options?: string[];
  overridden: boolean;
  updatedAt?: ISODateTime;
  updatedBy?: string;
}

export interface OrganizationProfile {
  id: UUID;
  code: string;
  name: string;
  legalName?: string;
  timezone: string;
  currency: string;
  address?: string;
  city?: string;
  state?: string;
  phone?: string;
  email?: string;
  registrationNumber?: string;
  version: number;
}

export type Channel = 'IN_APP' | 'EMAIL' | 'SMS';

export interface TemplateView {
  id: UUID;
  code: string;
  channel: Channel;
  subject: string;
  body: string;
  active: boolean;
  placeholders: string[];
  updatedBy?: string;
  updatedAt: ISODateTime;
  version: number;
}

export interface TemplateRequest {
  code: string;
  channel: Channel;
  subject: string;
  body: string;
  active?: boolean;
  version?: number;
}

export interface PreviewResponse {
  subject: string;
  body: string;
  placeholders: string[];
  unknownPlaceholders: string[];
  knownPlaceholders: Record<string, string>;
  smsSegments?: number;
}

export type EscalationEvent =
  | 'MEDICATION_OVERDUE'
  | 'CRITICAL_OBSERVATION'
  | 'ALERT_UNACKNOWLEDGED'
  | 'DISCHARGE_BLOCKED'
  | 'HANDOVER_UNACKNOWLEDGED'
  | 'LAB_RESULT_UNREVIEWED';

export interface EscalationView {
  id: UUID;
  eventType: EscalationEvent;
  thresholdMinutes: number;
  escalateToRole: Role;
  escalationLevel: number;
  priority: 'LOW' | 'NORMAL' | 'HIGH' | 'CRITICAL';
  description: string;
  active: boolean;
  updatedAt: ISODateTime;
  version: number;
}

export interface EscalationRequest {
  eventType: EscalationEvent;
  thresholdMinutes: number;
  escalateToRole: Role;
  escalationLevel: number;
  priority: string;
  description: string;
  active?: boolean;
  version?: number;
}

export type VitalParameter =
  | 'TEMPERATURE_C'
  | 'PULSE_BPM'
  | 'RESPIRATORY_RATE'
  | 'SYSTOLIC_BP'
  | 'DIASTOLIC_BP'
  | 'SPO2'
  | 'PAIN_SCORE'
  | 'BLOOD_GLUCOSE';

export interface ThresholdView {
  id: UUID;
  parameter: VitalParameter;
  minValue?: Decimal;
  maxValue?: Decimal;
  severity: 'WARNING' | 'CRITICAL';
  description: string;
  notifyRole?: Role;
  active: boolean;
  bounds: string;
  updatedAt: ISODateTime;
  version: number;
}

export interface ThresholdRequest {
  parameter: VitalParameter;
  minValue?: string | null;
  maxValue?: string | null;
  severity: 'WARNING' | 'CRITICAL';
  description: string;
  notifyRole?: Role | null;
  active?: boolean;
  version?: number;
}

export type DischargeCategory = 'CLINICAL' | 'NURSING' | 'PHARMACY' | 'ADMINISTRATIVE' | 'FINANCIAL' | 'PATIENT';

export interface DischargeTemplateView {
  id: UUID;
  code: string;
  stepOrder: number;
  name: string;
  description?: string;
  category: DischargeCategory;
  responsibleRole: Role;
  required: boolean;
  autoCheck?: string;
  active: boolean;
  version: number;
}

export interface DischargeTemplateRequest {
  code: string;
  name: string;
  description?: string | null;
  category: DischargeCategory;
  responsibleRole: Role;
  required: boolean;
  autoCheck?: string | null;
  active?: boolean;
  version?: number;
}

export type IntegrationType = 'ABDM' | 'FHIR' | 'LIS' | 'PAYMENT_GATEWAY' | 'SMS' | 'EMAIL' | 'INSURANCE_TPA';
export type IntegrationMode = 'DISABLED' | 'DEV_ADAPTER' | 'SANDBOX' | 'LIVE';

export interface IntegrationView {
  id: UUID;
  integrationType: IntegrationType;
  label: string;
  mode: IntegrationMode;
  endpointUrl?: string;
  secretName?: string;
  notes?: string;
  boundaryNote?: string;
  adapterStatus: string;
  liveImplemented: boolean;
  updatedBy?: string;
  updatedAt: ISODateTime;
  version: number;
}

export interface IntegrationRequest {
  mode: IntegrationMode;
  endpointUrl?: string | null;
  secretName?: string | null;
  notes?: string | null;
  confirmLive: boolean;
  version: number;
}

export type TariffCategory = 'ROOM' | 'PROCEDURE' | 'LAB' | 'PHARMACY' | 'CONSULTATION' | 'NURSING' | 'IMAGING' | 'CONSUMABLE' | 'OTHER';
export type BillingUnit = 'PER_UNIT' | 'PER_DAY' | 'PER_VISIT' | 'PER_HOUR';

export interface TariffView {
  id: UUID;
  code: string;
  name: string;
  category: TariffCategory;
  unitPrice: Decimal;
  taxRate: Decimal;
  billingUnit: BillingUnit;
  roomType?: RoomType;
  departmentId?: UUID;
  departmentName?: string;
  description?: string;
  active: boolean;
  effectiveFrom: ISODate;
  updatedAt: ISODateTime;
  version: number;
}

export interface TariffRequest {
  code: string;
  name: string;
  category: TariffCategory;
  unitPrice: string;
  taxRate: string;
  billingUnit: BillingUnit;
  roomType?: string | null;
  departmentId?: UUID | null;
  description?: string | null;
  effectiveFrom?: string | null;
  version?: number;
}

// ------------------------------------------------------------------ audit

export type AuditOutcome = 'SUCCESS' | 'DENIED' | 'FAILURE';

export interface AuditRow {
  id: UUID;
  occurredAt: ISODateTime;
  actorName?: string;
  actorRole?: string;
  action: string;
  entityType?: string;
  entityId?: UUID;
  patientMrn?: string;
  outcome: AuditOutcome;
  summary?: string;
  ipAddress?: string;
  hasDetails: boolean;
}

export interface AuditDetail extends Omit<AuditRow, 'hasDetails'> {
  actorUserId?: UUID;
  patientId?: UUID;
  patientName?: string;
  details?: Record<string, unknown>;
  traceId?: string;
}

export interface AuditParams {
  actor?: string;
  patientMrn?: string;
  action?: string;
  entityType?: string;
  outcome?: AuditOutcome | '';
  from?: string;
  to?: string;
  page?: number;
  size?: number;
  sort?: string;
}

// ------------------------------------------------------------------ incidents

export type IncidentType =
  | 'MEDICATION_ERROR'
  | 'NEAR_MISS'
  | 'FALL'
  | 'PRESSURE_INJURY'
  | 'EQUIPMENT'
  | 'INFECTION'
  | 'PATIENT_IDENTIFICATION'
  | 'BEHAVIOURAL'
  | 'OTHER';
export type IncidentSeverity = 'NO_HARM' | 'MINOR' | 'MODERATE' | 'SEVERE' | 'DEATH';
export type IncidentStatus = 'REPORTED' | 'UNDER_REVIEW' | 'ACTION_REQUIRED' | 'CLOSED';

export interface IncidentSummary {
  id: UUID;
  incidentNumber: string;
  incidentType: IncidentType;
  severity: IncidentSeverity;
  status: IncidentStatus;
  occurredAt: ISODateTime;
  reportedAt: ISODateTime;
  location?: string;
  wardName?: string;
  patientName?: string;
  patientMrn?: string;
  patientLinked: boolean;
  reportedByName?: string;
  reviewerName?: string;
  mine: boolean;
}

export interface IncidentDetail {
  id: UUID;
  incidentNumber: string;
  incidentType: IncidentType;
  severity: IncidentSeverity;
  status: IncidentStatus;
  occurredAt: ISODateTime;
  reportedAt: ISODateTime;
  location?: string;
  wardId?: UUID;
  wardName?: string;
  patientId?: UUID;
  patientName?: string;
  patientMrn?: string;
  admissionId?: UUID;
  admissionNumber?: string;
  medicationAdministrationId?: UUID;
  medicationEvent?: string;
  description: string;
  immediateActions?: string;
  reportedById: UUID;
  reportedByName?: string;
  reportedByRole?: string;
  reviewerId?: UUID;
  reviewerName?: string;
  reviewNotes?: string;
  correctiveActions?: string;
  closedAt?: ISODateTime;
  canReview: boolean;
  allowedTransitions: IncidentStatus[];
  version: number;
}

export interface IncidentReportRequest {
  incidentType: IncidentType;
  severity: IncidentSeverity;
  occurredAt: string;
  location?: string | null;
  wardId?: UUID | null;
  patientId?: UUID | null;
  admissionId?: UUID | null;
  medicationAdministrationId?: UUID | null;
  description: string;
  immediateActions?: string | null;
}

export interface IncidentHistoryEntry {
  at: ISODateTime;
  actorName?: string;
  actorRole?: string;
  action: string;
  summary?: string;
  details?: Record<string, unknown>;
}

export interface IncidentStats {
  reported: number;
  underReview: number;
  actionRequired: number;
  closed: number;
  severeOpen: number;
  reportedLast30Days: number;
}

export interface PatientOption {
  patientId: UUID;
  patientName: string;
  mrn: string;
  admissionId: UUID;
  admissionNumber: string;
  location: string;
}

export interface MedicationEventOption {
  id: UUID;
  scheduledAt: ISODateTime;
  medication: string;
  status: string;
  dose: string;
}

// ------------------------------------------------------------------ privacy

export type ConsentType =
  | 'TREATMENT'
  | 'PROCEDURE'
  | 'DATA_SHARING'
  | 'CAREGIVER_ACCESS'
  | 'COMMUNICATION'
  | 'RESEARCH'
  | 'INSURANCE_SHARING';
export type ConsentStatus = 'GRANTED' | 'WITHDRAWN' | 'EXPIRED' | 'DECLINED';
export type CaptureMethod = 'WRITTEN' | 'VERBAL' | 'ELECTRONIC' | 'PORTAL';

export interface ConsentView {
  id: UUID;
  patientId: UUID;
  patientName?: string;
  patientMrn?: string;
  admissionId?: UUID;
  admissionNumber?: string;
  consentType: ConsentType;
  purpose: string;
  sharedWith?: string;
  status: ConsentStatus;
  effectiveStatus: ConsentStatus;
  grantedAt: ISODateTime;
  expiresAt?: ISODateTime;
  withdrawnAt?: ISODateTime;
  withdrawalReason?: string;
  capturedByName?: string;
  captureMethod: CaptureMethod;
  notes?: string;
  portalWithdrawable: boolean;
  version: number;
}

export interface ConsentRequest {
  patientId: UUID;
  admissionId?: UUID | null;
  consentType: ConsentType;
  purpose: string;
  sharedWith?: string | null;
  captureMethod: CaptureMethod;
  decision: 'GRANTED' | 'DECLINED';
  grantedAt?: string | null;
  expiresAt?: string | null;
  notes?: string | null;
}

export type PatientRequestType = 'RECORD_COPY' | 'CORRECTION' | 'RESTRICT_SHARING' | 'WITHDRAW_CONSENT' | 'ACCESS_REVIEW' | 'OTHER';
export type PatientRequestStatus = 'OPEN' | 'IN_REVIEW' | 'COMPLETED' | 'REJECTED';

export interface PatientRequestView {
  id: UUID;
  patientId: UUID;
  patientName?: string;
  patientMrn?: string;
  requestType: PatientRequestType;
  subject: string;
  description: string;
  status: PatientRequestStatus;
  raisedByName?: string;
  raisedAt: ISODateTime;
  response?: string;
  respondedByName?: string;
  respondedAt?: ISODateTime;
  version: number;
}

export interface AccessEntry {
  at: ISODateTime;
  staffName: string;
  role: string;
  activity: string;
}

export interface PrivacyStats {
  consentsGranted: number;
  consentsWithdrawn: number;
  requestsOpen: number;
  requestsInReview: number;
  requestsClosedLast30Days: number;
}

// ------------------------------------------------------------------ documents

export type DocumentType = 'LAB_REPORT' | 'IMAGING' | 'DISCHARGE_SUMMARY' | 'CONSENT_FORM' | 'ID_PROOF' | 'INSURANCE' | 'REFERRAL' | 'OTHER';

export interface DocumentView {
  id: UUID;
  patientId: UUID;
  admissionId?: UUID;
  admissionNumber?: string;
  documentType: DocumentType;
  title: string;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  sha256: string;
  releasedToPatient: boolean;
  status: 'ACTIVE' | 'ARCHIVED';
  uploadedByName?: string;
  uploadedAt: ISODateTime;
  version: number;
}

// ------------------------------------------------------------------ staff & facility config

export interface StaffView {
  id: UUID;
  email: string;
  fullName: string;
  phone?: string;
  role: Role;
  status: 'ACTIVE' | 'LOCKED' | 'DISABLED';
  locked: boolean;
  lastLoginAt?: ISODateTime;
  createdAt: ISODateTime;
  departmentId?: UUID;
  departmentName?: string;
  designation?: string;
  specialty?: string;
  registrationNumber?: string;
  qualification?: string;
  employeeCode?: string;
  wards: string[];
  version: number;
}

export interface StaffCreateRequest {
  email: string;
  fullName: string;
  phone?: string | null;
  role: Role;
  departmentId?: UUID | null;
  designation?: string | null;
  specialty?: string | null;
  registrationNumber?: string | null;
  qualification?: string | null;
  employeeCode?: string | null;
  wardIds?: UUID[];
  temporaryPassword?: string | null;
}

export type StaffUpdateRequest = Omit<StaffCreateRequest, 'email' | 'role' | 'wardIds' | 'temporaryPassword'> & { version: number };

export interface RoleMatrix {
  roles: Array<{ role: Role; name: string; description?: string; permissions: string[]; userCount: number }>;
  permissions: Array<{ code: string; category: string; description: string }>;
}

export interface WardRef {
  assignmentId: UUID;
  wardId: UUID;
  wardName?: string;
  assignedAt: ISODateTime;
}

export interface ConfigBed {
  id: UUID;
  roomId: UUID;
  roomNumber?: string;
  bedNumber: string;
  bedType: BedType;
  status: BedStatus;
  isolationCapable: boolean;
  oxygenSupported: boolean;
  notes?: string;
  active: boolean;
  version: number;
}

export interface RoomView {
  id: UUID;
  wardId: UUID;
  roomNumber: string;
  roomType: RoomType;
  active: boolean;
}

export interface WardRequest {
  code: string;
  name: string;
  wardType: WardType;
  departmentId?: UUID | null;
  floor?: string | null;
  genderPolicy?: 'MIXED' | 'MALE' | 'FEMALE';
  active?: boolean;
}

export interface DepartmentRequest {
  code: string;
  name: string;
  specialty?: string | null;
  description?: string | null;
  headUserId?: UUID | null;
  active?: boolean;
}

export interface WardStaffView {
  assignmentId: UUID;
  userId: UUID;
  fullName: string;
  role?: Role;
  assignedAt: ISODateTime;
}

// ------------------------------------------------------------------ API

export const adminApi = {
  dashboard: () => api.get<AdminDashboard>('/api/dashboard/admin'),

  operationalReport: (p: ReportParams) => api.get<OperationalReport>('/api/reports/operational', p as Query),
  clinicalQuality: (p: ReportParams) => api.get<ClinicalQualityReport>('/api/reports/clinical-quality', p as Query),
  exportReport: (kind: 'operational' | 'clinical-quality', format: 'csv' | 'pdf', p: ReportParams) =>
    api.download(`/api/reports/${kind}/export`, `${kind}-report.${format}`, { ...p, format } as Query),

  settings: () => api.get<SettingView[]>('/api/settings'),
  updateSettings: (values: Record<string, string>) => api.put<SettingView[]>('/api/settings', { values }),
  organizationProfile: () => api.get<OrganizationProfile>('/api/settings/organization'),
  updateOrganizationProfile: (body: Omit<OrganizationProfile, 'id' | 'code' | 'timezone' | 'currency'>) =>
    api.put<OrganizationProfile>('/api/settings/organization', body),

  templates: () => api.get<TemplateView[]>('/api/admin/notification-templates'),
  createTemplate: (body: TemplateRequest) => api.post<TemplateView>('/api/admin/notification-templates', body),
  updateTemplate: (id: UUID, body: TemplateRequest) => api.put<TemplateView>(`/api/admin/notification-templates/${id}`, body),
  previewTemplate: (body: { subject: string; body: string; channel?: Channel; sample?: Record<string, string> }) =>
    api.post<PreviewResponse>('/api/admin/notification-templates/preview', body),

  escalations: () => api.get<EscalationView[]>('/api/admin/escalation-rules'),
  createEscalation: (body: EscalationRequest) => api.post<EscalationView>('/api/admin/escalation-rules', body),
  updateEscalation: (id: UUID, body: EscalationRequest) => api.put<EscalationView>(`/api/admin/escalation-rules/${id}`, body),

  thresholds: () => api.get<ThresholdView[]>('/api/admin/threshold-rules'),
  createThreshold: (body: ThresholdRequest) => api.post<ThresholdView>('/api/admin/threshold-rules', body),
  updateThreshold: (id: UUID, body: ThresholdRequest) => api.put<ThresholdView>(`/api/admin/threshold-rules/${id}`, body),

  dischargeTemplates: () => api.get<DischargeTemplateView[]>('/api/admin/discharge-templates'),
  createDischargeTemplate: (body: DischargeTemplateRequest) => api.post<DischargeTemplateView>('/api/admin/discharge-templates', body),
  updateDischargeTemplate: (id: UUID, body: DischargeTemplateRequest) =>
    api.put<DischargeTemplateView>(`/api/admin/discharge-templates/${id}`, body),
  reorderDischargeTemplates: (orderedIds: UUID[]) =>
    api.put<DischargeTemplateView[]>('/api/admin/discharge-templates/order', { orderedIds }),

  integrations: () => api.get<IntegrationView[]>('/api/admin/integrations'),
  updateIntegration: (type: IntegrationType, body: IntegrationRequest) =>
    api.put<IntegrationView>(`/api/admin/integrations/${type}`, body),

  tariffs: (p: { category?: string; includeInactive?: boolean; q?: string; page?: number; size?: number; sort?: string }) =>
    api.get<PageResponse<TariffView>>('/api/admin/tariffs', p),
  createTariff: (body: TariffRequest) => api.post<TariffView>('/api/admin/tariffs', body),
  updateTariff: (id: UUID, body: TariffRequest) => api.put<TariffView>(`/api/admin/tariffs/${id}`, body),
  deactivateTariff: (id: UUID, reason?: string) => api.post<TariffView>(`/api/admin/tariffs/${id}/deactivate`, { reason }),
  activateTariff: (id: UUID) => api.post<TariffView>(`/api/admin/tariffs/${id}/activate`),

  audit: (p: AuditParams) => api.get<PageResponse<AuditRow>>('/api/audit', p as Query),
  auditActions: () => api.get<string[]>('/api/audit/actions'),
  auditEvent: (id: UUID) => api.get<AuditDetail>(`/api/audit/${id}`),
  exportAudit: (p: AuditParams) => api.download('/api/audit/export', 'audit-log.csv', p as Query),

  // staff & roles (foundation StaffController) + ward assignment sync
  staff: (p: { role?: Role | ''; status?: string; q?: string; page?: number; size?: number; sort?: string }) =>
    api.get<PageResponse<StaffView>>('/api/staff', p as Query),
  createStaff: (body: StaffCreateRequest) => api.post<StaffView>('/api/staff', body),
  updateStaff: (id: UUID, body: StaffUpdateRequest) => api.put<StaffView>(`/api/staff/${id}`, body),
  staffStatus: (id: UUID, status: StaffView['status'], reason?: string) => api.post<StaffView>(`/api/staff/${id}/status`, { status, reason }),
  resendInvite: (id: UUID) => api.post<void>(`/api/staff/${id}/invite`),
  staffWards: (id: UUID) => api.get<WardRef[]>(`/api/admin/staff/${id}/wards`),
  setStaffWards: (id: UUID, wardIds: UUID[]) => api.put<WardRef[]>(`/api/admin/staff/${id}/wards`, { wardIds }),
  roles: () => api.get<RoleMatrix>('/api/roles'),
  updateRole: (role: Role, permissions: string[]) => api.put<RoleMatrix>(`/api/roles/${role}/permissions`, { permissions }),

  // facility (foundation FacilityController) + config bed list
  createDepartment: (body: DepartmentRequest) => api.post('/api/departments', body),
  updateDepartment: (id: UUID, body: DepartmentRequest) => api.put(`/api/departments/${id}`, body),
  createWard: (body: WardRequest) => api.post('/api/wards', body),
  updateWard: (id: UUID, body: WardRequest) => api.put(`/api/wards/${id}`, body),
  rooms: (wardId: UUID) => api.get<RoomView[]>(`/api/wards/${wardId}/rooms`),
  createRoom: (wardId: UUID, body: { roomNumber: string; roomType: RoomType; active?: boolean }) =>
    api.post<RoomView>(`/api/wards/${wardId}/rooms`, body),
  updateRoom: (wardId: UUID, roomId: UUID, body: { roomNumber: string; roomType: RoomType; active?: boolean }) =>
    api.put<RoomView>(`/api/wards/${wardId}/rooms/${roomId}`, body),
  wardBeds: (wardId: UUID) => api.get<ConfigBed[]>(`/api/admin/wards/${wardId}/beds`),
  createBed: (body: { roomId: UUID; bedNumber: string; bedType: BedType; isolationCapable: boolean; oxygenSupported: boolean; notes?: string | null; active?: boolean }) =>
    api.post('/api/beds', body),
  updateBed: (id: UUID, body: { roomId: UUID; bedNumber: string; bedType: BedType; isolationCapable: boolean; oxygenSupported: boolean; notes?: string | null; active?: boolean }) =>
    api.put(`/api/beds/${id}`, body),
  wardStaff: (wardId: UUID) => api.get<WardStaffView[]>(`/api/wards/${wardId}/staff`),
  assignWardStaff: (wardId: UUID, userId: UUID) => api.post<WardStaffView>(`/api/wards/${wardId}/staff`, { userId }),
  removeWardStaff: (wardId: UUID, assignmentId: UUID) => api.del<void>(`/api/wards/${wardId}/staff/${assignmentId}`),
};

export const incidentApi = {
  report: (body: IncidentReportRequest) => api.post<IncidentDetail>('/api/incidents', body),
  mine: (p: { status?: IncidentStatus | ''; page?: number; size?: number; sort?: string }) =>
    api.get<PageResponse<IncidentSummary>>('/api/incidents/mine', p as Query),
  all: (p: { status?: string; severity?: string; type?: string; reviewerId?: string; q?: string; page?: number; size?: number; sort?: string }) =>
    api.get<PageResponse<IncidentSummary>>('/api/incidents', p as Query),
  stats: () => api.get<IncidentStats>('/api/incidents/stats'),
  reviewers: () => api.get<Array<{ id: UUID; fullName: string; role: Role }>>('/api/incidents/reviewers'),
  get: (id: UUID) => api.get<IncidentDetail>(`/api/incidents/${id}`),
  history: (id: UUID) => api.get<IncidentHistoryEntry[]>(`/api/incidents/${id}/history`),
  assign: (id: UUID, reviewerId: UUID) => api.post<IncidentDetail>(`/api/incidents/${id}/assign`, { reviewerId }),
  transition: (id: UUID, body: { status: IncidentStatus; reviewNotes?: string; correctiveActions?: string; version: number }) =>
    api.post<IncidentDetail>(`/api/incidents/${id}/transition`, body),
  patientLookup: (q: string) => api.get<PatientOption[]>('/api/incidents/patient-lookup', { q }),
  medicationEvents: (admissionId: UUID) => api.get<MedicationEventOption[]>('/api/incidents/medication-events', { admissionId }),
};

export const privacyApi = {
  stats: () => api.get<PrivacyStats>('/api/privacy/stats'),
  consents: (p: { patientMrn?: string; status?: string; type?: string; page?: number; size?: number; sort?: string }) =>
    api.get<PageResponse<ConsentView>>('/api/consents', p as Query),
  recordConsent: (body: ConsentRequest) => api.post<ConsentView>('/api/consents', body),
  withdrawConsent: (id: UUID, reason: string) => api.post<ConsentView>(`/api/consents/${id}/withdraw`, { reason }),
  requests: (p: { status?: string; type?: string; patientMrn?: string; page?: number; size?: number; sort?: string }) =>
    api.get<PageResponse<PatientRequestView>>('/api/patient-requests', p as Query),
  respond: (id: UUID, body: { status: PatientRequestStatus; response: string; version: number }) =>
    api.post<PatientRequestView>(`/api/patient-requests/${id}/respond`, body),
  // portal
  portalConsents: (patientId: UUID) => api.get<ConsentView[]>(`/api/portal/privacy/${patientId}/consents`),
  portalWithdraw: (patientId: UUID, consentId: UUID, reason: string) =>
    api.post<ConsentView>(`/api/portal/privacy/${patientId}/consents/${consentId}/withdraw`, { reason }),
  portalRequests: (patientId: UUID) => api.get<PatientRequestView[]>(`/api/portal/privacy/${patientId}/requests`),
  portalSubmit: (patientId: UUID, body: { requestType: PatientRequestType; subject: string; description: string }) =>
    api.post<PatientRequestView>(`/api/portal/privacy/${patientId}/requests`, body),
  accessLog: (patientId: UUID, page: number, size: number) =>
    api.get<PageResponse<AccessEntry>>(`/api/portal/privacy/${patientId}/access-log`, { page, size }),
};

export const documentApi = {
  list: (p: { patientId?: UUID; admissionId?: UUID; includeArchived?: boolean }) => api.get<DocumentView[]>('/api/documents', p),
  upload: (form: FormData) => api.upload<DocumentView>('/api/documents', form),
  release: (id: UUID) => api.post<DocumentView>(`/api/documents/${id}/release`),
  archive: (id: UUID, reason?: string) => api.post<DocumentView>(`/api/documents/${id}/archive`, { reason }),
  portalList: (patientId: UUID) => api.get<DocumentView[]>(`/api/portal/patients/${patientId}/documents`),
  /** Requests a short-lived signed link and downloads the file through it. */
  async download(id: UUID, fallbackName: string) {
    const link = await api.post<{ url: string; expiresAt: string }>(`/api/documents/${id}/download-token`);
    await api.download(link.url, fallbackName);
  },
};
