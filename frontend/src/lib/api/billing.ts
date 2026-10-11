import { api, Query } from './client';
import type { AdmissionStatus, Decimal, ISODate, ISODateTime, PageResponse, UUID } from './types';

/** Billing & accounts API. All money values are decimal strings computed by the server. */

export type InvoiceStatus = 'OPEN' | 'FINALIZED' | 'CANCELLED';
export type PayerType = 'SELF' | 'INSURANCE' | 'CORPORATE' | 'GOVERNMENT';
export type PaymentStatus = 'NO_CHARGES' | 'UNPAID' | 'PARTIALLY_PAID' | 'PAID' | 'CREDIT_BALANCE';
export type ChargeCategory = 'ROOM' | 'PROCEDURE' | 'LAB' | 'PHARMACY' | 'CONSULTATION' | 'NURSING' | 'IMAGING' | 'CONSUMABLE' | 'OTHER';
export type PaymentMethod = 'CASH' | 'CARD' | 'UPI' | 'NET_BANKING' | 'CHEQUE' | 'DEMAND_DRAFT' | 'ONLINE';
export type AdjustmentType = 'DISCOUNT' | 'WRITE_OFF' | 'CREDIT_NOTE' | 'REFUND' | 'DEBIT_NOTE';
export type AdjustmentStatus = 'PENDING' | 'APPROVED' | 'REJECTED';
export type ClaimStatus = 'DRAFT' | 'SUBMITTED' | 'UNDER_REVIEW' | 'APPROVED' | 'PARTIALLY_APPROVED' | 'REJECTED' | 'SETTLED';
export type ClaimPayerType = 'INSURER' | 'TPA' | 'CORPORATE' | 'GOVERNMENT';
export type RequestType = 'DISPUTE' | 'QUERY' | 'ITEMIZATION' | 'PAYMENT_PLAN';
export type RequestStatus = 'OPEN' | 'IN_REVIEW' | 'RESOLVED' | 'REJECTED';
export type LedgerEntryType =
  | 'CHARGE'
  | 'CHARGE_REVERSAL'
  | 'PAYMENT'
  | 'PAYMENT_REVERSAL'
  | 'DISCOUNT'
  | 'WRITE_OFF'
  | 'CREDIT_NOTE'
  | 'DEBIT_NOTE'
  | 'REFUND'
  | 'INSURANCE_SETTLEMENT';

export interface BalanceSummary {
  grossCharges: Decimal;
  chargeReversals: Decimal;
  netCharges: Decimal;
  payments: Decimal;
  paymentReversals: Decimal;
  netPayments: Decimal;
  discounts: Decimal;
  writeOffs: Decimal;
  creditNotes: Decimal;
  debitNotes: Decimal;
  refunds: Decimal;
  insuranceSettled: Decimal;
  insuranceApprovedPending: Decimal;
  balance: Decimal;
  estimatedFutureCharges: Decimal;
  paymentStatus: PaymentStatus;
  formula: string;
}

export interface PatientRef {
  id: UUID;
  mrn: string;
  fullName: string;
  age?: number;
  gender?: string;
  phone?: string;
}

export interface AdmissionRef {
  id: UUID;
  admissionNumber: string;
  status: AdmissionStatus;
  admittedAt: ISODateTime;
  dischargedAt?: ISODateTime;
  departmentId?: UUID;
  departmentName?: string;
  attendingDoctorName?: string;
  location?: string;
}

export interface InvoiceSummary {
  id: UUID;
  invoiceNumber: string;
  status: InvoiceStatus;
  payerType: PayerType;
  createdAt: ISODateTime;
  finalizedAt?: ISODateTime;
  patientId: UUID;
  patientName: string;
  mrn: string;
  admissionId?: UUID;
  admissionNumber?: string;
  admissionStatus?: AdmissionStatus;
  admittedAt?: ISODateTime;
  dischargedAt?: ISODateTime;
  departmentName?: string;
  netCharges: Decimal;
  credits: Decimal;
  netPayments: Decimal;
  balance: Decimal;
  paymentStatus: PaymentStatus;
  lastPaymentAt?: ISODateTime;
}

