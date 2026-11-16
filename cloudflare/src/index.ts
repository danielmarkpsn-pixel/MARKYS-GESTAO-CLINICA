import { DurableObject } from "cloudflare:workers";

interface Env {
  MARKYS_API: DurableObjectNamespace<MarkysApiContainer>;
  DATABASE_URL: string;
  DATABASE_USERNAME: string;
  DATABASE_PASSWORD: string;
  MARKYS_PII_ENCRYPTION_KEY: string;
  MARKYS_PII_FINGERPRINT_KEY: string;
  MARKYS_MASTER_BOOTSTRAP_TOKEN?: string;
  R2_ENDPOINT: string;
  R2_BUCKET: string;
  R2_ACCESS_KEY_ID: string;
  R2_SECRET_ACCESS_KEY: string;
}

const PORT = 8080;
const IDLE_TIMEOUT_MS = 2 * 60 * 1000;

function missingConfiguration(env: Env): string[] {
  const required: Array<keyof Env> = [
    "DATABASE_URL",
    "DATABASE_USERNAME",
    "DATABASE_PASSWORD",
    "MARKYS_PII_ENCRYPTION_KEY",
    "MARKYS_PII_FINGERPRINT_KEY",
    "R2_ENDPOINT",
    "R2_BUCKET",
    "R2_ACCESS_KEY_ID",
    "R2_SECRET_ACCESS_KEY",
  ];
  return required.filter((key) => !env[key] || String(env[key]).trim() === "");
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const missing = missingConfiguration(env);
    if (missing.length) {
      return Response.json(
        { status: "not_configured", missing },
        { status: 503, headers: { "cache-control": "no-store" } },
      );
    }

    const backend = env.MARKYS_API.getByName("markys-api-singleton");
    return backend.fetch(request);
  },
};

export class MarkysApiContainer extends DurableObject<Env> {
  private starting?: Promise<void>;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    if (ctx.container?.running) {
      void ctx.blockConcurrencyWhile(() => ctx.container!.setInactivityTimeout(IDLE_TIMEOUT_MS));
    }
  }

  async fetch(request: Request): Promise<Response> {
    this.starting ??= this.startAndWaitForPort().finally(() => {
      this.starting = undefined;
    });
    await this.starting;

    const url = new URL(request.url);
    url.protocol = "http:";
    url.host = "container";
    const forwarded = new Request(url, request);
    forwarded.headers.delete("host");
    return this.ctx.container!.getTcpPort(PORT).fetch(forwarded);
  }

  private async startAndWaitForPort(): Promise<void> {
    const container = this.ctx.container!;

    if (!container.running) {
      try {
        // Wait until Cloudflare has accepted and completed the start operation
        // before configuring the timeout or requesting the container TCP port.
        await container.start({
          image: container.images.base,
          instance: "standard-1",
          enableInternet: true,
          env: {
            PORT: String(PORT),
            DATABASE_URL: this.env.DATABASE_URL,
            DATABASE_USERNAME: this.env.DATABASE_USERNAME,
            DATABASE_PASSWORD: this.env.DATABASE_PASSWORD,
            DB_POOL_SIZE: "3",
            SESSION_COOKIE_SECURE: "true",
            MARKYS_PII_ENCRYPTION_KEY: this.env.MARKYS_PII_ENCRYPTION_KEY,
            MARKYS_PII_FINGERPRINT_KEY: this.env.MARKYS_PII_FINGERPRINT_KEY,
            MARKYS_MASTER_BOOTSTRAP_TOKEN: this.env.MARKYS_MASTER_BOOTSTRAP_TOKEN ?? "",
            MARKYS_STORAGE_PROVIDER: "r2",
            R2_ENDPOINT: this.env.R2_ENDPOINT,
            R2_BUCKET: this.env.R2_BUCKET,
            R2_ACCESS_KEY_ID: this.env.R2_ACCESS_KEY_ID,
            R2_SECRET_ACCESS_KEY: this.env.R2_SECRET_ACCESS_KEY,
          },
        });
        console.info("MARKYS container start operation completed", {
          instance: "standard-1",
          port: PORT,
        });
      } catch (error) {
        const detail = error instanceof Error
          ? { message: error.message, stack: error.stack }
          : { message: String(error) };
        console.error("MARKYS container start failed", detail);
        throw new Error(`MARKYS container failed to start: ${detail.message}`, {
          cause: error,
        });
      }
    }

    await container.setInactivityTimeout(IDLE_TIMEOUT_MS);
    const port = container.getTcpPort(PORT);
    const maxAttempts = 120;
    const retryDelayMs = 1000;
    let lastStatus: number | undefined;
    let lastError: string | undefined;

    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        const response = await port.fetch("http://container/api/v1/health", {
          signal: AbortSignal.timeout(2000),
        });
        lastStatus = response.status;
        await response.body?.cancel();
        if (response.ok) {
          console.info("MARKYS Spring Boot container is ready", {
            port: PORT,
            attempt,
            elapsedMs: attempt * retryDelayMs,
          });
          return;
        }
        lastError = `Health endpoint returned HTTP ${response.status}`;
      } catch (error) {
        lastError = error instanceof Error ? error.message : String(error);
      }

      if (attempt < maxAttempts) {
        await scheduler.wait(retryDelayMs);
      }
    }

    console.error("MARKYS Spring Boot container readiness check failed", {
      port: PORT,
      attempts: maxAttempts,
      timeoutMs: maxAttempts * retryDelayMs,
      containerRunning: container.running,
      lastStatus,
      lastError,
    });
    throw new Error("MARKYS Spring Boot container did not become ready on port 8080", {
      cause: lastError ? new Error(lastError) : undefined,
    });
  }
}
