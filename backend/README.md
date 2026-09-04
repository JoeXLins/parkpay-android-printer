# ParkPay Backend Demo

This folder contains a minimal Node/Express demo backend used by the ParkPay Android demo.

What it does
- POST /payments/create
  - Accepts { session, amount_cents }
  - Returns a signed pay_url that expires in 10 minutes
- GET /pay
  - A simple HTML page where you can simulate payment
- POST /simulate-pay
  - Simulates a payment and updates in-memory order state (and shows webhook body)
- POST /webhook
  - Webhook endpoint that (for demo) verifies a simple HMAC-based signature and marks order paid

Run

```
cd backend
npm install
node index.js
```

Then call:

```
curl -X POST http://localhost:3000/payments/create \
  -H "Content-Type: application/json" \
  -d '{"session":"demo-1","amount_cents":300}'
```

Open the returned pay_url in a browser and click "模拟支付" to test the flow.

Notes
- The demo uses an in-memory ORDERS object. Replace with a DB in production.
- The HMAC secret is printed at server start. Set PAY_SECRET env var to override.
