"""
Prepare the offline Kannada model so the speech engine inside the app can open it.

Why: the published model file is labelled with newer ONNX format versions (ai.onnx.ml opset 5)
than the ONNX Runtime 1.17 inside the Android speech engine accepts, so the app crashed on load.

What this does:
1. Reads the model and lists which kinds of operations it really uses.
2. Lowers or removes the version labels it doesn't need (only when no operation depends on them).
3. Test-loads and runs the fixed model with ONNX Runtime 1.17.1, the same version the app uses.
4. Writes the fixed model; the workflow then publishes it in this repo's releases.

Every finding is printed as a GitHub annotation ("::notice::") so it shows on the build page.
"""
import sys
import numpy as np
import onnx
import onnxruntime as ort

src, dst = sys.argv[1], sys.argv[2]
MAX_IR = 9            # highest model format ONNX Runtime 1.17 reads
MAX_DEFAULT_OPSET = 20  # highest standard operation set ONNX Runtime 1.17 supports
MAX_ML_OPSET = 4


def note(msg):
    print(f"::notice::{msg}", flush=True)


def fail(msg):
    print(f"::error::{msg}", flush=True)
    sys.exit(1)


m = onnx.load(src)
note(f"Original: IR version {m.ir_version}, opsets " + ", ".join(f"{o.domain or 'ai.onnx'}={o.version}" for o in m.opset_import))
note("Metadata keys: " + ", ".join(p.key for p in m.metadata_props))


def domains_used(graph, acc):
    for n in graph.node:
        acc[n.domain or "ai.onnx"] = acc.get(n.domain or "ai.onnx", 0) + 1
        for a in n.attribute:
            if a.type == onnx.AttributeProto.GRAPH:
                domains_used(a.g, acc)
            for g in a.graphs:
                domains_used(g, acc)
    return acc


used = domains_used(m.graph, {})
note("Operation domains used: " + ", ".join(f"{d} ({c} ops)" for d, c in used.items()))

new_imports = []
for o in m.opset_import:
    d = o.domain or "ai.onnx"
    if d == "ai.onnx.ml":
        if "ai.onnx.ml" not in used:
            note(f"ai.onnx.ml opset {o.version} is only a label (no operations use it): removed")
            continue
        if o.version > MAX_ML_OPSET:
            fail(f"Model really uses ai.onnx.ml operations at opset {o.version}; can't safely lower it")
    if d == "ai.onnx" and o.version > MAX_DEFAULT_OPSET:
        fail(f"Model needs standard opset {o.version}, newer than ONNX Runtime 1.17 supports ({MAX_DEFAULT_OPSET})")
    new_imports.append(o)
del m.opset_import[:]
m.opset_import.extend(new_imports)

if m.ir_version > MAX_IR:
    note(f"IR version {m.ir_version} lowered to {MAX_IR}")
    m.ir_version = MAX_IR

onnx.save(m, dst)
note(f"Fixed: IR version {m.ir_version}, opsets " + ", ".join(f"{o.domain or 'ai.onnx'}={o.version}" for o in m.opset_import))

# Test with the same ONNX Runtime version as the app
note(f"Testing with ONNX Runtime {ort.__version__}")
try:
    sess = ort.InferenceSession(dst, providers=["CPUExecutionProvider"])
except Exception as e:
    fail(f"ONNX Runtime {ort.__version__} still can't open the model: {e}")

ins = sess.get_inputs()
note("Inputs: " + "; ".join(f"{i.name} {i.shape} {i.type}" for i in ins))
note("Outputs: " + "; ".join(f"{o.name} {o.shape} {o.type}" for o in sess.get_outputs()))

# One run on 2 seconds of silence-like features: proves the model actually computes, not just opens
frames = 200
feed = {}
for i in ins:
    if "int" in i.type:
        feed[i.name] = np.array([frames], dtype=np.int64)
    else:
        # NeMo speech models take features as [batch, 80 mel bands, time]
        feed[i.name] = np.zeros([1, 80, frames], dtype=np.float32)
try:
    outs = sess.run(None, feed)
except Exception as e:
    fail(f"Model opened but failed to run: {e}")
note("Test run OK. Output shapes: " + "; ".join(str(o.shape) for o in outs))
