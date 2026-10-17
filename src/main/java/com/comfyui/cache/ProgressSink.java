package com.comfyui.cache;

import com.comfyui.cache.FileUtil.CancelledException;

/** 长耗时操作的进度回调。 */
public interface ProgressSink {

    ProgressSink NOOP = new ProgressSink() {
        @Override
        public void message(String message) {
        }

        @Override
        public void progress(long done, long total) {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    };

    void message(String message);

    /** total <= 0 时表示进度未知。 */
    void progress(long done, long total);

    boolean isCancelled();

    default void checkCancelled() throws CancelledException {
        if (isCancelled()) {
            throw new CancelledException();
        }
    }
}
