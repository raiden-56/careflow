import { api, Query } from './client';
import type { AdmissionStatus, AdmissionSummary, Decimal, Gender, ISODate, ISODateTime, PageResponse, UUID } from './types';

// ------------------------------------------------------------------ types

export type MedicationForm =
  | 'TABLET'
  | 'CAPSULE'
  | 'SYRUP'
  | 'INJECTION'
  | 'INFUSION'
  | 'INHALER'
  | 'OINTMENT'
  | 'DROPS'
  | 'SUPPOSITORY'
  | 'PATCH'
  | 'OTHER';
export type MedicationRoute =
  | 'ORAL'
  | 'IV'
  | 'IM'
  | 'SC'
  | 'INHALED'
  | 'TOPICAL'
  | 'SUBLINGUAL'
  | 'RECTAL'
  | 'NASAL'
  | 'OPHTHALMIC'
  | 'OTIC';
export const MEDICATION_FORMS: MedicationForm[] = [
  'TABLET',
  'CAPSULE',
  'SYRUP',
  'INJECTION',
  'INFUSION',
  'INHALER',
  'OINTMENT',
  'DROPS',
  'SUPPOSITORY',
  'PATCH',
  'OTHER',
];
export const MEDICATION_ROUTES: MedicationRoute[] = [
  'ORAL',
  'IV',
  'IM',
  'SC',
  'INHALED',
  'TOPICAL',
  'SUBLINGUAL',
  'RECTAL',
  'NASAL',
  'OPHTHALMIC',
  'OTIC',
];

export type Availability = 'IN_STOCK' | 'LOW_STOCK' | 'OUT_OF_STOCK';
export type BatchStatus = 'ACTIVE' | 'QUARANTINED' | 'EXPIRED' | 'DEPLETED';
export type MovementType = 'RECEIPT' | 'DISPENSE' | 'RETURN' | 'ADJUSTMENT_IN' | 'ADJUSTMENT_OUT' | 'EXPIRY_WRITE_OFF' | 'DAMAGE';
export type DispensationStatus = 'DISPENSED' | 'PARTIALLY_RETURNED' | 'RETURNED';
export type SupplyStatus = 'AWAITING_VERIFICATION' | 'QUERIED' | 'READY_TO_DISPENSE' | 'SUPPLIED' | 'COURSE_ENDED';
export type QueueFilter = 'OUTSTANDING' | 'AWAITING_VERIFICATION' | 'QUERIED' | 'READY_TO_DISPENSE' | 'ALL';

export interface MedicationView {
  id: UUID;
  code: string;
  genericName: string;
  brandName?: string;
  form: MedicationForm;
  strength: string;
  defaultRoute: MedicationRoute;
  drugClass?: string;
  allergenGroup?: string;
  isHighAlert: boolean;
  isControlled: boolean;
  unitPrice: Decimal;
  dispenseUnit: string;
  reorderLevel: number;
  active: boolean;
  stockOnHand: number;
  availability: Availability;
  displayName: string;
  version: number;
}

export interface MedicationRequest {
  code: string;
  genericName: string;
  brandName?: string | null;
  form: MedicationForm;
  strength: string;
  defaultRoute: MedicationRoute;
  drugClass?: string | null;
  allergenGroup?: string | null;
  isHighAlert: boolean;
  isControlled: boolean;
  unitPrice: string;
  dispenseUnit: string;
  reorderLevel: number;
  version?: number;
}

export interface BatchView {
  id: UUID;
  medicationId: UUID;
  medicationCode?: string;
  medicationName?: string;
  dispenseUnit?: string;
  batchNumber: string;
  expiryDate: ISODate;
  daysToExpiry: number;
  expired: boolean;
  quantityReceived: number;
  quantityOnHand: number;
  unitCost: Decimal;
  stockValue: Decimal;
  supplier?: string;
  receivedAt: ISODateTime;
  receivedByName?: string;
  status: BatchStatus;
  usable: boolean;
  version: number;
}

