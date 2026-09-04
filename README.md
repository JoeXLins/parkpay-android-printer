# ParkPay Android + Backend

This repository contains:

- An Android sample app (Kotlin) demonstrating:
  - CameraX photo capture
  - ML Kit on-device text recognition for Chinese license plates
  - QR code generation (ZXing)
  - Printing receipt (text + QR bitmap) to a 58mm Bluetooth thermal printer via SPP (ESC/POS raster)

- A minimal Node/Express backend demo that generates signed payment shortlinks (HMAC-SHA256) and a simulated payment flow + webhook callback demonstration.

Important notes
- This is a demo project. DO NOT use the example secret or the dummy pay URLs in production. Always create payment orders securely on the server and sign/validate on the server.
- The Android app expects you to pair the Bluetooth thermal printer in the system Settings first. Typical SPP UUID is used (00001101-0000-1000-8000-00805F9B34FB).

Repository structure

- app/                 Android app module
- backend/             Node/Express demo backend
- README.md

Quick start - backend

1. Enter `backend` folder
2. Install dependencies: `npm install`
3. Start server: `node index.js`
4. Create a payment order (example):

```bash
curl -X POST http://localhost:3000/payments/create \
  -H "Content-Type: application/json" \
  -d '{"session":"demo-1","amount_cents":300}'
```

The server returns a signed pay_url like:

```
{"pay_url":"http://localhost:3000/pay?session=demo-1&amount=3&expires=1690000000&sig=..."}
```

Open the pay_url in the mobile browser to simulate payment; the demo page includes a "Simulate Pay" button which will call the webhook endpoint.

Quick start - Android

- Open this project in Android Studio.
- Ensure you run on a real device (CameraX and Bluetooth require a real device).
- Pair your 58mm Bluetooth thermal printer in Android Settings.
- Grant CAMERA and Bluetooth permissions when prompted.
- Use "拍照识别" to capture and recognize plate, then "选择打印机" to pick the paired printer, and "结算并打印" to generate a QR and print the receipt.

If you want me to provide a downloadable ZIP release, it's available as a GitHub Release on the repository page.
