package com.careflow.hms.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Decimal-safe monetary helpers. All amounts are INR with 2 decimal places, HALF_UP rounding. */
public final class Money {

    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Money() {
    }

    public static BigDecimal of(String value) {
        return scale(new BigDecimal(value));
    }

    public static BigDecimal of(long rupees) {
        return scale(BigDecimal.valueOf(rupees));
    }

    public static BigDecimal scale(BigDecimal v) {
        return v == null ? ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal multiply(BigDecimal unitPrice, BigDecimal quantity) {
        return scale(unitPrice.multiply(quantity));
    }

    /** Tax on a net amount at a percentage rate (e.g. 18.00 for 18%). */
    public static BigDecimal tax(BigDecimal net, BigDecimal ratePercent) {
        if (ratePercent == null || ratePercent.signum() == 0) {
            return ZERO;
        }
        return scale(net.multiply(ratePercent).divide(HUNDRED, 4, RoundingMode.HALF_UP));
    }

    public static BigDecimal sum(BigDecimal a, BigDecimal b) {
        return scale(nz(a).add(nz(b)));
    }

    public static BigDecimal nz(BigDecimal v) {
        return v == null ? ZERO : v;
    }

    public static boolean isPositive(BigDecimal v) {
        return v != null && v.signum() > 0;
    }
}
