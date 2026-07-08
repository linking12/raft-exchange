package com.binance.raftexchange.server.raft;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotHelperTest {

    // --- getSnapshotId ---

    @Test
    void getSnapshotId_nullFiles_throwsException() {
        RuntimeException ex = assertThrows(RuntimeException.class, () -> SnapshotHelper.getSnapshotId(null));
        assertTrue(ex.getMessage().contains("empty"), "message should mention 'empty', got: " + ex.getMessage());
    }

    @Test
    void getSnapshotId_emptySet_throwsException() {
        RuntimeException ex = assertThrows(RuntimeException.class, () -> SnapshotHelper.getSnapshotId(Set.of()));
        assertTrue(ex.getMessage().contains("empty"), "message should mention 'empty', got: " + ex.getMessage());
    }

    @Test
    void getSnapshotId_validMeFile_returnsTimestampPart() {
        long id = SnapshotHelper.getSnapshotId(Set.of("snapshot_20250307105705748_ME_0.dat"));
        assertEquals(20250307105705748L, id);
    }

    @Test
    void getSnapshotId_validReFile_returnsTimestampPart() {
        long id = SnapshotHelper.getSnapshotId(Set.of("snapshot_20260101120000000_RE_1.dat"));
        assertEquals(20260101120000000L, id);
    }

    @Test
    void getSnapshotId_multipleValidFiles_allShareSameSnapshotId() {
        // Both files carry the same snapshotId — method returns whichever comes first in iteration
        Set<String> files = Set.of("snapshot_20250307105705748_ME_0.dat", "snapshot_20250307105705748_RE_0.dat");
        long id = SnapshotHelper.getSnapshotId(files);
        assertEquals(20250307105705748L, id);
    }

    @Test
    void getSnapshotId_onlyTwoParts_throwsException() {
        assertThrows(RuntimeException.class, () -> SnapshotHelper.getSnapshotId(Set.of("badfile.dat")));
    }

    @Test
    void getSnapshotId_tooManyParts_throwsException() {
        assertThrows(RuntimeException.class,
            () -> SnapshotHelper.getSnapshotId(Set.of("snapshot_20250307_ME_0_extra.dat")));
    }

    // --- genSnapshotFileName (format must match getSnapshotId expectations) ---

    @Test
    void genSnapshotFileName_roundTrip_idRecoverable() {
        long snapshotId = 20250307105705748L;
        String name = SnapshotHelper.genSnapshotFileName(snapshotId,
            exchange.core2.core.processors.journaling.ISerializationProcessor.SerializedModuleType.MATCHING_ENGINE_ROUTER,
            0);
        long recovered = SnapshotHelper.getSnapshotId(Set.of(name));
        assertEquals(snapshotId, recovered, "getSnapshotId must recover the id embedded by genSnapshotFileName");
    }
}
