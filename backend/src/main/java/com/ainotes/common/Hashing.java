package com.ainotes.common;

import org.apache.commons.codec.digest.DigestUtils;

/**
 * 内容指纹工具:用于判断笔记是否需要重建索引 / 是否发生实质变更。
 *
 * <p>底层用 Apache Commons Codec 的 {@link DigestUtils},不自己实现摘要算法。
 */
public final class Hashing {

    private Hashing() {
    }

    public static String sha256Hex(String text) {
        return DigestUtils.sha256Hex(text);
    }
}
