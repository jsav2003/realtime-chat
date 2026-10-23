import type { NextConfig } from "next";

const apiUrl = process.env.API_URL ?? "http://localhost:8080";

const nextConfig: NextConfig = {
  // El navegador habla con /api y Next lo reenvía al backend: sin CORS.
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${apiUrl}/:path*` }];
  },
};

export default nextConfig;
