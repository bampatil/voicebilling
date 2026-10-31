package com.voicebilling.app.kws;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds known words in the model's rough guesses ("multiple choice" instead of writing down every word).
 *
 * The model gives, every 40 ms, a score for each of its 5,633 word-pieces (Kannada uses its own block of 256).
 * For a keyword such as ಟೀ we list the ways it can be spelled with those pieces, then find the place in the
 * window where that spelling fits best (CTC Viterbi alignment, free start and end), and compare it with the
 * model's own favourite pieces at the same moments. Score 0 = fits as well as the model's best guess;
 * more negative = worse. The score is divided by the number of letters so long and short words compare fairly.
 */
public final class Spotter {
    public final int blank;
    private final String[] tokens;
    private final Map<String, Integer> voc = new HashMap<>();   // Kannada pieces only

    public Spotter(List<String> tokenLines) {
        tokens = new String[tokenLines.size()];
        for (int i = 0; i < tokens.length; i++) {
            String l = tokenLines.get(i);
            int sp = l.lastIndexOf(' ');
            tokens[i] = sp > 0 ? l.substring(0, sp) : l;
        }
        blank = tokens.length - 1;                 // "<blk>" is the last piece
        int lo = Integer.MAX_VALUE, hi = -1;
        for (int i = 0; i < tokens.length; i++) if (hasKannada(tokens[i])) { lo = Math.min(lo, i); hi = Math.max(hi, i); }
        for (int i = Math.max(0, lo - 1); i <= hi; i++) voc.putIfAbsent(tokens[i], i);   // block includes "▁" and "<unk>"
    }

    static boolean hasKannada(String s) {
        for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); if (c >= 'ಀ' && c <= '೿') return true; }
        return false;
    }

    public String token(int id) { return tokens[id]; }

    /** All ways to spell the phrase with Kannada pieces (fewest pieces first), plus endings on the last piece (ಟಿ → ಟಿಯ). */
    public int[][] spellings(String phrase, int cap) {
        List<List<Integer>> res = new ArrayList<>();
        res.add(new ArrayList<>());
        for (String w : phrase.trim().split("\\s+")) {
            String s = "▁" + w;
            List<List<Integer>> segs = segment(s, 0, new HashMap<>());
            if (segs.isEmpty()) return new int[0][];
            List<List<Integer>> next = new ArrayList<>();
            for (List<Integer> a : res) for (List<Integer> b : segs) {
                if (next.size() >= 400) break;
                List<Integer> c = new ArrayList<>(a); c.addAll(b); next.add(c);
            }
            res = next;
        }
        res.sort((a, b) -> a.size() - b.size());
        if (res.size() > cap) res = new ArrayList<>(res.subList(0, cap));
        List<List<Integer>> more = new ArrayList<>();
        for (List<Integer> r : res) {
            String last = tokens[r.get(r.size() - 1)];
            for (Map.Entry<String, Integer> e : voc.entrySet()) {
                if (!e.getKey().equals(last) && e.getKey().startsWith(last)) {
                    List<Integer> c = new ArrayList<>(r); c.set(c.size() - 1, e.getValue()); more.add(c);
                }
            }
        }
        if (more.size() > cap) more = more.subList(0, cap);
        res.addAll(more);
        int[][] out = new int[res.size()][];
        for (int i = 0; i < out.length; i++) out[i] = res.get(i).stream().mapToInt(Integer::intValue).toArray();
        return out;
    }

    private List<List<Integer>> segment(String s, int k, Map<Integer, List<List<Integer>>> memo) {
        if (k == s.length()) { List<List<Integer>> one = new ArrayList<>(); one.add(new ArrayList<>()); return one; }
        List<List<Integer>> cached = memo.get(k);
        if (cached != null) return cached;
        List<List<Integer>> out = new ArrayList<>();
        for (int n = s.length() - k; n > 0; n--) {
            Integer id = voc.get(s.substring(k, k + n));
            if (id != null) for (List<Integer> r : segment(s, k + n, memo)) {
                List<Integer> c = new ArrayList<>(); c.add(id); c.addAll(r); out.add(c);
            }
            if (out.size() > 200) break;
        }
        memo.put(k, out);
        return out;
    }

    /** Model output for one window, made relative: 0 = the model's favourite piece in that 40 ms step. */
    public static float[][] relative(float[][] lp) {
        float[][] r = new float[lp.length][];
        for (int t = 0; t < lp.length; t++) {
            float m = Float.NEGATIVE_INFINITY;
            for (float v : lp[t]) if (v > m) m = v;
            r[t] = new float[lp[t].length];
            for (int i = 0; i < lp[t].length; i++) r[t][i] = lp[t][i] - m;
        }
        return r;
    }

    /** Best fit of one spelling: {score (not yet per letter), startStep, endStep}. */
    public double[] fit(float[][] rel, int[] kw) {
        final double NEG = -1e9;
        int L = kw.length, S = 2 * L + 1;
        int[] lab = new int[S];
        boolean[] skip = new boolean[S];
        for (int s = 0; s < S; s++) lab[s] = s % 2 == 0 ? blank : kw[s / 2];
        for (int s = 2; s < S; s++) skip[s] = lab[s] != blank && lab[s] != lab[s - 2];
        double[] D = new double[S], nD = new double[S];
        int[] st = new int[S], nst = new int[S];
        Arrays.fill(D, NEG);
        double best = NEG; int bs = 0, be = 0;
        for (int t = 0; t < rel.length; t++) {
            for (int s = 0; s < S; s++) {
                double v = D[s]; int from = st[s];                                  // stay
                if (s >= 1 && D[s - 1] > v) { v = D[s - 1]; from = st[s - 1]; }      // next piece
                if (s >= 2 && skip[s] && D[s - 2] > v) { v = D[s - 2]; from = st[s - 2]; }   // skip a blank
                if (s == 1 && 0.0 > v) { v = 0.0; from = t; }                       // the keyword may start here
                float e = rel[t][lab[s]];
                nD[s] = Float.isInfinite(e) ? NEG : v + e;
                nst[s] = from;
            }
            double[] tmp = D; D = nD; nD = tmp;
            int[] ti = st; st = nst; nst = ti;
            if (D[S - 2] > best) { best = D[S - 2]; bs = st[S - 2]; be = t; }
        }
        return new double[]{best, bs, be};
    }
}
