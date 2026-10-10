import type { LucideIcon } from 'lucide-react';
import {
  Activity,
  AlertTriangle,
  BadgeIndianRupee,
  BarChart3,
  BedDouble,
  Bell,
  Boxes,
  Building2,
  CalendarClock,
  ClipboardCheck,
  ClipboardList,
  CreditCard,
  FileCheck2,
  FileClock,
  FileHeart,
  FileSpreadsheet,
  FileText,
  FlaskConical,
  HeartPulse,
  History,
  Hospital,
  LayoutDashboard,
  ListChecks,
  LogOut as LogOutIcon,
  MessageSquareWarning,
  Network,
  PackageSearch,
  Pill,
  PlugZap,
  Receipt,
  RefreshCcw,
  Repeat2,
  Scale,
  ScrollText,
  Settings,
  ShieldCheck,
  ShieldAlert,
  Stethoscope,
  Syringe,
  TestTubes,
  Timer,
  UserCog,
  UserPlus,
  Users,
  Wallet,
} from 'lucide-react';
import type { Role } from './api/types';

export interface NavItem {
  label: string;
  href: string;
  icon: LucideIcon;
  /** Show only when the user holds this permission (UI hint only; the API always enforces access). */
  permission?: string;
}

export interface NavSection {
  title?: string;
  items: NavItem[];
}

/**
 * Role-specific navigation. Every href here MUST resolve to a working page.
 * Detail pages (e.g. /doctor/patients/[admissionId]) are reached from the list pages.
 */
