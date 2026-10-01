# Multi-stage Dockerfile for FleetPulse Web Dashboard
# Stage 1: Build the modern React Vite application
FROM node:20-alpine AS builder

WORKDIR /app

# Copy dependency manifests
COPY services/web_app/package*.json ./
RUN npm ci

# Copy application source and build bundle
COPY services/web_app ./
RUN npm run build

# Stage 2: Serve production bundle with Nginx & reverse proxy to FastAPI
FROM nginx:alpine

COPY services/web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
