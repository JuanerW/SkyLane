// Generates src/types/api.ts from the backend OpenAPI spec (springdoc, D25).
// Usage: npm run gen:types              (spec at <NEXT_PUBLIC_API_BASE without /api/v1>/v3/api-docs)
//        npm run gen:types -- <url|file>
import { execFileSync } from "node:child_process";

const base = (process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080/api/v1").replace(/\/api\/v1\/?$/, "");
const source = process.argv[2] ?? `${base}/v3/api-docs`;
console.log(`Generating src/types/api.ts from ${source}`);
execFileSync("npx", ["openapi-typescript", source, "-o", "src/types/api.ts"], { stdio: "inherit", shell: true });