export interface LineView {
  id: UUID;
  tariffId?: UUID;
  tariffCode?: string;
  category: ChargeCategory;
  description: string;
  serviceDate: ISODate;
  quantity: Decimal;
  unitPrice: Decimal;
  taxRate: Decimal;
  netAmount: Decimal;
  taxAmount: Decimal;
  totalAmount: Decimal;
  sourceType: string;
  status: 'POSTED' | 'REVERSED' | 'REVERSAL';
  reversesLineId?: UUID;
  reversedByLineId?: UUID;
  reason?: string;
  postedByName?: string;
  postedAt: ISODateTime;
}

export interface EstimateView {
  id: UUID;
  category: ChargeCategory;
  description: string;
  estimatedAmount: Decimal;
  basis?: string;
  active: boolean;
  createdByName?: string;
  createdAt: ISODateTime;
}

export interface PaymentView {
  id: UUID;
  invoiceId: UUID;
  invoiceNumber?: string;
  patientId: UUID;
  patientName?: string;
  mrn?: string;
  receiptNumber: string;
  amount: Decimal;
  method: PaymentMethod;
  methodReference?: string;
  payerName?: string;
  status: 'COMPLETED' | 'REVERSED';
  advance: boolean;
  notes?: string;
  receivedByName?: string;
  receivedAt: ISODateTime;
  reversedByName?: string;
  reversedAt?: ISODateTime;
  reversalReason?: string;
  refundedAmount: Decimal;
}

export interface AdjustmentView {
  id: UUID;
  invoiceId: UUID;
  invoiceNumber?: string;
  patientId?: UUID;
  patientName?: string;
  mrn?: string;
  adjustmentNumber: string;
  type: AdjustmentType;
  amount: Decimal;
  balanceSign: number;
  reason: string;
  relatedPaymentId?: UUID;
  relatedReceiptNumber?: string;
  refundMethod?: PaymentMethod;
  status: AdjustmentStatus;
  requestedById: UUID;
  requestedByName?: string;
  requestedAt: ISODateTime;
  decidedByName?: string;
  decidedAt?: ISODateTime;
  decisionNotes?: string;
  version: number;
}

export interface SettlementView {
  id: UUID;
  amount: Decimal;
  reference: string;
  notes?: string;
  settledAt: ISODateTime;
  recordedByName?: string;
}

export interface ClaimView {
  id: UUID;
  invoiceId: UUID;
  invoiceNumber?: string;
  patientId: UUID;
  patientName?: string;
  mrn?: string;
  claimNumber: string;
  payerName: string;
  payerType: ClaimPayerType;
  policyNumber: string;
  preAuthNumber?: string;
  claimedAmount: Decimal;
  approvedAmount?: Decimal;
  settledAmount: Decimal;
  pendingSettlement: Decimal;
  status: ClaimStatus;
  submittedAt?: ISODateTime;
  decidedAt?: ISODateTime;
  settledAt?: ISODateTime;
  rejectionReason?: string;
  notes?: string;
  createdByName?: string;
  createdAt: ISODateTime;
  updatedAt?: ISODateTime;
  settlements: SettlementView[];
  version: number;
}

export interface LedgerView {
  id: UUID;
  entryType: LedgerEntryType;
  amount: Decimal;
  runningBalance: Decimal;
  referenceType: string;
  referenceId: UUID;
  referenceNumber?: string;
  description: string;
  createdByName?: string;
  createdAt: ISODateTime;
}

export interface RequestView {
  id: UUID;
  invoiceId: UUID;
  invoiceNumber?: string;
  patientId: UUID;
  patientName?: string;
  mrn?: string;
  lineItemId?: UUID;
  lineItemDescription?: string;
  requestType: RequestType;
  subject: string;
  description: string;
  status: RequestStatus;
  raisedByName?: string;
  raisedAt: ISODateTime;
  response?: string;
  respondedByName?: string;
  respondedAt?: ISODateTime;
  version: number;
}

