/** FFmpeg 对外稳定错误码；文案固定为简体中文，不依赖任何多语言运行时。 */
export type FfmpegErrorCode =
  | "FFMPEG_TOOL_UNUSABLE"
  | "FFMPEG_INSTALL_DIRECTORY_INVALID"
  | "FFMPEG_PLATFORM_UNSUPPORTED"
  | "FFMPEG_DOWNLOAD_FAILED"
  | "FFMPEG_CHECKSUM_MISMATCH"
  | "FFMPEG_INSTALL_ROLLBACK_FAILED"
  | "FFMPEG_CHAIN_METHOD_UNSUPPORTED"
  | "FFMPEG_OPERATION_METHOD_UNSUPPORTED";

/** ACT: 仍是原生 Error（保留 name、cause 与抛出位置）；用固定错误码替代已移除的多语言描述符。 */
export function ffmpegError(code: FfmpegErrorCode, message: string, options?: ErrorOptions): Error & { code: FfmpegErrorCode } {
  return Object.assign(new Error(message, options), { code });
}
