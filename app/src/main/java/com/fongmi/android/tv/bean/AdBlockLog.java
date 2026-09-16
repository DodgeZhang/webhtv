package com.fongmi.android.tv.bean;

/** 单条广告切片拦截日志。 */
public class AdBlockLog {

    private long blockedAt;
    private String sourceName;
    private String pipelineName;
    private String adDomain;
    private String ruleId;
    private double segmentDurationSeconds;

    public AdBlockLog() {
    }

    public AdBlockLog(long blockedAt, String sourceName, String pipelineName, String adDomain,
                      String ruleId, double segmentDurationSeconds) {
        this.blockedAt = blockedAt;
        this.sourceName = sourceName;
        this.pipelineName = pipelineName;
        this.adDomain = adDomain;
        this.ruleId = ruleId;
        this.segmentDurationSeconds = segmentDurationSeconds;
    }

    public long getBlockedAt() {
        return blockedAt;
    }

    public String getSourceName() {
        return sourceName;
    }

    public String getPipelineName() {
        return pipelineName;
    }

    public String getAdDomain() {
        return adDomain;
    }

    public String getRuleId() {
        return ruleId;
    }

    public double getSegmentDurationSeconds() {
        return segmentDurationSeconds;
    }
}
