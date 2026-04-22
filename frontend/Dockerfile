# ── Stage 1: Install dependencies (cached layer) ─────────────────────────────
FROM node:20-alpine AS deps

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts

# ── Stage 2: Build static bundle ─────────────────────────────────────────────
FROM deps AS build

# Optional build-time override for the API base URL (e.g. a hosted backend).
# Leave unset to use the nginx /api reverse-proxy defined in nginx.conf.
ARG VITE_API_URL
ENV VITE_API_URL=${VITE_API_URL}

COPY . .
RUN npm run build

# ── Stage 3: Serve with nginx ─────────────────────────────────────────────────
FROM nginx:alpine AS final

# Copy the compiled SPA bundle
COPY --from=build /app/dist /usr/share/nginx/html

# Replace the default nginx config with our SPA + proxy config
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
