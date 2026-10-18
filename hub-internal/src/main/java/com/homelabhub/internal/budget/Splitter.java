package com.homelabhub.internal.budget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Equal split in whole paise (plan D2). The leftover paise go one each to the sharers in ascending person id order, so
 * the shares always add up to the amount exactly and the same input always gives the same result.
 */
public final class Splitter {

    private Splitter() {}

    /** Person id -> share in paise, in ascending id order. */
    public static Map<Long, Long> split(long amountPaise, List<Long> sharerIds) {
        if (sharerIds.isEmpty()) {
            throw new IllegalArgumentException("At least one sharer is needed");
        }
        if (amountPaise < 0) {
            throw new IllegalArgumentException("Negative amount");
        }
        List<Long> ids = new ArrayList<>(sharerIds);
        ids.sort(null);
        for (int i = 1; i < ids.size(); i++) {
            if (ids.get(i).equals(ids.get(i - 1))) {
                throw new IllegalArgumentException("Duplicate sharer " + ids.get(i));
            }
        }
        long n = ids.size();
        long base = amountPaise / n;
        long extra = amountPaise % n;
        Map<Long, Long> out = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            out.put(ids.get(i), base + (i < extra ? 1 : 0));
        }
        return out;
    }
}
