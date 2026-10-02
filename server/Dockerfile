FROM node:22.22.3-alpine AS build
WORKDIR /workspace
RUN corepack enable
COPY package.json pnpm-workspace.yaml pnpm-lock.yaml .npmrc ./
COPY server/package.json server/package.json
RUN pnpm install --frozen-lockfile
COPY server server
RUN pnpm --filter @stratawake/server build && pnpm deploy --filter @stratawake/server --prod /out

FROM node:22.22.3-alpine AS runtime
ENV NODE_ENV=production
USER node
WORKDIR /app
COPY --from=build --chown=node:node /out ./
COPY --from=build --chown=node:node /workspace/server/dist ./dist
EXPOSE 3000
CMD ["node", "dist/index.js"]
