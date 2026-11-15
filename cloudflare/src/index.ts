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
      container.start({
        image: container.images.base,
        instance: "basic",
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
    }

    await container.setInactivityTimeout(IDLE_TIMEOUT_MS);
    const port = container.getTcpPort(PORT);
    for (let attempt = 0; attempt < 100; attempt++) {
      try {
        const response = await port.fetch("http://container/api/v1/health", {
          signal: AbortSignal.timeout(1000),
        });
        await response.body?.cancel();
        if (response.ok) return;
      } catch {
        // Spring Boot may still be starting; retry only the readiness probe.
      }
      await scheduler.wait(200);
    }
    throw new Error("MARKYS Spring Boot container did not become ready on port 8080");
  }
}