export interface MovementView {
  id: UUID;
  batchId: UUID;
  batchNumber?: string;
  medicationId: UUID;
  medicationName?: string;
  type: MovementType;
  quantity: number;
  balanceAfter: number;
  referenceType?: string;
  referenceId?: UUID;
  reason?: string;
  performedByName?: string;
  performedAt: ISODateTime;
}

export interface BatchDetail {
  batch: BatchView;
  movements: MovementView[];
}

export interface StockLevel {
  medicationId: UUID;
  code: string;
  name: string;
  form: MedicationForm;
  strength: string;
  dispenseUnit: string;
  active: boolean;
  isHighAlert: boolean;
  isControlled: boolean;
  reorderLevel: number;
  usable: number;
  quarantined: number;
  expiredOnHand: number;
  nearExpiry: number;
  batchCount: number;
  nearestExpiry?: ISODate;
  stockValue: Decimal;
  unitPrice: Decimal;
  availability: Availability;
}

export interface AlertsView {
  nearExpiryDays: number;
  lowStock: StockLevel[];
  nearExpiry: BatchView[];
  expiredOnHand: BatchView[];
  quarantined: BatchView[];
}

export interface PharmacyDashboard {
  queueSize: number;
  awaitingVerification: number;
  openQueries: number;
  readyToDispense: number;
  dispensedToday: number;
  unitsDispensedToday: number;
  lowStockCount: number;
  outOfStockCount: number;
  nearExpiryCount: number;
  expiredOnHandCount: number;
  quarantinedCount: number;
  stockValue: Decimal;
  nearExpiryDays: number;
  dispensedLast7Days: { date: ISODate; dispensations: number; units: number }[];
}

export interface Warning {
  type: string;
  severity: 'error' | 'warning' | 'info';
  message: string;
}

export interface OrderQueryView {
  id: UUID;
  orderId: UUID;
  message: string;
  status: 'OPEN' | 'RESOLVED';
  raisedByName?: string;
  raisedAt: ISODateTime;
  resolvedByName?: string;
  resolvedAt?: ISODateTime;
  resolution?: string;
}

export interface QueuePatient {
  id: UUID;
  mrn: string;
  fullName: string;
  age: number;
  gender: Gender;
  bloodGroup?: string;
}

export interface QueueItem {
  orderId: UUID;
  admissionId: UUID;
  admissionNumber: string;
  admissionStatus: AdmissionStatus;
  patient: QueuePatient;
  location: string;
  medicationId: UUID;
  medicationCode: string;
  medicationName: string;
  form: MedicationForm;
  strength: string;
  dispenseUnit: string;
  isHighAlert: boolean;
  isControlled: boolean;
  unitPrice: Decimal;
  dose: string;
  doseUnit: string;
  route: MedicationRoute;
  frequency: string;
  scheduleTimes?: string;
  startAt: ISODateTime;
  endAt?: ISODateTime;
  indication?: string;
  instructions?: string;
  allergyOverrideReason?: string;
  dischargeMedication: boolean;
  prescribedById: UUID;
  prescribedByName?: string;
  prescribedAt: ISODateTime;
  verified: boolean;
  verifiedByName?: string;
  verifiedAt?: ISODateTime;
  openQuery?: OrderQueryView;
  allergies: string[];
  warnings: Warning[];
  stockOnHand: number;
  availability: Availability;
  lastDispensedAt?: ISODateTime;
  netDispensedQuantity: number;
  netDispensedInCycle: number;
  suggestedQuantity?: number;
  suggestedQuantityBasis?: string;
  supplyStatus: SupplyStatus;
  orderVersion: number;
}

export interface BatchOption {
  batchId: UUID;
  batchNumber: string;
  expiryDate: ISODate;
  daysToExpiry: number;
  quantityOnHand: number;
  suggested: boolean;
}

