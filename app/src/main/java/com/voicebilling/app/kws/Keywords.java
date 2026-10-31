package com.voicebilling.app.kws;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The words keyword listening looks for: numbers (Kannada and English), units, and the shop's items. */
public final class Keywords {
    public static final int NUM = 0, UNIT = 1, ITEM = 2;

    public static final class Word {
        public final int kind;
        public final String text;     // Kannada spelling searched for
        public final double num;      // number value (NUM)
        public final String key;      // unit code (UNIT) or item id (ITEM)
        public int[][] spellings;
        Word(int kind, String text, double num, String key) { this.kind = kind; this.text = text; this.num = num; this.key = key; }
        public String toString() { return text; }
    }

    private static final String[] ONES = {"", "ಒಂದು", "ಎರಡು", "ಮೂರು", "ನಾಲ್ಕು", "ಐದು", "ಆರು", "ಏಳು", "ಎಂಟು", "ಒಂಬತ್ತು"};
    private static final String[] TEENS = {"ಹತ್ತು", "ಹನ್ನೊಂದು", "ಹನ್ನೆರಡು", "ಹದಿಮೂರು", "ಹದಿನಾಲ್ಕು", "ಹದಿನೈದು", "ಹದಿನಾರು", "ಹದಿನೇಳು", "ಹದಿನೆಂಟು", "ಹತ್ತೊಂಬತ್ತು"};
    private static final String[] TENS = {"", "", "ಇಪ್ಪತ್ತು", "ಮೂವತ್ತು", "ನಲವತ್ತು", "ಐವತ್ತು", "ಅರವತ್ತು", "ಎಪ್ಪತ್ತು", "ಎಂಬತ್ತು", "ತೊಂಬತ್ತು"};

    /** Joins tens and ones the way they are spoken as one word: ಇಪ್ಪತ್ತು + ಐದು = ಇಪ್ಪತ್ತೈದು, ಇಪ್ಪತ್ತು + ಮೂರು = ಇಪ್ಪತ್ಮೂರು / ಇಪ್ಪತ್ತಮೂರು. */
    static List<String> compound(String tens, String one) {
        List<String> out = new ArrayList<>();
        String stem = tens.substring(0, tens.length() - 1);              // ಇಪ್ಪತ್ತ
        String sign = vowelSign(one.charAt(0));
        if (sign != null) out.add(stem + sign + one.substring(1));
        else {
            out.add(stem + one);                                           // ಇಪ್ಪತ್ತಮೂರು
            out.add(tens.substring(0, tens.length() - 3) + "್" + one);     // ಇಪ್ಪತ್ಮೂರು
        }
        return out;
    }

    private static String vowelSign(char v) {
        switch (v) {
            case 'ಒ': return "ೊ"; case 'ಎ': return "ೆ"; case 'ಐ': return "ೈ";
            case 'ಆ': return "ಾ"; case 'ಏ': return "ೇ"; case 'ಓ': return "ೋ";
            default: return null;
        }
    }

    public static List<Word> numbers() {
        List<Word> w = new ArrayList<>();
        for (int i = 1; i <= 9; i++) w.add(new Word(NUM, ONES[i], i, null));
        for (int i = 0; i <= 9; i++) w.add(new Word(NUM, TEENS[i], 10 + i, null));
        for (int t = 2; t <= 9; t++) {
            w.add(new Word(NUM, TENS[t], t * 10, null));
            for (int o = 1; o <= 9; o++) for (String s : compound(TENS[t], ONES[o])) w.add(new Word(NUM, s, t * 10 + o, null));
        }
        String[][] extra = {
            {"ಒಂದ", "1"}, {"ಎರಡ", "2"}, {"ಮೂರ", "3"},                               // final u dropped in quick speech
            {"ನೂರು", "100"}, {"ಇನ್ನೂರು", "200"}, {"ಮುನ್ನೂರು", "300"}, {"ಐನೂರು", "500"},
            {"ಅರ್ಧ", "0.5"}, {"ಕಾಲು", "0.25"}, {"ಮುಕ್ಕಾಲು", "0.75"}, {"ಒಂದೂವರೆ", "1.5"}, {"ಒಂದುವರೆ", "1.5"}, {"ಎರಡೂವರೆ", "2.5"},
            {"ಒನ್", "1"}, {"ವನ್", "1"}, {"ಟು", "2"}, {"ತ್ರೀ", "3"}, {"ಫೋರ್", "4"}, {"ಫೈವ್", "5"},
            {"ಸಿಕ್ಸ್", "6"}, {"ಸೆವೆನ್", "7"}, {"ಎಯ್ಟ್", "8"}, {"ನೈನ್", "9"}, {"ಟೆನ್", "10"}
        };
        for (String[] e : extra) w.add(new Word(NUM, e[0], Double.parseDouble(e[1]), null));
        return w;
    }

    public static List<Word> units() {
        Map<String, String> u = new LinkedHashMap<>();
        u.put("ಕೆಜಿ", "kg"); u.put("ಕೆ ಜಿ", "kg"); u.put("ಕೇಜಿ", "kg"); u.put("ಕಿಲೋ", "kg"); u.put("ಕಿಲೊ", "kg");
        u.put("ಗ್ರಾಂ", "g"); u.put("ಗ್ರಾಮ್", "g");
        u.put("ಲೀಟರ್", "l"); u.put("ಲೀಟರು", "l");
        u.put("ಪ್ಯಾಕೆಟ್", "packet"); u.put("ಪ್ಲೇಟ್", "plate"); u.put("ಡಜನ್", "dozen");
        List<Word> w = new ArrayList<>();
        for (Map.Entry<String, String> e : u.entrySet()) w.add(new Word(UNIT, e.getKey(), 0, e.getValue()));
        return w;
    }

    public static Word item(String id, String kannada) { return new Word(ITEM, kannada, 0, id); }
}
