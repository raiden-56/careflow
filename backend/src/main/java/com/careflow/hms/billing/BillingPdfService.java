package com.careflow.hms.billing;

import com.careflow.hms.audit.AuditService;
import com.careflow.hms.billing.BillingDtos.AdmissionRef;
import com.careflow.hms.common.ApiException;
import com.careflow.hms.common.Money;
import com.careflow.hms.org.Organization;
import com.careflow.hms.org.OrganizationRepository;
import com.careflow.hms.patient.Patient;
import com.careflow.hms.security.TenantContext;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Printable receipt and itemized invoice statement (OpenPDF). Amounts use Indian digit grouping in
 * INR; every figure comes from the ledger-backed services. Estimates are printed separately and
 * explicitly marked as not part of the balance.
 */
@Service
@RequiredArgsConstructor
public class BillingPdfService {

    private static final Color INK = new Color(0x10, 0x26, 0x3A);
    private static final Color MUTED = new Color(0x4A, 0x5B, 0x6C);
    private static final Color TEAL = new Color(0x0F, 0x76, 0x6E);
    private static final Color RULE = new Color(0xE4, 0xE1, 0xDA);
    private static final Color SOFT = new Color(0xF6, 0xF5, 0xF1);

    private static final Font TITLE = new Font(Font.HELVETICA, 16, Font.BOLD, INK);
    private static final Font H2 = new Font(Font.HELVETICA, 11, Font.BOLD, INK);
    private static final Font BODY = new Font(Font.HELVETICA, 9, Font.NORMAL, INK);
    private static final Font BODY_BOLD = new Font(Font.HELVETICA, 9, Font.BOLD, INK);
    private static final Font SMALL = new Font(Font.HELVETICA, 8, Font.NORMAL, MUTED);
    private static final Font SMALL_ITALIC = new Font(Font.HELVETICA, 8, Font.ITALIC, MUTED);
    private static final Font HOSPITAL = new Font(Font.HELVETICA, 14, Font.BOLD, TEAL);
    private static final Font STRIKE = new Font(Font.HELVETICA, 9, Font.STRIKETHRU, MUTED);

    private final PaymentRepository payments;
    private final PortalBillingService portal;
    private final BillingSupport support;
    private final OrganizationRepository organizations;
    private final LedgerService ledger;
    private final AuditService audit;

    public record PdfFile(String fileName, byte[] content) {
    }

