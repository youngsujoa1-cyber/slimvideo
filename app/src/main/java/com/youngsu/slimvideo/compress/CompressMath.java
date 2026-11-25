package com.youngsu.slimvideo.compress;

/**
 * 압축 계획을 세우는 순수 계산 로직.
 *
 * Android 프레임워크를 전혀 쓰지 않는 Java 클래스라서 기기 없이 JVM 에서 그대로 검증할 수 있다
 * (검증 프로그램: tools/logic-check/CompressMathCheck.java).
 *
 * 여기서 정하는 것
 *  - 출력 해상도(가로/세로, 짝수 정렬)와 회전 메타데이터 유지 여부
 *  - 출력 비트레이트 — v1.0.2 부터는 "해상도 기본값" 이 아니라 **원본 비트레이트 대비 배율** 로 정한다.
 *    (예전에는 1080p 기본값 × 0.82 라서, 이미 작게 압축된 휴대폰 영상은 원본과 비슷한 값이 나와
 *     6~11% 밖에 줄지 않았다.)
 *  - 오디오 처리 방식(없음 / AAC 그대로 복사 / AAC 로 다시 인코딩)
 *  - 예상 용량과 절감률
 */
public final class CompressMath {

    private CompressMath() {}

    // ---------------------------------------------------------------- 모드

    /** 화질 유지 — 원본 해상도 그대로, HEVC 기본 */
    public static final int MODE_KEEP_QUALITY = 0;
    /** 공유 균형 — 긴 변 기준 1080p 까지 */
    public static final int MODE_BALANCE = 1;
    /** 초경량 — 480p */
    public static final int MODE_LIGHT = 2;
    /** 목표 용량 맞춤 */
    public static final int MODE_TARGET = 3;

    // --------------------------------------------------------------- 코덱

    public static final int CODEC_HEVC = 0;
    public static final int CODEC_H264 = 1;
    /** 원본 코덱을 알 수 없을 때 */
    public static final int CODEC_UNKNOWN = -1;

    /** 목표 용량 모드에서 쓰는 영상 비트레이트 하한 */
    public static final int MIN_VIDEO_BITRATE = 200_000;
    /** 어떤 모드에서도 이보다 낮추지는 않는다 (화면이 완전히 뭉개지는 것을 막는 바닥) */
    public static final int MIN_VIDEO_BITRATE_HARD = 100_000;
    /** 영상 비트레이트 상한 */
    public static final int MAX_VIDEO_BITRATE = 45_000_000;

    /** AAC 오디오 비트레이트 (복사할 수 없을 때 다시 인코딩하는 값) */
    public static final int AUDIO_BITRATE = 128_000;
    /** 초경량 모드에서 AAC 로 다시 인코딩할 때 */
    public static final int AUDIO_BITRATE_LIGHT = 96_000;
    /** 무음일 때 */
    public static final int AUDIO_BITRATE_NONE = 0;

    /** 목표 용량에서 컨테이너 여유분(약 7%)을 뺀 실제 비트레이트 비율 */
    public static final double TARGET_USABLE_RATIO = 0.93;
    /** 예상 용량에 더하는 컨테이너 오버헤드 */
    public static final double ESTIMATE_OVERHEAD = 1.02;
    /** 목표 용량 모드에서 이 비트레이트보다 낮아지면 720p 로 낮춘다 */
    public static final int TARGET_MIN_COMFORT_BITRATE = 1_200_000;
    /** 이보다 낮아지면 480p 로 낮춘다 */
    public static final int TARGET_LOW_BITRATE = 450_000;

    // ------------------------------------------------- 원본 대비 비트레이트 배율

    /**
     * 모드별 "원본 영상 비트레이트 대비" 배율. H.264 원본을 H.265 로 다시 넣는 경우를 기준으로 한다.
     * H.265 는 같은 화질에서 H.264 의 60~70% 비트레이트로 충분하고,
     * 다시 인코딩하며 생기는 손실을 감안해 여유를 둔 값이다.
     */
    public static final double RATIO_KEEP_QUALITY = 0.52;
    public static final double RATIO_BALANCE = 0.42;
    public static final double RATIO_LIGHT = 0.28;

