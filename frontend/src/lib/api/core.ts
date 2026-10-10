import { api, Query } from './client';
import type {
  AdmissionDetail,
  AdmissionStatus,
  AdmissionSummary,
  AdmissionType,
  BedStatus,
  BedType,
  BedView,
  CaregiverGrant,
  CareTeamMember,
  DepartmentView,
  DirectoryEntry,
  Me,
  NotificationView,
  PageResponse,
  PatientDetail,
  PatientRequest,
  PatientSummary,
  Role,
  TimelineCategory,
  TimelineItem,
  TransferStatus,
  TransferView,
  UUID,
  WardBoard,
  WardView,
} from './types';

/** Query-key factory. Keys are hierarchical so related lists can be invalidated together. */
export const qk = {
  me: ['me'] as const,
  publicConfig: ['public-config'] as const,
  patients: (params?: Query) => ['patients', params ?? {}] as const,
  patient: (id: UUID) => ['patient', id] as const,
  patientAdmissions: (id: UUID) => ['patient', id, 'admissions'] as const,
  caregivers: (id: UUID) => ['patient', id, 'caregivers'] as const,
  admissions: (params?: Query) => ['admissions', params ?? {}] as const,
  myAdmissions: ['admissions', 'my'] as const,
  admission: (id: UUID) => ['admission', id] as const,
  timeline: (admissionId: UUID, params?: Query) => ['admission', admissionId, 'timeline', params ?? {}] as const,
  patientTimeline: (patientId: UUID, params?: Query) => ['patient', patientId, 'timeline', params ?? {}] as const,
  transfers: (params?: Query) => ['transfers', params ?? {}] as const,
  bedBoard: (params?: Query) => ['beds', 'board', params ?? {}] as const,
  availableBeds: (params?: Query) => ['beds', 'available', params ?? {}] as const,
  wards: ['wards'] as const,
  departments: ['departments'] as const,
  directory: (params?: Query) => ['staff', 'directory', params ?? {}] as const,
  notifications: (params?: Query) => ['notifications', params ?? {}] as const,
  unreadCount: ['notifications', 'unread-count'] as const,
};

export const authApi = {
  me: () => api.get<Me>('/api/auth/me'),
  login: (email: string, password: string) => api.post<{ accessToken: string }>('/api/auth/login', { email, password }),
  logout: () => api.post<void>('/api/auth/logout'),
  forgotPassword: (email: string) => api.post<{ message: string }>('/api/auth/forgot-password', { email }),
  accountRecovery: (email: string, reason?: string) =>
    api.post<{ message: string }>('/api/auth/account-recovery', { email, reason }),
  validateResetToken: (token: string) => api.get<{ valid: boolean }>('/api/auth/reset-token/validate', { token }),
  resetPassword: (token: string, newPassword: string) => api.post<void>('/api/auth/reset-password', { token, newPassword }),
  changePassword: (currentPassword: string, newPassword: string) =>
    api.post<void>('/api/auth/change-password', { currentPassword, newPassword }),
  publicConfig: () => api.get<{ appName: string; demoMode: boolean; devOutboxEnabled: boolean }>('/api/public/config'),
  devOutbox: () =>
    api.get<Array<{ id: UUID; channel: string; destination: string; subject?: string; body: string; createdAt: string }>>(
      '/api/dev/outbox',
    ),
};

export const patientApi = {
  search: (params: { q?: string; status?: string; page?: number; size?: number; sort?: string }) =>
    api.get<PageResponse<PatientSummary>>('/api/patients', params),
  get: (id: UUID) => api.get<PatientDetail>(`/api/patients/${id}`),
  register: (body: PatientRequest) => api.post<PatientDetail>('/api/patients', body),
  update: (id: UUID, body: PatientRequest) => api.put<PatientDetail>(`/api/patients/${id}`, body),
  createPortalAccount: (id: UUID, email: string) => api.post<PatientDetail>(`/api/patients/${id}/portal-account`, { email }),
  admissions: (id: UUID) => api.get<AdmissionSummary[]>(`/api/patients/${id}/admissions`),
  timeline: (id: UUID, params?: { category?: TimelineCategory[]; page?: number; size?: number }) =>
    api.get<PageResponse<TimelineItem>>(`/api/patients/${id}/timeline`, params),
  caregivers: (id: UUID) => api.get<CaregiverGrant[]>(`/api/patients/${id}/caregivers`),
  addCaregiver: (
    id: UUID,
    body: { email: string; fullName?: string; phone?: string; relationship: string; scope: CaregiverGrant['scope']; expiresAt?: string; notes?: string },
  ) => api.post<CaregiverGrant>(`/api/patients/${id}/caregivers`, body),
  revokeCaregiver: (id: UUID, grantId: UUID) => api.del<CaregiverGrant>(`/api/patients/${id}/caregivers/${grantId}`),
};

