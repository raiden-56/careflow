import { api, Query } from './client';
import type { ISODateTime, PageResponse, Role, UUID } from './types';

export type OrgStatus = 'ACTIVE' | 'SUSPENDED';

export interface OrganizationRow {
  id: UUID;
  code: string;
  name: string;
  legalName?: string;
  city?: string;
  state?: string;
  timezone: string;
  status: OrgStatus;
  staffUsers: number;
  hospitalAdmins: number;
  wards: number;
  beds: number;
  lastLoginAt?: ISODateTime;
  createdAt: ISODateTime;
}

export interface AdminAccount {
  id: UUID;
  organizationId: UUID;
  organizationName: string;
  organizationCode: string;
  email: string;
  fullName: string;
  phone?: string;
  status: 'ACTIVE' | 'LOCKED' | 'DISABLED';
  locked: boolean;
  invitationPending: boolean;
  lastLoginAt?: ISODateTime;
  createdAt: ISODateTime;
}

export interface OrganizationDetail {
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
  status: OrgStatus;
  createdAt: ISODateTime;
  updatedAt: ISODateTime;
  version: number;
  usersByRole: Record<string, number>;
  wards: number;
  beds: number;
  administrators: AdminAccount[];
}

export interface OrganizationRequest {
  code?: string;
  name: string;
  legalName?: string | null;
  timezone: string;
  address?: string | null;
  city?: string | null;
  state?: string | null;
  phone?: string | null;
  email?: string | null;
  registrationNumber?: string | null;
  version?: number;
}

export interface TemplateCatalogue {
  defaults: Record<Role, string[]>;
  permissions: Array<{ code: string; category: string; description: string }>;
  roles: Array<{ role: Role; name: string; description?: string }>;
}

export interface OrgRoleMatrix {
  organizationId: UUID;
  organizationName: string;
  roles: Array<{
    role: Role;
    name: string;
    current: string[];
    defaults: string[];
    added: string[];
    removed: string[];
    matchesDefault: boolean;
    users: number;
  }>;
}

export interface SecurityEvent {
  id: UUID;
  occurredAt: ISODateTime;
  organizationId?: UUID;
  organizationName?: string;
  actorName?: string;
  actorRole?: string;
  action: string;
  category: string;
  outcome: 'SUCCESS' | 'DENIED' | 'FAILURE';
  summary?: string;
  ipAddress?: string;
}

export interface SecuritySummary {
  failedLogins24h: number;
  lockouts24h: number;
  deniedAccess24h: number;
  sessionReuse7d: number;
  failedLogins7d: number;
  deniedAccess7d: number;
}

export interface IntegrationRow {
  organizationId: UUID;
  organizationName: string;
  organizationCode: string;
  integrationType: string;
  label: string;
  mode: string;
  endpointUrl?: string;
  secretReferenced: boolean;
  adapterStatus: string;
  updatedAt: ISODateTime;
}

export interface PlatformOverview {
  organizations: number;
  activeOrganizations: number;
  suspendedOrganizations: number;
  staffUsers: number;
  hospitalAdmins: number;
  activeSessions: number;
  security: SecuritySummary;
  recentOrganizations: OrganizationRow[];
  integrationModes: Record<string, number>;
}

export interface PlatformHealth {
  status: string;
  checkedAt: ISODateTime;
  databaseUp: boolean;
  databaseVersion?: string;
  databaseLatencyMs: number;
  flywayCurrentVersion?: string;
  flywayApplied: number;
  flywayAllSucceeded: boolean;
  migrations: Array<{ rank: number; version?: string; description: string; installedOn: ISODateTime; success: boolean; executionTimeMs: number }>;
  organizations: number;
  users: number;
  activeSessions: number;
  jobs: Array<{ job: string; lastStartedAt: ISODateTime; lastDurationMs: number; lastSucceeded: boolean; lastError?: string; runs: number; failures: number }>;
  uptimeSeconds: number;
  heapUsedMb: number;
  heapMaxMb: number;
  availableProcessors: number;
  liveThreads: number;
  javaVersion: string;
  demoMode: boolean;
  devOutboxEnabled: boolean;
}

export const SECURITY_CATEGORIES: Array<{ value: string; label: string }> = [
  { value: 'FAILED_LOGIN', label: 'Failed sign-ins' },
  { value: 'LOCKOUT', label: 'Lockouts / blocked sign-ins' },
  { value: 'ACCESS_DENIED', label: 'Denied access' },
  { value: 'SESSION_REUSE', label: 'Session reuse detected' },
  { value: 'ACCOUNT_CHANGE', label: 'Account changes' },
  { value: 'PERMISSION_CHANGE', label: 'Permission changes' },
  { value: 'ORGANIZATION_CHANGE', label: 'Organization changes' },
  { value: 'INTEGRATION_CHANGE', label: 'Integration changes' },
];

export const platformApi = {
  overview: () => api.get<PlatformOverview>('/api/platform/overview'),
  organizations: (p: { q?: string; status?: string; page?: number; size?: number }) =>
    api.get<PageResponse<OrganizationRow>>('/api/platform/organizations', p as Query),
  organization: (id: UUID) => api.get<OrganizationDetail>(`/api/platform/organizations/${id}`),
  createOrganization: (body: OrganizationRequest) => api.post<OrganizationDetail>('/api/platform/organizations', body),
  updateOrganization: (id: UUID, body: OrganizationRequest) => api.put<OrganizationDetail>(`/api/platform/organizations/${id}`, body),
  suspend: (id: UUID, reason: string) => api.post<OrganizationDetail>(`/api/platform/organizations/${id}/suspend`, { reason }),
  reactivate: (id: UUID) => api.post<OrganizationDetail>(`/api/platform/organizations/${id}/reactivate`),
  admins: (p: { organizationId?: string; q?: string; page?: number; size?: number }) =>
    api.get<PageResponse<AdminAccount>>('/api/platform/admins', p as Query),
  createAdmin: (orgId: UUID, body: { email: string; fullName: string; phone?: string | null; designation?: string | null }) =>
    api.post<AdminAccount>(`/api/platform/organizations/${orgId}/admins`, body),
  adminStatus: (userId: UUID, status: AdminAccount['status'], reason?: string) =>
    api.post<AdminAccount>(`/api/platform/admins/${userId}/status`, { status, reason }),
  resendInvite: (userId: UUID) => api.post<void>(`/api/platform/admins/${userId}/invite`),
  templates: () => api.get<TemplateCatalogue>('/api/platform/permission-templates'),
  orgRoles: (orgId: UUID) => api.get<OrgRoleMatrix>(`/api/platform/organizations/${orgId}/roles`),
  resetRole: (orgId: UUID, role: Role) => api.post<OrgRoleMatrix>(`/api/platform/organizations/${orgId}/roles/${role}/reset`),
  securityEvents: (p: { organizationId?: string; category?: string; outcome?: string; from?: string; to?: string; q?: string; page?: number; size?: number }) =>
    api.get<PageResponse<SecurityEvent>>('/api/platform/security-events', p as Query),
  securitySummary: () => api.get<SecuritySummary>('/api/platform/security-summary'),
  integrations: (organizationId?: string) => api.get<IntegrationRow[]>('/api/platform/integrations', { organizationId }),
  health: () => api.get<PlatformHealth>('/api/platform/health'),
};