export interface CategoryTotal {
  category: ChargeCategory;
  amount: Decimal;
  lineCount: number;
}

export interface InvoiceDetail {
  id: UUID;
  invoiceNumber: string;
  status: InvoiceStatus;
  payerType: PayerType;
  notes?: string;
  createdAt: ISODateTime;
  createdByName?: string;
  finalizedAt?: ISODateTime;
  finalizedByName?: string;
  reviewedAt?: ISODateTime;
  reviewedByName?: string;
  reopenedAt?: ISODateTime;
  reopenedByName?: string;
  reopenReason?: string;
  roomChargesPostedUntil?: ISODate;
  version: number;
  patient: PatientRef;
  admission?: AdmissionRef;
  summary: BalanceSummary;
  categoryTotals: CategoryTotal[];
  lines: LineView[];
  estimates: EstimateView[];
  payments: PaymentView[];
  adjustments: AdjustmentView[];
  claims: ClaimView[];
  ledger: LedgerView[];
  requests: RequestView[];
  refundableAmount: Decimal;
  pendingAdjustmentsTotal: Decimal;
}

export interface TariffOption {
  id: UUID;
  code: string;
  name: string;
  category: ChargeCategory;
  unitPrice: Decimal;
  taxRate: Decimal;
  billingUnit: string;
  roomType?: string;
}

export interface RoomChargeResult {
  roomDaysPosted: number;
  nursingDaysPosted: number;
  amountPosted: Decimal;
  postedThrough?: ISODate;
  warnings: string[];
}

export interface PaymentResult {
  payment: PaymentView;
  replayed: boolean;
  summary: BalanceSummary;
}

export interface AgingBucket {
  bucket: '0-30' | '31-60' | '61-90' | '90+';
  count: number;
  amount: Decimal;
}

export interface OutstandingRow {
  invoice: InvoiceSummary;
  referenceDate: ISODate;
  ageDays: number;
  bucket: AgingBucket['bucket'];
  overdue: boolean;
}

export interface OutstandingReport {
  buckets: AgingBucket[];
  totalOutstanding: Decimal;
  invoiceCount: number;
  overdueCount: number;
  overdueAmount: Decimal;
  rows: PageResponse<OutstandingRow>;
}

export interface DailyAmount {
  date: ISODate;
  amount: Decimal;
}

export interface DashboardStats {
  todaysCollections: Decimal;
  todaysPaymentCount: number;
  outstandingTotal: Decimal;
  outstandingCount: number;
  pendingApprovals: number;
  pendingApprovalAmount: Decimal;
  openClaims: number;
  claimsPendingSettlement: Decimal;
  unpaidDischargedCount: number;
  unpaidDischargedAmount: Decimal;
  openRequests: number;
  openInvoices: number;
  collectionsTrend: DailyAmount[];
  recentPayments: PaymentView[];
  aging: AgingBucket[];
}

// ------------------------------------------------------------------ reports

export interface CollectionsReport {
  from: ISODate;
  to: ISODate;
  rows: Array<{ date: ISODate; method: PaymentMethod; receipts: number; received: Decimal; reversed: Decimal; net: Decimal }>;
  byMethod: Array<{ method: PaymentMethod; receipts: number; received: Decimal; reversed: Decimal; net: Decimal }>;
  byDay: DailyAmount[];
  totalReceived: Decimal;
  totalReversed: Decimal;
  totalNet: Decimal;
  basis: string;
}

export interface ChargesVsPaymentsReport {
  from: ISODate;
  to: ISODate;
  rows: Array<{ date: ISODate; postedCharges: Decimal; collections: Decimal; adjustments: Decimal; insuranceSettlements: Decimal }>;
  totalPostedCharges: Decimal;
  totalCollections: Decimal;
  totalAdjustments: Decimal;
  totalInsurance: Decimal;
  basis: string;
}

