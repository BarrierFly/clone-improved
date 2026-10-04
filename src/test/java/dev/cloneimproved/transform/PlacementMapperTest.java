package dev.cloneimproved.transform;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-JVM tests for the transform math (design doc §9.2). Covers all 11 valid transform
 * sequences, rotation/mirror anchor+dims behaviour, coordinate bijection and mirror-plane
 * validation.
 */
class PlacementMapperTest {
    private static final TransformOp.Move MOVE = TransformOp.Move.INSTANCE;
    private static final TransformOp.Rotate CW = new TransformOp.Rotate(TransformOp.RotationDir.CW);
    private static final TransformOp.Rotate CCW = new TransformOp.Rotate(TransformOp.RotationDir.CCW);
    private static final TransformOp.Rotate REVERSE = new TransformOp.Rotate(TransformOp.RotationDir.REVERSE);
    private static final TransformOp.Mirror MIRROR_X = new TransformOp.Mirror(true, 1.0);
    private static final TransformOp.Mirror MIRROR_Z = new TransformOp.Mirror(false, 2.5);

    private static PlacementMapper.Placement source() {
        // begin (0,64,0), end (10,70,10) → 11 × 7 × 11
        return new PlacementMapper.Placement(0, 64, 0, 11, 7, 11);
    }

    private static PlacementMapper.Placement place(int minX, int minZ, int sizeX, int sizeZ) {
        return new PlacementMapper.Placement(minX, 64, minZ, sizeX, sizeY(), sizeZ);
    }

    private static int sizeY() {
        return 7;
    }

    @Test
    void rotationSwapsDimsAndKeepsAnchor() {
        PlacementMapper.Placement p = place(0, 0, 3, 2);
        PlacementMapper.Placement cw = PlacementMapper.apply(p, CW);
        assertEquals(new PlacementMapper.Placement(0, 64, 0, 2, 7, 3), cw);
        PlacementMapper.Placement ccw = PlacementMapper.apply(p, CCW);
        assertEquals(cw, ccw);
        PlacementMapper.Placement reverse = PlacementMapper.apply(p, REVERSE);
        assertEquals(p, reverse);
    }

    @Test
    void rotateCwMapsNorthWestToNorthEast() {
        // NW corner block → NE corner of the swapped region (clockwise seen from above)
        int[] nw = PlacementMapper.mapToDest(TransformSpec.of(List.of(CW)), place(0, 0, 3, 2), 100, 64, 100, 0, 64, 0);
        assertEquals(101, nw[0]);
        assertEquals(100, nw[2]);
        // SW block → NW
        int[] sw = PlacementMapper.mapToDest(TransformSpec.of(List.of(CW)), place(0, 0, 3, 2), 100, 64, 100, 0, 64, 1);
        assertEquals(100, sw[0]);
        assertEquals(100, sw[2]);
        // SE block → SW
        int[] se = PlacementMapper.mapToDest(TransformSpec.of(List.of(CW)), place(0, 0, 3, 2), 100, 64, 100, 2, 64, 1);
        assertEquals(100, se[0]);
        assertEquals(102, se[2]);
    }

    @Test
    void rotateReverseMapsCornersOntoEachOther() {
        TransformSpec spec = TransformSpec.of(List.of(REVERSE));
        PlacementMapper.Placement p = place(0, 0, 3, 2);
        int[] nw = PlacementMapper.mapToDest(spec, p, 100, 64, 100, 0, 64, 0);
        assertEquals(102, nw[0]);
        assertEquals(101, nw[2]);
        int[] se = PlacementMapper.mapToDest(spec, p, 100, 64, 100, 2, 64, 1);
        assertEquals(100, se[0]);
        assertEquals(100, se[2]);
    }

    @Test
    void mirrorXReflectsAboutPlane() {
        // plane x=1.0 → 2c=2; region [0,10] reflects to [-8, 2]; the destination equals the
        // reflected anchor so this isolates the mirror math (grammar-wise a lone mirror never
        // occurs — rotate/mirror always co-occur with move).
        TransformSpec spec = TransformSpec.of(List.of(MIRROR_X));
        PlacementMapper.Placement result = PlacementMapper.finalPlacement(spec, source(), -8, 64, 0);
        assertEquals(-8, result.minX());
        assertEquals(11, result.sizeX());
        int[] west = PlacementMapper.mapToDest(spec, source(), -8, 64, 0, 0, 64, 0);
        assertEquals(2, west[0]);
        int[] east = PlacementMapper.mapToDest(spec, source(), -8, 64, 0, 10, 64, 0);
        assertEquals(-8, east[0]);
    }

