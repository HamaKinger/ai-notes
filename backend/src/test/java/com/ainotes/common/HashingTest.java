package com.ainotes.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HashingTest {

    @Test
    void sha256HexIsStableAndContentSensitive() {
        String a = Hashing.sha256Hex("# 标题\n\n正文");
        String b = Hashing.sha256Hex("# 标题\n\n正文");
        String c = Hashing.sha256Hex("# 标题\n\n正文!");

        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(a).hasSize(64);
    }
}
