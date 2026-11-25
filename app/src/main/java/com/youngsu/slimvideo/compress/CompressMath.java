package com.youngsu.slimvideo.compress;

/**
 * 압축 계획을 세우는 순수 계산 로직.
 *
 * Android 프레임워크를 전혀 쓰지 않는 Java 클래스라서 기기 없이 JVM 에서 그대로 검증할 수 있다
 * (검증 프로그램: tools/logic-check/CompressMathCheck.java).
 *
 * 여기서 정하는 것
 *  - 출력 해상도(가로/세로, 짝수 정렬)와 회전 메타데이터 유지 여부
 *  - 출력 비트레이트(모드별, 목표 용량별)
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

    /** 영상 비트레이트 하한 — 이보다 낮추면 화면이 뭉개진다 */
    public static final int MIN_VIDEO_BITRATE = 200_000;
    /** 영상 비트레이트 상한 */
    public static final int MAX_VIDEO_BITRATE = 45_000_000;
    /** AAC 오디오 비트레이트 */
    public static final int AUDIO_BITRATE = 128_000;
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
        public final long estimatedBytes;
        public final double estimatedReductionPercent;
        public final boolean downscaled;

        Plan(int mode, int codec, int rotation, int outputWidth, int outputHeight,
             int displayWidth, int displayHeight, int outputFps, int videoBitrate,
             int audioBitrate, AudioStrategy audioStrategy, long estimatedBytes,
             double estimatedReductionPercent, boolean downscaled) {
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
            this.estimatedBytes = estimatedBytes;
            this.estimatedReductionPercent = estimatedReductionPercent;
            this.downscaled = downscaled;
        }

        public boolean hasAudio() {
            return audioStrategy != AudioStrategy.NONE;
        }

        @Override
        public String toString() {
            return "Plan{mode=" + mode + ", codec=" + codec + ", out=" + outputWidth + "x" + outputHeight
                    + ", display=" + displayWidth + "x" + displayHeight + ", rotation=" + rotation
                    + ", fps=" + outputFps + ", vbps=" + videoBitrate + ", abps=" + audioBitrate
                    + ", audio=" + audioStrategy + ", est=" + estimatedBytes
                    + ", reduction=" + String.format(java.util.Locale.US, "%.1f", estimatedReductionPercent) + "%}";
        }
    }

    // ------------------------------------------------------------ 기본 계산

    public static int clampInt(int value, int min, int max) {
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

    /** 해상도·프레임레이트로 계산한 기준 비트레이트 */
    public static int resolutionBitrate(int width, int height, int fps, int codec) {
        double bitsPerPixel = codec == CODEC_HEVC ? 0.082 : 0.112;
        double bps = (double) width * height * clampFps(fps) * bitsPerPixel;
        return clampInt((int) Math.round(bps), MIN_VIDEO_BITRATE, MAX_VIDEO_BITRATE);
    }

    /** 모드별 기본 비트레이트 배율 */
    public static double modeBitrateFactor(int mode) {
        switch (mode) {
            case MODE_KEEP_QUALITY:
                return 1.0;
            case MODE_BALANCE:
                return 0.82;
            case MODE_LIGHT:
                return 0.75;
            default:
                return 1.0;
        }
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

    /** 무음 트랙이 없을 때 흘려보낼 오디오 비트레이트 */
    public static int audioBitrateFor(AudioStrategy strategy) {
        return strategy == AudioStrategy.NONE ? AUDIO_BITRATE_NONE : AUDIO_BITRATE;
    }

    // ---------------------------------------------------------------- 계획

    /**
     * 실제 압축 계획을 한 번에 만든다.
     *
     * @param mode            MODE_*
     * @param codec           CODEC_* (기기가 HEVC 를 지원할 때만 CODEC_HEVC 를 넘긴다)
     * @param codedWidth      원본 저장 해상도(가로)
     * @param codedHeight     원본 저장 해상도(세로)
     * @param rotation        원본 회전 메타데이터(0/90/180/270)
     * @param sourceFps       원본 프레임레이트(모르면 30)
     * @param sourceBitrate   원본 영상 비트레이트(모르면 0)
     * @param durationUs      길이(마이크로초)
     * @param sourceBytes     원본 파일 크기
     * @param targetBytes     목표 용량(MODE_TARGET 일 때만)
     * @param hasAudio        오디오 트랙 유무
     * @param audioMime       오디오 코덱 MIME
     */
    public static Plan plan(int mode, int codec, int codedWidth, int codedHeight, int rotation,
                            int sourceFps, int sourceBitrate, long durationUs, long sourceBytes,
                            long targetBytes, boolean hasAudio, String audioMime) {
        int normalizedRotation = normalizeRotation(rotation);
        int[] display = displaySize(Math.max(2, codedWidth), Math.max(2, codedHeight), normalizedRotation);
        int maxShortSide = maxShortSideForMode(mode);
        int[] scaledDisplay = scaleToShortSide(display[0], display[1], maxShortSide);

        AudioStrategy strategy = audioStrategy(hasAudio, audioMime);
        int audioBitrate = audioBitrateFor(strategy);

        int fps;
        if (mode == MODE_KEEP_QUALITY) {
            fps = clampFps(sourceFps);
        } else {
            fps = Math.min(clampFps(sourceFps), 30);
        }

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
            int base = resolutionBitrate(codedForBitrate[0], codedForBitrate[1], fps, codec);
            videoBitrate = (int) Math.round(base * modeBitrateFactor(mode));
        }

        boolean downscaled = scaledDisplay[0] != even(display[0]) || scaledDisplay[1] != even(display[1]);
        int[] coded = codedSize(scaledDisplay[0], scaledDisplay[1], normalizedRotation);
        // 원본 비트레이트를 모르는 컨테이너(MKV·AVI 등)는 파일 크기와 길이로 추정한다
        int effectiveSourceBitrate = sourceBitrate;
        if (effectiveSourceBitrate <= 0 && sourceBytes > 0 && durationUs > 0) {
            double seconds = durationUs / 1_000_000d;
            effectiveSourceBitrate = (int) Math.max(
                    0L, Math.round(sourceBytes * 8d * 0.98d / seconds - audioBitrate));
        }
        // 원본보다 크게 잡으면 "압축"이 아니게 된다 — 원본 비트레이트의 88% 를 넘지 않게 한다
        if (effectiveSourceBitrate > 0) {
            int cap = Math.max(120_000, (int) Math.round(effectiveSourceBitrate * 0.88));
            videoBitrate = Math.min(videoBitrate, cap);
        }
        videoBitrate = clampInt(videoBitrate, 100_000, MAX_VIDEO_BITRATE);

        long estimated = estimateBytes(videoBitrate, audioBitrate, durationUs);
        if (sourceBytes > 0 && estimated > sourceBytes) {
            // 계산상 원본보다 커지면 원본을 넘지 않게 다시 맞춘다
            estimated = Math.max(1L, sourceBytes - Math.max(1L, sourceBytes / 20L));
        }
        double reduction = reductionPercent(sourceBytes, estimated);

        return new Plan(mode, codec, normalizedRotation, coded[0], coded[1], scaledDisplay[0],
                scaledDisplay[1], fps, videoBitrate, audioBitrate, strategy, estimated, reduction, downscaled);
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