    @Test
    void mirrorZReflectsAboutPlane() {
        // plane z=2.5 → 2c=5; region [0,10] reflects to [-5, 5]
        TransformSpec spec = TransformSpec.of(List.of(MIRROR_Z));
        PlacementMapper.Placement result = PlacementMapper.finalPlacement(spec, source(), 0, 64, -5);
        assertEquals(-5, result.minZ());
        int[] north = PlacementMapper.mapToDest(spec, source(), 0, 64, -5, 0, 64, 0);
        assertEquals(5, north[2]);
        int[] south = PlacementMapper.mapToDest(spec, source(), 0, 64, -5, 0, 64, 10);
        assertEquals(-5, south[2]);
    }

    @Test
    void moveReanchorsToDestination() {
        TransformSpec spec = TransformSpec.of(List.of(MOVE));
        PlacementMapper.Placement result = PlacementMapper.finalPlacement(spec, source(), 100, 64, 100);
        assertEquals(new PlacementMapper.Placement(100, 64, 100, 11, 7, 11), result);
        int[] mapped = PlacementMapper.mapToDest(spec, source(), 100, 64, 100, 3, 66, 4);
        assertEquals(103, mapped[0]);
        assertEquals(66, mapped[1]);
        assertEquals(104, mapped[2]);
    }

    @Test
    void chainsWithoutMoveStillLandOnDestination() {
        // mask-only request: destination + source dims, like vanilla
        TransformSpec spec = TransformSpec.empty();
        PlacementMapper.Placement result = PlacementMapper.finalPlacement(spec, source(), 100, 64, 100);
        assertEquals(new PlacementMapper.Placement(100, 64, 100, 11, 7, 11), result);
    }

    @Test
    void mirrorBeforeMoveFlipsContentAndThenReanchors() {
        // /clone ... mirror z 105.5 move — the mirror flips the content; move then re-anchors.
        TransformOp.Mirror mirrorZ = new TransformOp.Mirror(false, 105.5);
        TransformSpec spec = TransformSpec.of(List.of(mirrorZ, MOVE));
        PlacementMapper.Placement result = PlacementMapper.finalPlacement(spec, source(), 100, 64, 100);
        assertEquals(new PlacementMapper.Placement(100, 64, 100, 11, 7, 11), result);
        // Source north edge (z=0) ends up on the destination's south edge (z=110).
        int[] north = PlacementMapper.mapToDest(spec, source(), 100, 64, 100, 0, 64, 0);
        assertEquals(110, north[2]);
        int[] south = PlacementMapper.mapToDest(spec, source(), 100, 64, 100, 0, 64, 10);
        assertEquals(100, south[2]);
    }

    @Test
    void rotateAfterMoveRotatesInPlaceAtDestination() {
        TransformSpec spec = TransformSpec.of(List.of(MOVE, CW));
        PlacementMapper.Placement result = PlacementMapper.finalPlacement(spec, place(0, 0, 3, 2), 100, 64, 100);
        // Dims swapped around the destination anchor.
        assertEquals(new PlacementMapper.Placement(100, 64, 100, 2, 7, 3), result);
    }

    @Test
    void rotateBeforeMoveSwapsDimsThenTranslates() {
        TransformSpec spec = TransformSpec.of(List.of(CW, MOVE));
        PlacementMapper.Placement result = PlacementMapper.finalPlacement(spec, place(0, 0, 3, 2), 100, 64, 100);
        assertEquals(new PlacementMapper.Placement(100, 64, 100, 2, 7, 3), result);
        // Rotated local (dx,dz)=(0,0) → (sz−1−dz, dx) = (1, 0)
        int[] nw = PlacementMapper.mapToDest(spec, place(0, 0, 3, 2), 100, 64, 100, 0, 64, 0);
        assertEquals(101, nw[0]);
        assertEquals(100, nw[2]);
    }

