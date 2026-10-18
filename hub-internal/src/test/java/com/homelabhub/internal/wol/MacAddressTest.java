package com.homelabhub.internal.wol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class MacAddressTest {

    private static final String EXPECTED_MESSAGE_SUFFIX =
            ". Expected format: XX:XX:XX:XX:XX:XX or XX-XX-XX-XX-XX-XX";

    @Test
    void parsesColonForm() {
        MacAddress mac = MacAddress.parse("AA:BB:CC:DD:EE:FF");
        assertThat(mac.toString()).isEqualTo("AA:BB:CC:DD:EE:FF");
    }

    @Test
    void parsesDashForm() {
        MacAddress mac = MacAddress.parse("AA-BB-CC-DD-EE-FF");
        assertThat(mac.toString()).isEqualTo("AA:BB:CC:DD:EE:FF");
    }

    @Test
    void lowerCaseIsNormalisedToUpperCaseColonForm() {
        MacAddress mac = MacAddress.parse("aa:bb:cc:dd:ee:01");
        assertThat(mac.toString()).isEqualTo("AA:BB:CC:DD:EE:01");
    }

    @Test
    void mixedSeparatorsAreAccepted() {
        MacAddress mac = MacAddress.parse("AA:BB-CC:DD-EE:FF");
        assertThat(mac.toString()).isEqualTo("AA:BB:CC:DD:EE:FF");
    }

    @Test
    void surroundingWhitespaceIsAccepted() {
        MacAddress mac = MacAddress.parse("  aa:bb:cc:dd:ee:ff  ");
        assertThat(mac.toString()).isEqualTo("AA:BB:CC:DD:EE:FF");
    }

    @Test
    void bytesReturnsAFreshCopy() {
        MacAddress mac = MacAddress.parse("AA:BB:CC:DD:EE:FF");
        byte[] copy = mac.bytes();
        copy[0] = 0x00;
        assertThat(mac.bytes()).containsExactly(0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF);
    }

    @Test
    void equalsAndHashCodeAreBasedOnBytes() {
        MacAddress a = MacAddress.parse("aa-bb-cc-dd-ee-ff");
        MacAddress b = MacAddress.parse("AA:BB:CC:DD:EE:FF");
        assertThat(a).isEqualTo(b);
        assertThat(b).isEqualTo(a);
        assertThat(a).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(MacAddress.parse("AA:BB:CC:DD:EE:00"));
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("not a mac");
    }

    @ParameterizedTest(name = "rejects null input")
    @NullSource
    void rejectsNullInput(String input) {
        assertThatThrownBy(() -> MacAddress.parse(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid MAC address format: null" + EXPECTED_MESSAGE_SUFFIX);
    }

    @ParameterizedTest(name = "rejects {0}")
    @ValueSource(strings = {
            "",
            "AA:BB:CC:DD:EE",
            "AA:BB:CC:DD:EE:FF:00",
            "GG:BB:CC:DD:EE:FF",
            "A:BB:CC:DD:EE:FF",
            "AAA:BB:CC:DD:EE:FF",
            "AA.BB.CC.DD.EE.FF",
            "AA BB CC DD EE FF",
            "AA:BB:CC:DD:EE:FF:"
    })
    void rejectsInvalidInput(String input) {
        String expected = "Invalid MAC address format: " + input + EXPECTED_MESSAGE_SUFFIX;
        assertThatThrownBy(() -> MacAddress.parse(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(expected);
    }

    @Test
    void invalidMessageContainsUntrimmedOriginalInput() {
        String input = "x AA:BB:CC:DD:EE:FF";
        assertThatThrownBy(() -> MacAddress.parse(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid MAC address format: " + input + EXPECTED_MESSAGE_SUFFIX);

        String padded = "  AA:BB:CC:DD:EE:G  ";
        assertThatThrownBy(() -> MacAddress.parse(padded))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid MAC address format: " + padded + EXPECTED_MESSAGE_SUFFIX);
    }
}