    /** 화질 하한 — 초당 픽셀 수 대비 비트(너무 낮으면 블록이 보인다) */
    private static final double FLOOR_BPP_HEVC = 0.008;
    private static final double FLOOR_BPP_H264 = 0.011;
    /** 화질 상한 — 기본 비트레이트(resolutionBitrate)의 이 배수를 넘기지 않는다 */
    private static final double CEILING_FACTOR = 1.6;
    /** 원본 비트레이트를 넘지 않게 잡는 상한 */
    private static final double SOURCE_CAP_FACTOR = 0.9;

    /** 계산 결과 묶음 — UI 는 이 값만 읽어 화면을 그린다. */
    public static final class Plan {
        public final int mode;
        public final int codec;
        public final int rotation;
        public final int outputWidth;
        public final int outputHeight;
        public final int displayWidth;
        public final int displayHeight;
        public final int outputFps;
        public final int videoBitrate;
        public final int audioBitrate;
        public final AudioStrategy audioStrategy;
        /** 오디오를 AAC 로 다시 인코딩하는지 (false 면 원본 AAC 샘플을 그대로 옮긴다) */
        public final boolean audioReencode;
        public final long estimatedBytes;
        public final double estimatedReductionPercent;
        public final boolean downscaled;
        /** 계획을 세울 때 쓴 원본 영상 비트레이트 (0 이면 알 수 없음) */
        public final int sourceVideoBitrate;

        Plan(int mode, int codec, int rotation, int outputWidth, int outputHeight,
             int displayWidth, int displayHeight, int outputFps, int videoBitrate,
             int audioBitrate, AudioStrategy audioStrategy, boolean audioReencode,
             long estimatedBytes, double estimatedReductionPercent, boolean downscaled,
             int sourceVideoBitrate) {
            this.mode = mode;
            this.codec = codec;
            this.rotation = rotation;
            this.outputWidth = outputWidth;
            this.outputHeight = outputHeight;
            this.displayWidth = displayWidth;
            this.displayHeight = displayHeight;
            this.outputFps = outputFps;
            this.videoBitrate = videoBitrate;
            this.audioBitrate = audioBitrate;
            this.audioStrategy = audioStrategy;
            this.audioReencode = audioReencode;
            this.estimatedBytes = estimatedBytes;
            this.estimatedReductionPercent = estimatedReductionPercent;
            this.downscaled = downscaled;
            this.sourceVideoBitrate = sourceVideoBitrate;
        }

        public boolean hasAudio() {
            return audioStrategy != AudioStrategy.NONE;
        }

        @Override
        public String toString() {
            return "Plan{mode=" + mode + ", codec=" + codec + ", out=" + outputWidth + "x" + outputHeight
                    + ", display=" + displayWidth + "x" + displayHeight + ", rotation=" + rotation
                    + ", fps=" + outputFps + ", vbps=" + videoBitrate + ", abps=" + audioBitrate
                    + ", audio=" + audioStrategy + ", reencode=" + audioReencode
                    + ", srcVbps=" + sourceVideoBitrate
                    + ", est=" + estimatedBytes
                    + ", reduction=" + String.format(java.util.Locale.US, "%.1f", estimatedReductionPercent) + "%}";
        }
    }

    // ------------------------------------------------------------ 기본 계산