export interface DispensationView {
  id: UUID;
  orderId: UUID;
  admissionId: UUID;
  admissionNumber?: string;
  patientId: UUID;
  patientName?: string;
  patientMrn?: string;
  medicationId: UUID;
  medicationCode?: string;
  medicationName?: string;
  dispenseUnit?: string;
  batchId: UUID;
  batchNumber?: string;
  batchExpiry?: ISODate;
  quantity: number;
  returnedQuantity: number;
  netQuantity: number;
  status: DispensationStatus;
  dispensedByName?: string;
  dispensedAt: ISODateTime;
  notes?: string;
  chargeLineId?: UUID;
  unitPrice?: Decimal;
  chargedAmount: Decimal;
}

export interface ChargeLineView {
  id: UUID;
  description: string;
  serviceDate: ISODate;
  quantity: Decimal;
  unitPrice: Decimal;
  totalAmount: Decimal;
  status: 'POSTED' | 'REVERSED' | 'REVERSAL';
  postedAt: ISODateTime;
  reason?: string;
}

export interface DispensationDetail {
  dispensation: DispensationView;
  movements: MovementView[];
  charges: ChargeLineView[];
}

export interface OrderDetail {
  item: QueueItem;
  admission: AdmissionSummary;
  otherActiveMedications: string[];
  interactionNote: string;
  supplyCycleHours: number;
  batches: BatchOption[];
  dispensations: DispensationView[];
  queries: OrderQueryView[];
}

export type AdjustDirection = 'IN' | 'OUT';
export type AdjustReason = 'COUNT_CORRECTION' | 'DAMAGE' | 'OTHER';

// ------------------------------------------------------------------ query keys

export const pharmacyKeys = {
  dashboard: ['pharmacy', 'dashboard'] as const,
  alerts: (days: number) => ['pharmacy', 'alerts', days] as const,
  stock: (params?: Query) => ['pharmacy', 'stock', params ?? {}] as const,
  batches: (params?: Query) => ['pharmacy', 'batches', params ?? {}] as const,
  batch: (id: UUID) => ['pharmacy', 'batch', id] as const,
  movements: (params?: Query) => ['pharmacy', 'movements', params ?? {}] as const,
  queue: (params?: Query) => ['pharmacy', 'queue', params ?? {}] as const,
  order: (id: UUID) => ['pharmacy', 'order', id] as const,
  dispensations: (params?: Query) => ['pharmacy', 'dispensations', params ?? {}] as const,
  dispensation: (id: UUID) => ['pharmacy', 'dispensation', id] as const,
  medications: (params?: Query) => ['medications', params ?? {}] as const,
  drugClasses: ['medications', 'classes'] as const,
};

// ------------------------------------------------------------------ API

export const medicationApi = {
  search: (params: {
    q?: string;
    activeOnly?: boolean;
    active?: boolean;
    form?: MedicationForm;
    drugClass?: string;
    highAlert?: boolean;
    controlled?: boolean;
    availability?: Availability;
    page?: number;
    size?: number;
    sort?: string;
  }) => api.get<PageResponse<MedicationView>>('/api/medications', params),
  get: (id: UUID) => api.get<MedicationView>(`/api/medications/${id}`),
  drugClasses: () => api.get<string[]>('/api/medications/classes'),
  create: (body: MedicationRequest) => api.post<MedicationView>('/api/medications', body),
  update: (id: UUID, body: MedicationRequest) => api.put<MedicationView>(`/api/medications/${id}`, body),
  deactivate: (id: UUID, reason: string) => api.post<MedicationView>(`/api/medications/${id}/deactivate`, { reason }),
  activate: (id: UUID, reason: string) => api.post<MedicationView>(`/api/medications/${id}/activate`, { reason }),
};

