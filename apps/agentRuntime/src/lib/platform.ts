export function getRuntimePlatform() {
  return { os: process.platform, development: process.env.NODE_ENV === "dev" };
}
