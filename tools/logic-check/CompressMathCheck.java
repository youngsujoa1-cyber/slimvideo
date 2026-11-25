import com.youngsu.slimvideo.compress.AudioStrategy;
import com.youngsu.slimvideo.compress.CompressMath;

/**
 * 앱에 들어가는 그대로의 CompressMath 를 JVM 에서 검증하는 프로그램.
 *
 *   javac -d /tmp/slimcheck app/src/main/java/com/youngsu/slimvideo/compress/*.java \
 *         tools/logic-check/CompressMathCheck.java
 *   java -cp /tmp/slimcheck CompressMathCheck
 *
 * 기기·에뮬레이터 없이 비트레이트/목표 용량/해상도/회전/오디오 분기를 확인한다.
 */
public class CompressMathCheck {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        rotationRules();
        scalingRules();
        codecBitrateRules();
        targetSizeRules();
        audioRules();
        endToEndPlans();
        realDeviceCases();
        formatting();

        System.out.println();
        System.out.println("RESULT passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ 회전

    private static void rotationRules() {
        eq("normalize 0", 0, CompressMath.normalizeRotation(0));
        eq("normalize 90", 90, CompressMath.normalizeRotation(90));
        eq("normalize 270", 270, CompressMath.normalizeRotation(270));
        eq("normalize 360 -> 0", 0, CompressMath.normalizeRotation(360));
        eq("normalize -90 -> 270", 270, CompressMath.normalizeRotation(-90));
        eq("normalize 89 -> 90", 90, CompressMath.normalizeRotation(89));
        eq("normalize 44 -> 0", 0, CompressMath.normalizeRotation(44));

        truth("90 swaps", CompressMath.swapsSides(90));
        truth("270 swaps", CompressMath.swapsSides(270));
        truth("0 does not swap", !CompressMath.swapsSides(0));
        truth("180 does not swap", !CompressMath.swapsSides(180));

        // 아이폰 세로 영상: 저장은 1920x1080, 회전 90 -> 보이는 건 1080x1920
        int[] display = CompressMath.displaySize(1920, 1080, 90);
        eq("display w (rot90)", 1080, display[0]);
        eq("display h (rot90)", 1920, display[1]);

        // 다시 인코더에 넣을 때는 저장 방향으로 되돌린다 (회전은 메타데이터로 유지)
        int[] coded = CompressMath.codedSize(1080, 1920, 90);
        eq("coded w (rot90)", 1920, coded[0]);
        eq("coded h (rot90)", 1080, coded[1]);

        int[] same = CompressMath.codedSize(1920, 1080, 180);
        eq("coded w (rot180)", 1920, same[0]);
        eq("coded h (rot180)", 1080, same[1]);
    }

    // ---------------------------------------------------------------- 해상도

    private static void scalingRules() {
        int[] p720 = CompressMath.scaleToShortSide(1920, 1080, 720);
        eq("1080p -> 720p w", 1280, p720[0]);
        eq("1080p -> 720p h", 720, p720[1]);

        int[] p480 = CompressMath.scaleToShortSide(1920, 1080, 480);
        eq("1080p -> 480p w", 852, p480[0]);
        eq("1080p -> 480p h", 480, p480[1]);

        // 세로 영상도 짧은 변 기준
        int[] portrait = CompressMath.scaleToShortSide(1080, 1920, 720);
        eq("portrait 1080x1920 -> 720 short", 720, Math.min(portrait[0], portrait[1]));
        eq("portrait ratio kept", 1280, Math.max(portrait[0], portrait[1]));

        // 원본이 더 작으면 늘리지 않는다
        int[] small = CompressMath.scaleToShortSide(640, 360, 1080);
        eq("no upscale w", 640, small[0]);
        eq("no upscale h", 360, small[1]);

        // 홀수 해상도는 짝수로
        int[] odd = CompressMath.scaleToShortSide(1281, 721, 0);
        eq("odd w evened", 1280, odd[0]);
        eq("odd h evened", 720, odd[1]);

        eq("even 3", 2, CompressMath.even(3));
        eq("even 2", 2, CompressMath.even(2));
        eq("even 1", 2, CompressMath.even(1));

        eq("mode keep short side", 0, CompressMath.maxShortSideForMode(CompressMath.MODE_KEEP_QUALITY));
        eq("mode balance short side", 1080, CompressMath.maxShortSideForMode(CompressMath.MODE_BALANCE));
        eq("mode light short side", 480, CompressMath.maxShortSideForMode(CompressMath.MODE_LIGHT));
    }

    // ------------------------------------------------------------ 비트레이트

    private static void codecBitrateRules() {
        int h264 = CompressMath.resolutionBitrate(1920, 1080, 30, CompressMath.CODEC_H264);
        int hevc = CompressMath.resolutionBitrate(1920, 1080, 30, CompressMath.CODEC_HEVC);
        truth("HEVC bitrate below H.264 at same resolution", hevc < h264);
        truth("1080p30 H.264 in sane range (" + h264 + ")", h264 > 4_000_000 && h264 < 12_000_000);
        truth("1080p30 HEVC in sane range (" + hevc + ")", hevc > 3_000_000 && hevc < 9_000_000);

        int tiny = CompressMath.resolutionBitrate(320, 240, 15, CompressMath.CODEC_H264);
        truth("tiny resolution respects floor (" + tiny + ")", tiny >= CompressMath.MIN_VIDEO_BITRATE);

        eq("fps floor", 15, CompressMath.clampFps(1));
        eq("fps ceiling", 60, CompressMath.clampFps(240));
        eq("fps default", 30, CompressMath.clampFps(0));

        // 4K30 H.264 는 상한(45Mbps)을 넘지 않는다
        int uhd = CompressMath.resolutionBitrate(3840, 2160, 30, CompressMath.CODEC_H264);
        truth("4K capped (" + uhd + ")", uhd <= CompressMath.MAX_VIDEO_BITRATE);

        // 화질 하한/상한
        int floor1080 = CompressMath.bitrateFloor(1920, 1080, 30, CompressMath.CODEC_HEVC);
        int ceiling1080 = CompressMath.bitrateCeiling(1920, 1080, 30, CompressMath.CODEC_HEVC);
        truth("1080p HEVC floor sane (" + floor1080 + ")",
                floor1080 >= CompressMath.MIN_VIDEO_BITRATE_HARD && floor1080 < hevc);
        truth("1080p HEVC ceiling above floor (" + ceiling1080 + ")", ceiling1080 > floor1080);
        truth("ceiling above default bitrate", ceiling1080 > hevc);

        // 코덱 판별
        eq("mime hevc", CompressMath.CODEC_HEVC, CompressMath.codecFromMime("video/hevc"));
        eq("mime avc", CompressMath.CODEC_H264, CompressMath.codecFromMime("video/avc"));
        eq("mime vp9 unknown", CompressMath.CODEC_UNKNOWN,
                CompressMath.codecFromMime("video/x-vnd.on2.vp9"));
        eq("mime null unknown", CompressMath.CODEC_UNKNOWN, CompressMath.codecFromMime(null));

        // 같은 화질을 유지하는 데 필요한 배율
        truth("H.264 -> H.265 needs fewer bits",
                CompressMath.codecRatioFactor(CompressMath.CODEC_H264, CompressMath.CODEC_HEVC)
                        < CompressMath.codecRatioFactor(CompressMath.CODEC_HEVC, CompressMath.CODEC_HEVC));
        truth("H.265 -> H.264 needs the most bits",
                CompressMath.codecRatioFactor(CompressMath.CODEC_HEVC, CompressMath.CODEC_H264)
                        > CompressMath.codecRatioFactor(CompressMath.CODEC_HEVC, CompressMath.CODEC_HEVC));
        truth("light ratio below balance",
                CompressMath.targetRatio(CompressMath.MODE_LIGHT, CompressMath.CODEC_H264,
                        CompressMath.CODEC_HEVC)
                        < CompressMath.targetRatio(CompressMath.MODE_BALANCE, CompressMath.CODEC_H264,
                        CompressMath.CODEC_HEVC));
        truth("keep quality ratio below 1",
                CompressMath.targetRatio(CompressMath.MODE_KEEP_QUALITY, CompressMath.CODEC_H264,
                        CompressMath.CODEC_HEVC) < 0.8);
    }

    // ------------------------------------------------------------ 목표 용량

    private static void targetSizeRules() {
        long duration = 60_000_000L; // 60초
        long target25mb = 25L * 1024 * 1024;

        int bps = CompressMath.bitrateForTarget(target25mb, duration, CompressMath.AUDIO_BITRATE);
        // 25MB / 60s = 3.49 Mbps 총합, 오디오 128k 를 뺀 나머지
        truth("25MB/60s video bitrate (" + bps + ")", bps > 3_100_000 && bps < 3_450_000);

        long estimated = CompressMath.estimateBytes(bps, CompressMath.AUDIO_BITRATE, duration);
        truth("estimate fits target (" + CompressMath.formatBytes(estimated) + ")",
                estimated <= target25mb);

        // 아주 짧은 영상 + 작은 목표: 하한 아래로는 내려가지 않는다
        int floored = CompressMath.bitrateForTarget(300L * 1024, 10_000_000L, CompressMath.AUDIO_BITRATE);
        eq("target bitrate floor", CompressMath.MIN_VIDEO_BITRATE, floored);

        // 긴 영상 + 큰 목표: 상한을 넘지 않는다
        int capped = CompressMath.bitrateForTarget(4L * 1024 * 1024 * 1024, 3_600_000_000L, CompressMath.AUDIO_BITRATE);
        truth("target bitrate capped (" + capped + ")", capped <= CompressMath.MAX_VIDEO_BITRATE);

        // 잘못된 입력은 하한으로
        eq("zero duration", CompressMath.MIN_VIDEO_BITRATE,
                CompressMath.bitrateForTarget(target25mb, 0, CompressMath.AUDIO_BITRATE));

        eq("reduction 100 -> 25", 75d, round1(CompressMath.reductionPercent(100L, 25L)));
        eq("reduction never negative", 0d, round1(CompressMath.reductionPercent(100L, 130L)));
        truth("reduction under 100", CompressMath.reductionPercent(100L, 1L) < 100d);

        // 원본 비트레이트 추정
        eq("given bitrate wins", 5_000_000,
                CompressMath.sourceVideoBitrate(5_000_000, 100L, 10_000_000L, 128_000));
        int derived = CompressMath.sourceVideoBitrate(0, 10L * 1024 * 1024, 10_000_000L, 128_000);
        truth("derived bitrate (" + derived + ")",
                derived > 7_800_000 && derived < 8_200_000);
        eq("no data = 0", 0, CompressMath.sourceVideoBitrate(0, 0, 0, 0));
    }

    // ---------------------------------------------------------------- 오디오

    private static void audioRules() {
        eq("no track", AudioStrategy.NONE, CompressMath.audioStrategy(false, "audio/mp4a-latm"));
        eq("aac copies", AudioStrategy.COPY, CompressMath.audioStrategy(true, "audio/mp4a-latm"));
        eq("opus transcodes", AudioStrategy.TRANSCODE, CompressMath.audioStrategy(true, "audio/opus"));
        eq("vorbis transcodes", AudioStrategy.TRANSCODE, CompressMath.audioStrategy(true, "audio/vorbis"));
        eq("amr transcodes", AudioStrategy.TRANSCODE, CompressMath.audioStrategy(true, "audio/3gpp"));
        eq("null mime transcodes", AudioStrategy.TRANSCODE, CompressMath.audioStrategy(true, null));

        eq("aac bitrate", CompressMath.AUDIO_BITRATE, CompressMath.audioBitrateFor(AudioStrategy.COPY));
        eq("none bitrate", 0, CompressMath.audioBitrateFor(AudioStrategy.NONE));

        // 다시 인코딩하는 경우
        truth("opus reencodes", CompressMath.shouldReencodeAudio(
                CompressMath.MODE_BALANCE, AudioStrategy.TRANSCODE));
        truth("light reencodes aac", CompressMath.shouldReencodeAudio(
                CompressMath.MODE_LIGHT, AudioStrategy.COPY));
        truth("balance copies aac", !CompressMath.shouldReencodeAudio(
                CompressMath.MODE_BALANCE, AudioStrategy.COPY));
        truth("silent never reencodes", !CompressMath.shouldReencodeAudio(
                CompressMath.MODE_LIGHT, AudioStrategy.NONE));

        // 복사할 때는 원본 오디오 비트레이트로 예상 용량을 계산한다
        eq("copy uses source audio bitrate", 64_000, CompressMath.audioBitrateFor(
                CompressMath.MODE_BALANCE, AudioStrategy.COPY, false, 64_000));
        eq("copy unknown falls back", CompressMath.AUDIO_BITRATE, CompressMath.audioBitrateFor(
                CompressMath.MODE_BALANCE, AudioStrategy.COPY, false, 0));
        eq("light reencode bitrate", CompressMath.AUDIO_BITRATE_LIGHT, CompressMath.audioBitrateFor(
                CompressMath.MODE_LIGHT, AudioStrategy.COPY, true, 192_000));
        eq("silent bitrate is zero", 0, CompressMath.audioBitrateFor(
                CompressMath.MODE_BALANCE, AudioStrategy.NONE, false, 128_000));
    }

    // ---------------------------------------------------------- 전체 계획

    private static void endToEndPlans() {
        long srcBytes = 120L * 1024 * 1024;  // 120MB
        long duration = 120_000_000L;        // 2분
        int srcBitrate = 8_000_000;

        CompressMath.Plan quality = CompressMath.plan(
                CompressMath.MODE_KEEP_QUALITY, CompressMath.CODEC_HEVC,
                1920, 1080, 0, 30, srcBitrate, duration, srcBytes, 0, true, "audio/mp4a-latm",
                "video/avc", 128_000);
        eq("quality keeps width", 1920, quality.outputWidth);
        eq("quality keeps height", 1080, quality.outputHeight);
        eq("quality audio copy", AudioStrategy.COPY, quality.audioStrategy);
        truth("quality shrinks (" + quality.estimatedReductionPercent + "%)",
                quality.estimatedReductionPercent > 30d);
        truth("quality not bigger than source", quality.estimatedBytes < srcBytes);

        CompressMath.Plan light = CompressMath.plan(
                CompressMath.MODE_LIGHT, CompressMath.CODEC_HEVC,
                1920, 1080, 0, 30, srcBitrate, duration, srcBytes, 0, true, "audio/mp4a-latm",
                "video/avc", 128_000);
        eq("light 480 short side", 480, light.outputHeight);
        eq("light width 852", 852, light.outputWidth);
        truth("light smaller than quality", light.estimatedBytes < quality.estimatedBytes);
        truth("light is a downscale", light.downscaled);
        truth("light reencodes audio", light.audioReencode);

        // 아이폰 세로 영상(저장 1920x1080, 회전 90) — 보이는 방향 기준 1080p
        CompressMath.Plan portrait = CompressMath.plan(
                CompressMath.MODE_BALANCE, CompressMath.CODEC_H264,
                1920, 1080, 90, 30, 12_000_000, 30_000_000L, 40L * 1024 * 1024, 0, true, "audio/mp4a-latm",
                "video/avc", 128_000);
        eq("portrait display w", 1080, portrait.displayWidth);
        eq("portrait display h", 1920, portrait.displayHeight);
        eq("portrait coded w", 1920, portrait.outputWidth);
        eq("portrait coded h", 1080, portrait.outputHeight);
        eq("portrait keeps rotation", 90, portrait.rotation);

        // 무음 영상
        CompressMath.Plan silent = CompressMath.plan(
                CompressMath.MODE_BALANCE, CompressMath.CODEC_HEVC,
                1280, 720, 0, 60, 0, 10_000_000L, 20L * 1024 * 1024, 0, false, null);
        eq("silent audio none", AudioStrategy.NONE, silent.audioStrategy);
        eq("silent audio bitrate 0", 0, silent.audioBitrate);
        eq("60fps balanced drops to 30", 30, silent.outputFps);
        truth("silent estimated below source", silent.estimatedBytes < 20L * 1024 * 1024);

        // 목표 용량 모드
        long target = 10L * 1024 * 1024;
        CompressMath.Plan targetPlan = CompressMath.plan(
                CompressMath.MODE_TARGET, CompressMath.CODEC_H264,
                1920, 1080, 0, 30, srcBitrate, 90_000_000L, srcBytes, target, true, "audio/mp4a-latm");
        truth("target estimate at or below target (" + CompressMath.formatBytes(targetPlan.estimatedBytes) + ")",
                targetPlan.estimatedBytes <= target);
        // 90초를 10MB 로 맞추려면 720p 가 한계 — 해상도를 낮춰 목표를 지킨다
        eq("tight target short side", 720, Math.min(targetPlan.outputWidth, targetPlan.outputHeight));
        truth("tight target fits", targetPlan.estimatedBytes <= target);

        // 아주 작은 목표 용량 + 긴 4K 영상 → 해상도를 낮춰 목표를 지킨다
        long smallTarget = 10L * 1024 * 1024;
        CompressMath.Plan tinyFit = CompressMath.plan(
                CompressMath.MODE_TARGET, CompressMath.CODEC_HEVC,
                3840, 2160, 0, 30, 40_000_000, 300_000_000L, 900L * 1024 * 1024, smallTarget,
                true, "audio/mp4a-latm");
        truth("tiny target downscales (" + tinyFit.outputWidth + "x" + tinyFit.outputHeight + ")",
                Math.min(tinyFit.outputWidth, tinyFit.outputHeight) <= 720);
        // 5분 4K 를 10MB 로 줄이는 극단값 — 480p 하한(200kbps)에 걸려 조금 넘을 수 있다
        truth("tiny target downscales to 480p", Math.min(tinyFit.outputWidth, tinyFit.outputHeight) <= 480);
        truth("tiny target near target (" + CompressMath.formatBytes(tinyFit.estimatedBytes) + ")",
                tinyFit.estimatedBytes <= smallTarget * 1.3);

        // 넉넉한 목표면 해상도를 그대로 둔다
        CompressMath.Plan comfyFit = CompressMath.plan(
                CompressMath.MODE_TARGET, CompressMath.CODEC_HEVC,
                1920, 1080, 0, 30, 8_000_000, 60_000_000L, 120L * 1024 * 1024, 25L * 1024 * 1024,
                true, "audio/mp4a-latm");
        eq("comfy target keeps width", 1920, comfyFit.outputWidth);
        eq("comfy target keeps height", 1080, comfyFit.outputHeight);

        // 원본 비트레이트가 아주 낮으면 억지로 키우지 않는다
        CompressMath.Plan lowSource = CompressMath.plan(
                CompressMath.MODE_LIGHT, CompressMath.CODEC_H264,
                1280, 720, 0, 30, 400_000, 60_000_000L, 3L * 1024 * 1024, 0, true, "audio/mp4a-latm");
        truth("low source not inflated (" + lowSource.videoBitrate + ")", lowSource.videoBitrate <= 400_000);

        // 원본 비트레이트를 모르는 파일도 크기·길이로 추정해 원본보다 커지지 않는다
        CompressMath.Plan unknownBitrate = CompressMath.plan(
                CompressMath.MODE_KEEP_QUALITY, CompressMath.CODEC_HEVC,
                1920, 1080, 0, 30, 0, 120_000_000L, 20L * 1024 * 1024, 0, true, "audio/mp4a-latm");
        truth("unknown bitrate estimated and capped (" + unknownBitrate.videoBitrate + ")",
                unknownBitrate.videoBitrate <= 1_300_000);
        truth("unknown bitrate output smaller than source",
                unknownBitrate.estimatedBytes < 20L * 1024 * 1024);

        // 이미 작은 영상: 예상 용량이 원본을 넘지 않는다
        CompressMath.Plan alreadySmall = CompressMath.plan(
                CompressMath.MODE_TARGET, CompressMath.CODEC_H264,
                640, 360, 0, 30, 0, 5_000_000L, 500_000L, 50L * 1024 * 1024, false, null);
        truth("never estimates larger than source", alreadySmall.estimatedBytes <= 500_000L);
    }

    // ------------------------------------------------- 실제 기기에서 실패한 사례

    /**
     * 2026-11-25 사용자가 실제 기기(Samsung, Android 14/15)에서 변환을 시도한 두 영상.
     * v1.0.1 은 두 경우 모두 "예상 6~11% 절감" 이라고 보여 주고 실제로는 실패했다.
     */
    private static void realDeviceCases() {
        // 1) 돈키호테플로라.mp4 — 1830x1024 · 8:05 · H.264 · 84MB · AAC (공유 균형 · H.265)
        long bigBytes = 84L * 1024 * 1024;
        long bigDuration = 485_000_000L;
        CompressMath.Plan balance = CompressMath.plan(
                CompressMath.MODE_BALANCE, CompressMath.CODEC_HEVC,
                1830, 1024, 0, 30, 1_320_000, bigDuration, bigBytes, 0, true, "audio/mp4a-latm",
                "video/avc", 128_000);
        truth("1830x1024 keeps size", balance.outputWidth == 1830 && balance.outputHeight == 1024);
        truth("84MB balance saves 40~70% (" + fmt(balance.estimatedReductionPercent) + "%)",
                balance.estimatedReductionPercent >= 40d && balance.estimatedReductionPercent <= 70d);

        CompressMath.Plan bigQuality = CompressMath.plan(
                CompressMath.MODE_KEEP_QUALITY, CompressMath.CODEC_HEVC,
                1830, 1024, 0, 30, 1_320_000, bigDuration, bigBytes, 0, true, "audio/mp4a-latm",
                "video/avc", 128_000);
        truth("84MB keep quality saves 35~70% (" + fmt(bigQuality.estimatedReductionPercent) + "%)",
                bigQuality.estimatedReductionPercent >= 35d && bigQuality.estimatedReductionPercent <= 70d);

        CompressMath.Plan bigLight = CompressMath.plan(
                CompressMath.MODE_LIGHT, CompressMath.CODEC_HEVC,
                1830, 1024, 0, 30, 1_320_000, bigDuration, bigBytes, 0, true, "audio/mp4a-latm",
                "video/avc", 128_000);
        truth("84MB light saves 50~80% (" + fmt(bigLight.estimatedReductionPercent) + "%)",
                bigLight.estimatedReductionPercent >= 50d && bigLight.estimatedReductionPercent <= 80d);
        truth("84MB light is 480p", Math.min(bigLight.outputWidth, bigLight.outputHeight) == 480);

        // 2) 2024 ...mp4 — 1280x590 · 3:04 · H.264 · 6.8MB · AAC (초경량 1040x480 · H.265)
        long smallBytes = 7_140_000L;
        long smallDuration = 184_000_000L;
        CompressMath.Plan tiny = CompressMath.plan(
                CompressMath.MODE_LIGHT, CompressMath.CODEC_HEVC,
                1280, 590, 0, 30, 0, smallDuration, smallBytes, 0, true, "audio/mp4a-latm",
                "video/avc", 128_000);
        eq("1280x590 -> 480p height", 480, tiny.outputHeight);
        eq("1280x590 -> 480p width", 1040, tiny.outputWidth);
        truth("6.8MB light saves more than 25% (" + fmt(tiny.estimatedReductionPercent) + "%)",
                tiny.estimatedReductionPercent > 25d);
        truth("6.8MB light estimate below source", tiny.estimatedBytes < smallBytes);

        // 이 영상은 이미 310kbps 로 아주 작게 압축되어 있다 — 화질을 지키는 모드는
        // 억지로 원본보다 키우지 않는다
        CompressMath.Plan tinyBalance = CompressMath.plan(
                CompressMath.MODE_BALANCE, CompressMath.CODEC_HEVC,
                1280, 590, 0, 30, 0, smallDuration, smallBytes, 0, true, "audio/mp4a-latm",
                "video/avc", 128_000);
        truth("tiny source balance estimate below source",
                tinyBalance.estimatedBytes < smallBytes);
        truth("tiny source balance video bitrate not above source (" + tinyBalance.videoBitrate + ")",
                tinyBalance.videoBitrate <= 310_000);
    }

    private static void formatting() {
        eq("format 512 B", "512 B", CompressMath.formatBytes(512));
        eq("format 1 KB", "1 KB", CompressMath.formatBytes(1024));
        eq("format 1.5 MB", "1.5 MB", CompressMath.formatBytes((long) (1.5 * 1024 * 1024)));
        eq("format 125 MB", "125 MB", CompressMath.formatBytes(125L * 1024 * 1024));
        eq("format 2.50 GB", "2.50 GB", CompressMath.formatBytes((long) (2.5 * 1024 * 1024 * 1024)));

        eq("duration 0:07", "0:07", CompressMath.formatDuration(7_000_000L));
        eq("duration 3:05", "3:05", CompressMath.formatDuration(185_000_000L));
        eq("duration 1:02:03", "1:02:03", CompressMath.formatDuration(3_723_000_000L));
    }

    // ---------------------------------------------------------------- 도우미

    private static String fmt(double value) {
        return String.format(java.util.Locale.US, "%.1f", value);
    }

    private static double round1(double value) {
        return Math.round(value * 10d) / 10d;
    }

    private static void eq(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        report(ok, name, expected, actual);
    }

    private static void truth(String name, boolean value) {
        report(value, name, true, value);
    }

    private static void report(boolean ok, String name, Object expected, Object actual) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name + "  expected=" + expected + " actual=" + actual);
        }
    }
}