export interface AdjustmentsReport {
  from: ISODate;
  to: ISODate;
  byType: Array<{ type: AdjustmentType; approvedCount: number; approvedAmount: Decimal; pendingCount: number; pendingAmount: Decimal; rejectedCount: number }>;
  rows: Array<{
    id: UUID;
    adjustmentNumber: string;
    type: AdjustmentType;
    status: AdjustmentStatus;
    amount: Decimal;
    invoiceNumber: string;
    invoiceId: UUID;
    patientName: string;
    mrn: string;
    reason: string;
    refundMethod?: string;
    requestedBy: string;
    requestedAt: ISODateTime;
    decidedBy?: string;
    decidedAt?: ISODateTime;
  }>;
  approvedReductions: Decimal;
  approvedRefunds: Decimal;
  approvedDebitNotes: Decimal;
  basis: string;
}

export interface ClaimsReport {
  from: ISODate;
  to: ISODate;
  byStatus: Array<{ status: ClaimStatus; count: number; claimed: Decimal; approved: Decimal; settled: Decimal; pendingSettlement: Decimal }>;
  totalClaims: number;
  totalClaimed: Decimal;
  totalApproved: Decimal;
  totalSettled: Decimal;
  totalPendingSettlement: Decimal;
  basis: string;
}

export interface RevenueRow {
  key: string;
  label: string;
  amount: Decimal;
  lines: number;
}

export interface RevenueReport {
  from: ISODate;
  to: ISODate;
  byCategory: RevenueRow[];
  byDepartment: RevenueRow[];
  total: Decimal;
  basis: string;
}

export interface ReceivablesReport {
  asOf: ISODate;
  buckets: AgingBucket[];
  total: Decimal;
  count: number;
  overdueCount: number;
  overdueAmount: Decimal;
  rows: OutstandingRow[];
  basis: string;
}

export type ReportKey = 'collections' | 'charges-vs-payments' | 'receivables' | 'adjustments' | 'claims' | 'revenue';

// ------------------------------------------------------------------ portal

export interface ExplanationLine {
  label: string;
  amount: Decimal;
  operator: '+' | '-' | '=';
  hint?: string;
}

export interface PortalBillCard {
  invoiceId: UUID;
  invoiceNumber: string;
  status: InvoiceStatus;
  admissionNumber?: string;
  admissionStatus?: AdmissionStatus;
  admittedAt?: ISODateTime;
  dischargedAt?: ISODateTime;
  departmentName?: string;
  totalCharges: Decimal;
  paid: Decimal;
  insuranceSettled: Decimal;
  insurancePending: Decimal;
  adjustments: Decimal;
  refunds: Decimal;
  outstanding: Decimal;
  paymentStatus: PaymentStatus;
  estimatedFutureCharges: Decimal;
  estimatedOutstanding: Decimal;
  finalizedAt?: ISODateTime;
}

export interface PortalLine {
  id: UUID;
  description: string;
  serviceDate: ISODate;
  quantity: Decimal;
  unitPrice: Decimal;
  taxAmount: Decimal;
  totalAmount: Decimal;
  reversed: boolean;
}

export interface PortalPayment {
  id: UUID;
  invoiceId: UUID;
  invoiceNumber?: string;
  patientName?: string;
  receiptNumber: string;
  amount: Decimal;
  method: PaymentMethod;
  payerName?: string;
  status: 'COMPLETED' | 'REVERSED';
  advance: boolean;
  receivedAt: ISODateTime;
  reversedAt?: ISODateTime;
}

