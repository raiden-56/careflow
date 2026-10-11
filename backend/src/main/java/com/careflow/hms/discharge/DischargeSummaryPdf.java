package com.careflow.hms.discharge;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.List;

/** Renders a discharge summary as a printable A4 PDF (OpenPDF). */
@Component
public class DischargeSummaryPdf {

    private static final Color INK = new Color(16, 38, 58);
    private static final Color MUTED = new Color(74, 91, 108);
    private static final Color TEAL = new Color(15, 118, 110);
    private static final Color RULE = new Color(228, 225, 218);
    private static final Color SOFT = new Color(250, 249, 246);

    public record Field(String label, String value) {
    }

    public record Section(String title, String text) {
    }

    public record Model(String hospitalName, String hospitalAddress, String hospitalContact, String documentStatus,
                        List<Field> patientFields, List<Section> sections, String signature, List<String> amendments,
                        String footer) {
    }

    public byte[] render(Model m) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 48, 48, 54, 60);
        PdfWriter writer = PdfWriter.getInstance(doc, out);
        Font footerFont = FontFactory.getFont(FontFactory.HELVETICA, 8, MUTED);
        writer.setPageEvent(new PdfPageEventHelper() {
            @Override
            public void onEndPage(PdfWriter w, Document d) {
                ColumnText.showTextAligned(w.getDirectContent(), Element.ALIGN_LEFT,
                        new Phrase(m.footer(), footerFont), d.left(), d.bottom() - 24, 0);
                ColumnText.showTextAligned(w.getDirectContent(), Element.ALIGN_RIGHT,
                        new Phrase("Page " + w.getPageNumber(), footerFont), d.right(), d.bottom() - 24, 0);
            }
        });
        doc.open();

        Font hospital = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15, INK);
        Font small = FontFactory.getFont(FontFactory.HELVETICA, 8.5f, MUTED);
        Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, TEAL);
        Font label = FontFactory.getFont(FontFactory.HELVETICA, 8, MUTED);
        Font value = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9.5f, INK);
        Font heading = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10.5f, INK);
        Font body = FontFactory.getFont(FontFactory.HELVETICA, 9.5f, INK);
        Font status = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, m.documentStatus().startsWith("DRAFT") ? new Color(169, 94, 8) : TEAL);

        Paragraph h = new Paragraph(m.hospitalName(), hospital);
        doc.add(h);
        if (m.hospitalAddress() != null) {
            doc.add(new Paragraph(m.hospitalAddress(), small));
        }
        if (m.hospitalContact() != null) {
            doc.add(new Paragraph(m.hospitalContact(), small));
        }
        PdfPTable titleRow = new PdfPTable(new float[]{3, 1.4f});
        titleRow.setWidthPercentage(100);
        titleRow.setSpacingBefore(12);
        PdfPCell t = new PdfPCell(new Phrase("Discharge Summary", title));
        t.setBorder(Rectangle.BOTTOM);
        t.setBorderColor(TEAL);
        t.setBorderWidth(1.2f);
        t.setPaddingBottom(6);
        PdfPCell s = new PdfPCell(new Phrase(m.documentStatus(), status));
        s.setBorder(Rectangle.BOTTOM);
        s.setBorderColor(TEAL);
        s.setBorderWidth(1.2f);
        s.setHorizontalAlignment(Element.ALIGN_RIGHT);
        s.setVerticalAlignment(Element.ALIGN_BOTTOM);
        s.setPaddingBottom(6);
        titleRow.addCell(t);
        titleRow.addCell(s);
        doc.add(titleRow);

        PdfPTable grid = new PdfPTable(3);
        grid.setWidthPercentage(100);
        grid.setSpacingBefore(10);
        grid.setSpacingAfter(8);
        for (Field f : m.patientFields()) {
            PdfPCell c = new PdfPCell();
            c.setBorder(Rectangle.NO_BORDER);
            c.setBackgroundColor(SOFT);
            c.setPadding(6);
            c.addElement(new Paragraph(f.label(), label));
            c.addElement(new Paragraph(f.value() == null || f.value().isBlank() ? "-" : f.value(), value));
            grid.addCell(c);
        }
        int rem = m.patientFields().size() % 3;
        for (int i = 0; rem != 0 && i < 3 - rem; i++) {
            PdfPCell c = new PdfPCell(new Phrase(""));
            c.setBorder(Rectangle.NO_BORDER);
            c.setBackgroundColor(SOFT);
            grid.addCell(c);
        }
        doc.add(grid);

        for (Section sec : m.sections()) {
            if (sec.text() == null || sec.text().isBlank()) {
                continue;
            }
            Paragraph ph = new Paragraph(sec.title(), heading);
            ph.setSpacingBefore(10);
            ph.setSpacingAfter(3);
            doc.add(ph);
            PdfPTable rule = new PdfPTable(1);
            rule.setWidthPercentage(100);
            PdfPCell rc = new PdfPCell(new Phrase(""));
            rc.setBorder(Rectangle.TOP);
            rc.setBorderColor(RULE);
            rc.setFixedHeight(3);
            rule.addCell(rc);
            doc.add(rule);
            for (String line : sec.text().split("\\r?\\n")) {
                Paragraph p = new Paragraph(line.isBlank() ? " " : line, body);
                p.setLeading(13);
                doc.add(p);
            }
        }

        Paragraph sig = new Paragraph();
        sig.setSpacingBefore(18);
        sig.add(new Chunk(m.signature(), FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 9, MUTED)));
        doc.add(sig);
        if (!m.amendments().isEmpty()) {
            Paragraph ah = new Paragraph("Amendment history", heading);
            ah.setSpacingBefore(10);
            doc.add(ah);
            for (String a : m.amendments()) {
                doc.add(new Paragraph("- " + a, small));
            }
        }
        doc.close();
        return out.toByteArray();
    }
}
