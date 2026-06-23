package com.huawei.ascend.edp.stream;

/**
 * 流式事件帧工厂。
 *
 * 构建 A2A artifactUpdate 事件帧。
 */
public class StreamFrameFactory {

    /**
     * 创建文本帧。
     */
    public static String textFrame(String content) {
        return content;
    }

    /**
     * 创建思考块帧。
     */
    public static String thinkFrame(String content, int charsPerFrame) {
        if (content == null || content.length() <= charsPerFrame) {
            return content;
        }
        return content.substring(0, charsPerFrame);
    }
}
