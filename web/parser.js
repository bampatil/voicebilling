/* Voice Bill parser: spoken text (Kannada script, romanized Kannada, English, Hindi) → bill lines.
   Pure functions, no browser APIs, so it can be tested on its own and reused in other apps. */
const VB = (() => {
  "use strict";

  /* ---- 1. Kannada script → rough Latin ---- */
  const KV = { "ಅ":"a","ಆ":"aa","ಇ":"i","ಈ":"ii","ಉ":"u","ಊ":"uu","ಋ":"ru","ಎ":"e","ಏ":"e","ಐ":"ai","ಒ":"o","ಓ":"o","ಔ":"au" };
  const KC = { "ಕ":"k","ಖ":"kh","ಗ":"g","ಘ":"gh","ಙ":"n","ಚ":"ch","ಛ":"chh","ಜ":"j","ಝ":"jh","ಞ":"n","ಟ":"t","ಠ":"th","ಡ":"d","ಢ":"dh","ಣ":"n",
    "ತ":"t","ಥ":"th","ದ":"d","ಧ":"dh","ನ":"n","ಪ":"p","ಫ":"ph","ಬ":"b","ಭ":"bh","ಮ":"m","ಯ":"y","ರ":"r","ಱ":"r","ಲ":"l","ವ":"v","ಶ":"sh","ಷ":"sh","ಸ":"s","ಹ":"h","ಳ":"l","ೞ":"l" };
  const KS = { "ಾ":"aa","ಿ":"i","ೀ":"ii","ು":"u","ೂ":"uu","ೃ":"ru","ೆ":"e","ೇ":"e","ೈ":"ai","ೊ":"o","ೋ":"o","ೌ":"au" };
  const KD = "೦೧೨೩೪೫೬೭೮೯";
  // Devanagari (Hindi recognizer output) uses the same pattern.
  const DV = { "अ":"a","आ":"aa","इ":"i","ई":"ii","उ":"u","ऊ":"uu","ऋ":"ru","ए":"e","ऐ":"ai","ओ":"o","औ":"au" };
  const DC = { "क":"k","ख":"kh","ग":"g","घ":"gh","च":"ch","छ":"chh","ज":"j","झ":"jh","ट":"t","ठ":"th","ड":"d","ढ":"dh","ण":"n",
    "त":"t","थ":"th","द":"d","ध":"dh","न":"n","प":"p","फ":"ph","ब":"b","भ":"bh","म":"m","य":"y","र":"r","ल":"l","व":"v","श":"sh","ष":"sh","स":"s","ह":"h" };
  const DS = { "ा":"aa","ि":"i","ी":"ii","ु":"u","ू":"uu","ृ":"ru","े":"e","ै":"ai","ो":"o","ौ":"au","ॉ":"o" };
  const DD = "०१२३४५६७८९";
  Object.assign(KV, DV); Object.assign(KC, DC); Object.assign(KS, DS);
  function translitKannada(s) {
    let out = "";
    const ch = [...s];
    for (let i = 0; i < ch.length; i++) {
      const c = ch[i], nx = ch[i + 1];
      if (KC[c]) {
        out += KC[c];
        if (nx === "಼" || nx === "़") { i++; }
        const n2 = ch[i + 1];
        if (n2 === "್" || n2 === "्") { i++; }
        else if (KS[n2]) { out += KS[n2]; i++; }
        else if (DC[c] && !(n2 && /[\u0900-\u097F]/.test(n2))) { /* Hindi drops the final "a": चावल → chaaval */ }
        else out += "a";
      } else if (KV[c]) out += KV[c];
      else if (KS[c]) out += KS[c];
      else if (c === "ಂ" || c === "ं" || c === "ँ") out += (!nx || /[\sಪಬಮपबम]/.test(nx)) ? "m" : "n";
      else if (c === "ಃ") out += "h";
      else if (KD.includes(c)) out += KD.indexOf(c);
      else if (DD.includes(c)) out += DD.indexOf(c);
      else if ("಼़್्।".includes(c)) { /* skip */ }
      else out += c;
    }
    return out;
  }

  /* ---- 2. Phonetic key: spelling differences shrink so "paarle", "parle" and ಪಾರ್ಲೆ all meet ---- */
  function phon(w) {
    let s = translitKannada(String(w).toLowerCase()).normalize("NFKD").replace(/[̀-ͯ]/g, "");
    s = s.replace(/[^a-z0-9]/g, "");
    s = s.replace(/ee/g, "i").replace(/oo/g, "u");
    s = s.replace(/chh/g, "ch").replace(/ph/g, "f").replace(/([kgbdtj])h/g, "$1")
         .replace(/sh/g, "s").replace(/w/g, "v").replace(/z/g, "j").replace(/q/g, "k").replace(/x/g, "ks")
         .replace(/ck/g, "k").replace(/c(?!h)/g, "k").replace(/y/g, "i");
    s = s.replace(/(.)\1+/g, "$1");
    return s;
  }

  /* ---- 3. Numbers, fractions, units (all compared by phonetic key) ---- */
  const NUM_WORDS = {
    // Kannada
    ondu:1, ond:1, ondhu:1, eradu:2, yeradu:2, erdu:2, mooru:3, muru:3, naalku:4, nalku:4, aidu:5, aydu:5, aaru:6, elu:7, yelu:7,
    entu:8, yentu:8, ombattu:9, hattu:10, hannondu:11, hanneradu:12, hadinaidu:15, ippattu:20, muvattu:30, moovattu:30, nalavattu:40, aivattu:50, aravattu:60, eppattu:70, embattu:80, tombattu:90, mattu_:0,
    nooru:100, innooru:200, munnooru:300, naanooru:400, ainooru:500, saavira:1000,
    // English
    one:1, two:2, three:3, four:4, five:5, six:6, seven:7, eight:8, nine:9, ten:10, eleven:11, twelve:12, fifteen:15,
    twenty:20, twentyfive:25, thirty:30, forty:40, fifty:50, hundred:100, thousand:1000,
    // Hindi
    ek:1, do:2, teen:3, char:4, chaar:4, paanch:5, panch:5, chhe:6, che:6, saat:7, aath:8, nau:9, das:10, sau:100
  };
  delete NUM_WORDS.mattu_;
  const FRAC_WORDS = { half:0.5, ardha:0.5, aadha:0.5, adha:0.5, quarter:0.25, kaal:0.25, kaalu:0.25, paav:0.25, pav:0.25,
    mukkaal:0.75, mukkalu:0.75, vare:0.5, ovare:0.5 };
  const FIXED_QTY = { dedh:1.5, dhai:2.5, ondoovare:1.5, ondovare:1.5, eradoovare:2.5 };
  const UNIT_WORDS = {
    kg:"kg", kgs:"kg", kilo:"kg", kilos:"kg", kilogram:"kg", kilograms:"kg", keji:"kg", kejii:"kg",
    g:"g", gm:"g", gms:"g", gram:"g", grams:"g", graam:"g", gramu:"g",
    l:"l", ltr:"l", litre:"l", liter:"l", litres:"l", liters:"l", leetar:"l", litar:"l", liitar:"l", leetaru:"l",
    ml:"ml", milli:"ml", millilitre:"ml", milliliter:"ml",
    packet:"packet", packets:"packet", pkt:"packet", pack:"packet", paket:"packet", paaket:"packet", pakit:"packet", paakettu:"packet", pocket:"packet",
    piece:"piece", pieces:"piece", pc:"piece", pcs:"piece", peace:"piece",
    dozen:"dozen", dajan:"dozen", darjan:"dozen", dazan:"dozen"
  };
  const FILLER = ["of","the","a","an","and","please","pls","ok","okay","mattu","matte","aur","bhi","kodi","kodu","kottu","beku","beko","haaki","haku",
    "ge","ke","ka","ki","per","rupayi","rupaayi","rupay","sir","madam","anna","akka","guru","saar","nanage","nange","mujhe","chahiye","dena","do_", "aaytu","ayitu","illi","alli","inna","innu"];

  const keyMap = obj => { const m = new Map(); for (const k in obj) m.set(phon(k), obj[k]); return m; };
  const NUMK = keyMap(NUM_WORDS), FRACK = keyMap(FRAC_WORDS), FIXK = keyMap(FIXED_QTY), UNITK = keyMap(UNIT_WORDS);
  const FILLK = new Set(FILLER.map(phon));
  const KWK = new Set(["price", "prices", "rate", "rates", "daam", "kimmat", "kimat"].map(phon));

  const UNIT_KIND = { kg:["w",1000], g:["w",1], l:["v",1000], ml:["v",1], packet:["c",1], piece:["c",1], dozen:["c",12] };

  /* ---- 4. Tokenise ---- */
  function tokenize(text) {
    let s = translitKannada(String(text)).toLowerCase();
    s = s.replace(/(\d)\s*\/\s*(\d)/g, "$1/$2");                 // 1/2
    s = s.replace(/(\d)([a-z])/g, "$1 $2").replace(/([a-z])(\d)/g, "$1 $2");   // 2kg → 2 kg
    s = s.replace(/₹|rs\.?|rupees?/g, " ");
    s = s.replace(/[^a-z0-9./ ]+/g, " ");
    const raw = s.split(/\s+/).filter(Boolean);
    return raw.map(w => {
      const k = phon(w);
      let t = { w, k, type: "word" };
      if (/^\d+(\.\d+)?$/.test(w)) t = { w, k, type: "num", v: parseFloat(w) };
      else if (/^\d+\/\d+$/.test(w)) { const [a, b] = w.split("/").map(Number); t = { w, k, type: "frac", v: b ? a / b : 0 }; }
      else if (FIXK.has(k)) t = { w, k, type: "num", v: FIXK.get(k) };
      else if (NUMK.has(k)) t = { w, k, type: "num", v: NUMK.get(k) };
      else if (FRACK.has(k)) t = { w, k, type: "frac", v: FRACK.get(k) };
      else if (UNITK.has(k)) t = { w, k, type: "unit", u: UNITK.get(k) };
      else if (FILLK.has(k)) t = { w, k, type: "fill" };
      else if (KWK.has(k)) t = { w, k, type: "kw" };   // "price", "rate": never an item name
      return t;
    });
  }
  const isQty = t => t.type === "num" || t.type === "frac" || t.type === "unit";

  /* ---- 5. Fuzzy item matching ---- */
  function lev(a, b) {
    if (a === b) return 0;
    const m = a.length, n = b.length; if (!m) return n; if (!n) return m;
    let prev = Array.from({ length: n + 1 }, (_, j) => j), cur = new Array(n + 1);
    for (let i = 1; i <= m; i++) {
      cur[0] = i;
      for (let j = 1; j <= n; j++) cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
      [prev, cur] = [cur, prev];
    }
    return prev[n];
  }
  const sim = (a, b) => 1 - lev(a, b) / Math.max(a.length, b.length);
  const need = len => len <= 3 ? 1 : len <= 5 ? 0.8 : 0.75;
  // Kannada case endings: akki-yannu, sakkare-ge, haalu-alli, enne-inda
  const SUFFIX = new Set(["anu","ianu","nu","u","ge","ige","ke","ike","ali","inda","nda","a","e","du","na","da"].map(x => x));

  function prepareCatalogue(items) {
    return items.map(it => {
      const names = [it.name, ...(it.aliases || [])].map(s => String(s).trim()).filter(Boolean);
      const keys = [...new Set(names.map(n => phon(n)).filter(k => k.length >= 2))];
      return { ...it, keys };
    });
  }

  function scoreWindow(key, cat) {
    let best = null;
    for (const it of cat) for (const a of it.keys) {
      let s = sim(key, a);
      // Kannada adds endings to words (akki-yannu, sakkare-ge): allow the start of a longer word to match.
      if (key.length > a.length && a.length >= 4 && key.length - a.length <= 5) s = Math.max(s, sim(key.slice(0, a.length), a) - 0.05);
      const suffixHit = a.length >= 3 && key.length > a.length && key.startsWith(a) && SUFFIX.has(key.slice(a.length));
      if (suffixHit) s = Math.max(s, 0.95);
      if ((s >= need(a.length) || suffixHit) && (!best || s > best.s)) best = { item: it, s, alias: a };
    }
    return best;
  }

  function findItems(toks, cat) {
    const cands = [];
    for (let i = 0; i < toks.length; i++) {
      if (toks[i].type !== "word") continue;
      let key = "";
      for (let L = 1; L <= 3 && i + L <= toks.length; L++) {
        const t = toks[i + L - 1];
        if (t.type !== "word") break;
        key += t.k;
        const m = scoreWindow(key, cat);
        if (m) cands.push({ start: i, end: i + L, ...m, rank: m.s + 0.04 * (L - 1) });
      }
    }
    cands.sort((a, b) => b.rank - a.rank);
    const used = new Array(toks.length).fill(false), picked = [];
    for (const c of cands) {
      let free = true; for (let k = c.start; k < c.end; k++) if (used[k]) { free = false; break; }
      if (!free) continue;
      for (let k = c.start; k < c.end; k++) used[k] = true;
      picked.push(c);
    }
    picked.forEach(c => {
      const nx = toks[c.end];
      if (nx && nx.k === "g") {
        const base = toks.slice(c.start, c.end).map(t => t.k).join("");
        if (c.item.keys.some(k => k === base + "g")) c.end++;
      }
    });
    return picked.sort((a, b) => a.start - b.start);
  }

  /* ---- 6. Quantity from a run of tokens ---- */
  function readQty(run) {
    let num = null, unit = null, mult = 1;
    for (const t of run) {
      if (t.type === "num") {
        if ((t.v === 100 || t.v === 1000) && num !== null && num < 10) num *= t.v;     // "two hundred", "eradu nooru"
        else if (num !== null && num >= 100 && t.v < 100) num += t.v;                 // "nooru aivattu" style
        else if (num !== null && num >= 20 && num < 100 && num % 10 === 0 && t.v < 10 && t.v >= 1) num += t.v;   // "aivattu aidu" = 55
        else if (num !== null && t.v < 1) num += t.v;
        else num = (num !== null && num < 1) ? num + t.v : t.v;
      } else if (t.type === "frac") num = num === null ? t.v : num + t.v;               // "ondu vare", "one and half"
      else if (t.type === "unit") { if (t.u === "dozen") { mult = 12; unit = "piece"; } else unit = t.u; }
    }
    if (num === null && unit === null) return null;
    if (num === null) num = 1;
    return { n: num * mult, unit };
  }

  // Convert spoken quantity into the item's own selling unit.
  function toItemUnit(q, itemUnit) {
    if (!q) return { qty: 1, note: "" };
    if (!q.unit) return { qty: q.n, note: "" };
    const [sk, sf] = UNIT_KIND[q.unit] || [], [ik, iff] = UNIT_KIND[itemUnit] || [];
    if (sk && ik && sk === ik) return { qty: q.n * sf / iff, note: "" };
    return { qty: q.n, note: `heard "${q.unit}", sold per ${itemUnit}` };
  }

  /* ---- 7. Whole utterance → lines ---- */
  function parse(text, catalogue) {
    const cat = catalogue[0] && catalogue[0].keys ? catalogue : prepareCatalogue(catalogue);
    const toks = tokenize(text);
    const items = findItems(toks, cat);
    // Runs of quantity words between items.
    const runs = []; let cur = null;
    toks.forEach((t, i) => {
      const inItem = items.some(m => i >= m.start && i < m.end);
      if (!inItem && isQty(t)) { if (!cur) { cur = { start: i, end: i + 1, toks: [t] }; runs.push(cur); } else { cur.end = i + 1; cur.toks.push(t); } }
      else if (t.type === "fill" && cur && !inItem && toks[i + 1] && toks[i + 1].type === "frac") { /* "one and half" keeps the run going */ }
      else cur = null;
    });
    // Style of this sentence: quantity before the item ("ond kg sakkare") or after ("sakkare ond kg").
    const before = items.length && runs.some(r => r.end <= items[0].start);
    const usedRuns = new Set();
    const lines = items.map((m, idx) => {
      let run = null;
      if (before) {
        const prevEnd = idx ? items[idx - 1].end : 0;
        run = runs.filter(r => r.start >= prevEnd && r.end <= m.start).pop() || null;
      }
      if (!run) {
        const nextStart = idx + 1 < items.length ? items[idx + 1].start : toks.length;
        run = runs.find(r => r.start >= m.end && r.end <= nextStart && !usedRuns.has(r)) || null;
        if (!run && !before) run = null;
      }
      if (!run) {   // mixed order in one sentence: take an unused quantity right before the item
        const prevEnd = idx ? items[idx - 1].end : 0;
        run = runs.filter(r => r.start >= prevEnd && r.end <= m.start && !usedRuns.has(r)).pop() || null;
      }
      if (run) usedRuns.add(run);
      const q = run ? readQty(run.toks) : null;
      const { qty, note } = toItemUnit(q, m.item.unit);
      return { id: m.item.id, name: m.item.name, unit: m.item.unit, price: Number(m.item.price) || 0,
               qty: Math.round(qty * 1000) / 1000, heard: toks.slice(run && run.start < m.start ? run.start : m.start, run && run.end > m.end ? run.end : m.end).map(t => t.w).join(" "),
               match: Math.round(m.s * 100), note, qtySaid: !!q };
    });
    const covered = new Set();
    items.forEach(m => { for (let k = m.start; k < m.end; k++) covered.add(k); });
    runs.forEach(r => { if (usedRuns.has(r)) for (let k = r.start; k < r.end; k++) covered.add(k); });
    const unknown = toks.filter((t, i) => !covered.has(i) && t.type !== "fill" && t.type !== "kw").map(t => t.w);
    return { lines, unknown, tokens: toks.map(t => t.w).join(" ") };
  }

  /* ---- 8. Spoken commands ---- */
  const CMD = {
    undo: ["cancel", "remove", "remove last", "delete", "tegi", "tegeyiri", "beda", "hatao", "galat", "wrong"],
    clear: ["clear", "new bill", "hosa bill", "naya bill", "clear bill"],
    total: ["total", "ottu", "ottu eshtu", "kitna hua", "eshtu aaytu"],
    readback: ["read", "read bill", "read back", "bill odi", "odi", "list odi", "bill heli", "padho", "bill padho", "bill sunao", "repeat", "list"],
    stop: ["stop", "stop listening", "nillisu", "nilsu", "ruko", "band karo", "bas"]
  };
  const CMDK = Object.fromEntries(Object.entries(CMD).map(([k, v]) => [k, v.map(phon)]));
  function command(text) {
    const k = phon(text);
    for (const [name, keys] of Object.entries(CMDK)) if (keys.includes(k)) return name;
    return null;
  }

  /* ---- 9. Setting prices by voice ----
     "ಸಕ್ಕರೆ ಬೆಲೆ ಐವತ್ತು" / "sugar price 50"      → change an item's price
     "ಹೊಸ ಐಟಂ ಬಾದಾಮಿ 800 ಕೆಜಿ" / "add item badam 800 per kg" → new item */
  const PRICE_KW = new Set(["price", "rate", "bele", "daam", "dam", "kimmat", "kimat", "rait", "ret"].map(phon));
  const ADD_A = new Set(["add", "hosa", "naya", "new", "hosadu"].map(phon));
  const ADD_B = new Set(["item", "items", "saman", "samanu", "vastu", "product", "aitam", "aitum"].map(phon));
  function priceCommand(text, catalogue) {
    const cat = catalogue[0] && catalogue[0].keys ? catalogue : prepareCatalogue(catalogue);
    const toks = tokenize(text);
    let addAt = -1;
    toks.forEach((t, i) => { if (ADD_A.has(t.k) && toks[i + 1] && ADD_B.has(toks[i + 1].k)) addAt = i; });
    const items = findItems(toks, cat).filter(m => addAt < 0 || m.end <= addAt || m.start > addAt + 1);
    const inItem = i => items.some(m => i >= m.start && i < m.end);
    const priceAt = toks.findIndex((t, i) => (t.type === "kw" || PRICE_KW.has(t.k)) && !inItem(i));
    if (addAt < 0 && priceAt < 0) return null;
    const nums = toks.filter(t => t.type === "num" || t.type === "frac");
    if (!nums.length) return null;
    const price = readQty(nums).n;
    const unitTok = toks.filter(t => t.type === "unit").pop();
    const unit = unitTok ? (unitTok.u === "dozen" ? "piece" : unitTok.u) : null;
    if (items.length && (addAt < 0 || items[0].s >= 0.9)) {
      const it = items[0].item;
      return { kind: "price", id: it.id, name: it.name, price, unit };
    }
    if (addAt < 0) return null;
    const nameToks = toks.filter((t, i) => t.type === "word" && i !== addAt && i !== addAt + 1 && !PRICE_KW.has(t.k));
    if (!nameToks.length) return null;
    const keys = new Set(nameToks.map(t => t.k));
    // Keep the words as they were heard (Kannada script etc.) as an extra name.
    const original = String(text).split(/\s+/).filter(w => keys.has(phon(w))).join(" ");
    const name = nameToks.map(t => t.w.charAt(0).toUpperCase() + t.w.slice(1)).join(" ");
    return { kind: "new", name, alias: original && original.toLowerCase() !== name.toLowerCase() ? original : "", price, unit: unit || "piece" };
  }

  return { parse, prepareCatalogue, command, priceCommand, phon, translitKannada, tokenize };
})();
if (typeof module !== "undefined") module.exports = VB;
