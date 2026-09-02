/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef LATINIME_CHAR_UTILS_H
#define LATINIME_CHAR_UTILS_H

#include <cctype>
#include <cstring>
#include <vector>

#include "defines.h"

namespace latinime {

class CharUtils {
 public:
    static const std::vector<int> EMPTY_STRING;

    static AK_FORCE_INLINE bool isAsciiUpper(int c) {
        // Note: isupper(...) reports false positives for some Cyrillic characters, causing them to
        // be incorrectly lower-cased using toAsciiLower(...) rather than latin_tolower(...).
        return (c >= 'A' && c <= 'Z');
    }

    static AK_FORCE_INLINE int toLowerCase(const int c) {
        if (isAsciiUpper(c)) {
            return toAsciiLower(c);
        }
        if (isAscii(c)) {
            return c;
        }
        return latin_tolower(c);
    }

    static AK_FORCE_INLINE int toBaseLowerCase(const int c) {
        return toLowerCase(toBaseCodePoint(c));
    }

    static AK_FORCE_INLINE bool isIntentionalOmissionCodePoint(const int codePoint) {
        // TODO: Do not hardcode here
        return codePoint == KEYCODE_SINGLE_QUOTE || codePoint == KEYCODE_HYPHEN_MINUS;
    }
    static AK_FORCE_INLINE int getCodePointCount(const int arraySize, const int *const codePoints) {
        int size = 0;
        for (; size < arraySize; ++size) {
            if (codePoints[size] == '\0') {
                break;
            }
        }
        return size;
    }

    // The historical BASE_CHARS table predates Vietnamese U+1EA0..U+1EF9 and only
    // performs a one-level Unicode decomposition. Vietnamese letters such as ấ
    // decompose through â, so handle the whole precomposed Vietnamese block directly.
    static AK_FORCE_INLINE int toVietnameseBaseCodePoint(const int c) {
        if (c == 0x0110) return 'D'; // Đ
        if (c == 0x0111) return 'd'; // đ
        if (c < 0x1EA0 || c > 0x1EF9) return c;
        struct VietnameseRange {
            int first;
            int last;
            int uppercaseBase;
            int lowercaseBase;
        };
        static const VietnameseRange ranges[] = {
            { 0x1EA0, 0x1EAF, 'A', 'a' }, // A, Â
            { 0x1EB0, 0x1EB7, 'A', 'a' }, // Ă
            { 0x1EB8, 0x1EC7, 'E', 'e' }, // E, Ê
            { 0x1EC8, 0x1ECB, 'I', 'i' },
            { 0x1ECC, 0x1EE3, 'O', 'o' }, // O, Ô, Ơ
            { 0x1EE4, 0x1EF3, 'U', 'u' }, // U, Ư
            { 0x1EF4, 0x1EF9, 'Y', 'y' },
        };
        for (const auto &range : ranges) {
            if (c >= range.first && c <= range.last) {
                // Each code point in these Unicode ranges alternates upper/lower case.
                return (c & 1) == 0 ? range.uppercaseBase : range.lowercaseBase;
            }
        }
        return c;
    }

    static AK_FORCE_INLINE int toBaseCodePoint(int c) {
        const int vietnameseBase = toVietnameseBaseCodePoint(c);
        if (vietnameseBase != c) return vietnameseBase;
        if (c < BASE_CHARS_SIZE) {
            return static_cast<int>(BASE_CHARS[c]);
        }
        return c;
    }

    static AK_FORCE_INLINE int getSpaceCount(const int *const codePointBuffer, const int length) {
        int spaceCount = 0;
        for (int i = 0; i < length; ++i) {
            if (codePointBuffer[i] == KEYCODE_SPACE) {
                ++spaceCount;
            }
        }
        return spaceCount;
    }

    static AK_FORCE_INLINE int isInUnicodeSpace(const int codePoint) {
        return codePoint >= MIN_UNICODE_CODE_POINT && codePoint <= MAX_UNICODE_CODE_POINT;
    }

    // Returns updated code point count. Returns 0 when the code points cannot be marked as a
    // Beginning-of-Sentence.
    static AK_FORCE_INLINE int attachBeginningOfSentenceMarker(int *const codePoints,
            const int codePointCount, const int maxCodePoint) {
        if (codePointCount > 0 && codePoints[0] == CODE_POINT_BEGINNING_OF_SENTENCE) {
            // Marker has already been attached.
            return codePointCount;
        }
        if (codePointCount >= maxCodePoint) {
            // the code points cannot be marked as a Beginning-of-Sentence.
            return 0;
        }
        memmove(codePoints + 1, codePoints, sizeof(int) * codePointCount);
        codePoints[0] = CODE_POINT_BEGINNING_OF_SENTENCE;
        return codePointCount + 1;
    }

    // Returns updated code point count.
    static AK_FORCE_INLINE int removeBeginningOfSentenceMarker(int *const codePoints,
            const int codePointCount) {
        if (codePointCount <= 0 || codePoints[0] != CODE_POINT_BEGINNING_OF_SENTENCE) {
            return codePointCount;
        }
        const int newCodePointCount = codePointCount - 1;
        memmove(codePoints, codePoints + 1, sizeof(int) * newCodePointCount);
        return newCodePointCount;
    }

 private:
    DISALLOW_IMPLICIT_CONSTRUCTORS(CharUtils);

    static const int MIN_UNICODE_CODE_POINT;
    static const int MAX_UNICODE_CODE_POINT;

    /**
     * Table mapping most combined Latin, Greek, and Cyrillic characters
     * to their base characters.  If c is in range, BASE_CHARS[c] == c
     * if c is not a combined character, or the base character if it
     * is combined.
     */
    static const int BASE_CHARS_SIZE = 0x0500;
    static const unsigned short BASE_CHARS[BASE_CHARS_SIZE];

    static AK_FORCE_INLINE bool isAscii(int c) {
        return isascii(c) != 0;
    }

    static AK_FORCE_INLINE int toAsciiLower(int c) {
        return c - 'A' + 'a';
    }

    static int latin_tolower(const int c);
};
} // namespace latinime
#endif // LATINIME_CHAR_UTILS_H
