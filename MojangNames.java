package com.offlineskin;

import java.util.regex.Pattern;

public final class MojangNames {

    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private MojangNames() {
    }

    public static boolean isValid(String name) {
        return name != null && VALID.matcher(name).matches();
    }
}
