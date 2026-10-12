package io.github.apscot.laya.api;

import java.util.Set;

/**
 * Small heuristic language router (a simplification of upstream's lang.py): clearly English text goes to the
 * English checkpoint, everything else (non-Latin scripts, diacritics without English cues, unidentified Latin
 * text) goes to the multilingual one, as in upstream v0.4.1.
 */
public final class LanguageRouter {
    private static final Set<String> ENGLISH = Set.of("the", "and", "is", "are", "was", "were", "to", "of", "in", "for", "on", "with",
            "that", "this", "it", "my", "i", "you", "we", "be", "have", "has", "not", "please", "can", "will", "do", "does", "me",
            "your", "our", "from", "at", "by", "an", "a", "but", "or", "if", "so", "as", "how", "what", "why", "when", "who",
            "billed", "refund", "help", "thanks", "thank", "hello", "hi", "email", "order", "account", "payment", "issue");

    private LanguageRouter() {}

    /** @return true when the text looks English. */
    public static boolean isEnglish(String text) {
        if (text == null || text.isBlank()) return true;
        int letters = 0, nonLatin = 0, diacritics = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) continue;
            letters++;
            Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
            if (sc != Character.UnicodeScript.LATIN) nonLatin++;
            else if (cp > 127) diacritics++;
        }
        if (letters == 0) return true;
        if (nonLatin > 0 && nonLatin * 10 >= letters) return false;
        int words = 0, hits = 0;
        for (String w : text.toLowerCase().split("[^\\p{L}']+")) {
            if (w.isEmpty()) continue;
            words++;
            if (ENGLISH.contains(w)) hits++;
        }
        if (hits == 0) return false;
        if (diacritics > 0 && hits * 4 < words) return false;
        return true;
    }
}
