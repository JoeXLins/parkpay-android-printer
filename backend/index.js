// Minimal Express backend that generates HMAC-signed payment shortlinks
// and simulates a payment page + webhook callback.

const express = require('express');
const bodyParser = require('body-parser');
const crypto = require('crypto');

const app = express();
app.use(bodyParser.json());

// Example in-memory store (for demo only)
const ORDERS = {}; // session -> { amount_cents, status }

// Secret for HMAC signing - replace in production
const SECRET = process.env.PAY_SECRET || 'example-secret-please-change';
const PORT = process.env.PORT || 3000;

function signPayload(payload) {
  return crypto.createHmac('sha256', SECRET).update(payload).digest('hex');
}

// Create a payment link
// POST /payments/create { session, amount_cents }
app.post('/payments/create', (req, res) => {
  const { session, amount_cents } = req.body;
  if (!session || !amount_cents) return res.status(400).json({ error: 'session and amount_cents required' });

  const expires = Math.floor(Date.now() / 1000) + 10 * 60; // 10 minutes
  const payload = `${session}|${amount_cents}|${expires}`;
  const sig = signPayload(payload);
  const payUrl = `http://localhost:${PORT}/pay?session=${encodeURIComponent(session)}&amount=${Math.floor(amount_cents / 100)}&expires=${expires}&sig=${sig}`;

  // store order
  ORDERS[session] = { amount_cents, status: 'pending' };

  res.json({ pay_url: payUrl });
});

// Simulated pay page - in real world this would be handled by a payment provider
app.get('/pay', (req, res) => {
  const { session, amount, expires, sig } = req.query;
  if (!session || !amount || !expires || !sig) return res.status(400).send('missing params');

  const payload = `${session}|${parseInt(amount) * 100}|${expires}`;
  const expected = signPayload(payload);
  if (expected !== sig) return res.status(400).send('invalid signature');
  if (parseInt(expires) < Math.floor(Date.now() / 1000)) return res.status(400).send('link expired');

  // Simple HTML page with a "Simulate Pay" button which triggers server-side webhook
  res.send(`
    <html><body>
    <h3>模拟支付</h3>
    <p>订单: ${session}</p>
    <p>金额: ${amount} 元</p>
    <form method="POST" action="/simulate-pay">
      <input type="hidden" name="session" value="${session}" />
      <input type="hidden" name="amount" value="${amount}" />
      <button type="submit">模拟支付成功并回调Webhook</button>
    </form>
    </body></html>
  `);
});

app.post('/simulate-pay', bodyParser.urlencoded({ extended: false }), (req, res) => {
  const session = req.body.session;
  const amount = parseInt(req.body.amount);
  if (!session) return res.status(400).send('missing session');

  const providerTxId = 'tx_' + Math.random().toString(36).slice(2, 10);
  const payload = `${session}|${amount}`;
  const sig = signPayload(payload);

  // Simulate calling webhook - in demo we call our own /webhook endpoint
  const webhookBody = { session, amount_cents: amount * 100, provider_tx_id: providerTxId, sig };

  // Update internal state then call webhook handler directly for demo
  ORDERS[session] = { amount_cents: amount * 100, status: 'paid', provider_tx_id: providerTxId };

  // In real scenario, payment provider would call your webhook URL. Here we just show a result page.
  res.send(`<html><body><h3>支付模拟成功</h3><pre>${JSON.stringify(webhookBody, null, 2)}</pre><p>订单状态已标记为 paid (demo only)</p></body></html>`);
});

// Webhook endpoint (for actual providers you'd verify provider signature)
app.post('/webhook', (req, res) => {
  const { session, amount_cents, provider_tx_id, sig } = req.body;
  if (!session || !amount_cents || !provider_tx_id) return res.status(400).json({ error: 'missing fields' });

  // For demo, verify sig = HMAC(secret, `${session}|${amount_cents/100}`)
  const payload = `${session}|${Math.floor(amount_cents/100)}`;
  const expected = signPayload(payload);
  if (sig && sig !== expected) return res.status(400).json({ error: 'invalid signature' });

  ORDERS[session] = { amount_cents, status: 'paid', provider_tx_id };
  console.log('Webhook received, order updated:', session);
  res.json({ ok: true });
});

app.get('/orders/:session', (req, res) => {
  const s = req.params.session;
  res.json(ORDERS[s] || null);
});

app.listen(PORT, () => {
  console.log(`ParkPay demo backend listening at http://localhost:${PORT}`);
  console.log(`Demo secret: ${SECRET}`);
});
