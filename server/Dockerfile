FROM node:22.22.3-alpine AS build
WORKDIR /workspace
RUN corepack enable
COPY package.json pnpm-workspace.yaml pnpm-lock.yaml .npmrc ./
COPY server/package.json server/package.json
COPY contracts/package.json contracts/package.json
COPY web/package.json web/package.json
RUN pnpm install --frozen-lockfile
COPY server server
COPY web web
RUN pnpm --filter @afterchime/web build && pnpm --filter @afterchime/server build && pnpm deploy --filter @afterchime/server --prod /out

FROM node:22.22.3-alpine AS runtime
ENV NODE_ENV=production
ENV AFTERCHIME_WEB_ROOT=/app/web/dist
USER node
WORKDIR /app
COPY --from=build --chown=node:node /out ./
COPY --from=build --chown=node:node /workspace/server/dist ./dist
COPY --from=build --chown=node:node /workspace/web/dist ./web/dist
EXPOSE 3000
CMD ["node", "dist/index.js"]
