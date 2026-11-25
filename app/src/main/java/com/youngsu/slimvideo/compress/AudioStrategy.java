package com.youngsu.slimvideo.compress;

/** 오디오 처리 방식 */
public enum AudioStrategy {
    /** 오디오 트랙이 없다 (무음 영상) */
    NONE,
    /** 이미 AAC 라서 그대로 옮긴다 (가장 깔끔하고 빠르다) */
    COPY,
    /** AAC 가 아니어서 AAC 로 다시 인코딩한다 */
    TRANSCODE
}