export interface PortalInvoice {
  id: UUID;
  invoiceNumber: string;
  status: InvoiceStatus;
  createdAt: ISODateTime;
  finalizedAt?: ISODateTime;
  hospitalName: string;
  patient: PatientRef;
  admission?: AdmissionRef;
  summary: BalanceSummary;
  explanation: ExplanationLine[];
  explanationNotes: string[];
  categories: Array<{ category: ChargeCategory; total: Decimal; items: PortalLine[] }>;
  payments: PortalPayment[];
  adjustments: Array<{ adjustmentNumber: string; type: AdjustmentType; label: string; amount: Decimal; balanceSign: number; approvedAt?: ISODateTime }>;
  insurance: Array<{
    claimNumber: string;
    payerName: string;
    status: ClaimStatus;
    claimedAmount: Decimal;
    approvedAmount?: Decimal;
    settledAmount: Decimal;
    pendingSettlement: Decimal;
    submittedAt?: ISODateTime;
    rejectionReason?: string;
  }>;
  estimates: Array<{ category: ChargeCategory; description: string; estimatedAmount: Decimal; basis?: string }>;
  requests: Array<{
    id: UUID;
    requestType: RequestType;
    subject: string;
    description: string;
    lineItemDescription?: string;
    status: RequestStatus;
    raisedAt: ISODateTime;
    raisedByName?: string;
    response?: string;
    respondedAt?: ISODateTime;
  }>;
}

// ------------------------------------------------------------------ query keys + calls

export const billingKeys = {
  invoices: (params?: Query) => ['invoices', params ?? {}] as const,
  invoice: (id: UUID) => ['invoice', id] as const,
  tariffs: (params?: Query) => ['billing', 'tariffs', params ?? {}] as const,
  payments: (params?: Query) => ['payments', params ?? {}] as const,
  adjustments: (params?: Query) => ['adjustments', params ?? {}] as const,
  claims: (params?: Query) => ['claims', params ?? {}] as const,
  requests: (params?: Query) => ['billing-requests', params ?? {}] as const,
  outstanding: (params?: Query) => ['billing', 'outstanding', params ?? {}] as const,
  dashboard: ['billing', 'dashboard'] as const,
  report: (key: ReportKey, params?: Query) => ['billing', 'report', key, params ?? {}] as const,
  portalBills: (patientId: UUID) => ['portal', 'bills', patientId] as const,
  portalInvoice: (id: UUID) => ['portal', 'invoice', id] as const,
  portalPayments: (patientId: UUID) => ['portal', 'payments', patientId] as const,
};

export interface InvoiceSearch {
  status?: InvoiceStatus[];
  paymentStatus?: PaymentStatus[];
  from?: string;
  to?: string;
  departmentId?: UUID;
  q?: string;
  page?: number;
  size?: number;
  sort?: string;
}

