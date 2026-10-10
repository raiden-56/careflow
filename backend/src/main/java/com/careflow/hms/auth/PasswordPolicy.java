package com.careflow.hms.auth;

import com.careflow.hms.common.ApiException;

import java.util.Set;

public final class PasswordPolicy {

    private static final Set<String> COMMON = Set.of("password", "password1", "password123", "welcome123",
            "qwerty123", "admin123", "12345678", "123456789", "careflow", "hospital123");

    private PasswordPolicy() {
    }

    /** At least 10 characters with upper case, lower case and a digit; not a common password. */
    public static void validate(String password, String email) {
        if (password == null || password.length() < 10 || password.length() > 128) {
            throw ApiException.badRequest("WEAK_PASSWORD", "Password must be 10 to 128 characters long.");
        }
        if (!password.chars().anyMatch(Character::isUpperCase)
                || !password.chars().anyMatch(Character::isLowerCase)
                || !password.chars().anyMatch(Character::isDigit)) {
            throw ApiException.badRequest("WEAK_PASSWORD", "Password must include upper-case, lower-case letters and a digit.");
        }
        String lower = password.toLowerCase();
        if (COMMON.contains(lower) || (email != null && lower.contains(email.split("@")[0].toLowerCase()))) {
            throw ApiException.badRequest("WEAK_PASSWORD", "Password is too easy to guess.");
        }
    }
}
