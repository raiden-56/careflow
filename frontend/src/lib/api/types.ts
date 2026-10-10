/** Shared types mirroring the backend's core DTOs. Monetary/decimal values are strings. */

export type UUID = string;
export type ISODateTime = string; // UTC instant, e.g. 2026-10-10T08:30:00Z
export type ISODate = string; // yyyy-MM-dd
export type Decimal = string; // e.g. "1250.00"

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export type Role = 'SUPER_ADMIN' | 'HOSPITAL_ADMIN' | 'DOCTOR' | 'NURSE' | 'BILLING' | 'PHARMACIST' | 'PATIENT';

export interface OrganizationInfo {
  id: UUID;
  code: string;
  name: string;
  timezone: string;
  currency: string;
}

export interface WardInfo {
  id: UUID;
  code: string;
  name: string;
}

export interface StaffInfo {
  designation?: string;
  departmentId?: UUID;
  departmentName?: string;
  specialty?: string;
  employeeCode?: string;
  wards: WardInfo[];
}

export interface PatientLink {
  patientId: UUID;
  name: string;
  mrn: string;
  relationship: string;
  scope: 'FULL' | 'BILLING_ONLY' | 'CLINICAL_ONLY';
}

export interface Me {
  id: UUID;
  email: string;
  fullName: string;
  phone?: string;
  role: Role;
  permissions: string[];
  mustChangePassword: boolean;
  organization: OrganizationInfo;
  staff?: StaffInfo;
  patientId?: UUID;
  accessiblePatients: PatientLink[];
}

// ------------------------------------------------------------------ patients

export type Gender = 'MALE' | 'FEMALE' | 'OTHER' | 'UNDISCLOSED';
export type AdmissionStatus = 'PENDING_BED' | 'ADMITTED' | 'DISCHARGE_IN_PROGRESS' | 'DISCHARGED' | 'CANCELLED';
export type AdmissionType = 'ELECTIVE' | 'EMERGENCY' | 'TRANSFER_IN' | 'DAYCARE';

export interface CurrentLocation {
  admissionId: UUID;
  admissionNumber: string;
  status: AdmissionStatus;
  wardName?: string;
  roomNumber?: string;
  bedNumber?: string;
}

export interface PatientSummary {
  id: UUID;
  mrn: string;
  fullName: string;
  firstName: string;
  lastName: string;
  dateOfBirth: ISODate;
  age: number;
  gender: Gender;
  phone?: string;
  status: 'ACTIVE' | 'ARCHIVED' | 'DECEASED';
  hasPortalAccount: boolean;
  currentAdmission?: CurrentLocation;
  createdAt: ISODateTime;
}

export interface PatientDetail extends PatientSummary {
  bloodGroup?: string;
  email?: string;
  addressLine?: string;
  city?: string;
  state?: string;
  pincode?: string;
  emergencyContactName?: string;
  emergencyContactPhone?: string;
  emergencyContactRelation?: string;
  abhaNumber?: string;
  preferredLanguage?: string;
  updatedAt: ISODateTime;
  version: number;
}

export interface PatientRequest {
  firstName: string;
  lastName: string;
  dateOfBirth: ISODate;
  gender: Gender;
  bloodGroup?: string | null;
  phone?: string | null;
  email?: string | null;
  addressLine?: string | null;
  city?: string | null;
  state?: string | null;
  pincode?: string | null;
  emergencyContactName?: string | null;
  emergencyContactPhone?: string | null;
  emergencyContactRelation?: string | null;
  abhaNumber?: string | null;
  preferredLanguage?: string | null;
  version?: number;
  confirmNotDuplicate?: boolean;
}

export interface CaregiverGrant {
  id: UUID;
  granteeUserId: UUID;
  granteeName?: string;
  granteeEmail?: string;
  relationship: string;
  scope: 'FULL' | 'BILLING_ONLY' | 'CLINICAL_ONLY';
  grantedAt: ISODateTime;
  expiresAt?: ISODateTime;
  revokedAt?: ISODateTime;
  active: boolean;
}

// ------------------------------------------------------------------ admissions

export interface PatientBrief {
  id: UUID;
  mrn: string;
  fullName: string;
  age: number;
  gender: Gender;
  bloodGroup?: string;
}

export interface BedLocation {
  bedId: UUID;
  bedNumber: string;
  roomId: UUID;
  roomNumber?: string;
  wardId: UUID;
  wardName?: string;
  since: ISODateTime;
}

export interface AdmissionSummary {
  id: UUID;
  admissionNumber: string;
  status: AdmissionStatus;
  admissionType: AdmissionType;
  admittedAt: ISODateTime;
  expectedDischargeDate?: ISODate;
  dischargedAt?: ISODateTime;
  patient: PatientBrief;
  departmentId: UUID;
  departmentName?: string;
  attendingDoctorId: UUID;
  attendingDoctorName?: string;
  location?: BedLocation;
  lengthOfStayDays: number;
  requiresIsolation: boolean;
  /** Present only for callers with clinical access. */
  allergies?: string[];
}

export interface CareTeamMember {
  id: UUID;
  userId: UUID;
  fullName: string;
  role?: Role;
  careRole: 'ATTENDING' | 'CONSULTANT' | 'RESIDENT' | 'PRIMARY_NURSE';
  addedAt: ISODateTime;
}

