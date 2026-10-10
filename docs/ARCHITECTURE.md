# CareFlow HMS — Architecture & Conventions

This is the contract all modules follow. Read it before changing code.

## 1. Layout

```
backend/   Spring Boot 3.5 (Java 17), PostgreSQL 16, Flyway, Spring Security, JPA
frontend/  Next.js 15 App Router (TypeScript), MUI 7, TanStack Query 5, RHF + Zod, Recharts
docs/      Architecture, permission matrix, workflows
docker-compose.yml   postgres (+ optional full stack via --profile full)
```

The browser only talks to the Next.js origin. `next.config.mjs` rewrites `/api/*` to the backend, so
sessions use **httpOnly SameSite=Strict cookies** (`cf_at` access JWT 15 min, `cf_rt` rotating refresh
token scoped to `/api/auth`, `cf_sess` non-secret hint for route middleware). API tools may send
`Authorization: Bearer <token>` (returned by `/api/auth/login`).

## 2. Backend conventions (`com.careflow.hms.<module>`)

* **Schema** lives in `src/main/resources/db/migration`. V1–V4 define every table. New migrations use the
  version range assigned to your module (clinical V10–19, pharmacy V20–29, billing V30–39,
  discharge V40–49, admin/platform V50–59). Never edit V1–V4 after merge; add a migration.
  `ddl-auto=validate` — entities must match the schema exactly.
* **Entities** for all tables already exist (one class + repository per table). Org-scoped entities extend
  `common.TenantEntity` (UUID id + `organizationId` stamped automatically from the security context).
  Add query methods to repositories as needed; prefer `findByIdAndOrganizationId`.
* **Multi-tenancy:** every query must be scoped to `TenantContext.requireOrganizationId()` (or reached
  through an org-checked parent). Records from another org are reported as **404**.
* **Authorization = permission + object access, always both.**
  * Role permission: `@PreAuthorize("hasAuthority('PRESCRIBE')")` (codes in `security.Permissions`).
  * Object access: `AccessPolicy.requireAdmission(id, Scope.CLINICAL|MEDICATION|FINANCIAL|DEMOGRAPHICS)` or
    `requirePatient(...)` before returning or changing patient data. It throws 404/403, audits denials
    and (throttled) sensitive reads. For lists, filter with `canAccessAdmission`.
  * Patients/caregivers (`Role.PATIENT`, permission `PORTAL_ACCESS`) pass `AccessPolicy` for their own
    and granted records, but **portal endpoints must only return released content** (e.g.
    `releasedToPatient = true` notes/results, `PATIENT`-visibility timeline events, posted billing).
  * Administrators do not get clinical data unless explicitly granted `CLINICAL_READ`.
* **Errors:** throw `ApiException.notFound/forbidden/conflict/rule/badRequest`. `GlobalExceptionHandler`
  renders `{timestamp,status,code,message,path,traceId,fieldErrors[]}`. Use 422 (`rule`) for workflow
  violations, 409 for conflicts/duplicates/concurrency.
* **Validation:** Bean Validation on request records (`@Valid`), plus business checks in services.
* **DTOs:** Java records grouped in `<Module>Dtos`. Never return entities directly.
* **Pagination:** `Paging.of(page,size,sort,whitelist,default)` + `PageResponse.of(page, mapper)`.
  Sort param format `field,asc|desc`. Search uses `Paging.likePattern(q)`.
* **Money:** `BigDecimal` + `common.Money` (2 dp, HALF_UP). Serialized as JSON **strings**. All totals and
  balances are computed on the server. Never overwrite/delete posted financial rows; reverse/adjust.
* **Billing integration:** other modules post charges only through `billing.ChargeService.postCharge(...)`
  (idempotent per source; writes the ledger). Balances only through `billing.LedgerService`.
* **Timeline:** record significant events with `TimelineService.record(Entry.builder()...)`, choosing
  `Visibility` carefully: `PATIENT` (released to portal), `CARE_TEAM`, `BILLING`, `INTERNAL`.
* **Notifications:** `NotificationService.notifyUsers/notifyRole/notifyCareTeam/notifyWardNurses/notifyPatient`
  with a stable `dedupeKey` per event+recipient. Links are frontend routes (see section 4).
* **Audit:** `AuditService.record(action, entityType, entityId, patientId, summary)` for important changes.
* **Settings:** `org.SettingsService` (keys in that class; add keys there if needed).
* **Sequences:** `org.SequenceService` for human-readable numbers.
* **Time:** store `Instant` (UTC). Hospital-local dates via `common.TimeUtil` (`today()`, `localDate()`).
* **Concurrency:** `@Version` on mutable entities; pessimistic locks (`lockById`) for contended resources
  (beds, invoices, inventory batches). DB unique/partial indexes are the last line of defence.
* **Background jobs:** `@Scheduled` methods iterate active orgs (`OrganizationRepository.findByPlatformFalseAndStatus`)
  and run work inside `TenantContext.runAs(orgId, null, ...)` with a `TransactionTemplate`.
