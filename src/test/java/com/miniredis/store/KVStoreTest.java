package com.miniredis.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KVStoreTest {

    private KVStore store;

    @BeforeEach
    void setUp() {
        store = new KVStore();
    }

    @Test
    void putReturnsNullOnFirstInsert() {
        assertNull(store.put("k", bytes("v")));
    }

    @Test
    void putReturnsPreviousValueOnOverwrite() {
        store.put("k", bytes("first"));
        byte[] previous = store.put("k", bytes("second"));
        assertArrayEquals(bytes("first"), previous);
        assertArrayEquals(bytes("second"), store.get("k"));
    }

    @Test
    void getReturnsNullForMissingKey() {
        assertNull(store.get("missing"));
    }

    @Test
    void getReturnsStoredValue() {
        store.put("k", bytes("v"));
        assertArrayEquals(bytes("v"), store.get("k"));
    }

    @Test
    void deleteReturnsValueForExistingKey() {
        store.put("k", bytes("v"));
        assertArrayEquals(bytes("v"), store.delete("k"));
        assertNull(store.get("k"));
    }

    @Test
    void deleteReturnsNullForMissingKey() {
        assertNull(store.delete("missing"));
    }

    @Test
    void putWithNullKeyThrows() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> store.put(null, bytes("v")));
        assertEquals("key must not be null", ex.getMessage());
    }

    @Test
    void putWithNullValueThrows() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> store.put("k", null));
        assertEquals("value must not be null", ex.getMessage());
    }

    @Test
    void getWithNullKeyThrows() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> store.get(null));
        assertEquals("key must not be null", ex.getMessage());
    }

    @Test
    void deleteWithNullKeyThrows() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> store.delete(null));
        assertEquals("key must not be null", ex.getMessage());
    }

    @Test
    void sizeReflectsInsertsAndDeletes() {
        assertEquals(0, store.size());
        store.put("a", bytes("1"));
        store.put("b", bytes("2"));
        assertEquals(2, store.size());
        store.put("a", bytes("1-updated"));
        assertEquals(2, store.size());
        store.delete("a");
        assertEquals(1, store.size());
        store.delete("missing");
        assertEquals(1, store.size());
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
