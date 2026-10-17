package com.comfyui.cache;

/** 带用户可读说明的操作异常。 */
public class CacheException extends Exception {

    public CacheException(String message) {
        super(message);
    }

    public CacheException(String message, Throwable cause) {
        super(message, cause);
    }
}
