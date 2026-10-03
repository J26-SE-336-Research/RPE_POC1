# Northstar Commerce frontend

A React, Tailwind CSS, and Axios frontend for the RRPE microservices demo. The browser calls only `/api/...`; those requests go through the API gateway.

## Run in development

1. Start the backend and API gateway. The gateway should be reachable at `http://localhost:8080`.
2. From this folder, install the packages and start Vite:

   ```bash
   npm install
   npm run dev
   ```

3. Open the local URL printed by Vite (normally `http://localhost:5173`).

Vite forwards `/api` requests to the gateway. To use a different gateway address during local development, set `VITE_GATEWAY_URL` before starting Vite (default `http://localhost:8080`). This setting is for the Vite development server; Docker Compose configures the Nginx gateway address separately.

## Run in Docker Compose

The root Compose file can build and run the frontend with Nginx. `FRONTEND_PORT` chooses the port on your computer (default `3000`). `API_GATEWAY_HOST` and `API_GATEWAY_CONTAINER_PORT` choose the gateway's internal Docker hostname and container port. Compose passes them to the frontend container as `API_GATEWAY_HOST` and `API_GATEWAY_PORT`, which Nginx uses to forward `/api/...` requests. The browser still sends requests to the frontend origin; it does not call any microservice directly.

```bash
docker compose up -d --build frontend
```

Open `http://localhost:3000`.

## Included screens

- Overview with order totals, stock levels, recent orders, and the order journey.
- Inventory catalog with available, reserved, and total stock.
- Order list and order detail drawer with line items and payment details.
- Order form that calls the Order API and can deliberately force a payment decline to show the existing compensation flow.
- Payment activity and sent-notification views.

All backend calls use Axios from `src/api.js` with `/api` as the base path. The gateway's existing route configuration strips the `/api` prefix before it forwards requests to each service.
