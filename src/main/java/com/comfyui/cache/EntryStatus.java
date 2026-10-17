package com.comfyui.cache;

/** 缓存记录的当前状态（重新检查过文件系统之后得出）。 */
public record EntryStatus(State state, String text, String detail) {

    public enum State {
        /** 文件不在缓存列表中。 */
        NOT_CACHED("未缓存"),
        /** 一切正常。 */
        OK("已缓存"),
        /** 能用，但有需要留意的细节。 */
        ATTENTION("需注意"),
        /** 明显异常，建议处理。 */
        BROKEN("异常");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public static EntryStatus notCached() {
        return new EntryStatus(State.NOT_CACHED, "未缓存", "");
    }

    public boolean healthy() {
        return state == State.OK;
    }

    public boolean needsCare() {
        return state == State.ATTENTION || state == State.BROKEN;
    }

    public String fullText() {
        return detail == null || detail.isEmpty() ? text : text + "：" + detail;
    }
}