    @Transactional(readOnly = true)
    public PdfFile receipt(UUID paymentId) {
        Payment p = payments.findByIdAndOrganizationId(paymentId, TenantContext.requireOrganizationId())
                .orElseThrow(() -> ApiException.notFound("Payment"));
        Invoice inv = support.requireInvoice(p.getInvoiceId());
        Patient patient = support.patient(inv.getPatientId());
        AdmissionRef adm = support.admissionRef(inv.getAdmissionId());
        LedgerService.BalanceSummary s = ledger.summarize(inv.getId());
        ZoneId zone = support.zone();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A5.rotate(), 36, 36, 30, 30);
        PdfWriter.getInstance(doc, out);
        doc.open();
        header(doc, p.getStatus() == Payment.Status.REVERSED ? "PAYMENT RECEIPT (REVERSED)" : "PAYMENT RECEIPT");
        PdfPTable meta = table(new float[]{1.2f, 2f, 1.2f, 2f});
        kv(meta, "Receipt no.", p.getReceiptNumber());
        kv(meta, "Date", dateTime(p.getReceivedAt(), zone));
        kv(meta, "Patient", patient.fullName() + " (" + patient.getMrn() + ")");
        kv(meta, "Invoice", inv.getInvoiceNumber());
        kv(meta, "Admission", adm == null ? "-" : adm.admissionNumber());
        kv(meta, "Received from", p.getPayerName() == null ? "-" : p.getPayerName());
        kv(meta, "Payment method", BillingSupport.method(p.getMethod()));
        kv(meta, "Reference", p.getMethodReference() == null ? "-" : p.getMethodReference());
        doc.add(meta);
        doc.add(spacer(8));
        PdfPTable amt = table(new float[]{3f, 2f});
        cell(amt, "Amount received", H2, Element.ALIGN_LEFT, SOFT);
        cell(amt, "INR " + BillingSupport.inr(p.getAmount()), H2, Element.ALIGN_RIGHT, SOFT);
        cell(amt, "Amount in words", SMALL, Element.ALIGN_LEFT, null);
        cell(amt, words(p.getAmount()), SMALL, Element.ALIGN_RIGHT, null);
        if (p.isAdvance()) {
            cell(amt, "Includes an advance deposit held as credit against this admission.", SMALL_ITALIC, Element.ALIGN_LEFT, null);
            cell(amt, "", SMALL, Element.ALIGN_RIGHT, null);
        }
        if (p.getStatus() == Payment.Status.REVERSED) {
            cell(amt, "Reversed on " + dateTime(p.getReversedAt(), zone) + ": " + p.getReversalReason(), BODY_BOLD, Element.ALIGN_LEFT, null);
            cell(amt, "", SMALL, Element.ALIGN_RIGHT, null);
        }
        cell(amt, "Outstanding balance on the invoice after all transactions to date", BODY, Element.ALIGN_LEFT, null);
        cell(amt, (s.balance().signum() < 0 ? "Credit INR " + BillingSupport.inr(s.balance().negate()) : "INR " + BillingSupport.inr(s.balance())),
                BODY_BOLD, Element.ALIGN_RIGHT, null);
        doc.add(amt);
        doc.add(spacer(10));
        doc.add(new Paragraph("Received by " + support.name(p.getReceivedBy()) + ". This is a computer-generated receipt and does not require a signature.", SMALL));
        doc.close();
        audit.record("RECEIPT_DOWNLOADED", "PAYMENT", p.getId(), inv.getPatientId(), p.getReceiptNumber());
        return new PdfFile("receipt-" + p.getReceiptNumber() + ".pdf", out.toByteArray());
    }

    @Transactional(readOnly = true)
    public PdfFile invoice(UUID invoiceId) {
        BillingDtos.PortalInvoice v = portal.invoice(invoiceId);
        ZoneId zone = support.zone();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 36, 36, 30, 36);
        PdfWriter.getInstance(doc, out);
        doc.open();
        header(doc, v.status() == Invoice.Status.FINALIZED ? "FINAL BILL" : "INTERIM BILL");
        PdfPTable meta = table(new float[]{1.2f, 2f, 1.2f, 2f});
        kv(meta, "Invoice no.", v.invoiceNumber());
        kv(meta, "Status", v.status() == Invoice.Status.FINALIZED ? "Final (" + date(v.finalizedAt(), zone) + ")" : "Open (interim)");
        kv(meta, "Patient", v.patient().fullName());
        kv(meta, "MRN", v.patient().mrn());
        kv(meta, "Admission", v.admission() == null ? "-" : v.admission().admissionNumber());
        kv(meta, "Department", v.admission() == null ? "-" : nz(v.admission().departmentName()));
        kv(meta, "Admitted", v.admission() == null ? "-" : dateTime(v.admission().admittedAt(), zone));
        kv(meta, "Discharged", v.admission() == null || v.admission().dischargedAt() == null ? "-" : dateTime(v.admission().dischargedAt(), zone));
        kv(meta, "Statement date", dateTime(Instant.now(), zone));
        kv(meta, "Treating doctor", v.admission() == null ? "-" : nz(v.admission().attendingDoctorName()));
        doc.add(meta);

        section(doc, "Posted charges");
        PdfPTable items = table(new float[]{1.1f, 4.8f, 0.6f, 1.2f, 0.9f, 1.3f});
        head(items, "Date", "Description", "Qty", "Unit price", "Tax", "Amount (INR)");
        for (BillingDtos.PortalCategory c : v.categories()) {
            PdfPCell catCell = new PdfPCell(new Phrase(BillingReportService.categoryLabel(c.category()), BODY_BOLD));
            catCell.setColspan(5);
            catCell.setBackgroundColor(SOFT);
            catCell.setBorderColor(RULE);
            catCell.setPadding(4);
            items.addCell(catCell);
            cell(items, BillingSupport.inr(c.total()), BODY_BOLD, Element.ALIGN_RIGHT, SOFT);
            for (BillingDtos.PortalLine l : c.items()) {
                Font f = l.reversed() ? STRIKE : BODY;
                cell(items, l.serviceDate().format(DateTimeFormatter.ofPattern("dd MMM yy", Locale.ENGLISH)), f, Element.ALIGN_LEFT, null);
                cell(items, l.description() + (l.reversed() ? "  (reversed - not charged)" : ""), f, Element.ALIGN_LEFT, null);
                cell(items, l.quantity().stripTrailingZeros().toPlainString(), f, Element.ALIGN_RIGHT, null);
                cell(items, BillingSupport.inr(l.unitPrice()), f, Element.ALIGN_RIGHT, null);
                cell(items, BillingSupport.inr(l.taxAmount()), f, Element.ALIGN_RIGHT, null);
                cell(items, BillingSupport.inr(l.totalAmount()), f, Element.ALIGN_RIGHT, null);
            }
        }
        if (v.categories().isEmpty()) {
            PdfPCell none = new PdfPCell(new Phrase("No charges have been posted yet.", SMALL));
            none.setColspan(6);
            none.setPadding(6);
            none.setBorderColor(RULE);
            items.addCell(none);
        }
        doc.add(items);

        if (!v.payments().isEmpty()) {
            section(doc, "Payments received");
            PdfPTable pt = table(new float[]{1.6f, 1.4f, 1.6f, 2.4f, 1.4f});
            head(pt, "Receipt", "Date", "Method", "Paid by", "Amount (INR)");
            for (BillingDtos.PortalPayment p : v.payments()) {
                boolean rev = p.status() == Payment.Status.REVERSED;
                Font f = rev ? STRIKE : BODY;
                cell(pt, p.receiptNumber() + (rev ? " (reversed)" : ""), f, Element.ALIGN_LEFT, null);
                cell(pt, date(p.receivedAt(), zone), f, Element.ALIGN_LEFT, null);
                cell(pt, BillingSupport.method(p.method()), f, Element.ALIGN_LEFT, null);
                cell(pt, nz(p.payerName()), f, Element.ALIGN_LEFT, null);
                cell(pt, BillingSupport.inr(p.amount()), f, Element.ALIGN_RIGHT, null);
            }
            doc.add(pt);
        }
        if (!v.adjustments().isEmpty()) {
            section(doc, "Approved adjustments");
            PdfPTable at = table(new float[]{1.6f, 3.4f, 1.4f, 1.6f});
            head(at, "Reference", "Type", "Date", "Amount (INR)");
            for (BillingDtos.PortalAdjustment a : v.adjustments()) {
                cell(at, a.adjustmentNumber(), BODY, Element.ALIGN_LEFT, null);
                cell(at, a.label(), BODY, Element.ALIGN_LEFT, null);
                cell(at, date(a.approvedAt(), zone), BODY, Element.ALIGN_LEFT, null);
                cell(at, (a.balanceSign() < 0 ? "- " : "+ ") + BillingSupport.inr(a.amount()), BODY, Element.ALIGN_RIGHT, null);
            }
            doc.add(at);
        }
        if (!v.insurance().isEmpty()) {
            section(doc, "Insurance / third-party claims");
            PdfPTable it = table(new float[]{1.5f, 2.4f, 1.5f, 1.4f, 1.4f, 1.4f});
            head(it, "Claim", "Payer", "Status", "Claimed", "Approved", "Settled");
            for (BillingDtos.PortalClaim c : v.insurance()) {
                cell(it, c.claimNumber(), BODY, Element.ALIGN_LEFT, null);
                cell(it, c.payerName(), BODY, Element.ALIGN_LEFT, null);
                cell(it, BillingSupport.label(c.status()), BODY, Element.ALIGN_LEFT, null);
                cell(it, BillingSupport.inr(c.claimedAmount()), BODY, Element.ALIGN_RIGHT, null);
                cell(it, c.approvedAmount() == null ? "-" : BillingSupport.inr(c.approvedAmount()), BODY, Element.ALIGN_RIGHT, null);
                cell(it, BillingSupport.inr(c.settledAmount()), BODY, Element.ALIGN_RIGHT, null);
            }
            doc.add(it);
        }

        section(doc, "Balance");
        PdfPTable bt = table(new float[]{4f, 1.6f});
        for (BillingDtos.ExplanationLine l : v.explanation()) {
            boolean total = "=".equals(l.operator());
            Font f = total ? H2 : BODY;
            String prefix = total ? "" : "+".equals(l.operator()) ? "(+) " : "(-) ";
            cell(bt, prefix + l.label(), f, Element.ALIGN_LEFT, total ? SOFT : null);
            cell(bt, BillingSupport.inr(l.amount()), f, Element.ALIGN_RIGHT, total ? SOFT : null);
        }
        doc.add(bt);
        if (v.summary().insuranceApprovedPending().signum() > 0) {
            doc.add(new Paragraph("Insurance approved, pending settlement: INR " + BillingSupport.inr(v.summary().insuranceApprovedPending())
                    + " (not yet deducted above).", SMALL_ITALIC));
        }

        if (!v.estimates().isEmpty()) {
            section(doc, "Estimated upcoming charges - NOT PART OF THE BALANCE");
            PdfPTable et = table(new float[]{3.4f, 3f, 1.6f});
            head(et, "Description", "Basis", "Estimate (INR)");
            BigDecimal total = Money.ZERO;
            for (BillingDtos.PortalEstimate e : v.estimates()) {
                cell(et, e.description(), SMALL_ITALIC, Element.ALIGN_LEFT, null);
                cell(et, nz(e.basis()), SMALL_ITALIC, Element.ALIGN_LEFT, null);
                cell(et, BillingSupport.inr(e.estimatedAmount()), SMALL_ITALIC, Element.ALIGN_RIGHT, null);
                total = total.add(e.estimatedAmount());
            }
            cell(et, "Total estimate (informational only)", SMALL_ITALIC, Element.ALIGN_LEFT, null);
            cell(et, "", SMALL, Element.ALIGN_LEFT, null);
            cell(et, BillingSupport.inr(total), SMALL_ITALIC, Element.ALIGN_RIGHT, null);
            doc.add(et);
        }
        doc.add(spacer(10));
        doc.add(new Paragraph("Outstanding = posted charges - reversals + debit notes + refunds - payments (net of reversals) - discounts - "
                + "write-offs - credit notes - insurance settlements. Estimates are excluded. This is a computer-generated statement.", SMALL));
        doc.close();
        audit.record("INVOICE_PDF_DOWNLOADED", "INVOICE", v.id(), v.patient().id(), v.invoiceNumber());
        return new PdfFile("invoice-" + v.invoiceNumber() + ".pdf", out.toByteArray());
    }

    // ------------------------------------------------------------------ layout helpers

    private void header(Document doc, String title) {
        Organization org = organizations.findById(TenantContext.requireOrganizationId()).orElseThrow();
        PdfPTable h = new PdfPTable(new float[]{3f, 2f});
        h.setWidthPercentage(100);
        PdfPCell left = new PdfPCell();
        left.setBorder(Rectangle.NO_BORDER);
        left.addElement(new Paragraph(org.getName(), HOSPITAL));
        String addr = String.join(", ", List.of(nz(org.getAddress()), nz(org.getCity()), nz(org.getState())).stream()
                .filter(x -> !x.isBlank() && !"-".equals(x)).toList());
        left.addElement(new Paragraph(addr, SMALL));
        left.addElement(new Paragraph((org.getPhone() == null ? "" : "Phone " + org.getPhone() + "   ")
                + (org.getEmail() == null ? "" : org.getEmail())
                + (org.getRegistrationNumber() == null ? "" : "   Reg. " + org.getRegistrationNumber()), SMALL));
        h.addCell(left);
        PdfPCell right = new PdfPCell();
        right.setBorder(Rectangle.NO_BORDER);
        Paragraph t = new Paragraph(title, TITLE);
        t.setAlignment(Element.ALIGN_RIGHT);
        right.addElement(t);
        Paragraph cur = new Paragraph((title.endsWith("BILL") ? "Itemized statement. " : "") + "All amounts in Indian Rupees (INR)", SMALL);
        cur.setAlignment(Element.ALIGN_RIGHT);
        right.addElement(cur);
        h.addCell(right);
        doc.add(h);
        PdfPTable rule = new PdfPTable(1);
        rule.setWidthPercentage(100);
        PdfPCell rc = new PdfPCell(new Phrase(" ", new Font(Font.HELVETICA, 2)));
        rc.setBorder(Rectangle.BOTTOM);
        rc.setBorderColor(TEAL);
        rc.setBorderWidth(1.2f);
        rule.addCell(rc);
        doc.add(rule);
        doc.add(spacer(8));
    }

    private static void section(Document doc, String title) {
        Paragraph p = new Paragraph(title, H2);
        p.setSpacingBefore(12);
        p.setSpacingAfter(4);
        doc.add(p);
    }

    private static PdfPTable table(float[] widths) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        return t;
    }

    private static void head(PdfPTable t, String... labels) {
        for (int i = 0; i < labels.length; i++) {
            PdfPCell c = new PdfPCell(new Phrase(labels[i], new Font(Font.HELVETICA, 8, Font.BOLD, MUTED)));
            c.setHorizontalAlignment(i == labels.length - 1 || labels[i].contains("INR") || labels[i].matches("Qty|Unit price|Tax|Claimed|Approved|Settled")
                    ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
            c.setBorder(Rectangle.BOTTOM);
            c.setBorderColor(RULE);
            c.setPadding(4);
            t.addCell(c);
        }
        t.setHeaderRows(1);
    }

    private static void kv(PdfPTable t, String k, String v) {
        PdfPCell kc = new PdfPCell(new Phrase(k, SMALL));
        kc.setBorder(Rectangle.NO_BORDER);
        kc.setPadding(2);
        t.addCell(kc);
        PdfPCell vc = new PdfPCell(new Phrase(v == null ? "-" : v, BODY_BOLD));
        vc.setBorder(Rectangle.NO_BORDER);
        vc.setPadding(2);
        t.addCell(vc);
    }

    private static void cell(PdfPTable t, String text, Font f, int align, Color bg) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, f));
        c.setHorizontalAlignment(align);
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(RULE);
        c.setPadding(4);
        if (bg != null) {
            c.setBackgroundColor(bg);
        }
        t.addCell(c);
    }

    private static Paragraph spacer(float h) {
        Paragraph p = new Paragraph(new Chunk(" ", new Font(Font.HELVETICA, 2)));
        p.setSpacingAfter(h);
        return p;
    }

    private static String nz(String s) {
        return s == null ? "-" : s;
    }

    private static String dateTime(Instant i, ZoneId zone) {
        return i == null ? "-" : DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.ENGLISH).format(i.atZone(zone));
    }

    private static String date(Instant i, ZoneId zone) {
        return i == null ? "-" : DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH).format(i.atZone(zone));
    }

    // ------------------------------------------------------------------ amount in words (Indian system)

    private static final String[] ONES = {"", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
            "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen"};
    private static final String[] TENS = {"", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"};

    static String words(BigDecimal amount) {
        BigDecimal a = Money.scale(amount).abs();
        long rupees = a.longValue();
        int paise = a.remainder(BigDecimal.ONE).movePointRight(2).intValue();
        String r = rupees == 0 ? "Zero" : indian(rupees);
        return "Rupees " + r + (paise > 0 ? " and " + two(paise) + " Paise" : "") + " Only";
    }

    private static String indian(long n) {
        StringBuilder sb = new StringBuilder();
        long crore = n / 10_000_000;
        n %= 10_000_000;
        long lakh = n / 100_000;
        n %= 100_000;
        long thousand = n / 1000;
        n %= 1000;
        long hundred = n / 100;
        long rest = n % 100;
        if (crore > 0) {
            sb.append(indian(crore)).append(" Crore ");
        }
        if (lakh > 0) {
            sb.append(two((int) lakh)).append(" Lakh ");
        }
        if (thousand > 0) {
            sb.append(two((int) thousand)).append(" Thousand ");
        }
        if (hundred > 0) {
            sb.append(ONES[(int) hundred]).append(" Hundred ");
        }
        if (rest > 0) {
            sb.append(two((int) rest));
        }
        return sb.toString().trim();
    }

    private static String two(int n) {
        if (n < 20) {
            return ONES[n];
        }
        return (TENS[n / 10] + " " + ONES[n % 10]).trim();
    }
}
