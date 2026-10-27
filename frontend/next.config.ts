import path from "node:path";
import type { NextConfig } from "next";

const apiUrl = process.env.API_URL ?? "http://localhost:8080";

const nextConfig: NextConfig = {
  // La raíz es esta carpeta: sin esto Next toma un package-lock.json suelto de una carpeta superior.
  turbopack: { root: path.resolve(__dirname) },
  // El navegador habla con /api y Next lo reenvía al backend: sin CORS.
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${apiUrl}/:path*` }];
  },
};

export default nextConfig;
