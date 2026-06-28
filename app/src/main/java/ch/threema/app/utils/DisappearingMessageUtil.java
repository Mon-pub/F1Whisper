/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2025 Threema GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package ch.threema.app.utils;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import ch.threema.app.R;

/**
 * F1Whisper: single source of truth for the disappearing-messages duration options.
 * <p>
 * The picker offers a fixed Signal-style set of durations and an "Off" entry. Both the duration
 * picker and the disappearing-status message use the same option table and label resources so the
 * wording always matches. {@code 0} seconds means the timer is off.
 */
public final class DisappearingMessageUtil {

    private DisappearingMessageUtil() {
    }

    private static final int MINUTE = 60;
    private static final int HOUR = 60 * MINUTE;
    private static final int DAY = 24 * HOUR;
    private static final int WEEK = 7 * DAY;

    /**
     * The selectable timer values in seconds. The first entry ({@code 0}) is "Off". Mirrors the
     * Signal disappearing-timer presets. Index of this array is what the picker wheel selects.
     */
    public static final int[] DURATIONS_SECONDS = {
        0,
        30,
        5 * MINUTE,
        HOUR,
        8 * HOUR,
        DAY,
        WEEK,
        4 * WEEK,
    };

    /**
     * Returns the label for a given timer value (e.g. "Off", "1 day"). Unknown non-zero values fall
     * back to a generic seconds count so a value synced from a future build never renders blank.
     */
    @NonNull
    public static String getDurationLabel(@NonNull Context context, int seconds) {
        switch (seconds) {
            case 0:
                return context.getString(R.string.disappearing_off);
            case 30:
                return context.getString(R.string.disappearing_duration_30s);
            case 5 * MINUTE:
                return context.getString(R.string.disappearing_duration_5m);
            case HOUR:
                return context.getString(R.string.disappearing_duration_1h);
            case 8 * HOUR:
                return context.getString(R.string.disappearing_duration_8h);
            case DAY:
                return context.getString(R.string.disappearing_duration_1d);
            case WEEK:
                return context.getString(R.string.disappearing_duration_1w);
            case 4 * WEEK:
                return context.getString(R.string.disappearing_duration_4w);
            default:
                // A value from a newer build we don't have a fixed label for: fall back to a plain
                // "<n> s" so the status/badge is never blank. Our own picker only ever produces the
                // fixed values above, so this branch is effectively unreachable in practice.
                return seconds + " " + context.getString(R.string.seconds);
        }
    }

    /**
     * The wheel labels for the picker, in {@link #DURATIONS_SECONDS} order.
     */
    @NonNull
    public static String[] getPickerLabels(@NonNull Context context) {
        final String[] labels = new String[DURATIONS_SECONDS.length];
        for (int i = 0; i < DURATIONS_SECONDS.length; i++) {
            labels[i] = getDurationLabel(context, DURATIONS_SECONDS[i]);
        }
        return labels;
    }

    /**
     * Maps a timer value in seconds to its index in {@link #DURATIONS_SECONDS}, so the picker can
     * pre-select the conversation's current timer. Unknown values clamp to "Off" (index 0).
     */
    public static int indexForSeconds(@Nullable Integer seconds) {
        if (seconds == null) {
            return 0;
        }
        for (int i = 0; i < DURATIONS_SECONDS.length; i++) {
            if (DURATIONS_SECONDS[i] == seconds) {
                return i;
            }
        }
        return 0;
    }
}
