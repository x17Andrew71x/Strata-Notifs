import { createHmac, randomBytes, timingSafeEqual } from "node:crypto";

export const accessTokenLifetimeSeconds = 15 * 60;

type AccessTokenPayload = Readonly<{
  aud: string;
  exp: number;
  iat: number;
  installationId: string;
  iss: "afterchime-api";
  type: "access";
  userId: string;
}>;

export type CreateAccessTokenOptions = Readonly<{
  audience: string;
  installationId: string;
  issuedAt?: Date;
  secret: string;
  userId: string;
}>;

export type VerifyAccessTokenOptions = Readonly<{
  audience: string;
  now?: Date;
  secret: string;
  token: string;
}>;

export type AccessTokenClaims = Readonly<{
  audience: string;
  installationId: string;
  userId: string;
}>;

function encode(value: unknown): string {
  return Buffer.from(JSON.stringify(value)).toString("base64url");
}

function signature(value: string, secret: string): string {
  return createHmac("sha256", secret).update(value).digest("base64url");
}

type TokenPayloadCandidate = Readonly<{
  aud?: unknown;
  exp?: unknown;
  iat?: unknown;
  installationId?: unknown;
  iss?: unknown;
  type?: unknown;
  userId?: unknown;
}>;

type TokenHeaderCandidate = Readonly<{
  alg?: unknown;
  typ?: unknown;
}>;

function isObject(value: unknown): value is object {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function isTokenPayloadCandidate(value: unknown): value is TokenPayloadCandidate {
  return isObject(value);
}

function isTokenHeaderCandidate(value: unknown): value is TokenHeaderCandidate {
  return isObject(value);
}

function parsePayload(encodedPayload: string): AccessTokenPayload {
  try {
    const value: unknown = JSON.parse(Buffer.from(encodedPayload, "base64url").toString("utf8"));
    if (
      !isTokenPayloadCandidate(value) ||
      typeof value.aud !== "string" ||
      typeof value.exp !== "number" ||
      typeof value.iat !== "number" ||
      typeof value.installationId !== "string" ||
      value.iss !== "afterchime-api" ||
      value.type !== "access" ||
      typeof value.userId !== "string"
    ) {
      throw new Error("token_invalid");
    }
    return value as AccessTokenPayload;
  } catch {
    throw new Error("token_invalid");
  }
}

export function createAccessToken(options: CreateAccessTokenOptions): string {
  const issuedAt = Math.floor((options.issuedAt ?? new Date()).getTime() / 1000);
  const header = encode({ alg: "HS256", typ: "JWT" });
  const payload = encode({
    aud: options.audience,
    exp: issuedAt + accessTokenLifetimeSeconds,
    iat: issuedAt,
    installationId: options.installationId,
    iss: "afterchime-api",
    type: "access",
    userId: options.userId,
  } satisfies AccessTokenPayload);
  const signingInput = `${header}.${payload}`;
  return `${signingInput}.${signature(signingInput, options.secret)}`;
}

export function verifyAccessToken(options: VerifyAccessTokenOptions): AccessTokenClaims {
  const [encodedHeader, encodedPayload, receivedSignature, ...rest] = options.token.split(".");
  if (!encodedHeader || !encodedPayload || !receivedSignature || rest.length > 0) {
    throw new Error("token_invalid");
  }

  let header: unknown;
  try {
    header = JSON.parse(Buffer.from(encodedHeader, "base64url").toString("utf8"));
  } catch {
    throw new Error("token_invalid");
  }
  if (!isTokenHeaderCandidate(header) || header.alg !== "HS256" || header.typ !== "JWT") {
    throw new Error("token_invalid");
  }

  const expectedSignature = signature(`${encodedHeader}.${encodedPayload}`, options.secret);
  const expected = Buffer.from(expectedSignature);
  const received = Buffer.from(receivedSignature);
  if (expected.length !== received.length || !timingSafeEqual(expected, received)) {
    throw new Error("token_invalid");
  }

  const payload = parsePayload(encodedPayload);
  if (payload.aud !== options.audience) {
    throw new Error("token_audience_invalid");
  }
  if (payload.exp <= Math.floor((options.now ?? new Date()).getTime() / 1000)) {
    throw new Error("token_expired");
  }

  return {
    audience: payload.aud,
    installationId: payload.installationId,
    userId: payload.userId,
  };
}

export function createRefreshSecret(): string {
  return randomBytes(32).toString("base64url");
}

export function deriveRegistrationRefreshSecret(idempotencyKey: string, pepper: string): string {
  return createHmac("sha256", pepper).update(`registration:${idempotencyKey}`).digest("base64url");
}

export function hashRefreshSecret(refreshSecret: string, pepper: string): string {
  return createHmac("sha256", pepper).update(refreshSecret).digest("hex");
}