    public static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static long clampLong(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 짝수로 맞춘다 (인코더는 홀수 해상도를 거부하는 경우가 많다) */
    public static int even(int value) {
        int v = (value / 2) * 2;
        return Math.max(2, v);
    }

    /** 회전값을 0 / 90 / 180 / 270 으로 정리한다 */
    public static int normalizeRotation(int rotation) {
        int r = rotation % 360;
        if (r < 0) r += 360;
        int snapped = Math.round(r / 90f) * 90;
        return snapped % 360;
    }

    /** 90/270 이면 가로·세로가 뒤집힌다 */
    public static boolean swapsSides(int rotation) {
        int r = normalizeRotation(rotation);
        return r == 90 || r == 270;
    }

    /** 저장된(코딩된) 해상도 → 사용자가 보는 해상도 */
    public static int[] displaySize(int codedWidth, int codedHeight, int rotation) {
        return swapsSides(rotation)
                ? new int[]{codedHeight, codedWidth}
                : new int[]{codedWidth, codedHeight};
    }

    /** 사용자가 보는 해상도 → 인코더에 넣을 해상도 (회전은 메타데이터로만 유지) */
    public static int[] codedSize(int displayWidth, int displayHeight, int rotation) {
        return swapsSides(rotation)
                ? new int[]{displayHeight, displayWidth}
                : new int[]{displayWidth, displayHeight};
    }

    /** 짧은 변을 maxShortSide 이하로 줄인다. 원본이 더 작으면 늘리지 않는다. */
    public static int[] scaleToShortSide(int width, int height, int maxShortSide) {
        if (width <= 0 || height <= 0) return new int[]{2, 2};
        int shortSide = Math.min(width, height);
        if (maxShortSide <= 0 || shortSide <= maxShortSide) {
            return new int[]{even(width), even(height)};
        }
        double ratio = (double) maxShortSide / shortSide;
        int w = Math.max(16, even((int) Math.round(width * ratio)));
        int h = Math.max(16, even((int) Math.round(height * ratio)));
        return new int[]{w, h};
    }

    /** 모드별 해상도 상한 (0 = 원본 유지) */
    public static int maxShortSideForMode(int mode) {
        switch (mode) {
            case MODE_BALANCE:
                return 1080;
            case MODE_LIGHT:
                return 480;
            case MODE_KEEP_QUALITY:
            case MODE_TARGET:
            default:
                return 0;
        }
    }

    public static int clampFps(int fps) {
        if (fps <= 0) return 30;
        return clampInt(fps, 15, 60);
    }

    /** 해상도·프레임레이트로 계산한 기준 비트레이트 (원본 비트레이트를 모를 때 쓰는 기본값) */
    public static int resolutionBitrate(int width, int height, int fps, int codec) {
        double bitsPerPixel = codec == CODEC_HEVC ? 0.082 : 0.112;
        double bps = (double) width * height * clampFps(fps) * bitsPerPixel;
        return clampInt((int) Math.round(bps), MIN_VIDEO_BITRATE, MAX_VIDEO_BITRATE);
    }

    /** 모드별 기본 비트레이트 배율 (원본 비트레이트를 모를 때만 쓴다) */
    public static double modeBitrateFactor(int mode) {
        switch (mode) {
            case MODE_KEEP_QUALITY:
                return 1.0;
            case MODE_BALANCE:
                return 0.72;
            case MODE_LIGHT:
                return 0.55;
            default:
                return 1.0;
        }
    }

    /** 화질 하한 — 해상도·프레임레이트로 보아 이보다 낮추면 화면이 뭉개진다 */
    public static int bitrateFloor(int width, int height, int fps, int codec) {
        double bpp = codec == CODEC_HEVC ? FLOOR_BPP_HEVC : FLOOR_BPP_H264;
        double bps = (double) width * height * clampFps(fps) * bpp;
        return clampInt((int) Math.round(bps), MIN_VIDEO_BITRATE_HARD, MAX_VIDEO_BITRATE);
    }

    /** 화질 상한 — 이 해상도에 어울리지 않게 큰 비트레이트를 잡지 않는다 */
    public static int bitrateCeiling(int width, int height, int fps, int codec) {
        int base = resolutionBitrate(width, height, fps, codec);
        return clampInt((int) Math.round(base * CEILING_FACTOR), MIN_VIDEO_BITRATE_HARD, MAX_VIDEO_BITRATE);
    }

    /** MIME → CODEC_* */
    public static int codecFromMime(String mime) {
        if (mime == null) return CODEC_UNKNOWN;
        String m = mime.toLowerCase(java.util.Locale.US);
        if (m.contains("hevc") || m.contains("hvc1") || m.contains("hev1") || m.contains("h265")) {
            return CODEC_HEVC;
        }
        if (m.contains("avc") || m.contains("h264")) return CODEC_H264;
        return CODEC_UNKNOWN;
    }

    /**
     * 같은 화질을 유지하는 데 필요한 비트레이트 배율 (원본 코덱 → 출력 코덱).
     * H.265 끼리는 1.5, H.264 → H.265 는 1.0, H.265 → H.264 는 1.9 배가 필요하다.
     */
    public static double codecRatioFactor(int sourceCodec, int targetCodec) {
        if (sourceCodec == CODEC_HEVC) {
            return targetCodec == CODEC_HEVC ? 1.5 : 1.9;
        }
        if (sourceCodec == CODEC_H264) {
            return targetCodec == CODEC_HEVC ? 1.0 : 1.22;
        }
        // 원본 코덱을 모른다 — H.265 를 고른 경우에만 이득을 본다
        return targetCodec == CODEC_HEVC ? 1.0 : 1.25;
    }

    /** 모드 + 원본/출력 코덱으로 정한 "원본 영상 비트레이트 대비" 배율 */
    public static double targetRatio(int mode, int sourceCodec, int targetCodec) {
        double base;
        switch (mode) {
            case MODE_KEEP_QUALITY:
                base = RATIO_KEEP_QUALITY;
                break;
            case MODE_BALANCE:
                base = RATIO_BALANCE;
                break;
            case MODE_LIGHT:
                base = RATIO_LIGHT;
                break;
            default:
                base = 1.0;
                break;
        }
        double ratio = base * codecRatioFactor(sourceCodec, targetCodec);
        return Math.max(0.15, Math.min(0.92, ratio));
    }

    /** 목표 용량에 맞춘 영상 비트레이트 */
    public static int bitrateForTarget(long targetBytes, long durationUs, int audioBitrate) {
        if (targetBytes <= 0 || durationUs <= 0) return MIN_VIDEO_BITRATE;
        double seconds = durationUs / 1_000_000d;
        double totalBits = targetBytes * 8d * TARGET_USABLE_RATIO;
        double bps = totalBits / seconds - audioBitrate;
        return clampInt((int) Math.round(bps), MIN_VIDEO_BITRATE, MAX_VIDEO_BITRATE);
    }

    public static long estimateBytes(int videoBitrate, int audioBitrate, long durationUs) {
        if (durationUs <= 0) return 0;
        double seconds = durationUs / 1_000_000d;
        double bytes = (videoBitrate + audioBitrate) / 8d * seconds * ESTIMATE_OVERHEAD;
        return Math.max(0L, Math.round(bytes));
    }

    public static double reductionPercent(long sourceBytes, long outputBytes) {
        if (sourceBytes <= 0) return 0d;
        double percent = (1d - (double) outputBytes / (double) sourceBytes) * 100d;
        return Math.max(0d, Math.min(99.9d, percent));
    }

    public static AudioStrategy audioStrategy(boolean hasAudio, String audioMime) {
        if (!hasAudio) return AudioStrategy.NONE;
        if (audioMime != null && audioMime.equals("audio/mp4a-latm")) return AudioStrategy.COPY;
        return AudioStrategy.TRANSCODE;
    }

    /** 이 계획에서 오디오를 다시 인코딩해야 하는지 */
    public static boolean shouldReencodeAudio(int mode, AudioStrategy strategy) {
        if (strategy == AudioStrategy.NONE) return false;
        if (strategy == AudioStrategy.TRANSCODE) return true;
        // 초경량은 오디오도 조금 줄여야 "가장 가벼운 파일" 이 된다
        return mode == MODE_LIGHT;
    }

    /** 무음 트랙이 없을 때 흘려보낼 오디오 비트레이트 */
    public static int audioBitrateFor(AudioStrategy strategy) {
        return strategy == AudioStrategy.NONE ? AUDIO_BITRATE_NONE : AUDIO_BITRATE;
    }

    /**
     * 계획에 쓸 오디오 비트레이트.
     *
     * AAC 를 그대로 옮길 때는 원본 오디오 비트레이트가 그대로 유지되므로, 예상 용량도
     * 원본 오디오 비트레이트로 계산해야 실제 결과와 맞는다.
     */
    public static int audioBitrateFor(int mode, AudioStrategy strategy, boolean reencode,
                                      int sourceAudioBitrate) {
        if (strategy == AudioStrategy.NONE) return AUDIO_BITRATE_NONE;
        if (reencode) {
            return mode == MODE_LIGHT ? AUDIO_BITRATE_LIGHT : AUDIO_BITRATE;
        }
        int source = sourceAudioBitrate > 0 ? sourceAudioBitrate : AUDIO_BITRATE;
        return clampInt(source, 16_000, 384_000);
    }

    /** 원본 파일에서 영상이 차지하는 비트레이트를 추정한다 (컨테이너에 값이 없을 때) */
    public static int sourceVideoBitrate(int sourceBitrate, long sourceBytes, long durationUs,
                                         int audioBitrate) {
        if (sourceBitrate > 0) return sourceBitrate;
        if (sourceBytes <= 0 || durationUs <= 0) return 0;
        double seconds = durationUs / 1_000_000d;
        int total = (int) Math.round(sourceBytes * 8d * 0.98d / seconds);
        return Math.max(0, total - Math.max(0, audioBitrate));
    }

    // ---------------------------------------------------------------- 계획

    /** 이전 시그니처 호환 — 원본 코덱/오디오 비트레이트를 모르는 경우 */
    public static Plan plan(int mode, int codec, int codedWidth, int codedHeight, int rotation,
                            int sourceFps, int sourceBitrate, long durationUs, long sourceBytes,
                            long targetBytes, boolean hasAudio, String audioMime) {
        return plan(mode, codec, codedWidth, codedHeight, rotation, sourceFps, sourceBitrate,
                durationUs, sourceBytes, targetBytes, hasAudio, audioMime, null, 0);
    }

    /**
     * 실제 압축 계획을 한 번에 만든다.
     *
     * @param mode              MODE_*
     * @param codec             CODEC_* (기기가 HEVC 를 지원할 때만 CODEC_HEVC 를 넘긴다)
     * @param codedWidth        원본 저장 해상도(가로)
     * @param codedHeight       원본 저장 해상도(세로)
     * @param rotation          원본 회전 메타데이터(0/90/180/270)
     * @param sourceFps         원본 프레임레이트(모르면 30)
     * @param sourceBitrate     원본 영상 비트레이트(모르면 0)
     * @param durationUs        길이(마이크로초)
     * @param sourceBytes       원본 파일 크기
     * @param targetBytes       목표 용량(MODE_TARGET 일 때만)
     * @param hasAudio          오디오 트랙 유무
     * @param audioMime         오디오 코덱 MIME
     * @param sourceVideoMime   원본 영상 코덱 MIME (모르면 null)
     * @param sourceAudioBitrate 원본 오디오 비트레이트 (모르면 0)
     */
    public static Plan plan(int mode, int codec, int codedWidth, int codedHeight, int rotation,
                            int sourceFps, int sourceBitrate, long durationUs, long sourceBytes,
                            long targetBytes, boolean hasAudio, String audioMime,
                            String sourceVideoMime, int sourceAudioBitrate) {
        int normalizedRotation = normalizeRotation(rotation);
        int[] display = displaySize(Math.max(2, codedWidth), Math.max(2, codedHeight), normalizedRotation);
        int maxShortSide = maxShortSideForMode(mode);
        int[] scaledDisplay = scaleToShortSide(display[0], display[1], maxShortSide);

        AudioStrategy strategy = audioStrategy(hasAudio, audioMime);
        boolean reencode = shouldReencodeAudio(mode, strategy);
        int audioBitrate = audioBitrateFor(mode, strategy, reencode, sourceAudioBitrate);

        int fps;
        if (mode == MODE_KEEP_QUALITY) {
            fps = clampFps(sourceFps);
        } else {
            fps = Math.min(clampFps(sourceFps), 30);
        }

        int sourceCodec = codecFromMime(sourceVideoMime);
        int srcVideoBitrate = sourceVideoBitrate(sourceBitrate, sourceBytes, durationUs, audioBitrate);

        int videoBitrate;
        if (mode == MODE_TARGET) {
            videoBitrate = bitrateForTarget(targetBytes, durationUs, audioBitrate);
            // 목표가 너무 작으면 해상도를 낮춰야 화질이 버틴다 (4K 5분을 10MB 로 맞추는 경우 등)
            if (videoBitrate < TARGET_LOW_BITRATE) {
                scaledDisplay = scaleToShortSide(display[0], display[1], 480);
            } else if (videoBitrate < TARGET_MIN_COMFORT_BITRATE) {
                scaledDisplay = scaleToShortSide(display[0], display[1], 720);
            }
        } else {
            int[] codedForBitrate = codedSize(scaledDisplay[0], scaledDisplay[1], normalizedRotation);
            if (srcVideoBitrate > 0) {
                // 원본 비트레이트 대비 배율 — 실제로 40~70% 가 줄어드는 값이 나온다
                videoBitrate = (int) Math.round(srcVideoBitrate * targetRatio(mode, sourceCodec, codec));
            } else {
                videoBitrate = (int) Math.round(
                        resolutionBitrate(codedForBitrate[0], codedForBitrate[1], fps, codec)
                                * modeBitrateFactor(mode));
            }
            videoBitrate = clampInt(videoBitrate, MIN_VIDEO_BITRATE_HARD, MAX_VIDEO_BITRATE);
        }

        boolean downscaled = scaledDisplay[0] != even(display[0]) || scaledDisplay[1] != even(display[1]);
        int[] coded = codedSize(scaledDisplay[0], scaledDisplay[1], normalizedRotation);

        if (mode != MODE_TARGET) {
            // 해상도에 맞는 화질 상한/하한 안으로 넣는다
            int ceiling = bitrateCeiling(coded[0], coded[1], fps, codec);
            int floor = bitrateFloor(coded[0], coded[1], fps, codec);
            if (srcVideoBitrate > 0) {
                // 원본보다 이미 작게 압축된 영상에 원본보다 높은 하한을 들이대지 않는다
                int relativeFloor = (int) Math.round(srcVideoBitrate * (mode == MODE_LIGHT ? 0.6 : 0.7));
                floor = Math.min(floor, relativeFloor);
            }
            floor = Math.max(floor, MIN_VIDEO_BITRATE_HARD);
            videoBitrate = Math.min(videoBitrate, ceiling);
            videoBitrate = Math.max(videoBitrate, Math.min(floor, ceiling));
            if (srcVideoBitrate > 0) {
                videoBitrate = Math.min(videoBitrate,
                        Math.max(MIN_VIDEO_BITRATE_HARD, (int) Math.round(srcVideoBitrate * SOURCE_CAP_FACTOR)));
            }
            videoBitrate = clampInt(videoBitrate, MIN_VIDEO_BITRATE_HARD, MAX_VIDEO_BITRATE);
        }

        // 원본 파일보다 큰 결과가 나오지 않게 — 예상 용량이 원본의 95% 를 넘으면 비트레이트를 낮춘다
        if (sourceBytes > 0 && durationUs > 0) {
            long budget = Math.max(1L, Math.round(sourceBytes * 0.95d));
            int maxByBudget = bitrateForTarget(budget, durationUs, audioBitrate);
            if (mode != MODE_TARGET) {
                videoBitrate = Math.min(videoBitrate, Math.max(maxByBudget, MIN_VIDEO_BITRATE_HARD));
            } else {
                videoBitrate = Math.min(videoBitrate, maxByBudget);
            }
        }

        long estimated = estimateBytes(videoBitrate, audioBitrate, durationUs);
        double reduction = reductionPercent(sourceBytes, estimated);

        return new Plan(mode, codec, normalizedRotation, coded[0], coded[1], scaledDisplay[0],
                scaledDisplay[1], fps, videoBitrate, audioBitrate, strategy, reencode,
                estimated, reduction, downscaled, srcVideoBitrate);
    }

    // ---------------------------------------------------------------- 표시

    /** 12.3 MB 처럼 읽기 좋은 크기 문자열 */
    public static String formatBytes(long bytes) {
        if (bytes < 0) return "-";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024d;
        if (kb < 1024d) return String.format(java.util.Locale.US, "%.0f KB", kb);
        double mb = kb / 1024d;
        if (mb < 1024d) return String.format(java.util.Locale.US, mb < 10d ? "%.1f MB" : "%.0f MB", mb);
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024d);
    }

    public static String formatDuration(long durationUs) {
        long totalSeconds = Math.max(0L, durationUs / 1_000_000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) {
            return String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(java.util.Locale.US, "%d:%02d", minutes, seconds);
    }
}