    @Test
    void allElevenSequencesAreBijective() {
        List<TransformOp[]> sequences = List.of(
            new TransformOp[] {MOVE},
            new TransformOp[] {MOVE, CW}, new TransformOp[] {MOVE, MIRROR_Z},
            new TransformOp[] {CW, MOVE}, new TransformOp[] {MIRROR_X, MOVE},
            new TransformOp[] {MOVE, CW, MIRROR_X}, new TransformOp[] {MOVE, MIRROR_X, CW},
            new TransformOp[] {CW, MIRROR_X, MOVE}, new TransformOp[] {CW, MOVE, MIRROR_X},
            new TransformOp[] {MIRROR_X, CW, MOVE}, new TransformOp[] {MIRROR_X, MOVE, CW}
        );
        for (TransformOp[] seq : sequences) {
            TransformSpec spec = TransformSpec.of(List.of(seq));
            PlacementMapper.Placement src = place(0, 0, 4, 3);
            PlacementMapper.Placement dest = PlacementMapper.finalPlacement(spec, src, 50, 64, 50);
            assertEquals(src.volume(), dest.volume(), "volume must be preserved: " + List.of(seq));
            var seen = new java.util.HashSet<Long>();
            for (int x = 0; x < src.sizeX(); x++) {
                for (int z = 0; z < src.sizeZ(); z++) {
                    int[] d = PlacementMapper.mapToDest(spec, src, 50, 64, 50, x, 64, z);
                    assertTrue(dest.contains(d[0], d[1], d[2]),
                        "mapped cell outside destination: " + List.of(seq) + " " + x + "," + z + " -> " + d[0] + "," + d[2]);
                    assertTrue(seen.add((long) d[0] * 100000L + d[2]), "two cells map to the same cell: " + List.of(seq));
                }
            }
            assertEquals(src.sizeX() * src.sizeZ(), seen.size(), "bijection: " + List.of(seq));
        }
    }

    @Test
    void mirrorCoordinateMustBeHalfStep() {
        assertEquals(0.3, TransformSpec.invalidMirrorCoord(List.of(new TransformOp.Mirror(true, 0.3))));
        assertEquals(Double.NaN, TransformSpec.invalidMirrorCoord(List.of(new TransformOp.Mirror(true, 1.0))));
        assertEquals(Double.NaN, TransformSpec.invalidMirrorCoord(List.of(new TransformOp.Mirror(false, 2.5))));
        assertEquals(Double.NaN, TransformSpec.invalidMirrorCoord(List.of(MOVE)));
    }

    @Test
    void mirrorCoordinateMustBeFiniteAndInIntRange() {
        // 2c is cast to an int anchor downstream, so non-finite and out-of-int-range planes must fail validation.
        assertEquals(Double.POSITIVE_INFINITY,
            TransformSpec.invalidMirrorCoord(List.of(new TransformOp.Mirror(true, Double.POSITIVE_INFINITY))));
        assertEquals(Double.NEGATIVE_INFINITY,
            TransformSpec.invalidMirrorCoord(List.of(new TransformOp.Mirror(false, Double.NEGATIVE_INFINITY))));
        assertEquals(1.0E300, TransformSpec.invalidMirrorCoord(List.of(new TransformOp.Mirror(true, 1.0E300))));
        // 2c = 2^31 - 1 is exactly representable and still in int range.
        assertEquals(Double.NaN, TransformSpec.invalidMirrorCoord(List.of(new TransformOp.Mirror(true, 1073741823.5))));
        // 2c = 2^31 no longer fits an int.
        assertEquals(1073741824.0, TransformSpec.invalidMirrorCoord(List.of(new TransformOp.Mirror(true, 1073741824.0))));
    }

    @Test
    void moveTwiceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TransformSpec.of(List.of(MOVE, MOVE)));
    }

    @Test
    void pureTranslationDetection() {
        assertTrue(TransformSpec.of(List.of(MOVE)).isPureTranslation());
        assertTrue(TransformSpec.empty().isPureTranslation());
        assertFalse(TransformSpec.of(List.of(MOVE, CW)).isPureTranslation());
        assertFalse(TransformSpec.of(List.of(MIRROR_Z, MOVE)).isPureTranslation());
    }
}