* **Integrations:** behind interfaces with clearly labelled development adapters (see
  `notification.ExternalChannelGateway` / `DevOutboxGateway`). Never pretend an integration is live.
* **Demo data:** implement `seed.DemoSeedContributor` (see `DemoContext` for available keys). Use
  `ctx.actAs("dr_mehta", () -> service.call(...))` to create data through real services; backdate with
  repositories/JDBC where history is needed. Data must be clearly fictional.
* **Tests:** extend `support.IntegrationTestBase` (Testcontainers Postgres + seeded demo hospital; helpers
  `call(email, method, url, body)`, `token`, `patientId`, `openAdmissionId`, `registerPatient`...).
  Name classes `*IT` or `*Test`. Run `mvnw.cmd -q test` (Windows) — Docker must be running.

## 3. Frontend conventions

* Pages live in `src/app/(app)/<role>/...` (authenticated shell) or `src/app/(public)/...`.
  Every page is a client component (`'use client'`) using TanStack Query for data.
* API functions + types per module in `src/lib/api/<module>.ts` (core ones in `core.ts`/`types.ts`).
  Use `api.get/post/put/patch/del/download` from `client.ts`; it handles CSRF header, refresh and errors.
  Decimal values are strings; format with `formatINR` — never do money arithmetic in the browser.
* Query keys: hierarchical arrays starting with the resource name (`['invoices', params]`), invalidate
  broadly after mutations.
* Shared UI (do not fork): `PageHeader` (title, breadcrumbs, actions), `SectionCard`, `KpiCard`,
  `DataTable` (server pagination/sort, loading/empty/error, mobile cards), `StatusChip` (colours from
  `lib/status.ts` — add new status values there), `EmptyState`/`ErrorState`/`PageSkeleton`/`CardsSkeleton`,
  `ConfirmDialog` (supports required reason), `Money`/`DateTimeText`/`DefinitionList`, `PatientBanner`
  (identity + allergy warnings — always show on clinical screens), `Timeline`/`AdmissionTimeline`,
  `BedPicker`, form fields `RHFTextField`/`RHFSelect`/`RHFCheckbox`, hooks `useToast`, `useTableState`,
  `applyServerErrors`, `useDebouncedValue`. Portal pages use `usePortal()` + `PortalPatientSwitcher`.
* Forms: React Hook Form + Zod schema mirroring server rules; show server field errors with
  `applyServerErrors`. Disable submit while pending; toast success; invalidate queries.
* Every list/detail must handle loading, empty, error and success states. No horizontal overflow on
  mobile (390px). Use MUI `Grid` with `size={{ xs, md }}` (MUI v7 API), `slotProps` (not `InputProps`).
* Dates/times display in hospital timezone (`formatDateTime`, `formatDate`); datetime inputs use
  `isoToLocalInput`/`localInputToIso`.
* Permission-aware UI (`useAuth().hasPermission`) is a convenience only; the API enforces access.
* Navigation is fixed in `src/lib/nav.ts`. Every `href` there must resolve to a working page.

## 4. Route ownership (frontend)

| Area | Routes |
|------|--------|
| Foundation | `/login` `/forgot-password` `/reset-password` `/account-recovery` `/unauthorized` `/session-expired` `/dev-mailbox` `/notifications` `/profile` `/patient` `/patient/admissions[/id]` `/admin/patients[/new,/id]` `/admin/admissions[/new,/id]` `/admin/beds` `/admin/transfers` |
| Clinical & nursing | `/nurse/**` (except `/nurse/discharge`), `/doctor`, `/doctor/patients[/admissionId]`, `/doctor/alerts`, `/doctor/investigations`, `/doctor/prescriptions`, `/patient/records`, `/patient/medications` |
| Pharmacy | `/pharmacy/**` |
| Billing | `/billing/**` (except `/billing/discharge`), `/patient/bills[/invoiceId]`, `/patient/payments` |
| Discharge & follow-up | `/doctor/discharge[/admissionId]`, `/doctor/follow-ups`, `/nurse/discharge[/admissionId]`, `/admin/discharges[/admissionId]`, `/billing/discharge`, `/patient/discharge`, `/patient/follow-ups` |
| Admin & platform | `/admin` (dashboard), `/admin/staff`, `/admin/roles`, `/admin/departments`, `/admin/facility`, `/admin/tariffs`, `/admin/settings`, `/admin/analytics`, `/admin/audit`, `/admin/incidents`, `/admin/privacy`, `/incidents`, `/patient/privacy`, `/platform/**` |

Cross-module links use these routes (e.g. a doctor's patient record links to `/doctor/discharge/{admissionId}`).

## 5. Running locally

```
docker compose up -d postgres
cd backend  && set SPRING_PROFILES_ACTIVE=demo && mvnw.cmd spring-boot:run     # :8080, seeds demo data
cd frontend && npm install && npm run dev                                       # :3000 (BACKEND_URL=http://localhost:8080)
```
Demo accounts (development only) share the password `CareFlow#Demo2026` — see README.
