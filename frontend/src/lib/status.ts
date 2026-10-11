/**
 * One place that maps workflow/status values to a label and a semantic colour, so every screen
 * shows statuses consistently. Unknown values fall back to a humanized label in neutral.
 */
export type StatusTone = 'success' | 'warning' | 'error' | 'info' | 'neutral' | 'primary' | 'secondary';

export interface StatusMeta {
  label: string;
  tone: StatusTone;
}

const T = (label: string, tone: StatusTone): StatusMeta => ({ label, tone });

const MAP: Record<string, StatusMeta> = {
  // admissions
  PENDING_BED: T('Awaiting bed', 'warning'),
  ADMITTED: T('Admitted', 'primary'),
  DISCHARGE_IN_PROGRESS: T('Discharge in progress', 'info'),
  DISCHARGED: T('Discharged', 'neutral'),
  // beds
  AVAILABLE: T('Available', 'success'),
  OCCUPIED: T('Occupied', 'primary'),
  RESERVED: T('Reserved', 'info'),
  CLEANING: T('Cleaning', 'warning'),
  MAINTENANCE: T('Maintenance', 'error'),
  // medication administration
  SCHEDULED: T('Scheduled', 'neutral'),
  DUE: T('Due', 'info'),
  OVERDUE: T('Overdue', 'error'),
  ADMINISTERED: T('Administered', 'success'),
  DELAYED: T('Delayed', 'warning'),
  REFUSED: T('Refused', 'error'),
  MISSED: T('Missed', 'error'),
  HELD: T('Held', 'warning'),
  // orders
  ACTIVE: T('Active', 'success'),
  ON_HOLD: T('On hold', 'warning'),
  DISCONTINUED: T('Discontinued', 'neutral'),
  COMPLETED: T('Completed', 'success'),
  // investigations
  ORDERED: T('Ordered', 'info'),
  COLLECTED: T('Collected', 'info'),
  IN_PROGRESS: T('In progress', 'info'),
  RESULTED: T('Resulted', 'warning'),
  REVIEWED: T('Reviewed', 'success'),
  // discharge steps
  NOT_STARTED: T('Not started', 'neutral'),
  BLOCKED: T('Blocked', 'error'),
  NOT_APPLICABLE: T('Not applicable', 'neutral'),
  READY: T('Ready', 'success'),
  AUTHORIZED: T('Authorized', 'success'),
  CLEARED: T('Cleared', 'success'),
  EXCEPTION: T('Exception', 'warning'),
  REVOKED: T('Withdrawn', 'warning'),
  WAITING: T('Waiting', 'neutral'),
  // notification deliveries
  SENT: T('Sent', 'success'),
  DELIVERED: T('Delivered', 'success'),
  SKIPPED: T('Skipped', 'neutral'),
  FAILED: T('Failed', 'error'),
  // billing
  OPEN: T('Open', 'info'),
  FINALIZED: T('Finalized', 'primary'),
  NO_CHARGES: T('No charges', 'neutral'),
  UNPAID: T('Unpaid', 'error'),
  PARTIALLY_PAID: T('Partially paid', 'warning'),
  PAID: T('Paid', 'success'),
  CREDIT_BALANCE: T('Credit balance', 'info'),
  POSTED: T('Posted', 'success'),
  REVERSED: T('Reversed', 'neutral'),
  REVERSAL: T('Reversal', 'warning'),
  PENDING: T('Pending approval', 'warning'),
  APPROVED: T('Approved', 'success'),
  REJECTED: T('Rejected', 'error'),
  DRAFT: T('Draft', 'neutral'),
  SUBMITTED: T('Submitted', 'info'),
  UNDER_REVIEW: T('Under review', 'info'),
  PARTIALLY_APPROVED: T('Partially approved', 'warning'),
  SETTLED: T('Settled', 'success'),
  IN_REVIEW: T('In review', 'info'),
  ADVANCE: T('Advance', 'info'),
  // transfers
  REQUESTED: T('Requested', 'info'),
  // alerts / priority
  CRITICAL: T('Critical', 'error'),
  WARNING: T('Warning', 'warning'),
  INFO: T('Info', 'info'),
  ACKNOWLEDGED: T('Acknowledged', 'info'),
  RESOLVED: T('Resolved', 'success'),
  URGENT: T('Urgent', 'error'),
  STAT: T('STAT', 'error'),
  HIGH: T('High', 'warning'),
  NORMAL: T('Normal', 'neutral'),
  LOW: T('Low', 'neutral'),
  ROUTINE: T('Routine', 'neutral'),
  WATCH: T('Watch', 'warning'),
  // tasks
  DONE: T('Done', 'success'),
  // follow-ups
  PLANNED: T('Planned', 'info'),
  MISSED_FOLLOW_UP: T('Missed', 'error'),
  // incidents
  REPORTED: T('Reported', 'warning'),
  ACTION_REQUIRED: T('Action required', 'error'),
  CLOSED: T('Closed', 'neutral'),
  // generic
  CANCELLED: T('Cancelled', 'neutral'),
  FINAL: T('Final', 'success'),
  AMENDED: T('Amended', 'warning'),
  ENTERED_IN_ERROR: T('Entered in error', 'neutral'),
  INACTIVE: T('Inactive', 'neutral'),
  DISABLED: T('Disabled', 'neutral'),
  LOCKED: T('Locked', 'error'),
  GRANTED: T('Granted', 'success'),
  WITHDRAWN: T('Withdrawn', 'neutral'),
  EXPIRED: T('Expired', 'error'),
  QUARANTINED: T('Quarantined', 'warning'),
  DEPLETED: T('Depleted', 'neutral'),
  DISPENSED: T('Dispensed', 'success'),
  RETURNED: T('Returned', 'neutral'),
  PARTIALLY_RETURNED: T('Partially returned', 'warning'),
  // pharmacy stock & supply
  IN_STOCK: T('In stock', 'success'),
  LOW_STOCK: T('Low stock', 'warning'),
  OUT_OF_STOCK: T('Out of stock', 'error'),
  AWAITING_VERIFICATION: T('Awaiting verification', 'info'),
  QUERIED: T('Query open', 'warning'),
  READY_TO_DISPENSE: T('Ready to dispense', 'primary'),
  SUPPLIED: T('Supplied', 'success'),
  COURSE_ENDED: T('Course ended', 'neutral'),
  RECEIPT: T('Receipt', 'success'),
  DISPENSE: T('Dispense', 'primary'),
  RETURN: T('Return', 'info'),
  ADJUSTMENT_IN: T('Adjustment in', 'info'),
  ADJUSTMENT_OUT: T('Adjustment out', 'warning'),
  EXPIRY_WRITE_OFF: T('Expiry write-off', 'error'),
  DAMAGE: T('Damage', 'error'),
  // severities
  MILD: T('Mild', 'info'),
  MODERATE: T('Moderate', 'warning'),
  SEVERE: T('Severe', 'error'),
  LIFE_THREATENING: T('Life-threatening', 'error'),
  NO_HARM: T('No harm', 'neutral'),
  MINOR: T('Minor', 'info'),
  DEATH: T('Death', 'error'),
  // audit outcomes, privacy, platform, integrations
  SUCCESS: T('Success', 'success'),
  DENIED: T('Denied', 'error'),
  FAILURE: T('Failed', 'warning'),
  ARCHIVED: T('Archived', 'neutral'),
  DECLINED: T('Declined', 'neutral'),
  SUSPENDED: T('Suspended', 'error'),
  DEV_ADAPTER: T('Dev adapter', 'info'),
  SANDBOX: T('Sandbox', 'warning'),
  LIVE: T('Live (not connected)', 'error'),
  UP: T('Up', 'success'),
  DOWN: T('Down', 'error'),
  // lab flags
  ABNORMAL: T('Abnormal', 'warning'),
  CRITICAL_LOW: T('Critical low', 'error'),
  CRITICAL_HIGH: T('Critical high', 'error'),
};

export function statusMeta(value?: string | null): StatusMeta {
  if (!value) return T('—', 'neutral');
  const m = MAP[value];
  if (m) return m;
  const label = value
    .toLowerCase()
    .split('_')
    .map((w, i) => (i === 0 ? w.charAt(0).toUpperCase() + w.slice(1) : w))
    .join(' ');
  return T(label, 'neutral');
}
