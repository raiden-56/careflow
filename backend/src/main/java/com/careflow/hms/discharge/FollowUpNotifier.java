package com.careflow.hms.discharge;

import com.careflow.hms.facility.DepartmentRepository;
import com.careflow.hms.notification.ExternalChannelGateway;
import com.careflow.hms.notification.Notification;
import com.careflow.hms.notification.NotificationDelivery;
import com.careflow.hms.notification.NotificationDeliveryRepository;
import com.careflow.hms.notification.NotificationRepository;
import com.careflow.hms.notification.NotificationRequest;
import com.careflow.hms.notification.NotificationService;
import com.careflow.hms.notification.NotificationTemplate;
import com.careflow.hms.notification.NotificationTemplateRepository;
import com.careflow.hms.org.Organization;
import com.careflow.hms.org.OrganizationRepository;
import com.careflow.hms.org.SettingsService;
import com.careflow.hms.patient.Patient;
import com.careflow.hms.patient.PatientRepository;
import com.careflow.hms.user.User;
import com.careflow.hms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tells patients (and authorized caregivers) about follow-ups through the hospital's configured channels
 * ({@code followUp.notificationChannels}). In-app notifications go through {@link NotificationService};
 * email/SMS go through {@link ExternalChannelGateway} (the development outbox unless a real provider is
 * configured), using the editable {@code FOLLOW_UP_SCHEDULED} / {@code FOLLOW_UP_REMINDER} templates.
 * Every attempt is logged per follow-up and as a delivery of the patient's in-app notification.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FollowUpNotifier {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z]+)\\s*}}");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM yyyy, h:mm a", Locale.ENGLISH);

    private final NotificationService notifications;
    private final NotificationRepository notificationRepository;
    private final NotificationDeliveryRepository notificationDeliveries;
    private final NotificationTemplateRepository templates;
    private final ExternalChannelGateway gateway;
    private final FollowUpDeliveryRepository deliveries;
    private final PatientRepository patients;
    private final UserRepository users;
    private final DepartmentRepository departments;
    private final OrganizationRepository organizations;
    private final SettingsService settings;

    public record Outcome(boolean anySent, boolean alreadySent) {
    }

    /** True for follow-up types the patient acts on (result reviews are clinician tasks). */
    public static boolean patientFacing(FollowUp f) {
        return f.getFollowUpType() != FollowUp.FollowUpType.RESULT_REVIEW;
    }

    public Outcome notify(FollowUp f, FollowUpDelivery.Event event) {
        UUID orgId = f.getOrganizationId();
        Organization org = organizations.findById(orgId).orElseThrow();
        Patient p = patients.findById(f.getPatientId()).orElseThrow();
        Instant now = Instant.now();
        String key = "FOLLOW_UP_" + event + ":" + f.getId() + ":"
                + (f.getScheduledAt() == null ? "unscheduled" : String.valueOf(f.getScheduledAt().getEpochSecond()))
                + (event == FollowUpDelivery.Event.RESENT ? ":" + now.toEpochMilli() : "");
        if (deliveries.existsByFollowUpIdAndDedupeKey(f.getId(), key)) {
            return new Outcome(false, true);
        }
        Set<NotificationDelivery.Channel> channels = channels(settings.get(orgId, SettingsService.FOLLOWUP_CHANNELS));
        Map<String, String> vars = variables(f, p, org);
        String code = event == FollowUpDelivery.Event.REMINDER ? "FOLLOW_UP_REMINDER"
                : event == FollowUpDelivery.Event.CANCELLED ? null : "FOLLOW_UP_SCHEDULED";

        String[] inApp = content(orgId, code, "IN_APP", vars, event, f);
        int created = notifications.notifyPatient(p.getId(), NotificationRequest.builder()
                .category("FOLLOW_UP").priority(event == FollowUpDelivery.Event.CANCELLED ? Notification.Priority.HIGH : Notification.Priority.NORMAL)
                .title(truncate(inApp[0], 300)).message(truncate(inApp[1], 1900)).link("/patient/follow-ups")
                .entityType("FOLLOW_UP").entityId(f.getId()).dedupeKey(key).build());
        UUID notificationId = p.getUserId() == null ? null
                : notificationRepository.findFirstByRecipientUserIdAndDedupeKey(p.getUserId(), key).map(Notification::getId).orElse(null);
        boolean anySent = created > 0;
        log(f, event, NotificationDelivery.Channel.IN_APP, "in-app", null,
                created > 0 ? NotificationDelivery.Status.DELIVERED : NotificationDelivery.Status.SKIPPED,
                created > 0 ? null : "No portal account or authorized caregiver on file", notificationId, key);

        User portalUser = p.getUserId() == null ? null : users.findById(p.getUserId()).orElse(null);
        for (NotificationDelivery.Channel ch : new NotificationDelivery.Channel[]{NotificationDelivery.Channel.EMAIL, NotificationDelivery.Channel.SMS}) {
            if (!channels.contains(ch)) {
                continue;
            }
            String dest = ch == NotificationDelivery.Channel.EMAIL
                    ? firstNonBlank(p.getEmail(), portalUser == null ? null : portalUser.getEmail())
                    : firstNonBlank(p.getPhone(), portalUser == null ? null : portalUser.getPhone());
            String[] c = content(orgId, code, ch.name(), vars, event, f);
            ExternalChannelGateway.Result r;
            try {
                r = gateway.send(orgId, ch, dest, c[0], c[1]);
            } catch (RuntimeException ex) {
                log.warn("Follow-up {} delivery failed on {}", f.getId(), ch, ex);
                r = new ExternalChannelGateway.Result(NotificationDelivery.Status.FAILED, "unknown", truncate(ex.getMessage(), 900));
            }
            log(f, event, ch, r.provider() == null ? "unknown" : r.provider(), dest, r.status(), r.error(), notificationId, key);
            if (notificationId != null) {
                NotificationDelivery d = new NotificationDelivery();
                d.setNotificationId(notificationId);
                d.setChannel(ch);
                d.setProvider(r.provider() == null ? "unknown" : r.provider());
                d.setDestination(dest);
                d.setStatus(r.status());
                d.setErrorMessage(r.error());
                notificationDeliveries.save(d);
            }
            anySent |= r.status() == NotificationDelivery.Status.SENT || r.status() == NotificationDelivery.Status.DELIVERED;
        }
        return new Outcome(anySent, false);
    }

    private void log(FollowUp f, FollowUpDelivery.Event event, NotificationDelivery.Channel ch, String provider, String dest,
                     NotificationDelivery.Status status, String error, UUID notificationId, String key) {
        FollowUpDelivery d = new FollowUpDelivery();
        d.setOrganizationId(f.getOrganizationId());
        d.setFollowUpId(f.getId());
        d.setEvent(event);
        d.setChannel(ch);
        d.setProvider(provider);
        d.setDestination(dest);
        d.setStatus(status);
        d.setErrorMessage(error);
        d.setNotificationId(notificationId);
        d.setDedupeKey(key);
        deliveries.save(d);
    }

    static Set<NotificationDelivery.Channel> channels(String setting) {
        Set<NotificationDelivery.Channel> out = EnumSet.of(NotificationDelivery.Channel.IN_APP);
        if (setting != null) {
            for (String part : setting.split(",")) {
                try {
                    NotificationDelivery.Channel ch = NotificationDelivery.Channel.valueOf(part.trim().toUpperCase(Locale.ROOT));
                    if (ch != NotificationDelivery.Channel.PUSH) {
                        out.add(ch);
                    }
                } catch (IllegalArgumentException ignored) {
                    // unknown channel names are ignored (validated by the settings screen)
                }
            }
        }
        return out;
    }

    private Map<String, String> variables(FollowUp f, Patient p, Organization org) {
        ZoneId zone = org.zone();
        Map<String, String> v = new HashMap<>();
        v.put("hospital", org.getName());
        v.put("patient", p.fullName());
        v.put("title", f.getTitle());
        v.put("when", f.getScheduledAt() == null ? "a date to be confirmed" : WHEN.format(f.getScheduledAt().atZone(zone)));
        v.put("location", f.getLocation() == null || f.getLocation().isBlank() ? org.getName() : f.getLocation());
        v.put("clinician", f.getClinicianId() == null ? "" : users.findById(f.getClinicianId()).map(User::getFullName).orElse(""));
        v.put("department", f.getDepartmentId() == null ? "" : departments.findById(f.getDepartmentId()).map(d -> d.getName()).orElse(""));
        return v;
    }

    private String[] content(UUID orgId, String code, String channel, Map<String, String> vars, FollowUpDelivery.Event event, FollowUp f) {
        Optional<NotificationTemplate> t = code == null ? Optional.empty()
                : templates.findByOrganizationIdAndCodeAndChannelAndActiveTrue(orgId, code, channel);
        if (t.isPresent() && event != FollowUpDelivery.Event.RESCHEDULED) {
            return new String[]{render(t.get().getSubject(), vars), render(t.get().getBody(), vars)};
        }
        String withClinician = vars.get("clinician").isBlank() ? "" : " with " + vars.get("clinician");
        return switch (event) {
            case REMINDER -> new String[]{render("Reminder: {{title}}", vars),
                    render("{{hospital}} reminder: {{patient}}, '{{title}}'" + withClinician + " is on {{when}} at {{location}}.", vars)};
            case RESCHEDULED -> new String[]{render("Follow-up rescheduled: {{title}}", vars),
                    render("{{patient}}, your follow-up '{{title}}'" + withClinician + " has been moved to {{when}} at {{location}}. - {{hospital}}", vars)};
            case CANCELLED -> new String[]{render("Follow-up cancelled: {{title}}", vars),
                    render("{{patient}}, your follow-up '{{title}}' planned for {{when}} has been cancelled. The hospital will contact you if a new appointment is needed. - {{hospital}}", vars)};
            default -> f.getScheduledAt() == null
                    ? new String[]{render("New follow-up: {{title}}", vars),
                    render("{{patient}}, your care team has added a follow-up item: '{{title}}'. Details are in the patient portal. - {{hospital}}", vars)}
                    : new String[]{render("Follow-up scheduled: {{title}}", vars),
                    render("{{patient}}, your follow-up '{{title}}'" + withClinician + " is scheduled for {{when}} at {{location}}. - {{hospital}}", vars)};
        };
    }

    static String render(String template, Map<String, String> vars) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(vars.getOrDefault(m.group(1), "")));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b != null && !b.isBlank() ? b : null;
    }

    private static String truncate(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
