package com.voicebilling.app.kws;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Keyword listening, after the model: finds numbers, units and items in each 3-second window, merges what
 * several overlapping windows found into single "heard" events, and pairs a number with an item.
 *
 * Pairing rules (from Bhuvan's idea, 26 Oct):
 *  - a number waits up to 3 s for an item after it ("ಎರಡು … (chatter) … ಬಿಸ್ಕೆಟ್");
 *  - an item waits up to 2 s for a number after it ("ಟೀ ಒಂದು");
 *  - a unit between them gives the number 2 more seconds, never more than 5 s from the number;
 *  - tens then ones within 0.6 s add up ("ಇಪ್ಪತ್ತು … ಐದು" = 25);
 *  - a number alone or an item alone never adds anything; an item left alone is offered as "tap to add".
 * Times are in seconds of audio since listening started.
 */
public final class KeywordEngine {
    public static final double FRAME = 0.04;          // one model step = 40 ms

    public double threshold = -0.3;                   // per letter; 0 = as good as the model's best guess
    public double numBefore = 3.0, numAfter = 2.0, unitExtra = 2.0, maxSpan = 5.0, tensJoin = 0.6;

    private final Spotter spotter;
    private final List<Keywords.Word> words = new ArrayList<>();

    public static final class Hit {
        public Keywords.Word word; public double score, start, end, heardAt; public boolean used;
        public double expires;         // numbers only
        public String rival;           // another reading of the same moment that scored almost as well ("not sure")
        public Hit unit;               // numbers only: unit said after the number
        public String text() { return word.text + (unit != null ? " " + unit.word.text : ""); }
    }

    public static final class Result {
        public final String kind;      // "order" or "lone"
        public final Hit num, item;
        public final double at;        // when it was decided (audio time)
        Result(String kind, Hit num, Hit item, double at) { this.kind = kind; this.num = num; this.item = item; this.at = at; }
        /** Another reading was almost as likely: the number or the item may be wrong. */
        public String doubt() {
            if (num != null && num.rival != null) return num.rival;
            return item.rival;
        }
        public double qty() { return num == null ? 0 : num.word.num; }
        public String unit() { return num != null && num.unit != null ? num.unit.word.key : null; }
        public double start() { return num == null ? item.start : Math.min(num.start, item.start); }
        public double end() { return num == null ? item.end : Math.max(num.end, Math.max(item.end, num.unit != null ? num.unit.end : 0)); }
        public String words() {
            if (num == null) return item.word.text;
            return num.start <= item.start ? num.text() + " " + item.word.text : item.word.text + " " + num.text();
        }
    }

    private final List<Hit> events = new ArrayList<>();

    public KeywordEngine(Spotter spotter) { this.spotter = spotter; }

    public void setWords(List<Keywords.Word> list) {
        words.clear();
        for (Keywords.Word w : list) {
            w.spellings = spotter.spellings(w.text, 20);
            if (w.spellings.length > 0) words.add(w);
        }
        events.clear();
    }

    public int wordCount() { return words.size(); }

    /** Keywords found in one window, best first, at most one per kind for any stretch of time. */
    public List<Hit> detect(float[][] lp, double windowStart) {
        float[][] rel = Spotter.relative(lp);
        List<Hit> hits = new ArrayList<>();
        for (Keywords.Word w : words) {
            double best = -1e9; int s = 0, e = 0;
            for (int[] sp : w.spellings) {
                double[] r = spotter.fit(rel, sp);
                if (r[0] > best) { best = r[0]; s = (int) r[1]; e = (int) r[2]; }
            }
            double score = best / w.text.replace(" ", "").length();
            if (score > threshold) {
                Hit h = new Hit(); h.word = w; h.score = score;
                h.start = windowStart + s * FRAME; h.end = windowStart + (e + 1) * FRAME;
                hits.add(h);
            }
        }
        // best score first; on a tie the longer word wins (ಇಪ್ಪತ್ತೈದು over ಐದು)
        hits.sort((a, b) -> a.score != b.score ? Double.compare(b.score, a.score) : b.word.text.length() - a.word.text.length());
        List<Hit> keep = new ArrayList<>();
        for (Hit h : hits) {
            boolean clash = false;
            for (Hit k : keep) if (k.word.kind == h.word.kind && !(h.end <= k.start || h.start >= k.end)) {
                clash = true;
                if (k.word.kind != Keywords.UNIT && !sameValue(k, h) && k.score - h.score < 0.15 && k.rival == null) k.rival = h.word.text;
                break;
            }
            if (!clash) keep.add(h);
        }
        keep.sort((a, b) -> Double.compare(a.start, b.start));
        return keep;
    }

    /**
     * Feed one window of model output. windowEnd = audio time of the window's last sample.
     * Returns orders and lone items decided now.
     */
    public List<Result> process(float[][] lp, double windowStart, double windowEnd) {
        List<Result> out = new ArrayList<>();
        for (Hit h : detect(lp, windowStart)) {
            if (h.end > windowEnd - 0.15) continue;          // touching the window's end: may be cut off, wait for the next window
            Hit same = null;
            for (Hit x : events) {
                if (x.word.kind != h.word.kind) continue;
                boolean near = !(h.end < x.start - 0.2 || h.start > x.end + 0.2);
                double overlap = Math.min(h.end, x.end) - Math.max(h.start, x.start);
                // same word seen again by a later window, or another reading of the same moment (ಒಂದ / ಒಂದೂವರೆ)
                if ((near && sameValue(x, h)) || overlap > 0.5 * Math.min(h.end - h.start, x.end - x.start)) { same = x; break; }
            }
            if (same != null) {
                if (sameValue(same, h)) { if (h.score > same.score) same.score = h.score; }
                else {
                    if (!same.used && h.score > same.score + 0.05) { String was = same.word.text; same.word = h.word; same.score = h.score; same.rival = was; }   // a clearer reading of the same moment
                    else if (same.score - h.score < 0.15 && same.rival == null) same.rival = h.word.text;
                }
                continue;
            }
            h.heardAt = windowEnd;
            events.add(h);
            arrive(h, windowEnd, out);
        }
        expire(windowEnd, out);
        return out;
    }

    private static boolean sameValue(Hit a, Hit b) {
        if (a.word.kind == Keywords.NUM) return a.word.num == b.word.num;
        return a.word.key.equals(b.word.key);
    }

    private void arrive(Hit h, double now, List<Result> out) {
        if (h.word.kind == Keywords.NUM) {
            // "ಇಪ್ಪತ್ತು … ಐದು": add ones to a waiting tens
            for (Hit n : events) {
                if (n != h && !n.used && n.word.kind == Keywords.NUM && n.unit == null && n.word.num >= 20 && n.word.num < 100
                        && n.word.num % 10 == 0 && h.word.num >= 1 && h.word.num <= 9 && h.word.num % 1 == 0
                        && h.start - n.end >= -0.1 && h.start - n.end <= tensJoin) {
                    Keywords.Word joined = new Keywords.Word(Keywords.NUM, n.word.text + " " + h.word.text, n.word.num + h.word.num, null);
                    n.word = joined; n.end = h.end; h.used = true;
                    n.expires = Math.min(n.end + numBefore, n.start + maxSpan);
                    return;
                }
            }
            h.expires = h.end + numBefore;
            // A waiting item: said after the number but noticed first ("ಒಂದು ಟೀ", usual order),
            // or said before the number ("ಟೀ ಒಂದು").
            Hit item = null;
            for (Hit i : events) {
                if (i.used || i.word.kind != Keywords.ITEM) continue;
                double after = i.start - h.end, before = h.start - i.end;
                boolean ok = (after >= -0.2 && after <= numBefore) || (before >= -0.2 && before <= numAfter);
                if (ok && (item == null || Math.abs(i.start - h.end) < Math.abs(item.start - h.end))) item = i;
            }
            if (item != null) { h.used = true; item.used = true; out.add(new Result("order", h, item, now)); }
        } else if (h.word.kind == Keywords.UNIT) {
            Hit n = null;
            for (Hit x : events) {
                if (x.word.kind == Keywords.NUM && x.unit == null && h.start - x.end >= -0.2 && h.start - x.end <= 1.0 && (n == null || x.end > n.end)) n = x;
            }
            if (n == null) return;
            n.unit = h;
            n.expires = Math.min(h.end + unitExtra, n.start + maxSpan);
            if (n.used) {     // the order was already made ("ಸಕ್ಕರೆ ಎರಡು ಕೆಜಿ"): send it again with the unit
                for (Hit i : events) {
                    if (i.word.kind == Keywords.ITEM && i.used && Math.abs(i.end - n.start) <= numAfter + 0.5) { out.add(new Result("order", n, i, now)); break; }
                }
            }
        } else {
            Hit n = null;
            for (Hit x : events) {
                if (!x.used && x.word.kind == Keywords.NUM && x.end <= h.start + 0.2 && h.start <= x.expires && (n == null || x.end > n.end)) n = x;
            }
            if (n != null) { n.used = true; h.used = true; out.add(new Result("order", n, h, now)); }
        }
    }

    private void expire(double now, List<Result> out) {
        Iterator<Hit> it = events.iterator();
        while (it.hasNext()) {
            Hit h = it.next();
            if (h.word.kind == Keywords.ITEM && !h.used && now > h.end + numAfter + 0.5) {
                h.used = true;
                out.add(new Result("lone", null, h, now));
            }
            if (now > h.end + 8) it.remove();          // forget after 8 s
        }
    }
}