export const billingApi = {
  searchInvoices: (params: InvoiceSearch) => api.get<PageResponse<InvoiceSummary>>('/api/invoices', params as Query),
  invoice: (id: UUID) => api.get<InvoiceDetail>(`/api/invoices/${id}`),
  invoiceByAdmission: (admissionId: UUID) => api.get<InvoiceDetail>(`/api/invoices/by-admission/${admissionId}`),
  createInvoice: (admissionId: UUID) => api.post<InvoiceDetail>('/api/invoices', { admissionId }),
  tariffs: (params: { q?: string; category?: ChargeCategory }) => api.get<TariffOption[]>('/api/billing/tariffs', params),
  addCharge: (
    id: UUID,
    body: { tariffId: UUID; quantity: string; serviceDate?: string; unitPriceOverride?: string | null; overrideReason?: string | null; note?: string | null },
  ) => api.post<InvoiceDetail>(`/api/invoices/${id}/charges`, body),
  reverseCharge: (id: UUID, lineId: UUID, reason: string) =>
    api.post<InvoiceDetail>(`/api/invoices/${id}/charges/${lineId}/reverse`, { reason }),
  postRoomCharges: (id: UUID) => api.post<RoomChargeResult>(`/api/invoices/${id}/room-charges`),
  addEstimate: (id: UUID, body: { category: ChargeCategory; description: string; estimatedAmount: string; basis: string }) =>
    api.post<InvoiceDetail>(`/api/invoices/${id}/estimates`, body),
  deactivateEstimate: (id: UUID, estimateId: UUID) => api.post<InvoiceDetail>(`/api/invoices/${id}/estimates/${estimateId}/deactivate`),
  finalize: (id: UUID, notes?: string) => api.post<InvoiceDetail>(`/api/invoices/${id}/finalize`, { notes }),
  reopen: (id: UUID, reason: string) => api.post<InvoiceDetail>(`/api/invoices/${id}/reopen`, { reason }),
  downloadInvoicePdf: (id: UUID, number: string) => api.download(`/api/invoices/${id}/invoice.pdf`, `invoice-${number}.pdf`),

  recordPayment: (
    invoiceId: UUID,
    body: {
      amount: string;
      method: PaymentMethod;
      methodReference?: string | null;
      payerName: string;
      idempotencyKey: string;
      allowAdvance: boolean;
      notes?: string | null;
    },
  ) => api.post<PaymentResult>(`/api/invoices/${invoiceId}/payments`, body),
  reversePayment: (id: UUID, reason: string) => api.post<PaymentView>(`/api/payments/${id}/reverse`, { reason }),
  payments: (params: { from?: string; to?: string; method?: PaymentMethod[]; status?: string[]; q?: string; page?: number; size?: number; sort?: string }) =>
    api.get<PageResponse<PaymentView>>('/api/payments', params as Query),
  downloadReceipt: (paymentId: UUID, receiptNumber: string) => api.download(`/api/payments/${paymentId}/receipt.pdf`, `receipt-${receiptNumber}.pdf`),

  requestAdjustment: (
    invoiceId: UUID,
    body: { type: AdjustmentType; amount: string; reason: string; relatedPaymentId?: UUID | null; refundMethod?: PaymentMethod | null },
  ) => api.post<AdjustmentView>(`/api/invoices/${invoiceId}/adjustments`, body),
  approveAdjustment: (id: UUID, notes?: string) => api.post<AdjustmentView>(`/api/adjustments/${id}/approve`, { notes }),
  rejectAdjustment: (id: UUID, notes: string) => api.post<AdjustmentView>(`/api/adjustments/${id}/reject`, { notes }),
  adjustments: (params: { status?: AdjustmentStatus[]; type?: AdjustmentType[]; q?: string; page?: number; size?: number; sort?: string }) =>
    api.get<PageResponse<AdjustmentView>>('/api/adjustments', params as Query),

  createClaim: (
    invoiceId: UUID,
    body: { payerName: string; payerType: ClaimPayerType; policyNumber: string; preAuthNumber?: string | null; claimedAmount: string; notes?: string | null },
  ) => api.post<ClaimView>(`/api/invoices/${invoiceId}/claims`, body),
  claims: (params: { status?: ClaimStatus[]; q?: string; page?: number; size?: number; sort?: string }) =>
    api.get<PageResponse<ClaimView>>('/api/claims', params as Query),
  submitClaim: (id: UUID) => api.post<ClaimView>(`/api/claims/${id}/submit`),
  reviewClaim: (id: UUID) => api.post<ClaimView>(`/api/claims/${id}/review`),
  approveClaim: (id: UUID, approvedAmount: string, notes?: string) => api.post<ClaimView>(`/api/claims/${id}/approve`, { approvedAmount, notes }),
  rejectClaim: (id: UUID, reason: string) => api.post<ClaimView>(`/api/claims/${id}/reject`, { reason }),
  settleClaim: (id: UUID, body: { amount: string; reference: string; notes?: string | null }) => api.post<ClaimView>(`/api/claims/${id}/settle`, body),

  requests: (params: { status?: RequestStatus[]; type?: RequestType; q?: string; page?: number; size?: number; sort?: string }) =>
    api.get<PageResponse<RequestView>>('/api/billing-requests', params as Query),
  respondRequest: (id: UUID, status: RequestStatus, response?: string) =>
    api.post<RequestView>(`/api/billing-requests/${id}/respond`, { status, response }),

  dashboard: () => api.get<DashboardStats>('/api/dashboard/billing'),
  outstanding: (params: { admissionStatus?: string[]; bucket?: string; overdue?: boolean; q?: string; page?: number; size?: number; sort?: string }) =>
    api.get<OutstandingReport>('/api/billing/outstanding', params as Query),
  collections: (from?: string, to?: string) => api.get<CollectionsReport>('/api/reports/billing/collections', { from, to }),
  chargesVsPayments: (from?: string, to?: string) => api.get<ChargesVsPaymentsReport>('/api/reports/billing/charges-vs-payments', { from, to }),
  receivables: () => api.get<ReceivablesReport>('/api/reports/billing/receivables'),
  adjustmentsReport: (from?: string, to?: string) => api.get<AdjustmentsReport>('/api/reports/billing/adjustments', { from, to }),
  claimsReport: (from?: string, to?: string) => api.get<ClaimsReport>('/api/reports/billing/claims', { from, to }),
  revenue: (from?: string, to?: string) => api.get<RevenueReport>('/api/reports/billing/revenue', { from, to }),
  exportReport: (key: ReportKey, from?: string, to?: string) =>
    api.download(`/api/reports/billing/${key}/export`, `careflow-${key}.csv`, { from, to }),
};