export const NAV: Record<Role, NavSection[]> = {
  PATIENT: [
    {
      items: [
        { label: 'Dashboard', href: '/patient', icon: LayoutDashboard },
        { label: 'My admissions', href: '/patient/admissions', icon: Hospital },
        { label: 'Medical records', href: '/patient/records', icon: FileHeart },
        { label: 'Medications', href: '/patient/medications', icon: Pill },
      ],
    },
    {
      title: 'Billing',
      items: [
        { label: 'My bills', href: '/patient/bills', icon: Receipt },
        { label: 'Payments & receipts', href: '/patient/payments', icon: Wallet },
      ],
    },
    {
      title: 'Care',
      items: [
        { label: 'Discharge information', href: '/patient/discharge', icon: FileCheck2 },
        { label: 'Follow-up appointments', href: '/patient/follow-ups', icon: CalendarClock },
        { label: 'Privacy & consent', href: '/patient/privacy', icon: ShieldCheck },
      ],
    },
  ],
  NURSE: [
    {
      items: [
        { label: 'Dashboard', href: '/nurse', icon: LayoutDashboard },
        { label: 'Assigned patients', href: '/nurse/patients', icon: Users },
        { label: 'Medication rounds', href: '/nurse/medications', icon: Syringe },
        { label: 'Administration records', href: '/nurse/mar', icon: ClipboardList },
        { label: 'Medication exceptions', href: '/nurse/exceptions', icon: MessageSquareWarning },
      ],
    },
    {
      title: 'Care',
      items: [
        { label: 'Vital signs', href: '/nurse/vitals', icon: HeartPulse },
        { label: 'Nursing assessments', href: '/nurse/assessments', icon: FileText },
        { label: 'Care task board', href: '/nurse/tasks', icon: ListChecks },
        { label: 'Shift handover', href: '/nurse/handover', icon: Repeat2 },
      ],
    },
    {
      title: 'Flow',
      items: [
        { label: 'Transfers', href: '/nurse/transfers', icon: BedDouble },
        { label: 'Discharge checklist', href: '/nurse/discharge', icon: ClipboardCheck },
        { label: 'Report incident', href: '/incidents', icon: ShieldAlert },
      ],
    },
  ],
  DOCTOR: [
    {
      items: [
        { label: 'Dashboard', href: '/doctor', icon: LayoutDashboard },
        { label: 'My patients', href: '/doctor/patients', icon: Stethoscope },
        { label: 'Clinical alerts', href: '/doctor/alerts', icon: AlertTriangle },
        { label: 'Investigations', href: '/doctor/investigations', icon: FlaskConical },
        { label: 'Prescriptions', href: '/doctor/prescriptions', icon: Pill },
      ],
    },
    {
      title: 'Discharge & continuity',
      items: [
        { label: 'Discharge planning', href: '/doctor/discharge', icon: FileCheck2 },
        { label: 'Follow-up plans', href: '/doctor/follow-ups', icon: CalendarClock },
        { label: 'Report incident', href: '/incidents', icon: ShieldAlert },
      ],
    },
  ],
  HOSPITAL_ADMIN: [
    {
      items: [
        { label: 'Hospital dashboard', href: '/admin', icon: LayoutDashboard },
        { label: 'Patients', href: '/admin/patients', icon: UserPlus, permission: 'PATIENT_DEMOGRAPHICS_READ' },
        { label: 'Admissions', href: '/admin/admissions', icon: Hospital, permission: 'ADMISSION_MANAGE' },
        { label: 'Bed board', href: '/admin/beds', icon: BedDouble },
        { label: 'Transfers', href: '/admin/transfers', icon: RefreshCcw, permission: 'TRANSFER_COMPLETE' },
        { label: 'Discharge coordination', href: '/admin/discharges', icon: FileCheck2, permission: 'DISCHARGE_VIEW' },
      ],
    },
    {
      title: 'Organisation',
      items: [
        { label: 'Staff', href: '/admin/staff', icon: Users, permission: 'USER_MANAGE' },
        { label: 'Roles & permissions', href: '/admin/roles', icon: UserCog, permission: 'ROLE_MANAGE' },
        { label: 'Departments', href: '/admin/departments', icon: Building2, permission: 'DEPARTMENT_MANAGE' },
        { label: 'Wards, rooms & beds', href: '/admin/facility', icon: Network, permission: 'FACILITY_MANAGE' },
        { label: 'Services & tariffs', href: '/admin/tariffs', icon: BadgeIndianRupee, permission: 'TARIFF_MANAGE' },
        { label: 'Settings & rules', href: '/admin/settings', icon: Settings, permission: 'SETTINGS_MANAGE' },
      ],
    },
    {
      title: 'Oversight',
      items: [
        { label: 'Operational analytics', href: '/admin/analytics', icon: BarChart3, permission: 'REPORT_OPERATIONAL' },
        { label: 'Safety incidents', href: '/admin/incidents', icon: ShieldAlert, permission: 'INCIDENT_REVIEW' },
        { label: 'Consent & requests', href: '/admin/privacy', icon: ShieldCheck, permission: 'PATIENT_REQUEST_MANAGE' },
        { label: 'Audit logs', href: '/admin/audit', icon: ScrollText, permission: 'AUDIT_READ' },
      ],
    },
  ],
  BILLING: [
    {
      items: [
        { label: 'Billing dashboard', href: '/billing', icon: LayoutDashboard },
        { label: 'Invoices', href: '/billing/invoices', icon: FileSpreadsheet },
        { label: 'Payment collection', href: '/billing/payments', icon: CreditCard },
        { label: 'Refunds & adjustments', href: '/billing/adjustments', icon: Scale },
        { label: 'Insurance claims', href: '/billing/claims', icon: FileClock },
      ],
    },
    {
      title: 'Follow-through',
      items: [
        { label: 'Outstanding balances', href: '/billing/outstanding', icon: Timer },
        { label: 'Discharge clearance', href: '/billing/discharge', icon: FileCheck2 },
        { label: 'Patient queries', href: '/billing/requests', icon: MessageSquareWarning },
        { label: 'Financial reports', href: '/billing/reports', icon: BarChart3 },
      ],
    },
  ],
  PHARMACIST: [
    {
      items: [
        { label: 'Pharmacy dashboard', href: '/pharmacy', icon: LayoutDashboard },
        { label: 'Prescription queue', href: '/pharmacy/prescriptions', icon: ClipboardList },
        { label: 'Dispensing', href: '/pharmacy/dispensing', icon: Pill },
        { label: 'Medication catalogue', href: '/pharmacy/catalogue', icon: TestTubes },
      ],
    },
    {
      title: 'Inventory',
      items: [
        { label: 'Inventory', href: '/pharmacy/inventory', icon: Boxes },
        { label: 'Batches & expiry', href: '/pharmacy/batches', icon: PackageSearch },
        { label: 'Stock alerts', href: '/pharmacy/alerts', icon: AlertTriangle },
        { label: 'Report incident', href: '/incidents', icon: ShieldAlert },
      ],
    },
  ],
  SUPER_ADMIN: [
    {
      items: [
        { label: 'Platform overview', href: '/platform', icon: LayoutDashboard },
        { label: 'Organizations', href: '/platform/organizations', icon: Building2 },
        { label: 'User provisioning', href: '/platform/users', icon: UserPlus },
        { label: 'Permission templates', href: '/platform/permissions', icon: UserCog },
      ],
    },
    {
      title: 'Operations',
      items: [
        { label: 'Security & audit', href: '/platform/audit', icon: History },
        { label: 'Integrations', href: '/platform/integrations', icon: PlugZap },
        { label: 'System health', href: '/platform/health', icon: Activity },
      ],
    },
  ],
};

export const ROLE_HOME: Record<Role, string> = {
  PATIENT: '/patient',
  NURSE: '/nurse',
  DOCTOR: '/doctor',
  HOSPITAL_ADMIN: '/admin',
  BILLING: '/billing',
  PHARMACIST: '/pharmacy',
  SUPER_ADMIN: '/platform',
};

export const ROLE_LABEL: Record<Role, string> = {
  PATIENT: 'Patient portal',
  NURSE: 'Nursing',
  DOCTOR: 'Clinician',
  HOSPITAL_ADMIN: 'Administration',
  BILLING: 'Billing & accounts',
  PHARMACIST: 'Pharmacy',
  SUPER_ADMIN: 'Platform',
};

/** Route prefixes a role may open (UI guard; the API enforces authorization independently). */
export const ROLE_PREFIXES: Record<Role, string[]> = {
  PATIENT: ['/patient'],
  NURSE: ['/nurse', '/incidents'],
  DOCTOR: ['/doctor', '/incidents'],
  HOSPITAL_ADMIN: ['/admin', '/incidents'],
  BILLING: ['/billing', '/incidents'],
  PHARMACIST: ['/pharmacy', '/incidents'],
  SUPER_ADMIN: ['/platform'],
};

export const SHARED_ROUTES = ['/notifications', '/profile'];

export const SIGN_OUT_ICON = LogOutIcon;
export const NOTIFICATIONS_ICON = Bell;