export interface AdmitRequest {
  patientId: UUID;
  admissionType: AdmissionType;
  admittedAt?: string;
  admissionReason: string;
  departmentId: UUID;
  attendingDoctorId: UUID;
  referringDoctor?: string;
  expectedDischargeDate?: string | null;
  requiresIsolation: boolean;
  bedRequirements?: string;
  bedId?: UUID | null;
}

export const admissionApi = {
  search: (params: {
    status?: AdmissionStatus[];
    departmentId?: UUID;
    doctorId?: UUID;
    q?: string;
    page?: number;
    size?: number;
    sort?: string;
  }) => api.get<PageResponse<AdmissionSummary>>('/api/admissions', params),
  my: () => api.get<AdmissionSummary[]>('/api/admissions/my'),
  get: (id: UUID) => api.get<AdmissionDetail>(`/api/admissions/${id}`),
  admit: (body: AdmitRequest) => api.post<AdmissionDetail>('/api/admissions', body),
  update: (id: UUID, body: Omit<AdmitRequest, 'patientId' | 'admissionType' | 'admittedAt' | 'bedId'> & { version: number }) =>
    api.put<AdmissionDetail>(`/api/admissions/${id}`, body),
  setExpectedDischarge: (id: UUID, expectedDischargeDate: string | null) =>
    api.put<AdmissionDetail>(`/api/admissions/${id}/expected-discharge`, { expectedDischargeDate }),
  assignBed: (id: UUID, bedId: UUID, notes?: string) => api.post<AdmissionDetail>(`/api/admissions/${id}/assign-bed`, { bedId, notes }),
  cancel: (id: UUID, reason: string) => api.post<AdmissionDetail>(`/api/admissions/${id}/cancel`, { reason }),
  careTeam: (id: UUID) => api.get<CareTeamMember[]>(`/api/admissions/${id}/care-team`),
  addCareTeam: (id: UUID, userId: UUID, careRole: CareTeamMember['careRole']) =>
    api.post<CareTeamMember[]>(`/api/admissions/${id}/care-team`, { userId, careRole }),
  removeCareTeam: (id: UUID, memberId: UUID) => api.del<CareTeamMember[]>(`/api/admissions/${id}/care-team/${memberId}`),
  timeline: (id: UUID, params?: { category?: TimelineCategory[]; page?: number; size?: number }) =>
    api.get<PageResponse<TimelineItem>>(`/api/admissions/${id}/timeline`, params),
  requestTransfer: (id: UUID, body: { toWardId?: UUID; toBedId?: UUID; reason: string; priority?: 'ROUTINE' | 'URGENT' }) =>
    api.post<TransferView>(`/api/admissions/${id}/transfers`, body),
};

export const transferApi = {
  list: (params: { status?: TransferStatus[]; page?: number; size?: number }) =>
    api.get<PageResponse<TransferView>>('/api/transfers', params),
  approve: (id: UUID, toBedId?: UUID, notes?: string) => api.post<TransferView>(`/api/transfers/${id}/approve`, { toBedId, notes }),
  complete: (id: UUID) => api.post<TransferView>(`/api/transfers/${id}/complete`),
  reject: (id: UUID, notes?: string) => api.post<TransferView>(`/api/transfers/${id}/reject`, { notes }),
  cancel: (id: UUID, notes?: string) => api.post<TransferView>(`/api/transfers/${id}/cancel`, { notes }),
};

export const facilityApi = {
  departments: () => api.get<DepartmentView[]>('/api/departments'),
  wards: () => api.get<WardView[]>('/api/wards'),
  bedBoard: (params?: { wardId?: UUID; departmentId?: UUID; status?: BedStatus; bedType?: BedType }) =>
    api.get<WardBoard[]>('/api/beds/board', params),
  availableBeds: (params?: { wardId?: UUID; bedType?: BedType; isolationOnly?: boolean }) =>
    api.get<BedView[]>('/api/beds/available', params),
  changeBedStatus: (id: UUID, status: BedStatus, reason?: string, version?: number) =>
    api.patch<BedView>(`/api/beds/${id}/status`, { status, reason, version }),
  bedHistory: (id: UUID) =>
    api.get<Array<{ at: string; kind: string; description: string; actor: string }>>(`/api/beds/${id}/history`),
};

export const staffApi = {
  directory: (params?: { role?: Role; departmentId?: UUID }) => api.get<DirectoryEntry[]>('/api/staff/directory', params),
};

export const notificationApi = {
  list: (params: { unreadOnly?: boolean; page?: number; size?: number }) =>
    api.get<PageResponse<NotificationView>>('/api/notifications', params),
  unreadCount: () => api.get<{ count: number }>('/api/notifications/unread-count'),
  markRead: (id: UUID) => api.post<NotificationView>(`/api/notifications/${id}/read`),
  markAllRead: () => api.post<{ updated: number }>('/api/notifications/read-all'),
  acknowledge: (id: UUID) => api.post<NotificationView>(`/api/notifications/${id}/acknowledge`),
};
