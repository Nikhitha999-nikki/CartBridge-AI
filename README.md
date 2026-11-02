# CartBridge AI

CartBridge AI is a local checkout-recovery demo. This phase provides a sample storefront and a PayPal Sandbox Checkout flow. The backend creates orders, captures only after buyer approval, verifies PayPal's returned order/capture IDs, status, amount, and currency, and uses PayPal request IDs to make order creation and capture idempotent.

> **Sandbox only:** The backend accepts only `https://api-m.sandbox.paypal.com`. Do not use production credentials or real payment details. Checkout sessions are currently stored in memory and disappear when the backend restarts.

The Merchant Recovery Dashboard is a local demo feature. Checkout sessions and recovery history are stored in memory and disappear when the backend restarts. The merchant endpoints do not include authentication in this prototype; run locally only and do not expose them to the public internet.

## Requirements

- Java 17
- Maven 3.9+
- Node.js 20.19+ or 22.12+ (Node.js 24 is also supported by the installed Angular CLI)
- npm
- PayPal Developer Sandbox REST app credentials and a Sandbox personal/buyer account

## Configure local backend credentials

The root `.env.example` is the single placeholder-only template for backend settings. Spring Boot loads `backend/.env` as a Java properties file when starting from the `backend` directory. The `.env` file is ignored by Git. Never copy credentials into Angular files, source control, or logs.

In PowerShell, create the ignored local file and open it in a text editor:

```powershell
Set-Location 'C:\Users\Admin\Desktop\CartBridge AI\backend'
Copy-Item '..\.env.example' '.env'
notepad .env
```

In Notepad, replace the PayPal placeholders with your **PayPal Sandbox REST app** Client ID and Secret. Keep `PAYPAL_BASE_URL=https://api-m.sandbox.paypal.com`; production PayPal URLs are not supported. Set `PAYPAL_CURRENCY=USD` for the demo. To use Gemini, add a Google AI Studio key to `GOOGLE_API_KEY`. Gemini is optional: leave it blank to use safe `FALLBACK` recovery guidance. `GEMINI_MODEL` defaults to `gemini-3.8-flash`.

Save `backend/.env`. Do not paste credentials into PowerShell commands or commit this file. `.env.example` contains placeholders only. Spring Boot's optional config import means the backend also starts if `.env` is absent; without PayPal credentials, PayPal checkout reports its normal configuration error.

## Run locally

Start the backend from the `backend` directory so the local `.env` file is found:

```powershell
Set-Location 'C:\Users\Admin\Desktop\CartBridge AI\backend'
mvn spring-boot:run
```

In a second PowerShell terminal, start Angular:

```powershell
Set-Location 'C:\Users\Admin\Desktop\CartBridge AI\frontend'
npm start
```

Open [http://localhost:4200](http://localhost:4200). The backend listens on port 8080.

The Angular app does not load or contain backend secrets. Only the public PayPal Client ID is provided to the browser for the PayPal JavaScript SDK; PayPal Client Secret and Gemini key remain backend-only.

Health and product endpoints are available at `GET /api/health` and `GET /api/products`. `GET /api/paypal/config` returns only the public Client ID and currency; without both server-side credentials, it responds with a clear HTTP 503 configuration error.

Open [http://localhost:4200/dashboard](http://localhost:4200/dashboard) for the merchant dashboard. It reads `GET /api/merchant/dashboard` for backend-derived checkout statuses and `GET /api/merchant/recovery-history` for advice requests. Payment completion is shown only when the backend session status is `COMPLETED`. The backend stores each returned recovery recommendation and its source (`GEMINI` or `FALLBACK`) in memory.

## Try a Sandbox payment

1. Sign in to the PayPal Developer Dashboard and create or select a Sandbox personal account to act as the buyer.
2. Open the local storefront, choose a product, select a quantity, and click the PayPal button.
3. PayPal Sandbox opens for buyer approval. Sign in with the Sandbox buyer account, not your personal PayPal account.
4. Approve the payment. The Angular app sends the approved order ID and checkout-session ID to the backend; the backend fetches the order, verifies approval and amount/currency, captures it, and validates PayPal's capture response before displaying `COMPLETED`.
5. To test cancellation, close or cancel the PayPal approval flow. The UI displays the backend's cancellation result. A pending or declined result is not described as a completed payment.

The browser never receives the Client Secret or access token, and the backend does not accept a client-supplied total or success status. Do not enter real payment credentials into this Sandbox demo.

## Tests and production build checks

```powershell
Set-Location 'C:\Users\Admin\Desktop\CartBridge AI\backend'
mvn test
mvn clean package

Set-Location 'C:\Users\Admin\Desktop\CartBridge AI\frontend'
npm test -- --watch=false
npm run build
```

Backend PayPal tests use mocked HTTP responses and do not require live credentials.

## Checkout recovery advice API

`POST /api/recovery/advice` accepts the shopper's problem description and the backend checkout-session ID. The backend loads the authoritative session status, product, amount, and currency. Gemini provides advice for ordinary checkout issues; the backend itself supplies the no-retry guidance for APPROVED, PENDING, and COMPLETED sessions. Advice does not change or verify payment state.

Example request:

```json
{
  "checkoutSessionId": "49b5c470-f1d0-41d9-b9fc-29873c79ec81",
  "problemDescription": "The checkout page stopped responding after I selected PayPal."
}
```

Example response:

```json
{
  "detectedIssue": "Checkout page may have stalled",
  "explanation": "The page may not have finished loading the payment step.",
  "recommendedNextAction": "Wait briefly, then reload the checkout and check its status before trying again.",
  "safetyWarning": "Do not share payment credentials or sensitive payment details."
}
```
