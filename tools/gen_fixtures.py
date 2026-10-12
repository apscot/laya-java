"""Dev-time only: generate golden fixtures from the Python reference (laya-mlx).
Usage: python tools/gen_fixtures.py <model_dir> <out_dir>
Never shipped; Java artifacts do not depend on Python."""
import json, sys
import numpy as np
import mlx.core as mx
import laya_mlx as laya

model_dir, out_dir = sys.argv[1], sys.argv[2]
tag = sys.argv[3] if len(sys.argv) > 3 else ""
CASES = [
  {"name": "billing", "state": "I was billed twice. Please refund the duplicate today.",
   "questions": {
     "department": {"type": "choice", "instructions": "Which team should handle this request?",
        "criteria": {"billing": "invoices, payments, refunds", "technical": "bugs and outages", "sales": "new purchases"}},
     "urgency": {"type": "score", "instructions": "How urgent is this request?", "criteria": ["not urgent", "soon", "critical"]},
     "refund": {"type": "noul", "instructions": "Does the customer ask for money back?"}}},
  {"name": "outage", "state": "Help! My payouts have been failing for 3 days.",
   "questions": {
     "dept": {"type": "choice", "instructions": "Which team should handle this?", "criteria": ["billing", "technical", "sales"]},
     "frustration": {"type": "score", "instructions": "How frustrated is the customer?", "criteria": ["Calm", "Frustrated", "Very angry"]},
     "is_urgent": {"type": "noul", "instructions": "Does this convey urgency?", "criteria": {"true": "Explicitly time-sensitive", "false": "No urgency expressed"}}}},
  {"name": "unicode", "state": "Café ☕ — naïve résumé, 日本語のテキスト, emoji 🚀!  Two  spaces\nnewline\ttab",
   "questions": {"lang": {"type": "choice", "instructions": "Is this mostly English?", "criteria": ["yes", "no"]}}},
  {"name": "conversation", "state": [{"role": "user", "content": "hi"}, {"role": "assistant", "content": "hello, how can I help?"}, {"role": "user", "content": "cancel my subscription"}],
   "questions": {"cancel": {"type": "noul", "instructions": "Does the user want to cancel?"}}},
  {"name": "chinese", "state": "发票被重复扣款，请退款。我已经等了三天，非常着急！",
   "questions": {"dept": {"type": "choice", "instructions": "哪个团队应该处理这个请求？", "criteria": {"billing": "发票、付款、退款", "technical": "故障和错误", "sales": "新购买"}},
                 "urgent": {"type": "noul", "instructions": "客户是否表达了紧迫感？"}}},
  {"name": "long", "state": " ".join(["The quick brown fox jumps over the lazy dog."] * 80),
   "questions": {"animal": {"type": "choice", "instructions": "Which animal jumps?", "criteria": {"fox": None, "dog": None, "cat": None, "bird": None, "fish": None, "cow": None}}}},
]
tok_texts = ["Hello world", "  leading and  double", "don't stop, it's 2026", "Café ☕ 日本語 🚀", "Привет, мир! Ça va très bien. 你好，世界", "tab\tseparated  text\n\n next", "  leading spaces", "trailing spaces  ", "emoji 👩‍👩‍👧‍👦 zwj", "ﬁ ligature ① circled", "\u0000 control \u200b zwsp", "line1\nline2\n\n\nline3", "x = {'a': [1, 2, 3]}", "|||IP_ADDRESS||| 192.168.0.1", "[MASK] inside text", " " * 30 + "end"]
# MLX GPU fp32 is noisier (~1e-3 on hidden states); CPU is the precise reference.
agent = laya.load(model_dir, dtype="float32", device="cpu")
out = {"tokenizer": [{"text": t, "ids": agent.tok(t)["input_ids"]} for t in tok_texts], "cases": []}
for c in CASES:
    items, internal = agent.prepare(c["state"], c["questions"])
    batch = []
    for it in items:
        b = {k: v for k, v in it.items() if k in ("ids", "markers", "qtype")}
        batch.append(b)
    res = agent.predict(c["state"], c["questions"])
    # raw logits per question for tight numeric comparison
    from laya_mlx.agent import collate_items
    bt = collate_items(items, agent.tok.pad_token_id)
    logits, act = agent.forward(bt)
    logits, act = np.asarray(logits), np.asarray(act)
    out["cases"].append({**c, "prepared": batch, "logits": [logits[i, :len(items[i]["markers"])].tolist() for i in range(len(items))],
                         "act_logits": act.tolist(), "result": res})
# encoder hidden-state probe for the first prepared sequence
it = agent.prepare(CASES[0]["state"], CASES[0]["questions"])[0][0]
ids = mx.array(np.array([it["ids"]], dtype=np.int32)); am = mx.ones(ids.shape, dtype=mx.bool_)
with mx.stream(agent.device):
    h = np.asarray(agent.model.encoder(ids, am))[0]
    emb = np.asarray(agent.model.encoder.embeddings(ids))[0]
out["probe"] = {"ids": it["ids"], "emb_first": emb[:3, :8].tolist(), "enc_first": h[:3, :8].tolist(), "enc_last": h[-2:, :8].tolist(), "enc_mean_abs": float(np.abs(h).mean())}
json.dump(out, open(out_dir + "/reference" + ("-" + tag if tag else "") + ".json", "w"), ensure_ascii=False)
print("ok", len(out["cases"]))
