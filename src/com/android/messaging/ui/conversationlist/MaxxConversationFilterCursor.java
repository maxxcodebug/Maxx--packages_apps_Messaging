/*
 * Copyright (C) 2026 Anshuman_X (maxxcodebug) - MaxxOS design
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.messaging.ui.conversationlist;

import android.database.Cursor;
import android.database.CursorWrapper;
import android.text.TextUtils;

import com.android.messaging.datamodel.DatabaseHelper.ConversationColumns;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * MaxxOS: presents a filtered view (category + search text) of the conversation list cursor.
 * The wrapped cursor is owned by the loader, so this wrapper must never be closed by callers
 * that do not own the underlying cursor (the adapter's swapCursor() does not close it).
 */
public class MaxxConversationFilterCursor extends CursorWrapper {

    public static final int FILTER_ALL = 0;
    public static final int FILTER_PERSONAL = 1;
    public static final int FILTER_BUSINESS = 2;
    public static final int FILTER_OTP = 3;

    // Messages that look like one-time passwords / verification codes.
    private static final Pattern OTP_KEYWORDS = Pattern.compile(
            "\\b(otp|one[- ]?time (password|pin|code)|verification code|verify|passcode"
            + "|security code|login code|authentication code|auth code)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE_NEAR_DIGITS = Pattern.compile(
            "(\\b(code|pin)\\b.{0,25}\\b\\d{4,8}\\b)|(\\b\\d{4,8}\\b.{0,25}\\b(code|otp|pin)\\b)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final int[] mMap;
    private final int mCount;
    private int mPos = -1;

    public MaxxConversationFilterCursor(final Cursor cursor, final int filter,
            final String query) {
        super(cursor);
        final int total = cursor.getCount();
        final int[] map = new int[total];
        int n = 0;
        final int nameIdx = cursor.getColumnIndex(ConversationColumns.NAME);
        final int snippetIdx = cursor.getColumnIndex(ConversationColumns.SNIPPET_TEXT);
        final int destIdx = cursor.getColumnIndex(
                ConversationColumns.OTHER_PARTICIPANT_NORMALIZED_DESTINATION);
        final int contactIdx = cursor.getColumnIndex(
                ConversationColumns.PARTICIPANT_CONTACT_ID);
        final int countIdx = cursor.getColumnIndex(ConversationColumns.PARTICIPANT_COUNT);
        final String q = TextUtils.isEmpty(query) ? null
                : query.trim().toLowerCase(Locale.getDefault());
        for (int i = 0; i < total; i++) {
            if (!cursor.moveToPosition(i)) {
                continue;
            }
            final String name = nameIdx >= 0 ? cursor.getString(nameIdx) : null;
            final String snippet = snippetIdx >= 0 ? cursor.getString(snippetIdx) : null;
            if (q != null && !q.isEmpty()
                    && !contains(name, q) && !contains(snippet, q)) {
                continue;
            }
            if (filter != FILTER_ALL) {
                final String dest = destIdx >= 0 ? cursor.getString(destIdx) : null;
                final long contactId = contactIdx >= 0 ? cursor.getLong(contactIdx) : -1;
                final int participants = countIdx >= 0 ? cursor.getInt(countIdx) : 1;
                if (categoryOf(snippet, dest, contactId, participants) != filter) {
                    continue;
                }
            }
            map[n++] = i;
        }
        mMap = map;
        mCount = n;
    }

    private static boolean contains(final String haystack, final String lowerNeedle) {
        return haystack != null && haystack.toLowerCase(Locale.getDefault()).contains(lowerNeedle);
    }

    /** Classifies a conversation as OTP, Business or Personal. */
    static int categoryOf(final String snippet, final String destination, final long contactId,
            final int participantCount) {
        if (!TextUtils.isEmpty(snippet)
                && (OTP_KEYWORDS.matcher(snippet).find()
                || CODE_NEAR_DIGITS.matcher(snippet).find())) {
            return FILTER_OTP;
        }
        if (participantCount <= 1 && contactId <= 0 && isBusinessSender(destination)) {
            return FILTER_BUSINESS;
        }
        return FILTER_PERSONAL;
    }

    /** Alphanumeric sender IDs (VE-ViCARE-S) and short codes (611123) are not people. */
    private static boolean isBusinessSender(final String destination) {
        if (TextUtils.isEmpty(destination)) {
            return false;
        }
        boolean hasLetter = false;
        int digits = 0;
        for (int i = 0; i < destination.length(); i++) {
            final char c = destination.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                digits++;
            }
        }
        if (destination.indexOf('@') >= 0) {
            return false; // email address
        }
        return hasLetter || digits <= 6;
    }

    @Override
    public int getCount() {
        return mCount;
    }

    @Override
    public int getPosition() {
        return mPos;
    }

    @Override
    public boolean moveToPosition(final int position) {
        if (position < -1 || position > mCount) {
            return false;
        }
        mPos = position;
        if (position < 0 || position >= mCount) {
            return false;
        }
        return super.moveToPosition(mMap[position]);
    }

    @Override
    public boolean move(final int offset) {
        return moveToPosition(mPos + offset);
    }

    @Override
    public boolean moveToFirst() {
        return moveToPosition(0);
    }

    @Override
    public boolean moveToLast() {
        return moveToPosition(mCount - 1);
    }

    @Override
    public boolean moveToNext() {
        return moveToPosition(mPos + 1);
    }

    @Override
    public boolean moveToPrevious() {
        return moveToPosition(mPos - 1);
    }

    @Override
    public boolean isFirst() {
        return mCount > 0 && mPos == 0;
    }

    @Override
    public boolean isLast() {
        return mCount > 0 && mPos == mCount - 1;
    }

    @Override
    public boolean isBeforeFirst() {
        return mCount == 0 || mPos == -1;
    }

    @Override
    public boolean isAfterLast() {
        return mCount == 0 || mPos == mCount;
    }
}
