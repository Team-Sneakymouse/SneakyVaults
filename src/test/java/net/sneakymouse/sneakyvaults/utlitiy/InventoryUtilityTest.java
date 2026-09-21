package net.sneakymouse.sneakyvaults.utlitiy;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertThrows;

class InventoryUtilityTest {

    @Test
    void strictLegacyDecoderRejectsMalformedData() {
        assertThrows(IOException.class, () -> InventoryUtility.getSavedInventoryStrict("%%%not-base64%%%"));
    }
}
