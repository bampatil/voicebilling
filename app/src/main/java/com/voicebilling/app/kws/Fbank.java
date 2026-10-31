package com.voicebilling.app.kws;

/**
 * Turns sound into the "sound picture" the Kannada model reads: 80 pitch bands, one column every 10 ms.
 *
 * It must be drawn exactly the way the speech library (sherpa-onnx, via kaldi-native-fbank) draws it for
 * NeMo models, or the model hears worse. Settings copied from sherpa-onnx 1.12.23
 * offline-recognizer-ctc-impl.h (NeMo branch) and kaldi-native-fbank:
 *   16 kHz, 25 ms frames every 10 ms, edges mirrored (snip_edges=false), no dither, no DC removal,
 *   pre-emphasis 0.97, periodic Hann window, 512-point FFT, power spectrum,
 *   80 librosa/Slaney mel bands from 0 to 8000 Hz with Slaney area normalisation, log with floor FLT_EPSILON,
 *   then NeMo "per_feature" normalisation (each band to mean 0, std 1 over the clip).
 * Checked number-for-number against the Python kaldi-native-fbank used in the keyword-listening test.
 */
public final class Fbank {
    public static final int BANDS = 80;
    private static final int RATE = 16000, LEN = 400, SHIFT = 160, NFFT = 512;
    private static final float PREEMPH = 0.97f;
    private static final float EPS = 1.1920929e-07f;   // FLT_EPSILON

    private final float[] window = new float[LEN];
    private final int[] binStart = new int[BANDS];
    private final float[][] binWeights = new float[BANDS][];
    private final double[] cos = new double[NFFT / 2], sin = new double[NFFT / 2];
    private final int[] bitrev = new int[NFFT];

    public Fbank() {
        for (int i = 0; i < LEN; i++) window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI / LEN * i));
        // mel filters (librosa style)
        float low = melSlaney(0), high = melSlaney(RATE / 2f);
        float delta = (high - low) / (BANDS + 1);
        float binWidth = (float) RATE / NFFT;
        for (int b = 0; b < BANDS; b++) {
            float l = invMelSlaney(low + b * delta), c = invMelSlaney(low + (b + 1) * delta), r = invMelSlaney(low + (b + 2) * delta);
            float[] w = new float[NFFT / 2 + 1];
            int first = -1, last = -1;
            for (int i = 0; i <= NFFT / 2; i++) {
                float hz = binWidth * i;
                if (hz > l && hz < r) {
                    float weight = hz <= c ? (hz - l) / (c - l) : (r - hz) / (r - c);
                    weight *= 2 / (r - l);
                    w[i] = weight;
                    if (first < 0) first = i;
                    last = i;
                }
            }
            binStart[b] = first;
            binWeights[b] = java.util.Arrays.copyOfRange(w, first, last + 1);
        }
        for (int i = 0; i < NFFT / 2; i++) { cos[i] = Math.cos(-2 * Math.PI * i / NFFT); sin[i] = Math.sin(-2 * Math.PI * i / NFFT); }
        int bits = Integer.numberOfTrailingZeros(NFFT);
        for (int i = 0; i < NFFT; i++) bitrev[i] = Integer.reverse(i) >>> (32 - bits);
    }

    private static float melSlaney(float f) { return f <= 1000 ? f * 3 / 200f : 15 + 14.545078505785561f * (float) Math.log(f / 1000); }
    private static float invMelSlaney(float m) { return m <= 15 ? 200f / 3 * m : 1000 * (float) Math.exp((m - 15) * 0.06875177742094911f); }

    /** Number of picture columns for n samples (all frames, as the library does at the end of a clip). */
    public static int numFrames(int n) { return (n + SHIFT / 2) / SHIFT; }

    /**
     * The normalised picture for a clip, laid out as the model wants it: [band][frame] flattened (80 x frames).
     * Samples are in -1..1.
     */
    public float[] compute(float[] wave) {
        int frames = numFrames(wave.length);
        float[] logmel = new float[frames * BANDS];          // [frame][band]
        float[] frame = new float[LEN];
        double[] re = new double[NFFT], im = new double[NFFT];
        float[] power = new float[NFFT / 2 + 1];
        for (int f = 0; f < frames; f++) {
            int start = f * SHIFT + SHIFT / 2 - LEN / 2;
            for (int s = 0; s < LEN; s++) {
                int k = s + start;
                while (k < 0 || k >= wave.length) k = k < 0 ? -k - 1 : 2 * wave.length - 1 - k;   // mirror at the edges
                frame[s] = wave[k];
            }
            for (int i = LEN - 1; i > 0; i--) frame[i] -= PREEMPH * frame[i - 1];
            frame[0] -= PREEMPH * frame[0];
            for (int i = 0; i < NFFT; i++) { re[i] = 0; im[i] = 0; }
            for (int i = 0; i < LEN; i++) re[bitrev[i]] = frame[i] * window[i];
            fft(re, im);
            for (int i = 0; i <= NFFT / 2; i++) power[i] = (float) (re[i] * re[i] + im[i] * im[i]);
            for (int b = 0; b < BANDS; b++) {
                float e = 0; float[] w = binWeights[b]; int o = binStart[b];
                for (int k = 0; k < w.length; k++) e += w[k] * power[k + o];
                logmel[f * BANDS + b] = (float) Math.log(Math.max(e, EPS));
            }
        }
        // NeMo per_feature normalisation, and transpose to [band][frame]
        float[] out = new float[BANDS * frames];
        for (int b = 0; b < BANDS; b++) {
            double sum = 0, sq = 0;
            for (int f = 0; f < frames; f++) { double v = logmel[f * BANDS + b]; sum += v; sq += v * v; }
            double mean = sum / frames, var = Math.max(sq / frames - mean * mean, 0);
            double inv = 1 / (Math.sqrt(var) + 1e-5);
            for (int f = 0; f < frames; f++) out[b * frames + f] = (float) ((logmel[f * BANDS + b] - mean) * inv);
        }
        return out;
    }

    /** In-place radix-2 FFT; input already in bit-reversed order. */
    private void fft(double[] re, double[] im) {
        for (int size = 2; size <= NFFT; size <<= 1) {
            int half = size / 2, step = NFFT / size;
            for (int i = 0; i < NFFT; i += size) {
                for (int j = 0; j < half; j++) {
                    double wr = cos[j * step], wi = sin[j * step];
                    int a = i + j, b = a + half;
                    double tr = re[b] * wr - im[b] * wi, ti = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - tr; im[b] = im[a] - ti;
                    re[a] += tr; im[a] += ti;
                }
            }
        }
    }
}