export const pharmacyApi = {
  dashboard: (nearExpiryDays = 90) => api.get<PharmacyDashboard>('/api/dashboard/pharmacy', { nearExpiryDays }),
  alerts: (nearExpiryDays = 90) => api.get<AlertsView>('/api/pharmacy/alerts', { nearExpiryDays }),
  stock: (params: { q?: string; availability?: Availability; active?: boolean; page?: number; size?: number; sort?: string }) =>
    api.get<PageResponse<StockLevel>>('/api/pharmacy/stock', params),
  exportStock: () => api.download('/api/pharmacy/stock/export', 'stock-report.csv'),
  receive: (body: {
    medicationId: UUID;
    batchNumber: string;
    expiryDate: ISODate;
    quantity: number;
    unitCost: string;
    supplier?: string | null;
  }) => api.post<BatchView>('/api/pharmacy/batches', body),
  batches: (params: {
    medicationId?: UUID;
    status?: BatchStatus[];
    expiringWithinDays?: number;
    includeEmpty?: boolean;
    q?: string;
    page?: number;
    size?: number;
    sort?: string;
  }) => api.get<PageResponse<BatchView>>('/api/pharmacy/batches', params),
  batch: (id: UUID) => api.get<BatchDetail>(`/api/pharmacy/batches/${id}`),
  quarantine: (id: UUID, reason: string) => api.post<BatchView>(`/api/pharmacy/batches/${id}/quarantine`, { reason }),
  release: (id: UUID, reason: string) => api.post<BatchView>(`/api/pharmacy/batches/${id}/release`, { reason }),
  writeOff: (id: UUID, reason: string) => api.post<BatchView>(`/api/pharmacy/batches/${id}/write-off`, { reason }),
  adjust: (id: UUID, body: { direction: AdjustDirection; quantity: number; reasonCode: AdjustReason; reason: string }) =>
    api.post<BatchView>(`/api/pharmacy/batches/${id}/adjust`, body),
  movements: (params: { batchId?: UUID; medicationId?: UUID; type?: MovementType[]; from?: ISODate; to?: ISODate; page?: number; size?: number }) =>
    api.get<PageResponse<MovementView>>('/api/pharmacy/movements', params),
  queue: (params: { filter?: QueueFilter; q?: string; admissionId?: UUID; page?: number; size?: number }) =>
    api.get<PageResponse<QueueItem>>('/api/pharmacy/queue', params),
  order: (orderId: UUID) => api.get<OrderDetail>(`/api/pharmacy/orders/${orderId}`),
  verify: (orderId: UUID, body: { allergyAcknowledged: boolean; note?: string }) =>
    api.post<QueueItem>(`/api/pharmacy/orders/${orderId}/verify`, body),
  raiseQuery: (orderId: UUID, message: string) => api.post<OrderQueryView>(`/api/pharmacy/orders/${orderId}/queries`, { message }),
  resolveQuery: (queryId: UUID, resolution: string) =>
    api.post<OrderQueryView>(`/api/pharmacy/queries/${queryId}/resolve`, { resolution }),
  dispense: (body: { orderId: UUID; batchId?: UUID; quantity: number; notes?: string; safetyChecksConfirmed: boolean }) =>
    api.post<DispensationView>('/api/pharmacy/dispensations', body),
  dispensations: (params: {
    from?: ISODate;
    to?: ISODate;
    patientId?: UUID;
    medicationId?: UUID;
    admissionId?: UUID;
    status?: DispensationStatus[];
    q?: string;
    page?: number;
    size?: number;
    sort?: string;
  }) => api.get<PageResponse<DispensationView>>('/api/pharmacy/dispensations', params),
  dispensation: (id: UUID) => api.get<DispensationDetail>(`/api/pharmacy/dispensations/${id}`),
  returnStock: (id: UUID, body: { quantity: number; reason: string; damaged: boolean }) =>
    api.post<DispensationView>(`/api/pharmacy/dispensations/${id}/returns`, body),
};
