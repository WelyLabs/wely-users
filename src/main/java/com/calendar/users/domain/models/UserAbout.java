package com.calendar.users.domain.models;

import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a user says about themselves on their profile: a one-line bio and a few interests.
 *
 * <p>Tidied on the way in, so what is stored is what is shown: spaces trimmed and collapsed, an
 * empty bio becomes none, the same interest typed twice is kept once (« Rando » and « rando »).
 * Anything past the limits is refused rather than cut, so nobody loses words without knowing.
 */
public record UserAbout(String bio, List<String> interests) {

    public static final int MAX_BIO_LENGTH = 160;
    public static final int MAX_INTERESTS = 5;
    public static final int MAX_INTEREST_LENGTH = 24;

    public static final UserAbout EMPTY = new UserAbout(null, List.of());

    public UserAbout {
        bio = bio == null || tidy(bio).isEmpty() ? null : tidy(bio);
        List<String> kept = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String interest : interests == null ? List.<String>of() : interests) {
            String tidied = interest == null ? "" : tidy(interest);
            if (!tidied.isEmpty() && seen.add(tidied.toLowerCase(Locale.ROOT))) {
                kept.add(tidied);
            }
        }
        boolean valid = (bio == null || bio.length() <= MAX_BIO_LENGTH)
                && kept.size() <= MAX_INTERESTS
                && kept.stream().allMatch(interest -> interest.length() <= MAX_INTEREST_LENGTH);
        if (!valid) {
            throw new BusinessException(BusinessErrorCode.ABOUT_INVALID);
        }
        interests = List.copyOf(kept);
    }

    private static String tidy(String text) {
        return text.strip().replaceAll("\\s+", " ");
    }
}