export interface BedStay {
  bedId: UUID;
  bedNumber?: string;
  roomNumber?: string;
  wardName?: string;
  assignedAt: ISODateTime;
  releasedAt?: ISODateTime;
  releaseReason?: string;
}

export type TransferStatus = 'REQUESTED' | 'APPROVED' | 'COMPLETED' | 'CANCELLED' | 'REJECTED';

export interface TransferView {
  id: UUID;
  admissionId: UUID;
  admissionNumber?: string;
  patientName?: string;
  patientMrn?: string;
  status: TransferStatus;
  priority: 'ROUTINE' | 'URGENT';
  reason: string;
  fromLocation?: string;
  toWardId?: UUID;
  toWardName?: string;
  toBedId?: UUID;
  toLocation?: string;
  requestedBy?: string;
  requestedAt: ISODateTime;
  decidedBy?: string;
  decidedAt?: ISODateTime;
  decisionNotes?: string;
  completedBy?: string;
  completedAt?: ISODateTime;
  version: number;
}

export interface AdmissionDetail {
  summary: AdmissionSummary;
  admissionReason: string;
  referringDoctor?: string;
  bedRequirements?: string;
  cancellationReason?: string;
  dischargeDisposition?: string;
  createdByName?: string;
  createdAt: ISODateTime;
  careTeam: CareTeamMember[];
  bedHistory: BedStay[];
  transfers: TransferView[];
  clinicalAccess: boolean;
  version: number;
}

// ------------------------------------------------------------------ facility

export type BedStatus = 'AVAILABLE' | 'OCCUPIED' | 'RESERVED' | 'CLEANING' | 'MAINTENANCE';
export type BedType = 'STANDARD' | 'ICU' | 'ISOLATION' | 'PAEDIATRIC' | 'BARIATRIC' | 'MATERNITY';
export type RoomType = 'GENERAL' | 'SEMI_PRIVATE' | 'PRIVATE' | 'DELUXE' | 'ICU' | 'ISOLATION';
export type WardType = 'GENERAL' | 'ICU' | 'HDU' | 'PRIVATE' | 'PAEDIATRIC' | 'MATERNITY' | 'ISOLATION' | 'DAYCARE';

export interface Occupant {
  admissionId: UUID;
  admissionNumber: string;
  patientId: UUID;
  patientName: string;
  mrn?: string;
  gender?: Gender;
  since: ISODateTime;
  requiresIsolation: boolean;
  attendingDoctor?: string;
  admissionStatus: AdmissionStatus;
}

export interface BedView {
  id: UUID;
  bedNumber: string;
  bedType: BedType;
  status: BedStatus;
  isolationCapable: boolean;
  oxygenSupported: boolean;
  notes?: string;
  statusChangedAt: ISODateTime;
  active: boolean;
  version: number;
  roomId: UUID;
  roomNumber?: string;
  roomType?: RoomType;
  wardId: UUID;
  wardName?: string;
  occupant?: Occupant;
}

export interface RoomBoard {
  id: UUID;
  roomNumber: string;
  roomType: RoomType;
  beds: BedView[];
}

export interface WardBoard {
  id: UUID;
  code: string;
  name: string;
  wardType: WardType;
  departmentName?: string;
  floor?: string;
  genderPolicy: 'MIXED' | 'MALE' | 'FEMALE';
  rooms: RoomBoard[];
}

export interface WardView {
  id: UUID;
  code: string;
  name: string;
  wardType: WardType;
  departmentId?: UUID;
  departmentName?: string;
  floor?: string;
  genderPolicy: 'MIXED' | 'MALE' | 'FEMALE';
  active: boolean;
  totalBeds: number;
  available: number;
  occupied: number;
  reserved: number;
  cleaning: number;
  maintenance: number;
}

export interface DepartmentView {
  id: UUID;
  code: string;
  name: string;
  specialty?: string;
  description?: string;
  headUserId?: UUID;
  headName?: string;
  active: boolean;
  staffCount: number;
  wardCount: number;
}

export interface DirectoryEntry {
  id: UUID;
  fullName: string;
  role: Role;
  departmentId?: UUID;
  departmentName?: string;
  designation?: string;
  specialty?: string;
}

// ------------------------------------------------------------------ notifications & timeline

export type NotificationPriority = 'LOW' | 'NORMAL' | 'HIGH' | 'CRITICAL';

export interface NotificationView {
  id: UUID;
  category: string;
  priority: NotificationPriority;
  title: string;
  message: string;
  link?: string;
  entityType?: string;
  entityId?: UUID;
  requiresAck: boolean;
  readAt?: ISODateTime;
  acknowledgedAt?: ISODateTime;
  createdAt: ISODateTime;
}

export type TimelineCategory =
  | 'ADMISSION'
  | 'CLINICAL'
  | 'MEDICATION'
  | 'OBSERVATION'
  | 'INVESTIGATION'
  | 'NURSING'
  | 'BILLING'
  | 'DISCHARGE'
  | 'TRANSFER'
  | 'SAFETY';

export interface TimelineItem {
  id: UUID;
  admissionId?: UUID;
  eventType: string;
  category: TimelineCategory;
  title: string;
  detail?: string;
  status?: string;
  visibility: 'PATIENT' | 'CARE_TEAM' | 'BILLING' | 'INTERNAL';
  entityType?: string;
  entityId?: UUID;
  actorName?: string;
  actorRole?: string;
  occurredAt: ISODateTime;
}
