// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.emoji;

import android.content.Context;
import android.util.Xml;

import androidx.annotation.NonNull;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import helium314.keyboard.latin.common.StringUtilsKt;
import helium314.keyboard.latin.utils.Log;

/**
 * Small, offline emoji keyword index backed only by Unicode CLDR annotations.
 *
 * The Vietnamese upstream binary emoji dictionary mixes CLDR with Signal data. This fallback
 * deliberately keeps the personal build's Vietnamese search independent from that AGPL data.
 * Parsing is lazy and asynchronous, so it cannot delay the first keyboard frame.
 */
public final class CldrEmojiSearchIndex {
    private static final String TAG = "CldrEmojiSearch";
    private static final String VI_ASSET_PATH = "emoji/vi.xml";
    private static final int MAX_RESULTS = 80;
    private static final Map<String, SearchIndex> INDEXES = new ConcurrentHashMap<>();
    private static final Set<String> LOADING = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "cldr-emoji-index");
        thread.setDaemon(true);
        return thread;
    });

    private CldrEmojiSearchIndex() {}

    /**
     * Whether this APK deliberately bundles CLDR annotations for {@code locale}.
     *
     * This is a build-time capability rather than an index-readiness check: callers may expose
     * emoji search immediately, while {@link #warm(Context, Locale)} parses the relatively large
     * XML file off the IME thread.
     */
    public static boolean hasBundledAnnotations(@NonNull final Locale locale) {
        return "vi".equals(locale.getLanguage());
    }

    /** Starts a one-time background load if this locale has a bundled CLDR annotation asset. */
    public static void warm(@NonNull final Context context, @NonNull final Locale locale) {
        final String language = locale.getLanguage();
        if (!hasBundledAnnotations(locale) || INDEXES.containsKey(language) || !LOADING.add(language)) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        LOADER.execute(() -> {
            try {
                INDEXES.put(language, loadVietnamese(appContext));
            } catch (final Exception exception) {
                Log.e(TAG, "Failed to load CLDR emoji annotations", exception);
            } finally {
                LOADING.remove(language);
            }
        });
    }

    /** Returns a stable, de-duplicated result list. Empty means the index has not loaded yet. */
    @NonNull
    public static List<String> search(@NonNull final Locale locale, final String query) {
        final SearchIndex index = INDEXES.get(locale.getLanguage());
        if (index == null) return Collections.emptyList();
        return index.search(query);
    }

    private static SearchIndex loadVietnamese(final Context context) throws Exception {
        final Map<String, StringBuilder> annotationsByEmoji = new LinkedHashMap<>();
        try (InputStream stream = context.getAssets().open(VI_ASSET_PATH)) {
            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, "UTF-8");
            String currentEmoji = null;
            StringBuilder currentText = null;
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                if (event == XmlPullParser.START_TAG && "annotation".equals(parser.getName())) {
                    final String candidate = parser.getAttributeValue(null, "cp");
                    if (candidate != null && StringUtilsKt.isEmoji(candidate)) {
                        currentEmoji = candidate;
                        currentText = new StringBuilder();
                    } else {
                        currentEmoji = null;
                        currentText = null;
                    }
                } else if (event == XmlPullParser.TEXT && currentText != null) {
                    currentText.append(parser.getText());
                } else if (event == XmlPullParser.END_TAG && "annotation".equals(parser.getName())) {
                    if (currentEmoji != null && currentText != null && currentText.length() > 0) {
                        annotationsByEmoji.computeIfAbsent(currentEmoji, ignored -> new StringBuilder())
                                .append(' ')
                                .append(currentText);
                    }
                    currentEmoji = null;
                    currentText = null;
                }
            }
        }
        final ArrayList<Entry> entries = new ArrayList<>(annotationsByEmoji.size());
        for (final Map.Entry<String, StringBuilder> annotation : annotationsByEmoji.entrySet()) {
            entries.add(new Entry(annotation.getKey(), normalize(annotation.getValue().toString())));
        }
        return new SearchIndex(entries);
    }

    private static String normalize(final String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
    }

    private static final class Entry {
        final String emoji;
        final String searchableKeywords;

        Entry(final String emoji, final String searchableKeywords) {
            this.emoji = emoji;
            this.searchableKeywords = searchableKeywords;
        }
    }

    private static final class SearchIndex {
        final List<Entry> entries;

        SearchIndex(final List<Entry> entries) {
            this.entries = entries;
        }

        List<String> search(final String rawQuery) {
            final String normalized = normalize(rawQuery == null ? "" : rawQuery).trim();
            if (normalized.isEmpty()) return Collections.emptyList();
            final String[] tokens = normalized.split("\\s+");
            final List<String> matches = new ArrayList<>();
            final Set<String> seen = new LinkedHashSet<>();
            for (final Entry entry : entries) {
                boolean matchesEveryToken = true;
                for (final String token : tokens) {
                    if (!entry.searchableKeywords.contains(token)) {
                        matchesEveryToken = false;
                        break;
                    }
                }
                if (matchesEveryToken && seen.add(entry.emoji)) {
                    matches.add(entry.emoji);
                    if (matches.size() == MAX_RESULTS) break;
                }
            }
            return matches;
        }
    }
}
