package com.careflow.hms.pharmacy;

import com.careflow.hms.common.Money;
import com.careflow.hms.common.TimeUtil;
import com.careflow.hms.pharmacy.PharmacyDtos.AlertsView;
import com.careflow.hms.pharmacy.PharmacyDtos.Availability;
import com.careflow.hms.pharmacy.PharmacyDtos.DayCount;
import com.careflow.hms.pharmacy.PharmacyDtos.PharmacyDashboard;
import com.careflow.hms.pharmacy.PharmacyDtos.QueueItem;
import com.careflow.hms.pharmacy.PharmacyDtos.StockLevel;
import com.careflow.hms.pharmacy.PharmacyDtos.SupplyStatus;
import com.careflow.hms.security.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Pharmacy KPIs. Every figure is derived from live records on the server. */
@Service
@RequiredArgsConstructor
public class PharmacyDashboardService {

    private final PrescriptionQueueService queue;
    private final InventoryService inventory;
    private final DispensationRepository dispensations;

    @Transactional(readOnly = true)
    public PharmacyDashboard dashboard(int nearExpiryDays) {
        UUID orgId = TenantContext.requireOrganizationId();
        List<QueueItem> items = queue.allActiveItems();
        int awaiting = count(items, SupplyStatus.AWAITING_VERIFICATION);
        int queried = count(items, SupplyStatus.QUERIED);
        int ready = count(items, SupplyStatus.READY_TO_DISPENSE);

        LocalDate today = PharmacyClock.today();
        LocalDate first = today.minusDays(6);
        List<Dispensation> recent = dispensations.findByOrganizationIdAndDispensedAtGreaterThanEqual(orgId, TimeUtil.startOfDay(first));
        List<DayCount> days = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
            LocalDate day = d;
            List<Dispensation> onDay = recent.stream().filter(x -> TimeUtil.localDate(x.getDispensedAt()).equals(day)).toList();
            days.add(new DayCount(day, onDay.size(), onDay.stream().mapToLong(x -> x.getQuantity() - x.getReturnedQuantity()).sum()));
        }
        DayCount todayCount = days.get(days.size() - 1);

        List<StockLevel> levels = inventory.stockLevels(nearExpiryDays);
        int low = (int) levels.stream().filter(s -> s.active() && s.availability() == Availability.LOW_STOCK).count();
        int out = (int) levels.stream().filter(s -> s.active() && s.availability() == Availability.OUT_OF_STOCK).count();
        BigDecimal value = levels.stream().map(StockLevel::stockValue).reduce(Money.ZERO, BigDecimal::add);
        AlertsView alerts = inventory.alerts(nearExpiryDays);
        return new PharmacyDashboard(awaiting + queried + ready, awaiting, queried, ready, todayCount.dispensations(),
                todayCount.units(), low, out, alerts.nearExpiry().size(), alerts.expiredOnHand().size(),
                alerts.quarantined().size(), Money.scale(value), nearExpiryDays, days);
    }

    private static int count(List<QueueItem> items, SupplyStatus s) {
        return (int) items.stream().filter(i -> i.supplyStatus() == s).count();
    }
}