export const portalBillingApi = {
  bills: (patientId: UUID) => api.get<PortalBillCard[]>(`/api/portal/patients/${patientId}/bills`),
  invoice: (id: UUID) => api.get<PortalInvoice>(`/api/portal/invoices/${id}`),
  payments: (patientId: UUID) => api.get<PortalPayment[]>(`/api/portal/patients/${patientId}/payments`),
  raiseRequest: (id: UUID, body: { requestType: RequestType; subject: string; description: string; lineItemId?: UUID | null }) =>
    api.post<RequestView>(`/api/portal/invoices/${id}/requests`, body),
};

// ------------------------------------------------------------------ labels

export const CATEGORY_LABEL: Record<ChargeCategory, string> = {
  ROOM: 'Room & bed',
  PROCEDURE: 'Procedures',
  LAB: 'Laboratory',
  PHARMACY: 'Pharmacy',
  CONSULTATION: 'Consultations',
  NURSING: 'Nursing care',
  IMAGING: 'Imaging',
  CONSUMABLE: 'Consumables',
  OTHER: 'Other services',
};

export const METHOD_LABEL: Record<PaymentMethod, string> = {
  CASH: 'Cash',
  CARD: 'Card',
  UPI: 'UPI',
  NET_BANKING: 'Net banking',
  CHEQUE: 'Cheque',
  DEMAND_DRAFT: 'Demand draft',
  ONLINE: 'Online',
};

export const ADJUSTMENT_LABEL: Record<AdjustmentType, string> = {
  DISCOUNT: 'Discount',
  WRITE_OFF: 'Write-off',
  CREDIT_NOTE: 'Credit note',
  DEBIT_NOTE: 'Debit note',
  REFUND: 'Refund',
};

export const LEDGER_LABEL: Record<LedgerEntryType, string> = {
  CHARGE: 'Charge',
  CHARGE_REVERSAL: 'Charge reversal',
  PAYMENT: 'Payment',
  PAYMENT_REVERSAL: 'Payment reversal',
  DISCOUNT: 'Discount',
  WRITE_OFF: 'Write-off',
  CREDIT_NOTE: 'Credit note',
  DEBIT_NOTE: 'Debit note',
  REFUND: 'Refund',
  INSURANCE_SETTLEMENT: 'Insurance settlement',
};

export const REQUEST_TYPE_LABEL: Record<RequestType, string> = {
  DISPUTE: 'Dispute',
  QUERY: 'Question',
  ITEMIZATION: 'Itemized bill',
  PAYMENT_PLAN: 'Payment plan',
};

export const PAYMENT_METHODS = Object.keys(METHOD_LABEL) as PaymentMethod[];
export const CATEGORIES = Object.keys(CATEGORY_LABEL) as ChargeCategory[];
